package ly.count.sdk.java.internal;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URL;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
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
     * Verifies that the lines of the directive's levels logged during init, before any response,
     * are queued once init finishes, the very first line of the SDK first; that every batch echoes
     * the gather id, carries the required request parameters and at most {@code b} lines, each
     * with exactly {@code t}, {@code l} and {@code m}, none of another level and none quoting a
     * batch; that a full batch goes out on its own while a partial one waits for a flush; and that a
     * full batch is too long for a GET, so it is sent as a POST. An unknown top level key next to
     * {@code lg} changes none of it.
     */
    @Test
    public void armedDirective_uploadsTheInitLinesOfItsLevels_withTheGatherIdInBatchesOfAtMostB() throws Exception {
        long startMs = System.currentTimeMillis();
        server.respondWith(new ServerConfigBuilder().networking(false)
            .logGatheringOn("gather_init", "wi", 10)
            .topLevelKey("someFutureFeature", "a value this SDK has never heard of"));
        init(TestUtils.getConfigSdkBehaviorSettings());
        assertGathering("gather_init", "wi", 10);

        awaitLogDelivery();
        List<JSONObject> initBatches = batches();
        Assert.assertFalse("init end queued nothing", initBatches.isEmpty());
        Assert.assertEquals("[SDKCore] Initializing Countly", initBatches.get(0).getJSONArray("l").getJSONObject(0).getString("m"));

        String padding = repeat('p', 80);
        List<String> armedLines = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            armedLines.add("[Test] armed line " + i + " " + padding);
            logger().w(armedLines.get(i));
        }
        awaitLogDelivery();
        List<String> beforeFlush = linesStartingWith("[Test] armed line ", batches());
        Assert.assertTrue("[" + beforeFlush.size() + "] lines before a flush", beforeFlush.size() >= 10 && beforeFlush.size() < armedLines.size());
        Assert.assertEquals(armedLines.subList(0, beforeFlush.size()), beforeFlush);

        logger().flushGatheredLogs();
        awaitLogDelivery();
        Assert.assertEquals(armedLines, linesStartingWith("[Test] armed line ", batches()));

        List<Map<String, String>> requests = logRequests();
        for (Map<String, String> request : requests) {
            TestUtils.validateRequiredParams(request);
            JSONObject batch = new JSONObject(request.get(Log.keySdkLogs));
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
        }

        int fullArmedBatches = 0;
        for (String params : storedRequestParams()) {
            Map<String, String> decoded = decode(params);
            if (decoded.containsKey(Log.keySdkLogs) && decoded.get(Log.keySdkLogs).contains("[Test] armed line 9 ")) {
                Request request = new Request(0L);
                request.params = new Params(params);
                Assert.assertFalse("a full batch fits a GET", request.isGettable(new URL(TestUtils.SERVER_URL)));
                fullArmedBatches++;
            }
        }
        Assert.assertEquals(1, fullArmedBatches);
    }

    /**
     * While tracking is off the lines are held, the buffer keeps the newest 500 of them, and the
     * lines it drops are reported once, with the next batch.
     * <p>
     * Verifies that nothing is queued while tracking is off, flush or not; that once tracking is back
     * on the held lines go out oldest first in full batches of {@code b}, the first one reporting
     * every dropped line in {@code d}, so the uploaded and the dropped lines add up to every line
     * logged; and that a {@code b} over the buffer ceiling is clamped to it, so no batch ever holds
     * more than 500 lines.
     */
    @Test
    public void trackingOffHold_keepsTheNewest500Lines_andDAccountsForEveryDroppedLine() throws Exception {
        server.respondWith(heldGather("gather_hold", "e", 25));
        init(TestUtils.getConfigSdkBehaviorSettings());
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

        push(new ServerConfigBuilder().networking(false).tracking(true).logGatheringOn("gather_hold", "e", 25));
        assertGathering("gather_hold", "e", 25);
        Assert.assertEquals(ModuleConfiguration.logGatheringMaxBufferedLines, logger.heldLogLineCount());
        logger.flushGatheredLogs();
        awaitLogDelivery();
        Assert.assertEquals(0, logger.heldLogLineCount());

        List<JSONObject> batches = batches();
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
        push(new ServerConfigBuilder().networking(false).tracking(true).logGatheringOn("gather_hold", "e", 9999));
        logger.flushGatheredLogs();
        awaitLogDelivery();

        List<JSONObject> later = batches().subList(20, batches().size());
        Assert.assertEquals(1, later.size());
        Assert.assertEquals(heldBefore + 100, later.get(0).getInt("d"));
        Assert.assertEquals(numbered("[Test] second line ", 100, 600), messagesOf(later.get(0)));
    }

    /**
     * Every way a gather ends drops what it holds, and a new gather never carries the lines of an
     * earlier one.
     * <p>
     * Verifies, with tracking off so nothing leaves until the end, that the same directive repeated
     * and a change of levels or batch size within one gather keep the held lines; that
     * {@code "e":false}, a directive without a usable gather id and a live response without any
     * directive each drop them, stop the capture and release the delivery thread; that a new gather
     * id drops the lines of the previous gather; and that only the lines of the gather in effect,
     * with its id, are ever uploaded.
     */
    @Test
    public void endedOrReplacedGather_dropsTheHeldLines_andANewGatherNeverCarriesTheOldOnes() throws Exception {
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

        push(new ServerConfigBuilder().networking(false).tracking(false).logGatheringOff());
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

        push(new ServerConfigBuilder().networking(false).tracking(false).logGatheringDirective(true, "  ", "e", 10));
        assertNotGathering();

        push(heldGather("gather_d", "e", 10));
        logger.e("[Test] d1");
        push(new ServerConfigBuilder().networking(false).tracking(false));
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
     * While consent is required and none is given, gathered lines are held, as they quote event
     * keys and whole requests.
     * <p>
     * Verifies that neither a full batch nor a flush queues anything before any consent is given,
     * and that once one is, a flush queues every held line.
     */
    @Test
    public void requiredConsent_holdsTheLinesUntilAnyConsentIsGiven() throws Exception {
        server.respondWith(new ServerConfigBuilder().networking(false).logGatheringOn("gather_consent", "e", 10));
        init(TestUtils.getConfigSdkBehaviorSettings().setRequiresConsent(true).enableFeatures(Config.Feature.Events));
        assertGathering("gather_consent", "e", 10);

        Log logger = logger();
        int initLines = logger.heldLogLineCount();
        List<String> consentLines = numbered("[Test] consent line ", 0, 12);
        for (String line : consentLines) {
            logger.e(line);
        }
        awaitLogDelivery();
        logger.flushGatheredLogs();
        awaitLogDelivery();
        Assert.assertTrue(logRequests().isEmpty());
        Assert.assertEquals(initLines + 12, logger.heldLogLineCount());

        Countly.onConsent(Config.Feature.Events);
        logger.flushGatheredLogs();
        awaitLogDelivery();
        Assert.assertEquals(0, logger.heldLogLineCount());
        Assert.assertEquals(consentLines, linesStartingWith("[Test] consent line ", batches()));
    }

    /**
     * The global timer flushes a batch that is not full, a stop queues what is held before the
     * storage stops, and a halt discards it. Either way the logger ends for good.
     * <p>
     * Verifies that a line below the batch size goes out on a tick of a fast global timer; that
     * {@code stop()} queues the held line synchronously, so it is on disk once stop returns, with
     * the gather id; that the stopped logger captures and queues nothing more and its delivery
     * thread ends; and that {@code halt()} queues nothing at all.
     */
    @Test
    public void timerTick_flushesAPartialBatch_stopQueuesWhatIsHeld_andHaltDiscardsIt() throws Exception {
        List<String> queuedLogRequests = new CopyOnWriteArrayList<>();
        LogCallback listener = (message, level) -> {
            if (message != null && message.startsWith("New request ") && message.contains(Log.transportMarker)) {
                queuedLogRequests.add(message);
            }
        };
        server.respondWith(new ServerConfigBuilder().networking(false).logGatheringOn("gather_tick", "e", 100));
        init(TestUtils.getConfigSdkBehaviorSettings().setLogListener(listener));
        Log logger = logger();
        awaitLogDelivery();

        //the timer ticks through the modules, so it is sped up only after init built them
        CountlyTimer.TIMER_DELAY_MS = 50;
        push(new ServerConfigBuilder().networking(false).sessionUpdateInterval(30).logGatheringOn("gather_tick", "e", 100));
        logger.e("[Test] tick line");
        Assert.assertTrue("no tick flushed the partial batch", waitFor(10_000, () -> queuedLinesContain(queuedLogRequests, "[Test] tick line")));

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
        for (String params : storedRequestParams()) {
            Map<String, String> decoded = decode(params);
            if (decoded.containsKey(Log.keySdkLogs) && decoded.get(Log.keySdkLogs).contains("[Test] tail line")) {
                tail = new JSONObject(decoded.get(Log.keySdkLogs));
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
        Assert.assertTrue("the delivery thread outlived the SDK", waitFor(5_000, () -> {
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
        halted.e("[Test] halted line");
        Assert.assertEquals(1, halted.heldLogLineCount());
        int queuedBeforeHalt = queuedLogRequests.size();

        Countly.instance().halt();
        Assert.assertEquals(queuedBeforeHalt, queuedLogRequests.size());
        Assert.assertEquals(LogGatheringState.NOT_GATHERING, halted.logGatheringState);
        Assert.assertEquals(0, halted.heldLogLineCount());
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
        List<JSONObject> received = new CopyOnWriteArrayList<>();
        AtomicInteger eventRequests = new AtomicInteger();
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        httpServer.createContext("/", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            Map<String, String> params = decode((query == null ? "" : query) + "&" + readAll(exchange.getRequestBody()));
            if (params.containsKey(Log.keySdkLogs)) {
                received.add(new JSONObject(params.get(Log.keySdkLogs)));
            }
            if (params.containsKey("events")) {
                eventRequests.incrementAndGet();
            }
            byte[] body = "{\"result\":\"Success\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        httpServer.start();

        List<String> heard = new CopyOnWriteArrayList<>();
        try {
            Config config = new Config("http://localhost:" + httpServer.getAddress().getPort(), TestUtils.SERVER_APP_KEY, TestUtils.getTestSDirectory())
                .setApplicationVersion(TestUtils.APPLICATION_VERSION)
                .setCustomDeviceId(TestUtils.DEVICE_ID)
                .enableFeatures(Config.Feature.Events)
                .setEventQueueSizeToSend(1)
                .setNetworkRequestCooldown(0)
                .setLogListener((message, level) -> heard.add(message));
            server.respondWith(new ServerConfigBuilder().logGatheringOn("gather_net", "ewidv", 50));
            init(config);

            Assert.assertTrue("no upload arrived", waitFor(30_000, () -> !received.isEmpty()));
            Assert.assertTrue("no upload completed", waitFor(30_000, () -> !completedRequests(heard, requestIds(heard, Log.transportMarker)).isEmpty()));

            Countly.instance().events().recordEvent("ordinary_event");
            Assert.assertTrue("the event request never arrived", waitFor(30_000, () -> eventRequests.get() > 0));
            Assert.assertTrue("the event request never completed", waitFor(30_000, () -> !completedRequests(heard, requestIds(heard, "events=")).isEmpty()));
            Set<String> eventIds = completedRequests(heard, requestIds(heard, "events="));

            logger().flushGatheredLogs();
            Assert.assertTrue("the completion of the event request was not uploaded", waitFor(30_000, () -> {
                for (String line : receivedLines(received)) {
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
            for (JSONObject batch : received) {
                Assert.assertEquals("gather_net", batch.getString("i"));
            }
            for (String line : receivedLines(received)) {
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
        List<JSONObject> received = new CopyOnWriteArrayList<>();
        HttpServer httpServer = startBatchServer(received);
        try {
            Config config = new Config("http://localhost:" + httpServer.getAddress().getPort(), TestUtils.SERVER_APP_KEY, TestUtils.getTestSDirectory())
                .setApplicationVersion(TestUtils.APPLICATION_VERSION)
                .setCustomDeviceId(TestUtils.DEVICE_ID)
                .setNetworkRequestCooldown(0);
            server.respondWith(new ServerConfigBuilder().logGatheringOn("gather_idle", "ewidv", 100));
            init(config);

            awaitIdleTicks(received);
            logger().e("[Test] idle probe");
            Assert.assertEquals(1, tickAndCountBatches(received));
            Assert.assertTrue(messagesOf(received.get(received.size() - 1)).contains("[Test] idle probe"));
            for (int tick = 0; tick < 3; tick++) {
                Assert.assertEquals("tick " + tick, 0, tickAndCountBatches(received));
            }
            Assert.assertEquals(0, logger().heldLogLineCount());
        } finally {
            Countly.instance().halt();
            httpServer.stop(0);
        }
    }

    /**
     * An armed SDK that is otherwise idle queues nothing about its own batches while networking is
     * off.
     * <p>
     * Verifies on disk that once the lines logged before were queued a tick queues nothing, that a
     * line logged then is queued in one batch on the next tick, and that the ticks after that queue
     * nothing, as neither storing a batch nor the drain it asks for, which networking off skips, is
     * gathered.
     */
    @Test
    public void idleArmedSdk_withNetworkingOff_queuesNothingAboutItsOwnBatches() throws Exception {
        server.respondWith(new ServerConfigBuilder().networking(false).logGatheringOn("gather_idle_off", "ewidv", 100));
        init(TestUtils.getConfigSdkBehaviorSettings());

        awaitIdleTicks(null);
        logger().e("[Test] idle probe");
        Assert.assertEquals(1, tickAndCountBatches(null));
        List<JSONObject> queued = batches();
        Assert.assertTrue(messagesOf(queued.get(queued.size() - 1)).contains("[Test] idle probe"));
        for (int tick = 0; tick < 3; tick++) {
            Assert.assertEquals("tick " + tick, 0, tickAndCountBatches(null));
        }
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
        AtomicReference<Boolean> queuedBatch = new AtomicReference<>();
        Thread queueing = new Thread(() -> queuedBatch.set(ModuleRequests.pushSdkLogs(config, "{\"i\":\"gather_cancel\",\"d\":0,\"l\":[]}")));
        try {
            Assert.assertTrue(storageBusy.await(30, TimeUnit.SECONDS));
            queueing.start();
            Assert.assertTrue("the queueing never waited for its store", waitFor(30_000, () -> isWaitingForAFuture(queueing)));
            queueing.interrupt();
            queueing.join(30_000);
        } finally {
            storageFree.countDown();
        }

        Assert.assertEquals(Boolean.FALSE, queuedBatch.get());
        Storage.await(mock(Log.class));
        Assert.assertEquals(storedBefore, logRequests().size());
    }

    // endregion
    // region helpers

    /**
     * Starts a local server that accepts every request and keeps the log batches it receives.
     *
     * @param received where the batches go, in arrival order
     * @return the started server
     * @throws IOException when the server cannot start
     */
    private static HttpServer startBatchServer(List<JSONObject> received) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        httpServer.createContext("/", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            Map<String, String> params = decode((query == null ? "" : query) + "&" + readAll(exchange.getRequestBody()));
            if (params.containsKey(Log.keySdkLogs)) {
                received.add(new JSONObject(params.get(Log.keySdkLogs)));
            }
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
     * Ticks until a tick queues no batch, which an idle SDK reaches once the lines it logged before
     * went out, as nothing it logs about a batch is gathered.
     *
     * @param received the batches a local server received, {@code null} while networking is off
     * @throws Exception when a batch does not go out in time
     */
    private static void awaitIdleTicks(@Nullable List<JSONObject> received) throws Exception {
        for (int tick = 0; tick < 10; tick++) {
            if (tickAndCountBatches(received) == 0) {
                return;
            }
        }
        Assert.fail("every tick queued another batch, so each batch reported on the one before");
    }

    /**
     * Flushes what is held, as a timer tick does, waits until the batches it queued are stored, and
     * sent while networking is on, then lets the drain that follows them run.
     *
     * @param received the batches a local server received, {@code null} while networking is off
     * @return how many batches the tick queued
     * @throws Exception when a batch does not go out in time
     */
    private static int tickAndCountBatches(@Nullable List<JSONObject> received) throws Exception {
        int before = settledBatchCount(received);
        logger().flushGatheredLogs();
        awaitLogDelivery();
        int after = settledBatchCount(received);
        //nothing tells when the drain a stored batch asked for has finished, so it gets a moment
        Thread.sleep(200);
        return after - before;
    }

    /**
     * How many batches were queued so far, counted while none is in flight: the stored ones while
     * networking is off, otherwise the ones a local server received once every stored one was sent.
     *
     * @param received the batches a local server received, {@code null} while networking is off
     * @return the number of batches
     * @throws InterruptedException when interrupted while waiting
     */
    private static int settledBatchCount(@Nullable List<JSONObject> received) throws InterruptedException {
        if (received == null) {
            return storedBatchCount();
        }
        Assert.assertTrue("a queued batch never reached the server", waitFor(30_000, () -> storedBatchCount() == 0));
        return received.size();
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
     * Whether any queued log request a listener heard of carries the given line.
     *
     * @param queuedLogRequests the lines the SDK logged when it queued each log request
     * @param line the line
     * @return {@code true} once a queued batch carries it
     */
    private static boolean queuedLinesContain(List<String> queuedLogRequests, String line) {
        for (String queued : queuedLogRequests) {
            if (Utils.urldecode(queued).contains(line)) {
                return true;
            }
        }
        return false;
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

    /**
     * Polls a condition until it holds or the time is up.
     *
     * @param timeoutMs how long to wait, in milliseconds
     * @param condition the condition
     * @return whether it held in time
     * @throws InterruptedException when interrupted while waiting
     */
    private static boolean waitFor(long timeoutMs, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    // endregion
}
