package ly.count.sdk.java.internal;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import ly.count.sdk.java.internal.ConfigurationProvider.LogGatheringState;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static ly.count.sdk.java.internal.ServerConfigBuilder.names;
import static org.mockito.Mockito.mock;

/**
 * Operator armed SDK log gathering: the {@code lg} directive of a live SDK behavior settings
 * response arms it, and the SDK uploads its own log lines as the {@code sdk_logs} parameter of
 * requests of their own, {@code {"i":gatherId,"d":dropped,"l":[{"t":ms,"l":"e","m":message}]}}.
 * <p>
 * The settings request goes through a {@link ModuleConfigurationTests.ServerConfigResponder}, which
 * answers on the calling thread, so the directive of the fetch made at init is applied before init
 * returns. Batches are queued on the delivery thread of the logger; a test waits for it with
 * {@link #awaitLogDelivery()} and reads the batches off the request queue. Unless a test is about
 * sending, the responses switch networking off, so every queued request stays on disk. The lines a
 * test counts are logged at a level the SDK does not use in these flows, mostly {@code e}.
 */
@RunWith(JUnit4.class)
public class SdkLogGatheringTests {

    private static final String REQUEST_FILE_PREFIX = "[CLY]_request_";

    private ModuleConfigurationTests.ServerConfigResponder server;

    /**
     * Starts every test from empty storage, with the default timer and a fresh responder.
     */
    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
        SDKCore.testDummyModule = null;
        CountlyTimer.TIMER_DELAY_MS = 0;
        server = new ModuleConfigurationTests.ServerConfigResponder();
    }

    /**
     * Stops the SDK, clearing its data, and restores the default timer.
     */
    @After
    public void afterTest() {
        Countly.instance().halt();
        CountlyTimer.TIMER_DELAY_MS = 0;
    }

    // region scenarios

    /**
     * An armed directive adopts what was captured before it arrived.
     * <p>
     * Verifies against a local server that the lines of the directive's levels logged during init,
     * before any response, are uploaded once init finishes, the very first line of the SDK first;
     * that every batch echoes the gather id, carries the required request parameters and at most
     * {@code b} lines, each with exactly {@code t}, {@code l} and {@code m}, none of another level
     * and none quoting a batch; that a partial batch waits for a flush while no batch is in flight;
     * that a full batch goes out on its own, the lines after it following once it is sent; and that
     * a full batch is too long for a GET, so it is sent as a POST. An unknown top level key next to
     * {@code lg} changes none of it.
     */
    @Test
    public void armedDirective_uploadsTheInitLinesOfItsLevels_withTheGatherIdInBatchesOfAtMostB() throws Exception {
        long startMs = System.currentTimeMillis();
        List<ReceivedRequest> requests = new CopyOnWriteArrayList<>();
        HttpServer httpServer = startRecordingServer(requests);
        try {
            server.respondWith(new ServerConfigBuilder()
                .logGatheringOn("gather_init", "wi", 10)
                .topLevelKey("someFutureFeature", "a value this SDK has never heard of"));
            init(localConfig(httpServer));
            assertGathering("gather_init", "wi", 10);

            awaitSentBatches();
            List<JSONObject> initBatches = receivedBatches(requests);
            Assert.assertFalse("init end queued nothing", initBatches.isEmpty());
            Assert.assertEquals("[SDKCore] Initializing Countly", initBatches.get(0).getJSONArray("l").getJSONObject(0).getString("m"));

            List<String> partialLines = numbered("[Test] partial line ", 0, 5);
            for (String line : partialLines) {
                logger().w(line);
            }
            awaitLogDelivery();
            Thread.sleep(200);
            Assert.assertTrue(linesStartingWith("[Test] partial line ", receivedBatches(requests)).isEmpty());
            logger().flushGatheredLogs();
            awaitSentBatches();
            Assert.assertEquals(partialLines, linesStartingWith("[Test] partial line ", receivedBatches(requests)));

            String padding = repeat('p', 80);
            List<String> armedLines = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                armedLines.add("[Test] armed line " + i + " " + padding);
                logger().w(armedLines.get(i));
            }
            awaitSentBatches();
            Assert.assertEquals(armedLines, linesStartingWith("[Test] armed line ", receivedBatches(requests)));

            int fullArmedBatches = 0;
            for (ReceivedRequest request : requests) {
                if (!request.params.containsKey(Log.keySdkLogs)) {
                    continue;
                }
                TestUtils.validateRequiredParams(request.params);
                JSONObject batch = new JSONObject(request.params.get(Log.keySdkLogs));
                Assert.assertEquals(names("i", "d", "l"), batch.keySet());
                Assert.assertEquals("gather_init", batch.getString("i"));
                Assert.assertEquals(0, batch.getInt("d"));

                JSONArray lines = batch.getJSONArray("l");
                Assert.assertTrue("a batch of [" + lines.length() + "] lines", lines.length() >= 1 && lines.length() <= 10);
                for (int i = 0; i < lines.length(); i++) {
                    JSONObject line = lines.getJSONObject(i);
                    Assert.assertEquals(names("t", "l", "m"), line.keySet());
                    long timestamp = line.getLong("t");
                    Assert.assertTrue(timestamp >= startMs && timestamp <= System.currentTimeMillis());
                    Assert.assertTrue(line.getString("l").equals("w") || line.getString("l").equals("i"));
                    Assert.assertFalse(line.getString("m").contains(Log.keySdkLogs));
                }
                if (request.params.get(Log.keySdkLogs).contains("[Test] armed line 9 ")) {
                    Assert.assertEquals("a full batch fits a GET", "POST", request.method);
                    fullArmedBatches++;
                }
            }
            Assert.assertEquals(1, fullArmedBatches);
        } finally {
            Countly.instance().halt();
            httpServer.stop(0);
        }
    }

    /**
     * While tracking is off the lines are held, the buffer keeps the newest 500 of them, and the
     * lines it drops are reported once, with the next batch.
     * <p>
     * Verifies against a local server that nothing is queued while tracking is off, flush or not;
     * that once tracking is back on the held lines go out oldest first in full batches of {@code b},
     * one at a time as each is sent, the first one reporting every dropped line in {@code d}, so the
     * uploaded and the dropped lines add up to every line logged; and that a {@code b} over the
     * buffer ceiling is clamped to it, so no batch ever holds more than 500 lines.
     */
    @Test
    public void trackingOffHold_keepsTheNewest500Lines_andDAccountsForEveryDroppedLine() throws Exception {
        List<ReceivedRequest> requests = new CopyOnWriteArrayList<>();
        HttpServer httpServer = startRecordingServer(requests);
        try {
            server.respondWith(heldGather("gather_hold", "e", 25));
            init(localConfig(httpServer));
            assertGathering("gather_hold", "e", 25);

            Log logger = logger();
            //what the SDK logged at the same level during init is held too, and is the oldest
            int initLines = logger.heldLogLineCount();
            for (int i = 0; i < 520; i++) {
                logger.e("[Test] held line " + i);
            }
            Assert.assertEquals(ModuleConfiguration.logGatheringMaxBufferedLines, logger.heldLogLineCount());
            Assert.assertEquals(initLines + 20, logger.droppedLogLineCount);
            logger.flushGatheredLogs();
            awaitLogDelivery();
            Assert.assertEquals(ModuleConfiguration.logGatheringMaxBufferedLines, logger.heldLogLineCount());
            Assert.assertTrue(logRequests().isEmpty());

            push(new ServerConfigBuilder().tracking(true).logGatheringOn("gather_hold", "e", 25));
            assertGathering("gather_hold", "e", 25);
            logger.flushGatheredLogs();
            awaitSentBatches();
            Assert.assertEquals(0, logger.heldLogLineCount());

            List<JSONObject> batches = receivedBatches(requests);
            Assert.assertEquals(20, batches.size());
            List<String> uploaded = new ArrayList<>();
            int dropped = 0;
            for (int b = 0; b < batches.size(); b++) {
                JSONObject batch = batches.get(b);
                Assert.assertEquals("gather_hold", batch.getString("i"));
                Assert.assertEquals(b == 0 ? initLines + 20 : 0, batch.getInt("d"));
                Assert.assertEquals(25, batch.getJSONArray("l").length());
                dropped += batch.getInt("d");
                uploaded.addAll(messagesOf(batch));
            }
            Assert.assertEquals(numbered("[Test] held line ", 20, 520), uploaded);
            Assert.assertEquals(initLines + 520, uploaded.size() + dropped);

            push(heldGather("gather_hold", "e", 9999));
            assertGathering("gather_hold", "e", ModuleConfiguration.logGatheringMaxBufferedLines);
            int heldBefore = logger.heldLogLineCount();
            for (int i = 0; i < 600; i++) {
                logger.e("[Test] second line " + i);
            }
            push(new ServerConfigBuilder().tracking(true).logGatheringOn("gather_hold", "e", 9999));
            logger.flushGatheredLogs();
            awaitSentBatches();

            List<JSONObject> later = receivedBatches(requests).subList(20, receivedBatches(requests).size());
            Assert.assertEquals(1, later.size());
            Assert.assertEquals(heldBefore + 100, later.get(0).getInt("d"));
            Assert.assertEquals(numbered("[Test] second line ", 100, 600), messagesOf(later.get(0)));
        } finally {
            Countly.instance().halt();
            httpServer.stop(0);
        }
    }

    /**
     * Every way a gather ends leaves nothing held, and a new gather never carries the lines of an
     * earlier one.
     * <p>
     * Verifies, with tracking off so nothing leaves until the end, that the same directive repeated
     * and a change of levels or batch size within one gather keep the held lines; that
     * {@code "e":false}, a directive without a usable gather id and a live response without any
     * directive each stop the capture and release the delivery thread, their held lines offered to
     * the request queue once, which refuses them while tracking is off; that a new gather id drops
     * the lines of the previous gather; and that only the lines of the gather in effect, with its id,
     * are ever uploaded.
     */
    @Test
    public void endedOrReplacedGather_leavesNothingHeld_andANewGatherNeverCarriesTheOldOnes() throws Exception {
        server.respondWith(heldGather("gather_a", "e", 10));
        init(TestUtils.getConfigSdkBehaviorSettings());
        Log logger = logger();
        int initLines = logger.heldLogLineCount();
        logger.e("[Test] a1");
        logger.e("[Test] a2");
        Assert.assertEquals(initLines + 2, logger.heldLogLineCount());

        push(heldGather("gather_a", "e", 10));
        Assert.assertEquals(initLines + 2, logger.heldLogLineCount());
        push(heldGather("gather_a", "ew", 20));
        assertGathering("gather_a", "ew", 20);
        Assert.assertTrue(logger.heldLogLineCount() >= initLines + 2);
        push(heldGather("gather_a", "e", 10));
        assertGathering("gather_a", "e", 10);
        Assert.assertEquals(initLines + 2, logger.heldLogLineCount());

        pushStop(new ServerConfigBuilder().networking(false).tracking(false).logGatheringOff());
        assertNotGathering();
        logger.e("[Test] logged while off");
        Assert.assertEquals(0, logger.heldLogLineCount());

        push(heldGather("gather_b", "e", 10));
        assertGathering("gather_b", "e", 10);
        Assert.assertEquals(0, logger.heldLogLineCount());
        logger.e("[Test] b1");
        push(heldGather("gather_c", "e", 10));
        assertGathering("gather_c", "e", 10);
        Assert.assertEquals(0, logger.heldLogLineCount());
        logger.e("[Test] c1");

        pushStop(new ServerConfigBuilder().networking(false).tracking(false).logGatheringDirective(true, "  ", "e", 10));
        assertNotGathering();

        push(heldGather("gather_d", "e", 10));
        logger.e("[Test] d1");
        pushStop(new ServerConfigBuilder().networking(false).tracking(false));
        assertNotGathering();

        push(heldGather("gather_e", "e", 10));
        logger.e("[Test] e1");
        logger.e("[Test] e2");
        push(new ServerConfigBuilder().networking(false).tracking(true).logGatheringOn("gather_e", "e", 10));
        logger.flushGatheredLogs();
        awaitLogDelivery();

        List<JSONObject> batches = batches();
        Assert.assertEquals(1, batches.size());
        Assert.assertEquals("gather_e", batches.get(0).getString("i"));
        Assert.assertEquals(0, batches.get(0).getInt("d"));
        Assert.assertEquals(Arrays.asList("[Test] e1", "[Test] e2"), linesStartingWith("[Test] ", batches));
    }

    /**
     * Only a live response decides.
     * <p>
     * Verifies that an armed directive in the stored settings leaves the logger undecided, still
     * capturing the init lines speculatively and queueing nothing even when flushed; that a run
     * whose fetch fails decides off, lets go of what it held and captures nothing more; that a
     * running gather survives a failed refresh with its lines; and that a stopped logger ignores
     * every later line.
     */
    @Test
    public void onlyALiveResponseDecides_storedDirectiveStaysUndecided_andAFailedFetchDecidesOffOnlyWhileUndecided() throws Exception {
        TestUtils.writeToFile(SDKStorage.JSON_FILE_NAME, new JSONObject()
            .put("sc", new ServerConfigBuilder().networking(false).logGatheringOn("stored_gather_id", "ewidv", 40).build()).toString());
        server.pending();
        init(TestUtils.getConfigSdkBehaviorSettings());

        Log undecided = logger();
        Assert.assertEquals(LogGatheringState.UNDECIDED, undecided.logGatheringState);
        Assert.assertNull(undecided.logGatherId);
        Assert.assertTrue(undecided.isCapturingLogs());
        Assert.assertFalse(undecided.isGatheringLogs());
        Assert.assertTrue(undecided.heldLogLineCount() > 0);
        undecided.flushGatheredLogs();
        Assert.assertNull(undecided.logDeliveryExecutor);
        Assert.assertTrue(logRequests().isEmpty());
        Countly.instance().stop();

        Assert.assertEquals(LogGatheringState.NOT_GATHERING, undecided.logGatheringState);
        undecided.e("[Test] logged after stop");
        Assert.assertEquals(0, undecided.heldLogLineCount());

        server.failing();
        init(TestUtils.getConfigSdkBehaviorSettings());
        Log failed = logger();
        Assert.assertEquals(LogGatheringState.NOT_GATHERING, failed.logGatheringState);
        Assert.assertEquals(0, failed.heldLogLineCount());
        failed.e("[Test] logged after the failed fetch");
        Assert.assertEquals(0, failed.heldLogLineCount());
        Countly.instance().halt();

        server.respondWith(heldGather("gather_live", "e", 10));
        init(TestUtils.getConfigSdkBehaviorSettings());
        Log live = logger();
        live.e("[Test] kept across a failed refresh");
        int held = live.heldLogLineCount();
        Assert.assertTrue(held > 0);
        server.failing();
        module().fetchConfigFromServer(SDKCore.instance.config);
        assertGathering("gather_live", "e", 10);
        Assert.assertEquals(held, live.heldLogLineCount());
    }

    /**
     * While consent is required, a gathered line goes out only once the consent of the feature it
     * belongs to is given, a line of no feature needs both the events and the user profiles
     * consent, and the coordinates handed to a session are logged only with location consent.
     * <p>
     * Verifies, with networking off, that the batch queued with only the sessions consent given
     * holds no line of another feature and no coordinates; that the stop that follows events consent
     * queues the events line but neither the location line nor the line of no feature; and that
     * with location consent given the coordinates are gathered.
     */
    @Test
    public void requiredConsent_gathersEachLineOnlyWithTheConsentOfItsFeature() throws Exception {
        Config config = TestUtils.getConfigSdkBehaviorSettings().setRequiresConsent(true)
            .enableFeatures(Config.Feature.Events, Config.Feature.Sessions, Config.Feature.Location, Config.Feature.UserProfiles);
        server.respondWith(new ServerConfigBuilder().networking(false).logGatheringOn("gather_consent", "ewidv", 500));
        init(config);
        assertGathering("gather_consent", "ewidv", 500);

        Log logger = logger();
        logger.e("[Test] a line of no feature");
        logger.e("[ModuleEvents] an events line");
        logger.e("[ModuleLocation] a location line");
        Countly.onConsent(Config.Feature.Sessions);
        Countly.session().begin();
        ((SessionImpl) Countly.session()).addLocation(12.345678, 98.765432);
        logger.flushGatheredLogs();
        awaitLogDelivery();

        List<String> queued = receivedLines(batches());
        Assert.assertFalse(queued.isEmpty());
        Assert.assertFalse(queued.contains("[Test] a line of no feature"));
        Assert.assertFalse(queued.contains("[ModuleEvents] an events line"));
        Assert.assertFalse(queued.contains("[ModuleLocation] a location line"));
        for (String line : queued) {
            Assert.assertFalse(line, line.contains("12.345678"));
        }
        Assert.assertTrue(logger.awaitingConsentLineCount() >= 3);

        Countly.onConsent(Config.Feature.Events);
        Countly.instance().stop();
        List<String> stopped = receivedLines(storedBatchesOnDisk());
        Assert.assertTrue(stopped.contains("[ModuleEvents] an events line"));
        Assert.assertFalse(stopped.contains("[ModuleLocation] a location line"));
        Assert.assertFalse(stopped.contains("[Test] a line of no feature"));
        for (String line : stopped) {
            Assert.assertFalse(line, line.contains("12.345678"));
        }

        server.respondWith(new ServerConfigBuilder().networking(false).logGatheringOn("gather_location", "ewidv", 500));
        init(config);
        Countly.onConsent(Config.Feature.Sessions, Config.Feature.Location);
        Countly.session().begin();
        ((SessionImpl) Countly.session()).addLocation(12.345678, 98.765432);
        Countly.instance().stop();
        boolean coordinatesGathered = false;
        for (JSONObject batch : storedBatchesOnDisk()) {
            if ("gather_location".equals(batch.getString("i"))) {
                for (String line : messagesOf(batch)) {
                    coordinatesGathered |= line.contains("12.345678");
                }
            }
        }
        Assert.assertTrue(coordinatesGathered);
    }

    /**
     * While consent is required, a feature the app did not enable holds no line back, and a removed
     * consent drops the held lines of its feature.
     * <p>
     * Verifies, with networking off and the user profiles feature not enabled, that a line of no
     * feature is gathered once the events and sessions consents are given; and that after the
     * events consent is removed the stop queues the held sessions line but not the held events line.
     */
    @Test
    public void requiredConsent_aFeatureNotEnabledHoldsNoLineBack_andARemovedConsentDropsItsLines() throws Exception {
        Config config = TestUtils.getConfigSdkBehaviorSettings().setRequiresConsent(true)
            .enableFeatures(Config.Feature.Events, Config.Feature.Sessions);
        server.respondWith(new ServerConfigBuilder().networking(false).logGatheringOn("gather_enabled", "e", 500));
        init(config);
        Log logger = logger();
        logger.e("[Test] a line of no feature");
        Countly.onConsent(Config.Feature.Events, Config.Feature.Sessions);
        logger.flushGatheredLogs();
        awaitLogDelivery();
        Assert.assertTrue(receivedLines(batches()).contains("[Test] a line of no feature"));

        logger.e("[ModuleEvents] a held events line");
        logger.e("[SessionImpl] a held sessions line");
        Countly.onConsentRemoval(Config.Feature.Events);
        Countly.instance().stop();

        List<String> stopped = receivedLines(storedBatchesOnDisk());
        Assert.assertTrue(stopped.contains("[SessionImpl] a held sessions line"));
        Assert.assertFalse(stopped.contains("[ModuleEvents] a held events line"));
    }

    /**
     * The global timer flushes a batch that is not full, a stop queues what is held before the
     * storage stops, and a halt discards it. Either way the logger ends for good.
     * <p>
     * Verifies that a line below the batch size reaches a local server on a tick of a fast global
     * timer; that, with networking off, {@code stop()} queues the held line synchronously, so it is
     * on disk once stop returns, with the gather id; that the stopped logger captures and queues
     * nothing more and its delivery thread ends; and that {@code halt()} queues nothing at all.
     */
    @Test
    public void timerTick_flushesAPartialBatch_stopQueuesWhatIsHeld_andHaltDiscardsIt() throws Exception {
        List<String> queuedLogRequests = new CopyOnWriteArrayList<>();
        LogCallback listener = (message, level) -> {
            if (message != null && message.startsWith("New request ") && message.contains(Log.transportMarker)) {
                queuedLogRequests.add(message);
            }
        };
        List<ReceivedRequest> requests = new CopyOnWriteArrayList<>();
        HttpServer httpServer = startRecordingServer(requests);
        try {
            server.respondWith(new ServerConfigBuilder().logGatheringOn("gather_tick", "e", 100));
            init(localConfig(httpServer).setLogListener(listener));
            Log logger = logger();
            awaitSentBatches();

            //the timer ticks through the modules, so it is sped up only after init built them
            CountlyTimer.TIMER_DELAY_MS = 50;
            push(new ServerConfigBuilder().sessionUpdateInterval(30).logGatheringOn("gather_tick", "e", 100));
            logger.e("[Test] tick line");
            Assert.assertTrue("no tick flushed the partial batch", TestUtils.waitFor(10_000, () -> !linesStartingWith("[Test] tick line", receivedBatches(requests)).isEmpty()));

            CountlyTimer.TIMER_DELAY_MS = 0;
            push(new ServerConfigBuilder().networking(false).sessionUpdateInterval(60).logGatheringOn("gather_tick", "e", 100));
            //a tick of the replaced fast timer may still be running
            Thread.sleep(250);
            awaitLogDelivery();
            logger.e("[Test] tail line");
            Assert.assertEquals(1, logger.heldLogLineCount());
            Set<Thread> deliveryThreads = liveDeliveryThreads();
            Assert.assertFalse(deliveryThreads.isEmpty());
            int queuedBeforeStop = queuedLogRequests.size();

            Countly.instance().stop();
            Assert.assertEquals(queuedBeforeStop + 1, queuedLogRequests.size());
            JSONObject tail = null;
            for (JSONObject batch : storedBatchesOnDisk()) {
                if (messagesOf(batch).contains("[Test] tail line")) {
                    tail = batch;
                }
            }
            Assert.assertNotNull("stop did not queue the held line", tail);
            Assert.assertEquals("gather_tick", tail.getString("i"));
            Assert.assertEquals(Arrays.asList("[Test] tail line"), messagesOf(tail));

            Assert.assertEquals(LogGatheringState.NOT_GATHERING, logger.logGatheringState);
            logger.e("[Test] logged after stop");
            logger.flushGatheredLogs();
            Assert.assertEquals(0, logger.heldLogLineCount());
            Assert.assertNull(logger.logDeliveryExecutor);
            Assert.assertEquals(queuedBeforeStop + 1, queuedLogRequests.size());
            Assert.assertTrue("the delivery thread outlived the SDK", TestUtils.waitFor(5_000, () -> {
                for (Thread thread : deliveryThreads) {
                    if (thread.isAlive()) {
                        return false;
                    }
                }
                return true;
            }));

            server.respondWith(new ServerConfigBuilder().networking(false).logGatheringOn("gather_halt", "e", 100));
            init(TestUtils.getConfigSdkBehaviorSettings().setLogListener(listener));
            Log halted = logger();
            assertGathering("gather_halt", "e", 100);
            awaitLogDelivery();
            int heldAtStart = halted.heldLogLineCount();
            halted.e("[Test] halted line");
            Assert.assertEquals(heldAtStart + 1, halted.heldLogLineCount());
            int queuedBeforeHalt = queuedLogRequests.size();

            Countly.instance().halt();
            Assert.assertEquals(queuedBeforeHalt, queuedLogRequests.size());
            Assert.assertEquals(LogGatheringState.NOT_GATHERING, halted.logGatheringState);
            Assert.assertEquals(0, halted.heldLogLineCount());
        } finally {
            Countly.instance().halt();
            httpServer.stop(0);
        }
    }

    /**
     * The threads that upload a batch never gather what they log meanwhile, or every upload would
     * report on itself, while the lines about ordinary requests are gathered.
     * <p>
     * Verifies against a local server that, once uploads and an ordinary event request were sent,
     * no uploaded line quotes a batch, comes from the delivery thread or reports the preparing,
     * sending or completion of an upload, while the completion of the event request is reported.
     */
    @Test
    public void uploadThreads_neverGatherTheirOwnLines_whileLinesAboutOrdinaryRequestsAreGathered() throws Exception {
        List<ReceivedRequest> requests = new CopyOnWriteArrayList<>();
        HttpServer httpServer = startRecordingServer(requests);

        List<String> heard = new CopyOnWriteArrayList<>();
        try {
            Config config = localConfig(httpServer)
                .enableFeatures(Config.Feature.Events)
                .setEventQueueSizeToSend(1)
                .setLogListener((message, level) -> heard.add(message));
            server.respondWith(new ServerConfigBuilder().logGatheringOn("gather_net", "ewidv", 50));
            init(config);

            Assert.assertTrue("no upload arrived", TestUtils.waitFor(30_000, () -> !receivedBatches(requests).isEmpty()));
            Assert.assertTrue("no upload completed", TestUtils.waitFor(30_000, () -> !completedRequests(heard, requestIds(heard, Log.transportMarker)).isEmpty()));

            Countly.instance().events().recordEvent("ordinary_event");
            Assert.assertTrue("the event request never arrived", TestUtils.waitFor(30_000, () -> requests.stream().anyMatch(request -> request.params.containsKey("events"))));
            Assert.assertTrue("the event request never completed", TestUtils.waitFor(30_000, () -> !completedRequests(heard, requestIds(heard, "events=")).isEmpty()));
            Set<String> eventIds = completedRequests(heard, requestIds(heard, "events="));

            logger().flushGatheredLogs();
            Assert.assertTrue("the completion of the event request was not uploaded", TestUtils.waitFor(30_000, () -> {
                for (String line : receivedLines(receivedBatches(requests))) {
                    for (String eventId : eventIds) {
                        if (line.startsWith("[Networking] Request " + eventId + " sent?: true")) {
                            return true;
                        }
                    }
                }
                return false;
            }));

            Set<String> uploadIds = completedRequests(heard, requestIds(heard, Log.transportMarker));
            Assert.assertFalse(uploadIds.isEmpty());
            for (JSONObject batch : receivedBatches(requests)) {
                Assert.assertEquals("gather_net", batch.getString("i"));
            }
            for (String line : receivedLines(receivedBatches(requests))) {
                Assert.assertFalse(line, line.contains(Log.keySdkLogs));
                Assert.assertFalse(line, line.startsWith("[Log] deliverHeldLogBatches"));
                for (String uploadId : uploadIds) {
                    Assert.assertFalse(line, line.contains("Request " + uploadId + " sent?"));
                    Assert.assertFalse(line, line.contains("for request[" + uploadId + "]"));
                }
            }
        } finally {
            Countly.instance().halt();
            httpServer.stop(0);
        }
    }

    /**
     * An armed SDK that is otherwise idle uploads nothing about its own uploads.
     * <p>
     * Verifies against a local server that once the lines logged before went out a tick uploads
     * nothing, that a line logged then goes out in one batch on the next tick, and that the ticks
     * after that upload nothing, as neither queueing nor sending a batch, nor the drain that
     * follows it, is gathered.
     */
    @Test
    public void idleArmedSdk_uploadsNothingAboutItsOwnUploads_onTheTicksAfterAnUpload() throws Exception {
        List<ReceivedRequest> requests = new CopyOnWriteArrayList<>();
        HttpServer httpServer = startRecordingServer(requests);
        try {
            server.respondWith(new ServerConfigBuilder().logGatheringOn("gather_idle", "ewidv", 100));
            init(localConfig(httpServer));

            awaitIdleTicks(requests);
            logger().e("[Test] idle probe");
            Assert.assertEquals(1, tickAndCountBatches(requests));
            List<JSONObject> received = receivedBatches(requests);
            Assert.assertTrue(messagesOf(received.get(received.size() - 1)).contains("[Test] idle probe"));
            for (int tick = 0; tick < 3; tick++) {
                Assert.assertEquals("tick " + tick, 0, tickAndCountBatches(requests));
            }
            Assert.assertEquals(0, logger().heldLogLineCount());
        } finally {
            Countly.instance().halt();
            httpServer.stop(0);
        }
    }

    /**
     * While nothing is sent, one log batch waits in the request queue and the rest stays in the
     * capped buffer; once networking is back on, the batches go out one after another until nothing
     * is held.
     * <p>
     * Verifies, with networking off, that full batches and flushes queue nothing while a batch
     * waits, the lines staying held; then, with networking on against a local server, that every
     * held line arrives once, in order, with never more than one batch stored at a time.
     */
    @Test
    public void networkingOff_queuesOneBatchAtATime_andTheRestFollowsOnceItIsSent() throws Exception {
        List<ReceivedRequest> requests = new CopyOnWriteArrayList<>();
        HttpServer httpServer = startRecordingServer(requests);
        try {
            server.respondWith(new ServerConfigBuilder().networking(false).logGatheringOn("gather_offline", "e", 10));
            init(localConfig(httpServer));
            awaitLogDelivery();
            Log logger = logger();
            for (int i = 0; i < 30; i++) {
                logger.e("[Test] offline line " + i);
            }
            for (int flush = 0; flush < 3; flush++) {
                logger.flushGatheredLogs();
                awaitLogDelivery();
            }
            Assert.assertEquals(1, storedBatchCount());
            Assert.assertTrue(logger.heldLogLineCount() >= 20);

            push(new ServerConfigBuilder().logGatheringOn("gather_offline", "e", 10));
            int mostStored = 0;
            long end = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < end) {
                mostStored = Math.max(mostStored, storedBatchCount());
                if (logger.heldLogLineCount() == 0 && storedBatchCount() == 0) {
                    break;
                }
                Thread.sleep(5);
            }
            awaitSentBatches();
            Assert.assertEquals(1, mostStored);
            Assert.assertEquals(numbered("[Test] offline line ", 0, 30), linesStartingWith("[Test] offline line ", receivedBatches(requests)));
        } finally {
            Countly.instance().halt();
            httpServer.stop(0);
        }
    }

    /**
     * A device ID change without merge queues the gathered lines under the previous device ID and
     * stops the gather, an answer requested for a previous device ID does not arm it again, and a
     * change with merge keeps a gather running.
     * <p>
     * Verifies, with networking off, that the line logged before the change is stored before the
     * change returns, under the previous device ID and with the gather id; that nothing logged
     * afterwards is held; that a settings answer requested before a later change and applied after
     * it leaves the gather stopped while its other settings apply; that an answer for the current
     * device ID arms a gather again; that a change with merge leaves that gather running; and that
     * an answer requested before a change with merge arms its gather.
     */
    @Test
    public void deviceIdChangeWithoutMerge_queuesTheLinesUnderThePreviousId_andStopsTheGather() throws Exception {
        server.respondWith(new ServerConfigBuilder().networking(false).logGatheringOn("gather_user_a", "e", 100));
        init(TestUtils.getConfigSdkBehaviorSettings());
        awaitLogDelivery();
        logger().e("[Test] line of user A");
        Countly.instance().deviceId().changeWithoutMerge("user_B");

        Map<String, String> userA = null;
        for (Map<String, String> request : logRequests()) {
            if (request.get(Log.keySdkLogs).contains("[Test] line of user A")) {
                userA = request;
            }
        }
        Assert.assertNotNull("the line of user A was not queued before the change", userA);
        Assert.assertEquals(TestUtils.DEVICE_ID, userA.get("device_id"));
        Assert.assertEquals("gather_user_a", new JSONObject(userA.get(Log.keySdkLogs)).getString("i"));
        assertNotGathering();
        logger().e("[Test] line of user B");
        Assert.assertEquals(0, logger().heldLogLineCount());

        AtomicReference<ImmediateRequestMaker.InternalImmediateRequestCallback> heldAnswer = new AtomicReference<>();
        InternalConfig config = SDKCore.instance.config;
        config.immediateRequestGenerator = () -> (requestData, customEndpoint, transport, requestShouldBeDelayed, networkingIsEnabled, callback, log) -> heldAnswer.set(callback);
        module().fetchConfigFromServer(config);
        Assert.assertNotNull(heldAnswer.get());
        Countly.instance().deviceId().changeWithoutMerge("user_C");
        heldAnswer.get().callback(new JSONObject(new ServerConfigBuilder().networking(false).sessionUpdateInterval(45).logGatheringOn("gather_stale", "e", 100).build()));
        assertNotGathering();
        Assert.assertEquals(45, module().getSessionUpdateInterval());

        config.immediateRequestGenerator = server;
        push(new ServerConfigBuilder().networking(false).logGatheringOn("gather_user_c", "e", 100));
        assertGathering("gather_user_c", "e", 100);
        Countly.instance().deviceId().changeWithMerge("user_D");
        assertGathering("gather_user_c", "e", 100);

        config.immediateRequestGenerator = () -> (requestData, customEndpoint, transport, requestShouldBeDelayed, networkingIsEnabled, callback, log) -> heldAnswer.set(callback);
        module().fetchConfigFromServer(config);
        Countly.instance().deviceId().changeWithMerge("user_E");
        heldAnswer.get().callback(new JSONObject(new ServerConfigBuilder().networking(false).logGatheringOn("gather_after_merge", "e", 100).build()));
        assertGathering("gather_after_merge", "e", 100);
    }

    /**
     * A gather the server stops queues the lines it held once, with its id, and a gather replaced
     * by a new id drops them.
     * <p>
     * Verifies, with networking off and tracking on, that after {@code "e":false} exactly the lines
     * held until then are stored, in one batch with the stopped gather's id, past the batch already
     * waiting, and nothing logged afterwards; and that a gather replaced by a new id holds none of
     * the lines of the one before.
     */
    @Test
    public void stoppedGather_queuesItsHeldLinesOnce_whileAReplacedGatherDropsThem() throws Exception {
        server.respondWith(new ServerConfigBuilder().networking(false).logGatheringOn("gather_stopped", "e", 100));
        init(TestUtils.getConfigSdkBehaviorSettings());
        awaitLogDelivery();
        logger().e("[Test] stopped 1");
        logger().e("[Test] stopped 2");

        push(new ServerConfigBuilder().networking(false).logGatheringOff());
        assertNotGathering();
        Assert.assertTrue("the held lines were not queued", TestUtils.waitFor(10_000, () -> linesStartingWith("[Test] stopped ", batches()).size() == 2));
        JSONObject tail = null;
        for (JSONObject batch : batches()) {
            if (messagesOf(batch).contains("[Test] stopped 1")) {
                tail = batch;
            }
        }
        Assert.assertNotNull(tail);
        Assert.assertEquals("gather_stopped", tail.getString("i"));
        Assert.assertEquals(Arrays.asList("[Test] stopped 1", "[Test] stopped 2"), linesStartingWith("[Test] stopped ", Arrays.asList(tail)));
        logger().e("[Test] after the stop");
        Thread.sleep(200);
        Assert.assertTrue(linesStartingWith("[Test] after the stop", batches()).isEmpty());

        push(new ServerConfigBuilder().networking(false).logGatheringOn("gather_first", "e", 100));
        logger().e("[Test] first gather line");
        Assert.assertTrue(logger().heldLogLineCount() >= 1);
        push(new ServerConfigBuilder().networking(false).logGatheringOn("gather_second", "e", 100));
        assertGathering("gather_second", "e", 100);
        Assert.assertEquals(0, logger().heldLogLineCount());
    }

    /**
     * Queueing a batch that is given up while the storage is busy cancels its store.
     * <p>
     * Verifies, with the storage thread held busy, that a queueing interrupted while it waits for
     * the store reports the batch as not queued, and that the store never runs once the storage is
     * free again, so the batch is not stored behind the lines kept for another batch.
     */
    @Test
    public void queueingABatch_givenUpWhileTheStorageIsBusy_cancelsItsStore() throws Exception {
        server.respondWith(new ServerConfigBuilder().networking(false).logGatheringOff());
        init(TestUtils.getConfigSdkBehaviorSettings());
        InternalConfig config = SDKCore.instance.config;
        Storage.await(mock(Log.class));
        int storedBefore = logRequests().size();

        CountDownLatch storageBusy = new CountDownLatch(1);
        CountDownLatch storageFree = new CountDownLatch(1);
        Storage.removeAsync(config, new Request(1L), removed -> {
            storageBusy.countDown();
            storageFree.await(30, TimeUnit.SECONDS);
        });
        AtomicReference<Long> queuedBatch = new AtomicReference<>(-1L);
        Thread queueing = new Thread(() -> queuedBatch.set(ModuleRequests.pushSdkLogs(config, "{\"i\":\"gather_cancel\",\"d\":0,\"l\":[]}")));
        try {
            Assert.assertTrue(storageBusy.await(30, TimeUnit.SECONDS));
            queueing.start();
            Assert.assertTrue("the queueing never waited for its store", TestUtils.waitFor(30_000, () -> isWaitingForAFuture(queueing)));
            queueing.interrupt();
            queueing.join(30_000);
        } finally {
            storageFree.countDown();
        }

        Assert.assertNull(queuedBatch.get());
        Storage.await(mock(Log.class));
        Assert.assertEquals(storedBefore, logRequests().size());
    }

    // endregion
    // region helpers

    /**
     * A request a local server received.
     */
    private static final class ReceivedRequest {
        final String method;
        final Map<String, String> params;

        /**
         * Holds one request.
         *
         * @param method the HTTP method it came with
         * @param params its parameters, from the query and the body
         */
        ReceivedRequest(String method, Map<String, String> params) {
            this.method = method;
            this.params = params;
        }
    }

    /**
     * Starts a local server that accepts every request and records it.
     *
     * @param requests where the requests go, in arrival order
     * @return the started server
     * @throws IOException when the server cannot start
     */
    private static HttpServer startRecordingServer(List<ReceivedRequest> requests) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        httpServer.createContext("/", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            requests.add(new ReceivedRequest(exchange.getRequestMethod(), decode((query == null ? "" : query) + "&" + readAll(exchange.getRequestBody()))));
            byte[] body = "{\"result\":\"Success\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        httpServer.start();
        return httpServer;
    }

    /**
     * The log batches among the requests a local server received.
     *
     * @param requests the requests, in arrival order
     * @return the batches, in arrival order
     */
    private static List<JSONObject> receivedBatches(List<ReceivedRequest> requests) {
        List<JSONObject> received = new ArrayList<>();
        for (ReceivedRequest request : requests) {
            if (request.params.containsKey(Log.keySdkLogs)) {
                received.add(new JSONObject(request.params.get(Log.keySdkLogs)));
            }
        }
        return received;
    }

    /**
     * The test configuration, sending to a local server instead of the test server.
     *
     * @param httpServer the local server
     * @return the configuration
     */
    private static Config localConfig(HttpServer httpServer) {
        return new Config("http://localhost:" + httpServer.getAddress().getPort(), TestUtils.SERVER_APP_KEY, TestUtils.getTestSDirectory())
            .setApplicationVersion(TestUtils.APPLICATION_VERSION)
            .setCustomDeviceId(TestUtils.DEVICE_ID)
            .setNetworkRequestCooldown(0);
    }

    /**
     * Waits until every batch the logger queued was sent and no delivery is left to run, as each
     * sent batch lets the next one go.
     *
     * @throws Exception when the batches are not all sent in time
     */
    private static void awaitSentBatches() throws Exception {
        for (int round = 0; round < 200; round++) {
            awaitLogDelivery();
            Assert.assertTrue("a queued batch never reached the server", TestUtils.waitFor(30_000, () -> storedBatchCount() == 0));
            awaitLogDelivery();
            if (storedBatchCount() == 0) {
                return;
            }
        }
        Assert.fail("the logger kept queueing batches");
    }

    /**
     * The log batches stored on disk, read straight from the request files, which works once the
     * SDK stopped too.
     *
     * @return the batches
     * @throws IOException when a request file cannot be read
     */
    private static List<JSONObject> storedBatchesOnDisk() throws IOException {
        List<JSONObject> stored = new ArrayList<>();
        for (String params : storedRequestParams()) {
            Map<String, String> decoded = decode(params);
            if (decoded.containsKey(Log.keySdkLogs)) {
                stored.add(new JSONObject(decoded.get(Log.keySdkLogs)));
            }
        }
        return stored;
    }

    /**
     * Ticks until a tick queues no batch, which an idle SDK reaches once the lines it logged before
     * went out, as nothing it logs about a batch is gathered.
     *
     * @param requests the requests a local server received
     * @throws Exception when a batch does not go out in time
     */
    private static void awaitIdleTicks(List<ReceivedRequest> requests) throws Exception {
        for (int tick = 0; tick < 10; tick++) {
            if (tickAndCountBatches(requests) == 0) {
                return;
            }
        }
        Assert.fail("every tick queued another batch, so each batch reported on the one before");
    }

    /**
     * Flushes what is held, as a timer tick does, waits until the batches it queued are sent, then
     * lets the drain that follows them run.
     *
     * @param requests the requests a local server received
     * @return how many batches the tick queued
     * @throws Exception when a batch does not go out in time
     */
    private static int tickAndCountBatches(List<ReceivedRequest> requests) throws Exception {
        int before = settledBatchCount(requests);
        logger().flushGatheredLogs();
        awaitLogDelivery();
        int after = settledBatchCount(requests);
        //nothing tells when the drain a stored batch asked for has finished, so it gets a moment
        Thread.sleep(200);
        return after - before;
    }

    /**
     * How many batches a local server received, counted once every stored one was sent.
     *
     * @param requests the requests a local server received
     * @return the number of batches
     * @throws InterruptedException when interrupted while waiting
     */
    private static int settledBatchCount(List<ReceivedRequest> requests) throws InterruptedException {
        Assert.assertTrue("a queued batch never reached the server", TestUtils.waitFor(30_000, () -> storedBatchCount() == 0));
        return receivedBatches(requests).size();
    }

    /**
     * How many stored requests carry a log batch, read straight from disk, skipping a request the
     * queue removes while it is read.
     *
     * @return the number of requests
     */
    private static int storedBatchCount() {
        File[] files = TestUtils.getTestSDirectory().listFiles((directory, name) -> name.startsWith(REQUEST_FILE_PREFIX));
        int count = 0;
        for (File file : files == null ? new File[0] : files) {
            try {
                if (new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).contains(Log.transportMarker)) {
                    count++;
                }
            } catch (IOException e) {
                //sent and removed while it was read
            }
        }
        return count;
    }

    /**
     * Whether a thread is waiting for the result of a future.
     *
     * @param thread the thread
     * @return {@code true} while it waits in {@link java.util.concurrent.Future#get}
     */
    private static boolean isWaitingForAFuture(Thread thread) {
        for (StackTraceElement frame : thread.getStackTrace()) {
            if ("java.util.concurrent.FutureTask".equals(frame.getClassName()) && "get".equals(frame.getMethodName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Initializes the SDK with {@link #server} answering its settings requests.
     *
     * @param config the configuration
     */
    private void init(Config config) {
        InternalConfig internalConfig = new InternalConfig(config);
        internalConfig.immediateRequestGenerator = server;
        Countly.instance().init(internalConfig);
    }

    /**
     * Answers the next settings request with the given payload and makes the SDK fetch, as the
     * refresh timer does.
     *
     * @param builder the payload
     */
    private void push(ServerConfigBuilder builder) {
        server.respondWith(builder);
        module().fetchConfigFromServer(SDKCore.instance.config);
    }

    /**
     * Answers the next settings request with a payload that stops the gather, makes the SDK fetch,
     * and waits until the delivery thread offered the last batch of the stopped gather to the
     * request queue and ended, so the settings that follow cannot change how the queue answers.
     *
     * @param builder the payload
     * @throws InterruptedException when interrupted while waiting
     */
    private void pushStop(ServerConfigBuilder builder) throws InterruptedException {
        ExecutorService executor = logger().logDeliveryExecutor;
        push(builder);
        if (executor != null) {
            Assert.assertTrue("the delivery thread did not end", executor.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    /**
     * An armed directive next to settings that switch tracking off, so the gathered lines are held,
     * and networking off, so nothing queued is sent.
     *
     * @param gatherId {@code i}
     * @param levels {@code l}
     * @param batchSize {@code b}
     * @return the payload
     */
    private static ServerConfigBuilder heldGather(String gatherId, String levels, int batchSize) {
        return new ServerConfigBuilder().networking(false).tracking(false).logGatheringOn(gatherId, levels, batchSize);
    }

    /**
     * The configuration module of the running SDK.
     *
     * @return the module
     */
    private static ModuleConfiguration module() {
        return SDKCore.instance.module(ModuleConfiguration.class);
    }

    /**
     * The logger of the running SDK.
     *
     * @return the logger
     */
    private static Log logger() {
        return SDKCore.instance.L;
    }

    /**
     * Waits until every delivery the logger was asked for so far ran, and until the storage wrote
     * every request it was handed.
     *
     * @throws Exception when the delivery thread does not finish in time
     */
    private static void awaitLogDelivery() throws Exception {
        ExecutorService executor = logger().logDeliveryExecutor;
        if (executor != null) {
            executor.submit(() -> {
            }).get(30, TimeUnit.SECONDS);
        }
        Storage.await(mock(Log.class));
    }

    /**
     * Asserts the directive twice over: as the configuration module decided it and as the logger
     * mirrors it, which is what decides the capture.
     *
     * @param gatherId the gather id
     * @param levels the level characters
     * @param batchSize the batch size
     */
    private static void assertGathering(String gatherId, String levels, int batchSize) {
        ConfigurationProvider provider = SDKCore.instance.config.getConfigurationProvider();
        Assert.assertEquals(LogGatheringState.GATHERING, provider.getLogGatheringState());
        Assert.assertEquals(gatherId, provider.getLogGatheringId());
        Assert.assertEquals(levels, provider.getLogGatheringLevels());
        Assert.assertEquals(batchSize, provider.getLogGatheringBatchSize());

        Log logger = logger();
        Assert.assertEquals(LogGatheringState.GATHERING, logger.logGatheringState);
        Assert.assertTrue(logger.isGatheringLogs());
        Assert.assertTrue(logger.isCapturingLogs());
        Assert.assertEquals(gatherId, logger.logGatherId);
        Assert.assertEquals(levels, logger.logGatherLevels);
        Assert.assertEquals(batchSize, logger.logGatherBatchSize);
    }

    /**
     * Asserts that a decision against gathering was mirrored: nothing is captured or held, no
     * gather id is left and no delivery thread is kept.
     */
    private static void assertNotGathering() {
        ConfigurationProvider provider = SDKCore.instance.config.getConfigurationProvider();
        Assert.assertEquals(LogGatheringState.NOT_GATHERING, provider.getLogGatheringState());
        Assert.assertNull(provider.getLogGatheringId());

        Log logger = logger();
        Assert.assertEquals(LogGatheringState.NOT_GATHERING, logger.logGatheringState);
        Assert.assertFalse(logger.isGatheringLogs());
        Assert.assertFalse(logger.isCapturingLogs());
        Assert.assertNull(logger.logGatherId);
        Assert.assertEquals(ModuleConfiguration.logGatheringAllLevels, logger.logGatherLevels);
        Assert.assertEquals(ModuleConfiguration.logGatheringDefaultBatchSize, logger.logGatherBatchSize);
        Assert.assertEquals(0, logger.heldLogLineCount());
        Assert.assertNull(logger.logDeliveryExecutor);
    }

    /**
     * The queued requests that carry a log batch, oldest first.
     *
     * @return the parameters of each request
     */
    private static List<Map<String, String>> logRequests() {
        List<Map<String, String>> requests = new ArrayList<>();
        for (Map<String, String> request : TestUtils.getCurrentRQ()) {
            if (request.containsKey(Log.keySdkLogs)) {
                requests.add(request);
            }
        }
        return requests;
    }

    /**
     * The queued log batches, oldest first.
     *
     * @return the batches
     */
    private static List<JSONObject> batches() {
        List<JSONObject> batches = new ArrayList<>();
        for (Map<String, String> request : logRequests()) {
            batches.add(new JSONObject(request.get(Log.keySdkLogs)));
        }
        return batches;
    }

    /**
     * The messages of a batch, in order.
     *
     * @param batch the batch
     * @return the messages
     */
    private static List<String> messagesOf(JSONObject batch) {
        List<String> messages = new ArrayList<>();
        JSONArray lines = batch.getJSONArray("l");
        for (int i = 0; i < lines.length(); i++) {
            messages.add(lines.getJSONObject(i).getString("m"));
        }
        return messages;
    }

    /**
     * The messages of every batch that start with the given prefix, in upload order.
     *
     * @param prefix the prefix
     * @param batches the batches
     * @return the messages
     */
    private static List<String> linesStartingWith(String prefix, List<JSONObject> batches) {
        List<String> messages = new ArrayList<>();
        for (JSONObject batch : batches) {
            for (String message : messagesOf(batch)) {
                if (message.startsWith(prefix)) {
                    messages.add(message);
                }
            }
        }
        return messages;
    }

    /**
     * The messages of every batch a server received.
     *
     * @param received the batches, in arrival order
     * @return the messages
     */
    private static List<String> receivedLines(List<JSONObject> received) {
        List<String> messages = new ArrayList<>();
        for (JSONObject batch : new ArrayList<>(received)) {
            messages.addAll(messagesOf(batch));
        }
        return messages;
    }

    /**
     * The storage IDs of the queued requests whose parameters contain the given text, read off
     * the lines the SDK logs as it queues a request.
     *
     * @param heard every line the SDK logged
     * @param paramsFragment the text
     * @return the IDs
     */
    private static Set<String> requestIds(List<String> heard, String paramsFragment) {
        Pattern queued = Pattern.compile("^New request (\\d+): ");
        Set<String> ids = new HashSet<>();
        for (String line : heard) {
            Matcher matcher = queued.matcher(line == null ? "" : line);
            if (matcher.find() && line.contains(paramsFragment)) {
                ids.add(matcher.group(1));
            }
        }
        return ids;
    }

    /**
     * The given requests the SDK reported as sent and accepted.
     *
     * @param heard every line the SDK logged
     * @param ids storage IDs of requests
     * @return the IDs of those that completed
     */
    private static Set<String> completedRequests(List<String> heard, Set<String> ids) {
        Set<String> completed = new HashSet<>();
        for (String line : heard) {
            for (String id : ids) {
                if (line != null && line.startsWith("[Networking] Request " + id + " sent?: true")) {
                    completed.add(id);
                }
            }
        }
        return completed;
    }

    /**
     * The live delivery threads of every logger.
     *
     * @return the threads
     */
    private static Set<Thread> liveDeliveryThreads() {
        Set<Thread> threads = new HashSet<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (Log.DELIVERY_THREAD_NAME.equals(thread.getName()) && thread.isAlive()) {
                threads.add(thread);
            }
        }
        return threads;
    }

    /**
     * The parameters of every stored request, oldest first, read straight from disk, so they can be
     * read after the storage stopped.
     *
     * @return the url encoded parameters of each request
     * @throws IOException when a request cannot be read
     */
    private static List<String> storedRequestParams() throws IOException {
        File[] files = TestUtils.getTestSDirectory().listFiles((directory, name) -> name.startsWith(REQUEST_FILE_PREFIX));
        List<File> sorted = new ArrayList<>(Arrays.asList(files == null ? new File[0] : files));
        sorted.sort(Comparator.comparing(file -> Long.parseLong(file.getName().substring(REQUEST_FILE_PREFIX.length()))));

        List<String> params = new ArrayList<>();
        for (File file : sorted) {
            String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            int end = content.indexOf('\n');
            params.add(end < 0 ? content : content.substring(0, end));
        }
        return params;
    }

    /**
     * Decodes url encoded parameters.
     *
     * @param params the parameters
     * @return the parameters by name
     */
    private static Map<String, String> decode(String params) {
        Map<String, String> decoded = new HashMap<>();
        for (String pair : params.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            String[] keyValue = pair.split("=", 2);
            decoded.put(Utils.urldecode(keyValue[0]), keyValue.length > 1 ? Utils.urldecode(keyValue[1]) : "");
        }
        return decoded;
    }

    /**
     * Reads a stream to its end.
     *
     * @param stream the stream
     * @return its content as UTF-8 text
     * @throws IOException when it cannot be read
     */
    private static String readAll(InputStream stream) throws IOException {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            content.write(buffer, 0, read);
        }
        return new String(content.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * Numbered lines, a prefix followed by each number in a range.
     *
     * @param prefix the prefix
     * @param from the first number
     * @param to one past the last number
     * @return the lines
     */
    private static List<String> numbered(String prefix, int from, int to) {
        List<String> lines = new ArrayList<>();
        for (int i = from; i < to; i++) {
            lines.add(prefix + i);
        }
        return lines;
    }

    /**
     * A text of one character repeated.
     *
     * @param character the character
     * @param count how many times
     * @return the text
     */
    private static String repeat(char character, int count) {
        StringBuilder text = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            text.append(character);
        }
        return text.toString();
    }


    // endregion
}
