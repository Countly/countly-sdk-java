package ly.count.sdk.java.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
    private final AtomicInteger queueAttempts = new AtomicInteger();
    private volatile boolean queueAccepts = true;

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
     * every line with its level, that the print level stays as configured, that every line is held
     * while nothing is decided, and that an armed directive uploads them with their level characters.
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
            Assert.assertEquals(5, log.heldLogLineCount());

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
        for (int i = 0; i < 600; i++) {
            lines.e("line " + i);
        }
        Assert.assertEquals(ModuleConfiguration.logGatheringMaxBufferedLines, lines.heldLogLineCount());
        Assert.assertEquals(100, lines.droppedLogLineCount);

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
        Assert.assertEquals(1, log.heldLogLineCount());

        handOver(log, settings(LogGatheringState.NOT_GATHERING, null, ModuleConfiguration.logGatheringAllLevels, ModuleConfiguration.logGatheringDefaultBatchSize));
        Assert.assertEquals(LogGatheringState.NOT_GATHERING, log.logGatheringState);
        Assert.assertFalse(log.isCapturingLogs());
        Assert.assertEquals(0, log.heldLogLineCount());

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
        log.d("New request 1: [1] app_key=key&" + Log.transportMarker + "%7B%22i%22%3A%22gather%22%7D");
        Assert.assertEquals(0, log.heldLogLineCount());

        log.setOwnTransportWork(true);
        log.i("logged while doing transport work");
        Assert.assertEquals(0, log.heldLogLineCount());
        Thread other = new Thread(() -> log.i("logged by another thread meanwhile"));
        other.start();
        other.join();
        Assert.assertEquals(1, log.heldLogLineCount());

        log.setOwnTransportWork(false);
        log.i("logged once the mark is cleared");
        Assert.assertEquals(2, log.heldLogLineCount());
    }

    /**
     * A batch the queue refuses goes back to the front of the buffer with its drop count, so a later
     * delivery uploads every line in order and still reports the loss.
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

        kept.setLogGatheringProviders(settings(LogGatheringState.GATHERING, "gather_again", "e", 10), () -> true, this::queue);
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
     * A batch is valid JSON with its keys always in the same order, every message escaped.
     */
    @Test
    public void batchPayload_isJsonWithItsKeysInOrder_andEscapesEveryMessage() {
        String awkward = "quote \" backslash \\ newline \n tab \t closing </script> pair \uD83D\uDE00";
        List<Log.LogLine> lines = Arrays.asList(new Log.LogLine(1755500000000L, 'e', awkward), new Log.LogLine(1755500000001L, 'd', ""));
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
     * Hands a logger the given settings, a consent check that always allows and {@link #queue(String)},
     * as an SDK does once its settings are resolved. Nothing is uploaded before the test tells the
     * logger that init finished.
     *
     * @param log the logger
     * @param settings the settings
     */
    private void handOver(Log log, ModuleConfiguration settings) {
        handedOver.add(log);
        log.setLogGatheringProviders(settings, () -> true, this::queue);
    }

    /**
     * Records a batch, or refuses it while {@link #queueAccepts} is off.
     *
     * @param payload the batch
     * @return whether the batch was queued
     */
    private boolean queue(String payload) {
        queueAttempts.incrementAndGet();
        if (!queueAccepts) {
            return false;
        }
        queuedBatches.add(new JSONObject(payload));
        return true;
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
