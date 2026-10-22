package ly.count.sdk.java.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;
import ly.count.sdk.java.internal.ConfigurationProvider.LogGatheringState;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import ly.count.sdk.java.Config;

import static ly.count.sdk.java.Config.LoggingLevel.DEBUG;
import static ly.count.sdk.java.Config.LoggingLevel.ERROR;
import static ly.count.sdk.java.Config.LoggingLevel.INFO;
import static ly.count.sdk.java.Config.LoggingLevel.OFF;
import static ly.count.sdk.java.Config.LoggingLevel.VERBOSE;
import static ly.count.sdk.java.Config.LoggingLevel.WARN;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@RunWith(JUnit4.class)
public class LogTests {
    private static final String message = "message";
    private static final Throwable exception = new IllegalStateException("IAS");
    private InternalConfig config;

    private final List<Log> handedOver = new ArrayList<>();
    private final List<JSONObject> queuedBatches = new CopyOnWriteArrayList<>();
    //the storage IDs of the batches the played request queue still holds, oldest first
    private final Deque<Long> waitingBatchIds = new ConcurrentLinkedDeque<>();
    private final AtomicLong nextRequestId = new AtomicLong(1);
    private final AtomicInteger queueAttempts = new AtomicInteger();
    private volatile boolean queueAccepts = true;

    /**
     * Plays the request queue: records each batch, or refuses it while {@link #queueAccepts} is off,
     * and holds it until {@link #sendOldestBatch(Log)}.
     */
    private final Log.LogBatchQueue queue = new Log.LogBatchQueue() {
        /**
         * Records the batch and holds it, or refuses it.
         */
        @Override
        public Long queueLogBatch(@Nonnull String payload) {
            queueAttempts.incrementAndGet();
            if (!queueAccepts) {
                return null;
            }
            queuedBatches.add(new JSONObject(payload));
            long requestId = nextRequestId.getAndIncrement();
            waitingBatchIds.addLast(requestId);
            return requestId;
        }

        /**
         * Whether the batch was not sent yet.
         */
        @Override
        public boolean isQueued(@Nonnull Long requestId) {
            return waitingBatchIds.contains(requestId);
        }
    };

    @Before
    public void setupEveryTest() {
        String serverUrl = "http://www.serverurl.com";
        String serverAppKey = "1234";
        config = new InternalConfig(new Config(serverUrl, serverAppKey));
    }

    @After
    public void cleanupEveryTests() {
        for (Log log : handedOver) {
            log.endLogGathering(false);
        }
        config = null;
    }

    @Test(expected = NullPointerException.class)
    public void logInit_null() {
        Log log = new Log(null, null);
    }

    /**
     * Every level method prints as the print level says and informs the listener, and it feeds the
     * capture whatever the print level.
     * <p>
     * Verifies, for a logger printing nothing, only errors and everything, that the listener hears
     * every line with its level, that the print level stays as configured, that every line waits for
     * its consent while no consent provider is set, and that an armed directive uploads them with
     * their level characters.
     */
    @Test
    public void levelMethods_informTheListenerAndFeedTheCapture_whateverThePrintLevel() throws Exception {
        for (Config.LoggingLevel printLevel : new Config.LoggingLevel[] { OFF, ERROR, VERBOSE }) {
            List<String> heard = new ArrayList<>();
            Log log = new Log(printLevel, (logMessage, level) -> heard.add(level + " " + logMessage));
            log.v("aa");
            log.d("bb");
            log.i("cc");
            log.w("dd");
            log.e("ee");

            Assert.assertEquals(Arrays.asList("VERBOSE aa", "DEBUG bb", "INFO cc", "WARN dd", "ERROR ee"), heard);
            Assert.assertEquals(printLevel, log.getPrintLevel());
            Assert.assertEquals(LogGatheringState.UNDECIDED, log.logGatheringState);
            Assert.assertEquals(5, log.awaitingConsentLineCount());

            queuedBatches.clear();
            handOver(log, settings(LogGatheringState.GATHERING, "gather_levels", ModuleConfiguration.logGatheringAllLevels, 100));
            log.onSdkInitFinished();
            awaitDelivery(log);
            JSONArray lines = queuedBatches.get(0).getJSONArray("l");
            String[] expected = { "v aa", "d bb", "i cc", "w dd", "e ee" };
            for (int i = 0; i < expected.length; i++) {
                JSONObject line = lines.getJSONObject(i);
                Assert.assertEquals(expected[i], line.getString("l") + " " + line.getString("m"));
            }
        }
    }

    /**
     * The buffer holds at most 500 lines and 128 KB of characters, dropping the oldest lines and
     * counting them, and a message is cut to what the server keeps.
     * <p>
     * Verifies both ceilings with the drop count and the character count they leave, that the first
     * batch reports every dropped line in {@code d}, and that a message is cut at 4096 characters,
     * or one less where the cut would split a surrogate pair.
     */
    @Test
    public void buffer_holdsAtMost500LinesAnd128KB_droppingTheOldestIntoD_andCutsLongMessages() throws Exception {
        Log lines = new Log(OFF, null);
        handOver(lines, settings(LogGatheringState.UNDECIDED, null, ModuleConfiguration.logGatheringAllLevels, ModuleConfiguration.logGatheringDefaultBatchSize));
        int handOverLines = lines.heldLogLineCount();
        for (int i = 0; i < 600; i++) {
            lines.e("line " + i);
        }
        Assert.assertEquals(ModuleConfiguration.logGatheringMaxBufferedLines, lines.heldLogLineCount());
        Assert.assertEquals(handOverLines + 100, lines.droppedLogLineCount);

        Log characters = new Log(OFF, null);
        handOver(characters, settings(LogGatheringState.GATHERING, "gather_characters", "e", 500));
        String longest = repeat('x', Log.maxMessageLength);
        for (int i = 0; i < 40; i++) {
            characters.e(longest);
        }
        int fitting = Log.gatheredCharCeiling / Log.maxMessageLength;
        Assert.assertEquals(fitting, characters.heldLogLineCount());
        Assert.assertEquals(40 - fitting, characters.droppedLogLineCount);
        Assert.assertEquals(Log.gatheredCharCeiling, characters.gatheredChars);

        characters.onSdkInitFinished();
        awaitDelivery(characters);
        Assert.assertEquals(1, queuedBatches.size());
        Assert.assertEquals(40 - fitting, queuedBatches.get(0).getInt("d"));
        Assert.assertEquals(fitting, queuedBatches.get(0).getJSONArray("l").length());

        Log cut = new Log(OFF, null);
        cut.e(repeat('y', 5000));
        cut.e(repeat('z', Log.maxMessageLength - 1) + "\uD83D\uDE00 after the pair");
        cut.e("short");
        queuedBatches.clear();
        handOver(cut, settings(LogGatheringState.GATHERING, "gather_cut", "e", 10));
        cut.onSdkInitFinished();
        awaitDelivery(cut);
        JSONArray cutLines = queuedBatches.get(0).getJSONArray("l");
        Assert.assertEquals(repeat('y', Log.maxMessageLength), cutLines.getJSONObject(0).getString("m"));
        Assert.assertEquals(repeat('z', Log.maxMessageLength - 1), cutLines.getJSONObject(1).getString("m"));
        Assert.assertEquals("short", cutLines.getJSONObject(2).getString("m"));
    }

    /**
     * Once something decides against gathering, capturing costs nothing: no line is held and no
     * delivery thread is started.
     */
    @Test
    public void notGathering_holdsNothing_andStartsNoDeliveryThread() {
        Log log = new Log(OFF, null);
        log.e("held while undecided");
        Assert.assertEquals(1, log.awaitingConsentLineCount());

        handOver(log, settings(LogGatheringState.NOT_GATHERING, null, ModuleConfiguration.logGatheringAllLevels, ModuleConfiguration.logGatheringDefaultBatchSize));
        Assert.assertEquals(LogGatheringState.NOT_GATHERING, log.logGatheringState);
        Assert.assertFalse(log.isCapturingLogs());
        Assert.assertEquals(0, log.heldLogLineCount());
        Assert.assertEquals(0, log.awaitingConsentLineCount());

        for (int i = 0; i < 1000; i++) {
            log.e("error " + i);
            log.v("verbose " + i);
        }
        log.flushGatheredLogs();
        Assert.assertEquals(0, log.heldLogLineCount());
        Assert.assertEquals(0, log.droppedLogLineCount);
        Assert.assertEquals(0, log.gatheredChars);
        Assert.assertNull(log.logDeliveryExecutor);
        Assert.assertEquals(0, queueAttempts.get());
    }

    /**
     * A line that carries an uploaded batch, and a line logged by a thread doing this feature's own
     * transport work, are never held, while other threads are captured meanwhile.
     */
    @Test
    public void selfCapture_batchCarryingLinesAndLinesOfAMarkedThread_areNotHeld() throws InterruptedException {
        Log log = new Log(OFF, null);
        handOver(log, settings(LogGatheringState.UNDECIDED, null, ModuleConfiguration.logGatheringAllLevels, ModuleConfiguration.logGatheringDefaultBatchSize));
        int handOverLines = log.heldLogLineCount();
        log.d("New request 1: [1] app_key=key&" + Log.transportMarker + "%7B%22i%22%3A%22gather%22%7D");
        Assert.assertEquals(handOverLines, log.heldLogLineCount());

        log.setOwnTransportWork(true);
        log.i("logged while doing transport work");
        Assert.assertEquals(handOverLines, log.heldLogLineCount());
        Thread other = new Thread(() -> log.i("logged by another thread meanwhile"));
        other.start();
        other.join();
        Assert.assertEquals(handOverLines + 1, log.heldLogLineCount());

        log.setOwnTransportWork(false);
        log.i("logged once the mark is cleared");
        Assert.assertEquals(handOverLines + 2, log.heldLogLineCount());
    }

    /**
     * A batch the queue refuses goes back to the front of the buffer with its drop count, so the
     * later deliveries, one batch at a time as each is sent, upload every line in order and still
     * report the loss.
     */
    @Test
    public void refusedBatch_goesBackToTheFrontOfTheBuffer_withItsDropCount() throws Exception {
        Log log = new Log(OFF, null);
        handOver(log, settings(LogGatheringState.GATHERING, "gather_refused", "e", 100));
        for (int i = 0; i < 505; i++) {
            log.e("line " + i);
        }
        queueAccepts = false;
        log.onSdkInitFinished();
        awaitDelivery(log);
        Assert.assertEquals(1, queueAttempts.get());
        Assert.assertEquals(ModuleConfiguration.logGatheringMaxBufferedLines, log.heldLogLineCount());
        Assert.assertEquals(5, log.droppedLogLineCount);

        queueAccepts = true;
        log.flushGatheredLogs();
        awaitDelivery(log);
        sendQueuedBatches(log);
        Assert.assertEquals(0, log.heldLogLineCount());
        Assert.assertEquals(5, queuedBatches.size());
        List<String> messages = new ArrayList<>();
        for (int b = 0; b < queuedBatches.size(); b++) {
            Assert.assertEquals(b == 0 ? 5 : 0, queuedBatches.get(b).getInt("d"));
            messages.addAll(messagesOf(queuedBatches.get(b)));
        }
        List<String> expected = new ArrayList<>();
        for (int i = 5; i < 505; i++) {
            expected.add("line " + i);
        }
        Assert.assertEquals(expected, messages);
    }

    /**
     * Ending the gathering queues what is held, synchronously, only when asked, and leaves a logger
     * that neither captures nor queues anything, whatever is decided afterwards.
     */
    @Test
    public void endedGathering_queuesTheHeldLinesOnlyWhenAsked_andIgnoresEverythingAfterwards() throws Exception {
        Log kept = new Log(OFF, null);
        handOver(kept, settings(LogGatheringState.GATHERING, "gather_end", "e", 100));
        kept.onSdkInitFinished();
        kept.e("tail 1");
        kept.e("tail 2");
        kept.endLogGathering(true);
        Assert.assertEquals(1, queuedBatches.size());
        Assert.assertEquals("gather_end", queuedBatches.get(0).getString("i"));
        Assert.assertEquals(Arrays.asList("tail 1", "tail 2"), messagesOf(queuedBatches.get(0)));
        Assert.assertEquals(LogGatheringState.NOT_GATHERING, kept.logGatheringState);
        Assert.assertNull(kept.logDeliveryExecutor);

        kept.setLogGatheringProviders(settings(LogGatheringState.GATHERING, "gather_again", "e", 10), () -> ~0, queue);
        kept.e("logged after the end");
        kept.flushGatheredLogs();
        Assert.assertEquals(LogGatheringState.NOT_GATHERING, kept.logGatheringState);
        Assert.assertEquals(0, kept.heldLogLineCount());
        Assert.assertNull(kept.logDeliveryExecutor);
        Assert.assertEquals(1, queueAttempts.get());

        Log discarded = new Log(OFF, null);
        handOver(discarded, settings(LogGatheringState.GATHERING, "gather_discarded", "e", 100));
        discarded.onSdkInitFinished();
        discarded.e("discarded line");
        discarded.endLogGathering(false);
        Assert.assertEquals(1, queueAttempts.get());
        Assert.assertEquals(0, discarded.heldLogLineCount());
        Assert.assertEquals(LogGatheringState.NOT_GATHERING, discarded.logGatheringState);
    }

    /**
     * A line is gathered only once the consent of the feature it belongs to is given, found from
     * the name it starts with in brackets, and a line of no feature needs both the events and the
     * user profiles consent. Until then it waits apart, and it joins the gather in capture order.
     * <p>
     * Verifies that every line waits before the providers are set; that with only the sessions
     * consent given only the session lines are held; that each consent given releases exactly its
     * lines, the line of no feature once both of its consents are given; that the batch keeps the
     * order the lines were logged in; and that the waiting lines are capped at 500, oldest first,
     * without counting into {@code d}.
     */
    @Test
    public void perLineConsent_gathersALineOnlyWithTheConsentOfItsFeature_inCaptureOrder() throws Exception {
        Log log = new Log(OFF, null);
        log.e("[ModuleLocation] setLocation, gps:[12.345678,98.765432]");
        log.e("[SessionImpl] begin");
        Assert.assertEquals(0, log.heldLogLineCount());
        Assert.assertEquals(2, log.awaitingConsentLineCount());

        AtomicInteger given = new AtomicInteger(CoreFeature.Sessions.getIndex());
        handedOver.add(log);
        log.setLogGatheringProviders(settings(LogGatheringState.GATHERING, "gather_consent", "e", 100), given::get, queue);
        Assert.assertEquals(1, log.heldLogLineCount());
        log.e("[ModuleEvents] recordEventInternal, key:[purchase]");
        log.e("[Test] a line of no feature");
        log.e("[Views] startView, name:[home]");
        Assert.assertEquals(1, log.heldLogLineCount());
        Assert.assertEquals(4, log.awaitingConsentLineCount());

        given.set(given.get() | CoreFeature.Events.getIndex());
        log.releaseConsentedLogLines();
        Assert.assertEquals(2, log.heldLogLineCount());
        given.set(given.get() | CoreFeature.UserProfiles.getIndex() | CoreFeature.Location.getIndex());
        log.releaseConsentedLogLines();
        Assert.assertEquals(4, log.heldLogLineCount());
        Assert.assertEquals(1, log.awaitingConsentLineCount());

        log.onSdkInitFinished();
        awaitDelivery(log);
        Assert.assertEquals(1, queuedBatches.size());
        Assert.assertEquals(Arrays.asList("[ModuleLocation] setLocation, gps:[12.345678,98.765432]", "[SessionImpl] begin",
            "[ModuleEvents] recordEventInternal, key:[purchase]", "[Test] a line of no feature"), messagesOf(queuedBatches.get(0)));

        for (int i = 0; i < 520; i++) {
            log.e("[ModuleViews] waiting line " + i);
        }
        Assert.assertEquals(ModuleConfiguration.logGatheringMaxBufferedLines, log.awaitingConsentLineCount());
        Assert.assertEquals(0, log.droppedLogLineCount);
    }

    /**
     * One batch waits in the request queue at a time: until it is sent the lines stay in the
     * buffer, and each send lets the next batch go, a partial one included.
     * <p>
     * Verifies that a delivery queues a single batch however many are held; that neither a full
     * batch nor a flush queues another while it waits; that each sent batch lets exactly one more
     * go, until every line went out once, in order; and that a refused batch does not count as
     * waiting.
     */
    @Test
    public void oneBatchAtATime_holdsTheLinesUntilTheWaitingBatchIsSent() throws Exception {
        Log log = new Log(OFF, null);
        handOver(log, settings(LogGatheringState.GATHERING, "gather_one", "e", 10));
        for (int i = 0; i < 35; i++) {
            log.e("line " + i);
        }
        log.onSdkInitFinished();
        awaitDelivery(log);
        Assert.assertEquals(1, queuedBatches.size());
        Assert.assertEquals(1, waitingBatchIds.size());
        Assert.assertEquals(25, log.heldLogLineCount());

        log.e("line 35");
        log.flushGatheredLogs();
        awaitDelivery(log);
        Assert.assertEquals(1, queuedBatches.size());
        Assert.assertEquals(26, log.heldLogLineCount());

        for (int sent = 1; sent <= 3; sent++) {
            sendOldestBatch(log);
            awaitDelivery(log);
            Assert.assertEquals(sent + 1, queuedBatches.size());
            Assert.assertEquals(1, waitingBatchIds.size());
        }
        sendOldestBatch(log);
        awaitDelivery(log);
        Assert.assertEquals(4, queuedBatches.size());
        Assert.assertEquals(0, waitingBatchIds.size());
        List<String> messages = new ArrayList<>();
        for (JSONObject batch : queuedBatches) {
            messages.addAll(messagesOf(batch));
        }
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            expected.add("line " + i);
        }
        Assert.assertEquals(expected, messages);

        queueAccepts = false;
        log.e("refused line");
        log.flushGatheredLogs();
        awaitDelivery(log);
        Assert.assertEquals(0, waitingBatchIds.size());
        queueAccepts = true;
        log.flushGatheredLogs();
        awaitDelivery(log);
        Assert.assertEquals(5, queuedBatches.size());
        Assert.assertEquals(Arrays.asList("refused line"), messagesOf(queuedBatches.get(4)));
    }

    /**
     * The batch waiting in the request queue holds the next one back only while the queue holds it,
     * and its send resumes only a delivery it held back.
     * <p>
     * Verifies that a batch the queue dropped without sending, as when the queue is over its size,
     * no longer holds the lines back on the next flush; that a send that held nothing back queues
     * nothing, so a line logged meanwhile waits for the next flush; and that a send after a delivery
     * it held back queues the line that waited, a partial batch.
     */
    @Test
    public void theWaitingBatch_holdsTheNextBackOnlyWhileQueued_andItsSendResumesOnlyADeliveryItHeldBack() throws Exception {
        Log log = new Log(OFF, null);
        handOver(log, settings(LogGatheringState.GATHERING, "gather_waiting", "e", 10));
        log.e("line 0");
        log.onSdkInitFinished();
        awaitDelivery(log);
        log.e("line 1");
        log.flushGatheredLogs();
        awaitDelivery(log);
        Assert.assertEquals(1, queuedBatches.size());
        Assert.assertEquals(1, log.heldLogLineCount());

        waitingBatchIds.clear();
        log.flushGatheredLogs();
        awaitDelivery(log);
        Assert.assertEquals(2, queuedBatches.size());
        Assert.assertEquals(Collections.singletonList("line 1"), messagesOf(queuedBatches.get(1)));

        log.e("line 2");
        sendOldestBatch(log);
        awaitDelivery(log);
        Assert.assertEquals(2, queuedBatches.size());
        Assert.assertEquals(1, log.heldLogLineCount());

        log.flushGatheredLogs();
        awaitDelivery(log);
        log.e("line 3");
        log.flushGatheredLogs();
        awaitDelivery(log);
        Assert.assertEquals(3, queuedBatches.size());
        sendOldestBatch(log);
        awaitDelivery(log);
        Assert.assertEquals(4, queuedBatches.size());
        Assert.assertEquals(Collections.singletonList("line 3"), messagesOf(queuedBatches.get(3)));
    }

    /**
     * A removed consent drops the held lines that needed it, so nothing gathered for a feature goes
     * out once its consent is withdrawn, and the lines dropped are reported in {@code d}.
     */
    @Test
    public void removedConsent_dropsTheHeldLinesThatNeededIt_countingThemIntoD() throws Exception {
        Log log = new Log(OFF, null);
        AtomicInteger given = new AtomicInteger(CoreFeature.Events.getIndex() | CoreFeature.Location.getIndex() | CoreFeature.UserProfiles.getIndex());
        handedOver.add(log);
        log.setLogGatheringProviders(settings(LogGatheringState.GATHERING, "gather_withdrawn", "e", 100), given::get, queue);
        log.e("[ModuleEvents] an events line");
        log.e("[ModuleLocation] a location line");
        log.e("[Test] a line of no feature");
        Assert.assertEquals(3, log.heldLogLineCount());

        given.set(CoreFeature.Events.getIndex());
        log.dropLinesWithoutConsent();

        Assert.assertEquals(1, log.heldLogLineCount());
        log.onSdkInitFinished();
        awaitDelivery(log);
        Assert.assertEquals(Collections.singletonList("[ModuleEvents] an events line"), messagesOf(queuedBatches.get(0)));
        Assert.assertEquals(2, queuedBatches.get(0).getInt("d"));
    }

    /**
     * A gather the server stops queues the lines it still holds once, with its id, while a gather
     * replaced by another drops them, and so does a decision against gathering made while nothing
     * was decided.
     * <p>
     * Verifies that stopping a gather queues one batch of exactly its held lines, with its id and
     * past a batch already waiting; that the capture stops; that a gather replaced by a new id
     * queues nothing; and that deciding off while undecided queues nothing.
     */
    @Test
    public void stoppedGather_queuesItsHeldLinesOnceWithItsId_whileAReplacedGatherDropsThem() throws Exception {
        ModuleConfiguration stopped = settings(LogGatheringState.GATHERING, "gather_tail", "e", 10);
        Log log = new Log(OFF, null);
        handOver(log, stopped);
        log.onSdkInitFinished();
        for (int i = 0; i < 13; i++) {
            log.e("line " + i);
        }
        awaitDelivery(log);
        Assert.assertEquals(1, queuedBatches.size());
        Assert.assertEquals(3, log.heldLogLineCount());

        stopped.currentVLogGathering = ModuleConfiguration.LogGatheringDirective.NOT_GATHERING;
        log.applyLogGatheringDirective("test");
        Assert.assertTrue("the held lines were not queued", TestUtils.waitFor(10_000, () -> queuedBatches.size() == 2));
        Assert.assertEquals("gather_tail", queuedBatches.get(1).getString("i"));
        Assert.assertEquals(0, queuedBatches.get(1).getInt("d"));
        Assert.assertEquals(Arrays.asList("line 10", "line 11", "line 12"), messagesOf(queuedBatches.get(1)));
        Assert.assertFalse(log.isCapturingLogs());
        log.e("logged after the stop");
        Assert.assertEquals(0, log.heldLogLineCount());

        ModuleConfiguration replaced = settings(LogGatheringState.GATHERING, "gather_one", "e", 100);
        Log other = new Log(OFF, null);
        handOver(other, replaced);
        other.onSdkInitFinished();
        awaitDelivery(other);
        other.e("line of the first gather");
        replaced.currentVLogGathering = new ModuleConfiguration.LogGatheringDirective(LogGatheringState.GATHERING, "gather_two", "e", 100);
        other.applyLogGatheringDirective("test");
        awaitDelivery(other);
        Assert.assertEquals(0, other.heldLogLineCount());

        Log undecided = new Log(OFF, null);
        undecided.e("line held while undecided");
        handOver(undecided, settings(LogGatheringState.NOT_GATHERING, null, ModuleConfiguration.logGatheringAllLevels, ModuleConfiguration.logGatheringDefaultBatchSize));
        undecided.onSdkInitFinished();
        Thread.sleep(100);
        Assert.assertEquals(2, queuedBatches.size());
        Assert.assertEquals(2, queueAttempts.get());
    }

    /**
     * Before the device ID changes without merge the gather stops: every held line is queued on
     * the calling thread, past a batch already waiting, the lines waiting for their consent are
     * dropped, and a later directive can still arm a new gather.
     * <p>
     * Verifies that the held lines are queued before the call returns, in batches of at most
     * {@code b} and in order; that a line waiting for its consent is not queued; that nothing is
     * captured afterwards; and that a directive applied later arms a gather again.
     */
    @Test
    public void deviceIdChange_queuesTheHeldLinesThenStops_andALaterDirectiveCanArmAgain() throws Exception {
        AtomicInteger given = new AtomicInteger(Log.UNATTRIBUTED_LINE_CONSENT);
        ModuleConfiguration settings = settings(LogGatheringState.GATHERING, "gather_did", "e", 10);
        Log log = new Log(OFF, null);
        handedOver.add(log);
        log.setLogGatheringProviders(settings, given::get, queue);
        log.onSdkInitFinished();
        for (int i = 0; i < 25; i++) {
            log.e("[Test] user A line " + i);
        }
        log.e("[ModuleLocation] user A location");
        awaitDelivery(log);
        Assert.assertEquals(1, queuedBatches.size());
        Assert.assertEquals(1, log.awaitingConsentLineCount());

        log.stopLogGatheringForDeviceIdChange();
        Assert.assertEquals(3, queuedBatches.size());
        List<String> messages = new ArrayList<>();
        for (JSONObject batch : queuedBatches) {
            Assert.assertEquals("gather_did", batch.getString("i"));
            Assert.assertTrue(batch.getJSONArray("l").length() <= 10);
            messages.addAll(messagesOf(batch));
        }
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            expected.add("[Test] user A line " + i);
        }
        Assert.assertEquals(expected, messages);
        Assert.assertEquals(LogGatheringState.NOT_GATHERING, log.logGatheringState);
        Assert.assertEquals(0, log.awaitingConsentLineCount());
        log.e("[Test] user B line");
        Assert.assertEquals(0, log.heldLogLineCount());
        Assert.assertNull(log.logDeliveryExecutor);

        settings.currentVLogGathering = new ModuleConfiguration.LogGatheringDirective(LogGatheringState.GATHERING, "gather_b", "e", 10);
        log.applyLogGatheringDirective("test");
        Assert.assertTrue(log.isGatheringLogs());
        log.e("[Test] user B armed line");
        Assert.assertEquals(1, log.heldLogLineCount());
    }

    /**
     * A batch is valid JSON with its keys always in the same order, every message escaped.
     */
    @Test
    public void batchPayload_isJsonWithItsKeysInOrder_andEscapesEveryMessage() {
        String awkward = "quote \" backslash \\ newline \n tab \t closing </script> pair \uD83D\uDE00";
        List<Log.LogLine> lines = Arrays.asList(new Log.LogLine(0, 1755500000000L, 'e', awkward, 0), new Log.LogLine(1, 1755500000001L, 'd', "", 0));
        String payload = Log.buildLogBatchPayload("gather \"quoted\"", 3, lines);
        Assert.assertTrue(payload, payload.startsWith("{\"i\":\"gather \\\"quoted\\\"\",\"d\":3,\"l\":[{\"t\":1755500000000,\"l\":\"e\",\"m\":\""));

        JSONObject batch = new JSONObject(payload);
        Assert.assertEquals("gather \"quoted\"", batch.getString("i"));
        Assert.assertEquals(3, batch.getInt("d"));
        JSONArray parsed = batch.getJSONArray("l");
        Assert.assertEquals(2, parsed.length());
        Assert.assertEquals(1755500000000L, parsed.getJSONObject(0).getLong("t"));
        Assert.assertEquals("e", parsed.getJSONObject(0).getString("l"));
        Assert.assertEquals(awkward, parsed.getJSONObject(0).getString("m"));
        Assert.assertEquals("d", parsed.getJSONObject(1).getString("l"));
        Assert.assertEquals("", parsed.getJSONObject(1).getString("m"));

        Assert.assertEquals("{\"i\":\"gather\",\"d\":0,\"l\":[]}", Log.buildLogBatchPayload("gather", 0, Collections.<Log.LogLine>emptyList()));
    }

    /**
     * Settings that hold nothing but the given log gathering directive.
     *
     * @param state the decision
     * @param gatherId the gather id
     * @param levels the level characters
     * @param batchSize the batch size
     * @return the settings
     */
    private static ModuleConfiguration settings(LogGatheringState state, String gatherId, String levels, int batchSize) {
        ModuleConfiguration settings = new ModuleConfiguration();
        settings.currentVLogGathering = new ModuleConfiguration.LogGatheringDirective(state, gatherId, levels, batchSize);
        return settings;
    }

    /**
     * Hands a logger the given settings, every consent given and {@link #queue}, as an SDK
     * that requires no consent does once its settings are resolved. Nothing is uploaded before the
     * test tells the logger that init finished.
     *
     * @param log the logger
     * @param settings the settings
     */
    private void handOver(Log log, ModuleConfiguration settings) {
        handedOver.add(log);
        log.setLogGatheringProviders(settings, () -> ~0, queue);
    }

    /**
     * Waits until every delivery the logger was asked for so far ran.
     *
     * @param log the logger
     * @throws Exception when the delivery thread does not finish in time
     */
    private static void awaitDelivery(Log log) throws Exception {
        ExecutorService executor = log.logDeliveryExecutor;
        if (executor != null) {
            executor.submit(() -> {
            }).get(10, TimeUnit.SECONDS);
        }
    }

    /**
     * Plays the request queue sending every batch the logger queued, one at a time, each send
     * letting the logger queue its next batch, until none waits.
     *
     * @param log the logger
     * @throws Exception when a delivery does not finish in time
     */
    private void sendQueuedBatches(Log log) throws Exception {
        for (int i = 0; i < 1000 && !waitingBatchIds.isEmpty(); i++) {
            sendOldestBatch(log);
            awaitDelivery(log);
        }
    }

    /**
     * Plays the request queue sending its oldest batch, then telling the logger.
     *
     * @param log the logger
     */
    private void sendOldestBatch(Log log) {
        waitingBatchIds.pollFirst();
        log.onLogBatchSent();
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
}
