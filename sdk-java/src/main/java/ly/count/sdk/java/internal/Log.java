package ly.count.sdk.java.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.internal.ConfigurationProvider.LogGatheringState;
import org.json.JSONObject;

/**
 * Logging module. Exposes static functions for simplicity, thus can be used only from some point
 * in time when {@link Config} is created and {@link ModuleBase}s are up.
 * <p>
 * The logger also gathers the SDK's own lines for the server when the {@code lg} directive of the
 * SDK behavior settings asks for them, and uploads them as the {@code sdk_logs} parameter of
 * requests of their own, shaped {@code {"i":gatherId,"d":dropped,"l":[{"t":ms,"l":"e","m":message}]}}.
 * Every level is captured, whatever is printed. Capture starts with the first line, into a
 * provisional buffer, since the init lines are the ones worth having: a live directive adopts the
 * buffer filtered to its levels, or drops it. Nothing of it is ever stored. A line whose consent
 * is not given when it is logged waits apart until it is, see {@link #logLineConsentFeatures(String)}.
 */

public class Log {
    static final String keySdkLogs = "sdk_logs";
    //a line that carries an uploaded batch is never gathered, or each batch would nest the previous one
    static final String transportMarker = keySdkLogs + "=";

    static final String keyBatchGatherId = "i";
    static final String keyBatchDroppedCount = "d";
    static final String keyBatchLines = "l";
    static final String keyLineTimestamp = "t";
    static final String keyLineLevel = "l";
    static final String keyLineMessage = "m";

    //the server keeps at most this many characters of a message
    static final int maxMessageLength = 4096;
    //a ceiling on the characters held, since the line ceiling alone does not bound the memory
    static final int gatheredCharCeiling = 128 * 1024;
    static final String DELIVERY_THREAD_NAME = "Countly-LogDelivery";

    //the longest bracketed name a line starts with, so finding it never scans a long line to its end
    private static final int maxLogLineNameLength = 64;
    //the consent a line of no feature needs, as it can quote any feature's data, such as a whole request
    static final int UNATTRIBUTED_LINE_CONSENT = CoreFeature.Events.getIndex() | CoreFeature.UserProfiles.getIndex();

    /**
     * The consent a gathered line needs while consent is required, by the name its message starts
     * with in brackets, as in {@code "[ModuleEvents] ..."}: the features any one of whose consent
     * lets the line go. A line of any other name needs {@link #UNATTRIBUTED_LINE_CONSENT}.
     */
    private static final Map<String, Integer> logLineConsent;

    static {
        Map<String, Integer> consent = new HashMap<>();
        mapLogLineConsent(consent, CoreFeature.Events, "ModuleEvents", "Events", "EventImpl", "EventQueue", "TimedEvents");
        mapLogLineConsent(consent, CoreFeature.Sessions, "ModuleSessions", "SessionImpl");
        mapLogLineConsent(consent, CoreFeature.Views, "ModuleViews", "Views", "ViewImpl", "View");
        mapLogLineConsent(consent, CoreFeature.CrashReporting, "ModuleCrash", "Crashes", "CrashImpl");
        mapLogLineConsent(consent, CoreFeature.Location, "ModuleLocation", "Location");
        mapLogLineConsent(consent, CoreFeature.UserProfiles, "ModuleUserProfile", "UserProfile", "UserEditorImpl");
        mapLogLineConsent(consent, CoreFeature.RemoteConfig, "ModuleRemoteConfig", "RemoteConfig", "RemoteConfigValueStore");
        mapLogLineConsent(consent, CoreFeature.Feedback, "ModuleFeedback", "Feedback");
        mapLogLineConsent(consent, CoreFeature.Content, "ModuleContent", "Content", "ContentParser", "ContentRequestBuilder", "WidgetActionParser");
        logLineConsent = Collections.unmodifiableMap(consent);
    }

    private final LogCallback logListener;
    private final Config.LoggingLevel loggingLevel;
    //the lowest level printed: the developer level unless the SDK behavior settings override it
    private volatile Config.LoggingLevel printLevel;

    //guards the buffer and the directive mirror; nothing is logged and nothing is called out while it is held
    private final Object logBufferLock = new Object();
    //held while batches are taken and queued, so ending the gathering waits for a delivery in flight
    private final Object logDeliveryLock = new Object();
    private final Object logDeliveryExecutorLock = new Object();

    private final ArrayDeque<LogLine> gatheredLines = new ArrayDeque<>();
    int droppedLogLineCount = 0;
    int gatheredChars = 0;
    //lines whose consent was not given when they were logged, kept out of every batch until it is
    private final ArrayDeque<LogLine> linesAwaitingConsent = new ArrayDeque<>();
    int awaitingConsentChars = 0;
    //orders the lines of both lists, as the millisecond timestamps of a burst do not
    private long nextLineSequence = 0;
    //the last batch of a gather the server stopped, taken under the lock and queued outside it
    private LogBatch pendingTailBatch = null;

    //the directive mirror, so capturing a line never reads the settings; the state alone is read without the lock
    volatile LogGatheringState logGatheringState = LogGatheringState.UNDECIDED;
    String logGatherId = null;
    String logGatherLevels = ModuleConfiguration.logGatheringAllLevels;
    int logGatherBatchSize = ModuleConfiguration.logGatheringDefaultBatchSize;
    private volatile boolean logGatheringEnded = false;

    private final ThreadLocal<Boolean> ownTransportWork = new ThreadLocal<>();
    private final AtomicBoolean logDeliveryPending = new AtomicBoolean(false);
    private final AtomicBoolean partialLogDeliveryRequested = new AtomicBoolean(false);
    //the storage IDs of the batches this logger queued, until the request queue no longer holds them
    private final Set<Long> queuedLogBatchIds = ConcurrentHashMap.newKeySet();
    //a delivery left lines for the batch waiting in the request queue, so the send of that batch resumes it
    private final AtomicBoolean logDeliveryAwaitsSentBatch = new AtomicBoolean(false);
    volatile ExecutorService logDeliveryExecutor = null;
    private volatile boolean sdkInitFinished = false;

    private volatile ConfigurationProvider configProvider = null;
    private volatile IntSupplier givenConsents = null;
    private volatile LogBatchQueue logBatchQueue = null;

    /**
     * The request queue as the log gathering uses it: one request per batch of gathered log lines.
     */
    interface LogBatchQueue {
        /**
         * Queues the batch.
         *
         * @param payload the {@code sdk_logs} value
         * @return the storage ID of the request carrying the batch, {@code null} when the queue refused it
         */
        @Nullable
        Long queueLogBatch(@Nonnull String payload);

        /**
         * Whether a request still waits in the queue, until it is sent or dropped.
         *
         * @param requestId the storage ID {@link #queueLogBatch(String)} returned
         * @return {@code true} while the request waits
         */
        boolean isQueued(@Nonnull Long requestId);
    }

    public Log(Config.LoggingLevel loggingLevel, LogCallback logListener) {
        if (loggingLevel == null) {
            throw new NullPointerException("Logging level can't null.");
        }
        this.loggingLevel = loggingLevel;
        this.printLevel = loggingLevel;
        this.logListener = logListener;
    }

    /**
     * Applies the {@code log} switch of the SDK behavior settings to what is printed. Switched off,
     * nothing is printed. Switched on, the developer level is printed, or every level when the
     * developer level is {@link Config.LoggingLevel#OFF}. The log listener receives every line either way.
     *
     * @param loggingEnabled the resolved {@code log} switch
     */
    void setLoggingEnabled(boolean loggingEnabled) {
        if (!loggingEnabled) {
            printLevel = Config.LoggingLevel.OFF;
        } else if (loggingLevel == Config.LoggingLevel.OFF) {
            printLevel = Config.LoggingLevel.VERBOSE;
        } else {
            printLevel = loggingLevel;
        }
    }

    /**
     * The lowest level printed, which the SDK behavior settings can change.
     *
     * @return the level, {@link Config.LoggingLevel#OFF} while nothing is printed
     */
    Config.LoggingLevel getPrintLevel() {
        return printLevel;
    }

    /**
     * {@link Config.LoggingLevel} level logging
     *
     * @param logMessage string to log
     */
    public void d(String logMessage) {
        print("[DEBUG] [Countly]\t" + logMessage, Config.LoggingLevel.DEBUG);
        informListener(logMessage, Config.LoggingLevel.DEBUG);
        captureLogLine(logMessage, 'd');
    }

    /**
     * {@link Config.LoggingLevel#INFO} level logging
     *
     * @param logMessage string to log
     */
    public void i(String logMessage) {
        print("[INFO] [Countly]\t" + logMessage, Config.LoggingLevel.INFO);
        informListener(logMessage, Config.LoggingLevel.INFO);
        captureLogLine(logMessage, 'i');
    }

    /**
     * {@link Config.LoggingLevel#WARN} level logging
     *
     * @param logMessage string to log
     */
    public void w(String logMessage) {
        print("[WARN] [Countly]\t" + logMessage, Config.LoggingLevel.WARN);
        informListener(logMessage, Config.LoggingLevel.WARN);
        captureLogLine(logMessage, 'w');
    }

    /**
     * {@link Config.LoggingLevel#ERROR} level logging
     *
     * @param logMessage string to log
     */
    public void e(String logMessage) {
        print("[ERROR] [Countly]\t" + logMessage, Config.LoggingLevel.ERROR);
        informListener(logMessage, Config.LoggingLevel.ERROR);
        captureLogLine(logMessage, 'e');
    }

    /**
     * {@link Config.LoggingLevel#VERBOSE} level logging
     *
     * @param logMessage string to log
     */
    public void v(String logMessage) {
        print("[VERBOSE] [Countly]\t" + logMessage, Config.LoggingLevel.VERBOSE);
        informListener(logMessage, Config.LoggingLevel.VERBOSE);
        captureLogLine(logMessage, 'v');
    }

    private void print(String msg, Config.LoggingLevel level) {
        if (level != null && printLevel.prints(level)) {
            System.out.println(msg);
        }
    }

    private void informListener(String msg, Config.LoggingLevel level) {
        if (logListener != null) {
            logListener.LogHappened(msg, level);
        }
    }

    /**
     * Hands the logger what it needs to act on the log gathering directive, lets the lines logged
     * so far whose consent is given join the gather, then applies the directive in effect. Setters
     * rather than constructor parameters, as the logger exists before any of them.
     *
     * @param configProvider the SDK behavior settings, which hold the directive
     * @param givenConsents the {@link CoreFeature} indexes whose consent is given, every bit while
     *     no consent is required; read inside log calls, so it must never log
     * @param logBatchQueue queues one batch as a request of its own
     */
    void setLogGatheringProviders(@Nonnull ConfigurationProvider configProvider, @Nonnull IntSupplier givenConsents, @Nonnull LogBatchQueue logBatchQueue) {
        v("[Log] setLogGatheringProviders, held lines:[" + heldLogLineCount() + "], awaiting consent:[" + awaitingConsentLineCount() + "]");
        this.givenConsents = givenConsents;
        this.logBatchQueue = logBatchQueue;
        this.configProvider = configProvider;
        releaseConsentedLogLines();
        applyLogGatheringDirective("init");
    }

    /**
     * Applies the directive the settings hold to what is held. An armed directive adopts the held
     * lines filtered to its levels, and a new gather id drops the lines of the previous gather. A
     * decision against gathering stops the capture: the lines a running gather held are queued once,
     * with its id, and the lines held while undecided are dropped. Called after every decision, and
     * ignored once the gathering ended.
     *
     * @param source what decided, for the log line only
     */
    void applyLogGatheringDirective(@Nonnull String source) {
        if (logGatheringEnded) {
            return;
        }

        ConfigurationProvider provider = configProvider;
        if (provider == null) {
            v("[Log] applyLogGatheringDirective, no configuration provider yet, source:[" + source + "]");
            return;
        }

        LogGatheringState state = provider.getLogGatheringState();
        String gatherId = provider.getLogGatheringId();
        String levels = provider.getLogGatheringLevels();
        int batchSize = provider.getLogGatheringBatchSize();

        if (!adoptLogGatheringDirective(state, gatherId, levels, batchSize)) {
            return;
        }

        d("[Log] applyLogGatheringDirective, source:[" + source + "], state:[" + state + "], id:[" + gatherId + "], levels:[" + levels + "], batch size:[" + batchSize + "], held lines:[" + heldLogLineCount() + "]");

        if (state != LogGatheringState.GATHERING) {
            shutdownLogDeliveryAfterTail();
            return;
        }

        ensureLogDeliveryExecutor();
        scheduleLogDelivery(false);
    }

    /**
     * Lets the lines held during init go out, as the request queue is usable from here on.
     */
    void onSdkInitFinished() {
        sdkInitFinished = true;
        if (isGatheringLogs()) {
            d("[Log] onSdkInitFinished, uploading the lines gathered during init");
            scheduleLogDelivery(true);
        }
    }

    /**
     * Uploads what is held, on a timer tick, so a buffer that never fills a batch still goes out.
     */
    void flushGatheredLogs() {
        if (isGatheringLogs() && heldLogLineCount() > 0) {
            scheduleLogDelivery(true);
        }
    }

    /**
     * Tells the logger that a request carrying a log batch left the request queue, sent or dropped
     * unsent. One batch waits in the queue at a time, so while nothing is sent the lines stay in the
     * capped buffer instead of filling the queue; a delivery that left lines behind for the waiting
     * batch resumes now, a partial batch included. Called by the networking, on a thread marked as
     * doing this feature's own transport work, and never logs.
     */
    void onLogBatchSent() {
        if (logDeliveryAwaitsSentBatch.compareAndSet(true, false) && isGatheringLogs()) {
            scheduleLogDelivery(true);
        }
    }

    /**
     * Stops the gather before the device ID changes without merge: queues every held line on the
     * calling thread, past the one waiting batch, so each request carries the current device ID,
     * then drops the lines still waiting for their consent and stops the capture, so nothing the
     * next user does is gathered for the current gather. A later live directive can arm it again.
     */
    void stopLogGatheringForDeviceIdChange() {
        queueHeldLinesAndStop("stopLogGatheringForDeviceIdChange", true, false);
        d("[Log] stopLogGatheringForDeviceIdChange, log gathering stopped before the device ID change");
    }

    /**
     * Drops the held lines whose consent is no longer given, as after a consent was removed, the
     * last lines of a stopped gather included, so nothing gathered for a feature goes out once its
     * consent is withdrawn. They are counted as dropped.
     */
    void dropLinesWithoutConsent() {
        IntSupplier consents = givenConsents;
        if (consents == null) {
            return;
        }
        int given = consents.getAsInt();

        synchronized (logBufferLock) {
            Iterator<LogLine> iterator = gatheredLines.iterator();
            while (iterator.hasNext()) {
                LogLine line = iterator.next();
                if (!isConsentGiven(line.consentFeatures, given)) {
                    iterator.remove();
                    gatheredChars -= line.message.length();
                    droppedLogLineCount++;
                }
            }

            LogBatch tail = pendingTailBatch;
            if (tail != null) {
                List<LogLine> kept = new ArrayList<>(tail.lines.size());
                for (LogLine line : tail.lines) {
                    if (isConsentGiven(line.consentFeatures, given)) {
                        kept.add(line);
                    }
                }
                pendingTailBatch = kept.isEmpty() ? null : new LogBatch(tail.gatherId, tail.dropped + tail.lines.size() - kept.size(), kept);
            }
        }
    }

    /**
     * Moves the lines whose consent is given by now out of the waiting list into the buffer, in
     * capture order with the lines already there, and asks for a delivery when a full batch is
     * held. Called when the providers are set and whenever consent is given.
     */
    void releaseConsentedLogLines() {
        IntSupplier consents = givenConsents;
        if (consents == null) {
            return;
        }
        int given = consents.getAsInt();

        boolean fullBatch;
        synchronized (logBufferLock) {
            List<LogLine> released = new ArrayList<>();
            Iterator<LogLine> iterator = linesAwaitingConsent.iterator();
            while (iterator.hasNext()) {
                LogLine line = iterator.next();
                if (isConsentGiven(line.consentFeatures, given)) {
                    iterator.remove();
                    awaitingConsentChars -= line.message.length();
                    released.add(line);
                }
            }
            if (!released.isEmpty()) {
                mergeIntoBufferLocked(released);
            }
            fullBatch = isGatheringLogs() && gatheredLines.size() >= logGatherBatchSize;
        }

        if (fullBatch) {
            scheduleLogDelivery(false);
        }
    }

    /**
     * Marks the calling thread as doing this feature's own transport work, so nothing it logs
     * meanwhile is gathered, or every upload would report on itself.
     *
     * @param transporting {@code true} to mark the thread, {@code false} to clear the mark
     */
    void setOwnTransportWork(boolean transporting) {
        if (transporting) {
            ownTransportWork.set(Boolean.TRUE);
        } else {
            ownTransportWork.remove();
        }
    }

    /**
     * Whether the calling thread is marked as doing this feature's own transport work.
     *
     * @return {@code true} while the thread is marked
     */
    boolean isOwnTransportWork() {
        return Boolean.TRUE.equals(ownTransportWork.get());
    }

    /**
     * Ends log gathering for good, as the SDK that owns this logger stops. A stop that keeps the SDK
     * data queues what is held first, as far as tracking and consent allow, since nothing held
     * outlives the SDK; a stop that clears the data discards it. Waits for a delivery in flight, so
     * no batch of this logger is queued afterwards, and releases the delivery thread. Lines logged
     * afterwards, by threads that outlive the SDK, are neither captured nor queued.
     *
     * @param queueHeldLines {@code true} to queue the held lines first, {@code false} to discard them
     */
    void endLogGathering(boolean queueHeldLines) {
        queueHeldLinesAndStop("endLogGathering", queueHeldLines, true);
        configProvider = null;
        givenConsents = null;
        logBatchQueue = null;
        v("[Log] endLogGathering, log gathering ended");
    }

    /**
     * Queues what is held on the calling thread when asked, the held lines and then the last batch
     * of a gather the server stopped, so each request carries the current device ID, then stops the
     * capture and drops what is left, lines waiting for their consent included. Waits for a
     * delivery in flight first, and releases the delivery thread in the same step as the stop, so a
     * directive applied meanwhile either runs before it or starts a delivery thread of its own.
     *
     * @param caller the caller, for the log lines
     * @param queueHeldLines {@code true} to queue what is held first, {@code false} to drop it
     * @param endForGood {@code true} when the SDK stops, so nothing is gathered again, {@code false}
     *     when a later live directive may arm a gather again
     */
    private void queueHeldLinesAndStop(@Nonnull String caller, boolean queueHeldLines, boolean endForGood) {
        ExecutorService executor = null;
        Boolean previousMark = ownTransportWork.get();
        setOwnTransportWork(true);
        try {
            synchronized (logDeliveryLock) {
                try {
                    LogBatchQueue queue = logBatchQueue;
                    if (queueHeldLines && queue != null) {
                        if (isGatheringLogs()) {
                            int heldLines = heldLogLineCount();
                            d("[Log] " + caller + ", queueing the [" + heldLines + "] held lines");
                            deliverHeldLogBatches(heldLines, false);
                        }
                        LogBatch tail = takePendingTailBatch();
                        if (tail != null) {
                            queueTail(queue, tail);
                        }
                    }
                } catch (RuntimeException e) {
                    e("[Log] " + caller + ", failed to queue the held lines, [" + e + "]");
                } finally {
                    synchronized (logBufferLock) {
                        if (!logGatheringEnded) {
                            logGatheringEnded = endForGood;
                            logGatheringState = LogGatheringState.NOT_GATHERING;
                            logGatherId = null;
                            logGatherLevels = ModuleConfiguration.logGatheringAllLevels;
                            logGatherBatchSize = ModuleConfiguration.logGatheringDefaultBatchSize;
                            pendingTailBatch = null;
                            clearBufferLocked();
                            executor = takeLogDeliveryExecutor();
                        }
                    }
                }
            }
        } finally {
            setOwnTransportWork(Boolean.TRUE.equals(previousMark));
        }

        if (executor != null) {
            v("[Log] " + caller + ", stopping the log batch delivery executor");
            executor.shutdown();
        }
    }

    /**
     * Whether lines are still held for the server, which is true until something decides against
     * gathering.
     *
     * @return {@code true} while lines are captured
     */
    boolean isCapturingLogs() {
        return logGatheringState != LogGatheringState.NOT_GATHERING;
    }

    /**
     * Whether a live directive armed the gathering.
     *
     * @return {@code true} while gathering
     */
    boolean isGatheringLogs() {
        return logGatheringState == LogGatheringState.GATHERING;
    }

    /**
     * How many lines are held.
     *
     * @return the number of lines in the buffer
     */
    int heldLogLineCount() {
        synchronized (logBufferLock) {
            return gatheredLines.size();
        }
    }

    /**
     * How many lines wait for their consent, outside the buffer.
     *
     * @return the number of waiting lines
     */
    int awaitingConsentLineCount() {
        synchronized (logBufferLock) {
            return linesAwaitingConsent.size();
        }
    }

    /**
     * Holds a line for the server: every level while nothing is decided, the directive's levels
     * while gathering, nothing while not gathering. Lines that carry an uploaded batch and lines of a
     * thread doing this feature's own transport work are skipped. A line whose consent is not given
     * waits apart until it is. A full batch is handed to the delivery thread.
     *
     * @param message the line as logged
     * @param level the level character: e, w, i, d or v
     */
    private void captureLogLine(@Nullable String message, char level) {
        if (logGatheringState == LogGatheringState.NOT_GATHERING) {
            return;
        }
        if (Boolean.TRUE.equals(ownTransportWork.get())) {
            return;
        }
        String msg = message == null ? "" : message;
        if (msg.contains(transportMarker)) {
            return;
        }

        int consentFeatures = logLineConsentFeatures(msg);
        boolean consentGiven = isConsentGiven(consentFeatures);
        boolean batchIsFull = false;
        synchronized (logBufferLock) {
            LogGatheringState state = logGatheringState;
            if (state == LogGatheringState.NOT_GATHERING) {
                return;
            }
            boolean gathering = state == LogGatheringState.GATHERING;
            //while nothing is decided the wanted levels are unknown, so every level is held and filtered on adoption
            if (gathering && logGatherLevels.indexOf(level) < 0) {
                return;
            }

            LogLine line = new LogLine(nextLineSequence++, System.currentTimeMillis(), level, trimLogMessage(msg), consentFeatures);
            if (consentGiven) {
                gatheredLines.addLast(line);
                gatheredChars += line.message.length();
                trimBufferLocked();
                batchIsFull = gathering && gatheredLines.size() >= logGatherBatchSize;
            } else {
                linesAwaitingConsent.addLast(line);
                awaitingConsentChars += line.message.length();
                trimAwaitingConsentLocked();
            }
        }

        if (batchIsFull && !logDeliveryAwaitsSentBatch.get()) {
            scheduleLogDelivery(false);
        } else if (!consentGiven && isConsentGiven(consentFeatures)) {
            //given since it was read above, possibly right after the release that would have moved this line
            releaseConsentedLogLines();
        }
    }

    /**
     * Whether a line needing the given consent may join the gather now. Says no until the
     * providers are set, so the lines logged before that wait.
     *
     * @param consentFeatures what {@link #logLineConsentFeatures(String)} returned for the line
     * @return {@code true} when the line may join the gather
     */
    private boolean isConsentGiven(int consentFeatures) {
        IntSupplier consents = givenConsents;
        return consents != null && isConsentGiven(consentFeatures, consents.getAsInt());
    }

    /**
     * Whether a line needing the given consent may join the gather.
     *
     * @param consentFeatures the features any one of whose consent lets the line go, 0 for
     *     {@link #UNATTRIBUTED_LINE_CONSENT}
     * @param given the {@link CoreFeature} indexes whose consent is given
     * @return {@code true} when the line may join the gather
     */
    static boolean isConsentGiven(int consentFeatures, int given) {
        if (consentFeatures == 0) {
            return (given & UNATTRIBUTED_LINE_CONSENT) == UNATTRIBUTED_LINE_CONSENT;
        }
        return (given & consentFeatures) != 0;
    }

    /**
     * Finds the consent a log line needs from the name it starts with in brackets, up to the
     * closing bracket or a comma.
     *
     * @param message the line as logged
     * @return the {@link CoreFeature} indexes any one of whose consent lets the line go, 0 for a
     *     line that needs {@link #UNATTRIBUTED_LINE_CONSENT}
     */
    static int logLineConsentFeatures(@Nonnull String message) {
        if (message.isEmpty() || message.charAt(0) != '[') {
            return 0;
        }
        int limit = Math.min(message.length(), maxLogLineNameLength + 2);
        int end = 1;
        while (end < limit && message.charAt(end) != ']' && message.charAt(end) != ',') {
            end++;
        }
        if (end == 1 || end == limit) {
            return 0;
        }
        Integer features = logLineConsent.get(message.substring(1, end));
        return features == null ? 0 : features;
    }

    /**
     * Maps the bracketed names a feature's log lines start with to the consent of that feature.
     *
     * @param consent the map to fill
     * @param feature the feature whose consent the lines need
     * @param names the names, without the brackets
     */
    private static void mapLogLineConsent(@Nonnull Map<String, Integer> consent, @Nonnull CoreFeature feature, @Nonnull String... names) {
        for (String name : names) {
            consent.put(name, feature.getIndex());
        }
    }

    /**
     * Mirrors a directive and applies it to what is held.
     *
     * @param newState the decision
     * @param newGatherId the gather id, {@code null} unless gathering
     * @param newLevels the level characters to gather
     * @param newBatchSize how many lines make a batch
     * @return whether anything changed
     */
    private boolean adoptLogGatheringDirective(@Nonnull LogGatheringState newState, @Nullable String newGatherId, @Nonnull String newLevels, int newBatchSize) {
        synchronized (logBufferLock) {
            if (logGatheringEnded) {
                return false;
            }

            boolean changed = logGatheringState != newState
                || !Objects.equals(logGatherId, newGatherId)
                || !logGatherLevels.equals(newLevels)
                || logGatherBatchSize != newBatchSize;
            if (!changed) {
                return false;
            }

            boolean wasGathering = logGatheringState == LogGatheringState.GATHERING;
            String previousGatherId = logGatherId;

            logGatherId = newGatherId;
            logGatherLevels = newLevels;
            logGatherBatchSize = newBatchSize;
            logGatheringState = newState;

            switch (newState) {
                case NOT_GATHERING:
                    //the server accepts the last lines of a stopped gather under its id; lines held while undecided are dropped
                    if (wasGathering && previousGatherId != null && !gatheredLines.isEmpty()) {
                        pendingTailBatch = new LogBatch(previousGatherId, droppedLogLineCount, new ArrayList<>(gatheredLines));
                    }
                    clearBufferLocked();
                    break;
                case GATHERING:
                    if (wasGathering && previousGatherId != null && !previousGatherId.equals(newGatherId)) {
                        //the held lines belong to the previous gather and never go out under another id
                        clearBufferLocked();
                    } else {
                        filterBufferLocked();
                        trimBufferLocked();
                    }
                    break;
                case UNDECIDED:
                default:
                    break;
            }
            return true;
        }
    }

    /**
     * Asks the delivery thread to queue what is held, never the calling thread, which may hold a
     * lock of its own. A delivery already waiting to run takes the request over. Never logs, as it
     * runs from the capture of a line.
     *
     * @param includePartialBatch whether a batch that is not full goes out too
     */
    private void scheduleLogDelivery(boolean includePartialBatch) {
        if (!sdkInitFinished) {
            return;
        }
        if (includePartialBatch) {
            partialLogDeliveryRequested.set(true);
        }
        if (!logDeliveryPending.compareAndSet(false, true)) {
            return;
        }

        ExecutorService executor = logDeliveryExecutor;
        if (executor == null) {
            logDeliveryPending.set(false);
            return;
        }

        try {
            executor.execute(this::runLogDelivery);
        } catch (RejectedExecutionException e) {
            logDeliveryPending.set(false);
        }
    }

    /**
     * Runs one delivery on the delivery thread: every full batch, and when asked also what is held
     * beyond them, up to what was held when the delivery started.
     */
    private void runLogDelivery() {
        logDeliveryPending.set(false);
        boolean includePartialBatch = partialLogDeliveryRequested.getAndSet(false);
        try {
            synchronized (logDeliveryLock) {
                if (logGatheringEnded) {
                    return;
                }
                deliverHeldLogBatches(includePartialBatch ? heldLogLineCount() : 0, true);
            }
        } catch (RuntimeException e) {
            e("[Log] runLogDelivery, failed to upload the gathered log lines, [" + e + "]");
        }
    }

    /**
     * Queues the held lines batch by batch, oldest first, until nothing is left to queue or the
     * queue refuses a batch: every full batch, and within the given budget also a batch that is not
     * full. Holds everything while tracking is off. The buffer holds only lines whose consent was
     * given. The caller holds the delivery lock and has marked its thread.
     *
     * @param partialLineBudget how many lines may still go out in batches that are not full
     * @param oneBatchAtATime whether to queue nothing while a batch this logger queued waits in the
     *     request queue, and at most one batch otherwise
     */
    private void deliverHeldLogBatches(int partialLineBudget, boolean oneBatchAtATime) {
        LogBatchQueue queue = logBatchQueue;
        ConfigurationProvider provider = configProvider;
        if (queue == null || provider == null) {
            return;
        }

        int budget = partialLineBudget;
        while (true) {
            if (!provider.getTrackingEnabled()) {
                d("[Log] deliverHeldLogBatches, tracking is disabled, keeping the gathered lines buffered");
                return;
            }
            if (oneBatchAtATime) {
                if (forgetLogBatchesNoLongerQueued(queue) > 0) {
                    if (hasLinesToDeliver(budget > 0)) {
                        logDeliveryAwaitsSentBatch.set(true);
                        d("[Log] deliverHeldLogBatches, a log batch is still waiting in the request queue, delivering the rest once it is sent");
                    }
                    return;
                }
                //no batch waits any more, as when the queue dropped it unsent, so a resume asked for earlier is moot
                logDeliveryAwaitsSentBatch.set(false);
            }

            LogBatch batch = takeLogBatch(budget > 0);
            if (batch == null) {
                return;
            }
            budget -= batch.lines.size();

            String payload = buildLogBatchPayload(batch.gatherId, batch.dropped, batch.lines);
            d("[Log] deliverHeldLogBatches, uploading a gathered log batch of [" + batch.lines.size() + "] lines, [" + payload.length() + "] characters");
            if (!queueLogBatch(queue, payload)) {
                d("[Log] deliverHeldLogBatches, the request queue refused the batch, keeping its lines buffered");
                restoreLogBatch(batch);
                return;
            }
        }
    }

    /**
     * Hands one batch to the request queue and keeps the storage ID of its request, so the next
     * batch waits while the queue holds it.
     *
     * @param queue the request queue
     * @param payload the batch
     * @return whether the queue took the batch
     */
    private boolean queueLogBatch(@Nonnull LogBatchQueue queue, @Nonnull String payload) {
        Long requestId = queue.queueLogBatch(payload);
        if (requestId == null) {
            return false;
        }
        queuedLogBatchIds.add(requestId);
        return true;
    }

    /**
     * Forgets the batches the request queue no longer holds, sent or dropped, which also covers a
     * request dropped without a send, as when the queue is over its size.
     *
     * @param queue the request queue
     * @return how many batches of this logger the queue still holds
     */
    private int forgetLogBatchesNoLongerQueued(@Nonnull LogBatchQueue queue) {
        queuedLogBatchIds.removeIf(requestId -> !queue.isQueued(requestId));
        return queuedLogBatchIds.size();
    }

    /**
     * Whether a delivery would queue anything now.
     *
     * @param includePartialBatch whether a batch that is not full counts
     * @return {@code true} when a batch could be taken
     */
    private boolean hasLinesToDeliver(boolean includePartialBatch) {
        synchronized (logBufferLock) {
            return canTakeLogBatchLocked(includePartialBatch);
        }
    }

    /**
     * Whether a batch can be taken out of the buffer. The caller holds the buffer lock.
     *
     * @param includePartialBatch whether a batch that is not full counts
     * @return {@code true} when a batch can be taken
     */
    private boolean canTakeLogBatchLocked(boolean includePartialBatch) {
        if (logGatheringState != LogGatheringState.GATHERING || logGatherId == null || gatheredLines.isEmpty()) {
            return false;
        }
        return includePartialBatch || gatheredLines.size() >= logGatherBatchSize;
    }

    /**
     * Queues the last batch of a gather the server stopped, once, from the delivery thread, as the
     * decision that stopped the gather may hold a lock the request queue needs.
     */
    private void queueTailBatch() {
        synchronized (logDeliveryLock) {
            LogBatch tail = takePendingTailBatch();
            LogBatchQueue queue = logBatchQueue;
            if (tail == null || queue == null || !sdkInitFinished || logGatheringEnded) {
                return;
            }
            try {
                queueTail(queue, tail);
            } catch (RuntimeException e) {
                e("[Log] queueTailBatch, failed to upload the last batch, [" + e + "]");
            }
        }
    }

    /**
     * Takes the last batch of a gather the server stopped, if one waits.
     *
     * @return the batch, {@code null} for none
     */
    @Nullable
    private LogBatch takePendingTailBatch() {
        synchronized (logBufferLock) {
            LogBatch tail = pendingTailBatch;
            pendingTailBatch = null;
            return tail;
        }
    }

    /**
     * Queues the last batch of a stopped gather, with its id. Its lines are gone if the queue
     * refuses it. The caller holds the delivery lock.
     *
     * @param queue the request queue
     * @param tail the batch
     */
    private void queueTail(@Nonnull LogBatchQueue queue, @Nonnull LogBatch tail) {
        d("[Log] queueTail, uploading the last [" + tail.lines.size() + "] lines of the stopped gather");
        if (!queueLogBatch(queue, buildLogBatchPayload(tail.gatherId, tail.dropped, tail.lines))) {
            d("[Log] queueTail, the request queue refused the last batch, its lines are dropped");
        }
    }

    /**
     * Takes up to one batch of the oldest lines out of the buffer, with the lines dropped since the
     * last batch, which are reported once, with the batch that follows the loss.
     *
     * @param includePartialBatch whether a batch that is not full may be taken
     * @return the batch, {@code null} when there is nothing to take
     */
    @Nullable
    private LogBatch takeLogBatch(boolean includePartialBatch) {
        synchronized (logBufferLock) {
            if (!canTakeLogBatchLocked(includePartialBatch)) {
                return null;
            }

            int count = Math.min(logGatherBatchSize, gatheredLines.size());
            List<LogLine> taken = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                LogLine line = gatheredLines.removeFirst();
                gatheredChars -= line.message.length();
                taken.add(line);
            }

            LogBatch batch = new LogBatch(logGatherId, droppedLogLineCount, taken);
            droppedLogLineCount = 0;
            return batch;
        }
    }

    /**
     * Puts a batch the queue refused back at the front of the buffer, oldest first, with its drop
     * count, unless its gather ended meanwhile.
     *
     * @param batch the refused batch
     */
    private void restoreLogBatch(@Nonnull LogBatch batch) {
        synchronized (logBufferLock) {
            if (logGatheringState != LogGatheringState.GATHERING || !batch.gatherId.equals(logGatherId)) {
                return;
            }

            for (int i = batch.lines.size() - 1; i >= 0; i--) {
                LogLine line = batch.lines.get(i);
                if (logGatherLevels.indexOf(line.level) > -1) {
                    gatheredLines.addFirst(line);
                    gatheredChars += line.message.length();
                }
            }
            droppedLogLineCount += batch.dropped;
            trimBufferLocked();
        }
    }

    /**
     * Creates the delivery thread's executor unless it exists or the gathering ended. The thread is
     * a daemon, and it is marked as doing transport work for its whole life.
     */
    private void ensureLogDeliveryExecutor() {
        synchronized (logDeliveryExecutorLock) {
            if (logGatheringEnded || logDeliveryExecutor != null) {
                return;
            }
            logDeliveryExecutor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(() -> {
                    setOwnTransportWork(true);
                    runnable.run();
                }, DELIVERY_THREAD_NAME);
                thread.setDaemon(true);
                return thread;
            });
        }
        v("[Log] ensureLogDeliveryExecutor, created the log batch delivery executor");
    }

    /**
     * Releases the delivery thread once it queued the last batch of a gather the server stopped,
     * if there is one. The batch is queued on the delivery thread, behind any delivery already
     * waiting there, which finds nothing to do once the gathering is off.
     */
    private void shutdownLogDeliveryAfterTail() {
        ExecutorService executor = takeLogDeliveryExecutor();
        if (executor == null) {
            takePendingTailBatch();
            return;
        }

        try {
            executor.execute(this::queueTailBatch);
        } catch (RejectedExecutionException e) {
            takePendingTailBatch();
        }
        v("[Log] shutdownLogDeliveryAfterTail, stopping the log batch delivery executor");
        executor.shutdown();
    }

    /**
     * Takes the delivery thread's executor away, so no delivery is scheduled on it any more.
     *
     * @return the executor, {@code null} when there was none
     */
    @Nullable
    private ExecutorService takeLogDeliveryExecutor() {
        synchronized (logDeliveryExecutorLock) {
            ExecutorService executor = logDeliveryExecutor;
            logDeliveryExecutor = null;
            return executor;
        }
    }

    /**
     * Enforces both buffer ceilings by dropping the oldest lines, counting them into {@code d}.
     */
    private void trimBufferLocked() {
        while (gatheredLines.size() > ModuleConfiguration.logGatheringMaxBufferedLines
            || (gatheredChars > gatheredCharCeiling && gatheredLines.size() > 1)) {
            gatheredChars -= gatheredLines.removeFirst().message.length();
            droppedLogLineCount++;
        }
    }

    /**
     * Enforces both ceilings on the lines waiting for their consent by dropping the oldest. They
     * are not counted into {@code d}, as a line waiting for its consent has not joined the gather.
     */
    private void trimAwaitingConsentLocked() {
        while (linesAwaitingConsent.size() > ModuleConfiguration.logGatheringMaxBufferedLines
            || (awaitingConsentChars > gatheredCharCeiling && linesAwaitingConsent.size() > 1)) {
            awaitingConsentChars -= linesAwaitingConsent.removeFirst().message.length();
        }
    }

    /**
     * Adds lines whose consent was given to the buffer, which stays in capture order.
     *
     * @param released the lines, in capture order
     */
    private void mergeIntoBufferLocked(@Nonnull List<LogLine> released) {
        List<LogLine> merged = new ArrayList<>(gatheredLines);
        merged.addAll(released);
        merged.sort(Comparator.comparingLong(line -> line.sequence));
        for (LogLine line : released) {
            gatheredChars += line.message.length();
        }

        gatheredLines.clear();
        gatheredLines.addAll(merged);
        trimBufferLocked();
    }

    /**
     * Drops the lines of levels the directive does not ask for, held or waiting for their consent.
     * They are not counted as dropped, as they were never wanted.
     */
    private void filterBufferLocked() {
        Iterator<LogLine> iterator = gatheredLines.iterator();
        while (iterator.hasNext()) {
            LogLine line = iterator.next();
            if (logGatherLevels.indexOf(line.level) < 0) {
                gatheredChars -= line.message.length();
                iterator.remove();
            }
        }

        Iterator<LogLine> awaiting = linesAwaitingConsent.iterator();
        while (awaiting.hasNext()) {
            LogLine line = awaiting.next();
            if (logGatherLevels.indexOf(line.level) < 0) {
                awaitingConsentChars -= line.message.length();
                awaiting.remove();
            }
        }
    }

    /**
     * Empties the buffer and the lines waiting for their consent, and forgets the drop count.
     */
    private void clearBufferLocked() {
        gatheredLines.clear();
        gatheredChars = 0;
        linesAwaitingConsent.clear();
        awaitingConsentChars = 0;
        droppedLogLineCount = 0;
    }

    /**
     * Builds the {@code sdk_logs} value of one batch, its keys always in the same order.
     *
     * @param gatherId the gather id the directive named, which the server requires
     * @param dropped how many lines were dropped since the last batch
     * @param lines the lines, oldest first
     * @return the JSON text
     */
    @Nonnull
    static String buildLogBatchPayload(@Nonnull String gatherId, int dropped, @Nonnull List<LogLine> lines) {
        StringBuilder payload = new StringBuilder(64 + lines.size() * 96);
        payload.append("{\"").append(keyBatchGatherId).append("\":").append(JSONObject.quote(gatherId))
            .append(",\"").append(keyBatchDroppedCount).append("\":").append(dropped)
            .append(",\"").append(keyBatchLines).append("\":[");
        for (int i = 0; i < lines.size(); i++) {
            LogLine line = lines.get(i);
            if (i > 0) {
                payload.append(',');
            }
            payload.append("{\"").append(keyLineTimestamp).append("\":").append(line.timestamp)
                .append(",\"").append(keyLineLevel).append("\":\"").append(line.level)
                .append("\",\"").append(keyLineMessage).append("\":").append(JSONObject.quote(line.message))
                .append('}');
        }
        return payload.append("]}").toString();
    }

    /**
     * Cuts a message to what the server keeps, never between the two halves of a surrogate pair.
     *
     * @param message the message
     * @return the message, at most {@value #maxMessageLength} characters long
     */
    @Nonnull
    static String trimLogMessage(@Nonnull String message) {
        if (message.length() <= maxMessageLength) {
            return message;
        }

        int end = maxMessageLength;
        if (Character.isHighSurrogate(message.charAt(end - 1))) {
            end--;
        }
        return message.substring(0, end);
    }

    /**
     * One captured line.
     */
    static final class LogLine {
        final long sequence;
        final long timestamp;
        final char level;
        final String message;
        final int consentFeatures;

        /**
         * Holds one line.
         *
         * @param sequence its place in capture order
         * @param timestamp when it was logged, in milliseconds
         * @param level the level character
         * @param message the message, already cut
         * @param consentFeatures the {@link CoreFeature} indexes any one of whose consent lets the
         *     line go, 0 for {@link #UNATTRIBUTED_LINE_CONSENT}
         */
        LogLine(long sequence, long timestamp, char level, @Nonnull String message, int consentFeatures) {
            this.sequence = sequence;
            this.timestamp = timestamp;
            this.level = level;
            this.message = message;
            this.consentFeatures = consentFeatures;
        }
    }

    /**
     * One batch taken out of the buffer, kept whole so a refused batch can be put back.
     */
    private static final class LogBatch {
        final String gatherId;
        final int dropped;
        final List<LogLine> lines;

        /**
         * Holds one batch.
         *
         * @param gatherId the gather id the batch is uploaded with
         * @param dropped how many lines were dropped before it
         * @param lines the lines, oldest first
         */
        LogBatch(@Nonnull String gatherId, int dropped, @Nonnull List<LogLine> lines) {
            this.gatherId = gatherId;
            this.dropped = dropped;
            this.lines = lines;
        }
    }
}
