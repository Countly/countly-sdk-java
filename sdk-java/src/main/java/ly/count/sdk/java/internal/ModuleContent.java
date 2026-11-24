package ly.count.sdk.java.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import ly.count.sdk.java.Countly;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Fetches content blocks from the server while the app is in a content zone, and hands whatever the
 * server decided to show to a {@link ContentDisplay}.
 * <p>
 * The module itself never touches a UI toolkit. A display has to be registered through
 * {@link Content#setContentDisplay(ContentDisplay)} before a content zone can be entered; the
 * "ly.count.sdk:java-ui" artifact ships a JavaFX one.
 *
 * @apiNote This is an EXPERIMENTAL feature, and it can have breaking changes
 */
public class ModuleContent extends ModuleBase {

    /**
     * How long after the SDK started the first fetch of a zone may happen, so a zone entered right
     * after init does not race the rest of the SDK coming up.
     * <p>
     * It is a floor measured from init, not a delay charged to every {@code enterContentZone}: an
     * application that turns the zone on later has already waited it out, and charging it anyway put
     * four seconds between the call and the content appearing every single time. Same rule as the
     * Android SDK's {@code CONTENT_START_DELAY_MS}, which only adds it while the SDK is that new.
     */
    static final long START_DELAY_MS = 4000;

    /**
     * How many timer ticks are skipped after a content block was closed, giving the server time to
     * process whatever the content recorded before the SDK asks for the next one.
     */
    static final int POST_CLOSE_SKIPPED_TICKS = 2;

    /** How often a content trigger round checks whether the requests queued before it are sent. */
    static final long TRIGGER_QUEUE_CHECK_MS = 500;
    /** How many checks in a row without a sent request end a round without a content request. */
    static final int TRIGGER_QUEUE_CHECKS = 5;
    /** How long a round waits before it asks for content again when nothing was shown. */
    static final long TRIGGER_CONTENT_RETRY_MS = 1000;
    /** How many content requests a round sends at most. */
    static final int TRIGGER_CONTENT_REQUESTS = 3;
    /** How many triggers recorded before the first settings answer of the run are kept. */
    static final int TRIGGER_EARLY_LIMIT = 50;

    Content contentInterface = null;
    ContentDisplay display = null;
    CountlyTimer contentTimer = null;

    private final Object contentLock = new Object();
    private boolean zoneActive = false;
    private boolean shouldFetch = false;
    private boolean contentShown = false;
    private boolean fetching = false;
    private int waitForDelay = 0;
    private int generation = 0;
    /** The {@code ecz} value of the settings in use at init, which decides the entry at init. */
    private final AtomicBoolean contentZoneEnabledAtInit = new AtomicBoolean(false);
    /** Whether the {@code ecz} setting decided to enter the zone, which then happens once a display is registered. */
    private final AtomicBoolean settingsAskForZone = new AtomicBoolean(false);
    /** Set after the application or the {@code ecz} setting entered the zone, until the zone is left. */
    private final AtomicBoolean contentZoneWanted = new AtomicBoolean(false);
    /** Set after the application left the zone, until it enters it again. */
    private final AtomicBoolean contentZoneExited = new AtomicBoolean(false);
    /** Set once the settings answer of this run arrived, or once none will arrive. */
    private final AtomicBoolean behaviorSettingsSettled = new AtomicBoolean(false);
    /** Runs the content trigger rounds, one task at a time; the fields below are used only on it. */
    private ScheduledExecutorService triggerExecutor = null;
    private boolean triggerRoundRunning = false;
    private boolean triggerRoundPending = false;
    private int contentTriggerChain = 0;
    private final List<ContentTriggers.Trigger> earlyTriggers = new ArrayList<>();
    /**
     * Set once {@link #stop(InternalConfig, boolean)} starts, after which no zone is entered, so no
     * zone timer outlives the module. Guarded by {@link #contentLock}.
     */
    private boolean stopped = false;

    /** When the SDK came up, which is what {@link #START_DELAY_MS} is measured from. */
    private long startedAt = 0;
    private ContentCallback globalContentCallback = null;

    ModuleContent() {
    }

    @Override
    public void init(InternalConfig config) {
        super.init(config);
        L.v("[ModuleContent] Initializing");

        globalContentCallback = config.content.globalContentCallback;
        contentInterface = new Content();
        startedAt = TimeUtils.uniqueTimestampMs();
        synchronized (contentLock) {
            display = config.sdk.contentDisplay;
        }
        // the settings in use at init decide the entry, also when an answer arrives before init ends
        contentZoneEnabledAtInit.set(config.getConfigurationProvider().getContentZoneEnabled());
        triggerExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Countly-content-triggers");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Enters the content zone when the {@code ecz} setting of the settings in use at init asks for
     * it. While no display is registered the zone is entered once one is.
     *
     * @param config configuration of the running SDK
     */
    @Override
    protected void initFinished(InternalConfig config) {
        super.initFinished(config);
        if (contentZoneEnabledAtInit.get()) {
            enterContentZoneForSettings("initFinished");
        }
    }

    /**
     * Applies the content settings a server response changed: a changed {@code czi} restarts the
     * timer of an active zone. A changed {@code ecz} takes effect at the next init, it never enters
     * or leaves the zone of a running SDK.
     *
     * @param config configuration of the running SDK
     */
    @Override
    protected void onSdkConfigurationChanged(InternalConfig config) {
        restartZoneTimerIfIntervalChanged(config.getConfigurationProvider().getContentZoneTimerInterval());
    }

    /**
     * Enters the content zone because the {@code ecz} setting asks for it, once a display is registered.
     *
     * @param caller the calling method, for the log
     */
    private void enterContentZoneForSettings(@Nonnull String caller) {
        settingsAskForZone.set(true);
        boolean hasDisplay;
        synchronized (contentLock) {
            hasDisplay = display != null;
        }

        if (!hasDisplay) {
            L.d("[ModuleContent] " + caller + ", the SDK behavior settings ask for the content zone, it is entered once a content display is registered");
            return;
        }

        L.d("[ModuleContent] " + caller + ", entering the content zone as the SDK behavior settings ask");
        enterContentZoneInternal();
    }

    /**
     * Restarts the timer of an active zone when the fetch interval changed, keeping the zone as it
     * is. The next fetch comes as soon as the start delay allows.
     *
     * @param intervalSeconds the fetch interval now in effect
     */
    private void restartZoneTimerIfIntervalChanged(int intervalSeconds) {
        CountlyTimer timerToStop;
        synchronized (contentLock) {
            if (!zoneActive || contentTimer == null || contentTimer.getTimerDelaySeconds() == intervalSeconds) {
                return;
            }

            L.d("[ModuleContent] restartZoneTimerIfIntervalChanged, the fetch interval changed from [" + contentTimer.getTimerDelaySeconds() + "] to [" + intervalSeconds + "] seconds, restarting the zone timer");
            timerToStop = contentTimer;
            contentTimer = new CountlyTimer(L);
            contentTimer.startTimer(intervalSeconds, firstFetchDelay(), this::onZoneTimerTick);
        }

        // Not awaited: the zone stays active, so a tick of the replaced timer that is still running is harmless.
        timerToStop.stopTimer(false);
    }

    @Override
    public Boolean onRequest(Request request) {
        return true;
    }

    /**
     * Leaves the content zone for good: from here on no zone is entered, not even by a settings
     * change that arrives while the SDK stops.
     *
     * @param config configuration of the SDK being stopped
     * @param clear whether the SDK clears its data
     */
    @Override
    public void stop(InternalConfig config, boolean clear) {
        synchronized (contentLock) {
            stopped = true;
        }
        ScheduledExecutorService executor = triggerExecutor;
        triggerExecutor = null;
        if (executor != null) {
            executor.shutdownNow();
        }
        super.stop(config, clear);
        contentZoneWanted.set(false);
        exitContentZoneInternal();
        display = null;
        contentInterface = null;
        globalContentCallback = null;
    }

    /**
     * A device ID change without merge means a different user, so whatever the server decided to
     * show the previous one no longer applies. The zone is torn down and the developer decides
     * whether to enter it again for the new user. A change with merge is the same user, so the zone
     * keeps running.
     */
    @Override
    protected void deviceIdChanged(String oldDeviceId, boolean withMerge) {
        super.deviceIdChanged(oldDeviceId, withMerge);
        L.d("[ModuleContent] deviceIdChanged, oldDeviceId:[" + oldDeviceId + "] withMerge:[" + withMerge + "]");

        if (withMerge) {
            return;
        }

        L.i("[ModuleContent] deviceIdChanged, the device ID changed without merge, leaving the content zone");
        contentZoneWanted.set(false);
        exitContentZoneInternal();
    }

    void setContentDisplayInternal(ContentDisplay contentDisplay) {
        L.d("[ModuleContent] setContentDisplayInternal, display set:[" + (contentDisplay != null) + "]");
        boolean zoneInactive;
        synchronized (contentLock) {
            display = contentDisplay;
            zoneInactive = !zoneActive;
        }

        if (contentDisplay != null && zoneInactive && settingsAskForZone.get()) {
            enterContentZoneForSettings("setContentDisplayInternal");
        }
    }

    /**
     * Enters the content zone, fetching every {@code czi} seconds. Entering a zone that is already
     * active does nothing.
     */
    void enterContentZoneInternal() {
        if (display == null) {
            L.w("[ModuleContent] enterContentZoneInternal, no content display is registered, ignoring the call");
            return;
        }

        if (internalConfig.isTemporaryIdEnabled()) {
            L.w("[ModuleContent] enterContentZoneInternal, content can't be fetched while in temporary device ID mode");
            return;
        }

        int zoneTimerInterval = internalConfig.getConfigurationProvider().getContentZoneTimerInterval();
        synchronized (contentLock) {
            if (stopped) {
                L.d("[ModuleContent] enterContentZoneInternal, the module is stopped, ignoring the call");
                return;
            }

            contentZoneWanted.set(true);
            contentZoneExited.set(false);
            if (zoneActive) {
                L.d("[ModuleContent] enterContentZoneInternal, already in a content zone, ignoring the call");
                return;
            }

            zoneActive = true;
            shouldFetch = true;
            contentShown = false;
            fetching = false;
            waitForDelay = 0;
            // Any fetch left in flight from a previous zone belongs to an older generation and is
            // discarded when it completes.
            generation++;

            contentTimer = new CountlyTimer(L);
            contentTimer.startTimer(zoneTimerInterval, firstFetchDelay(), this::onZoneTimerTick);
        }

        L.i("[ModuleContent] enterContentZoneInternal, entered the content zone, fetch interval:[" + zoneTimerInterval + "] seconds");
    }

    /**
     * @return how long the first fetch of this zone has to wait: whatever is left of
     *     {@link #START_DELAY_MS} since the SDK started, and nothing once that has passed
     */
    long firstFetchDelay() {
        long sinceStart = TimeUtils.uniqueTimestampMs() - startedAt;
        long remaining = START_DELAY_MS - sinceStart;
        return remaining > 0 ? remaining : 0;
    }

    /**
     * Pretends the SDK started at another time, so a test can exercise the delay rule without
     * waiting out the window.
     *
     * @param when when the SDK is to be considered started, in milliseconds
     */
    void startedAtForTests(long when) {
        startedAt = when;
    }

    void exitContentZoneInternal() {
        exitContentZoneInternal(true);
    }

    /**
     * Leaves the content zone because the application asked for it. Content triggers then ask for
     * nothing until the application enters the zone again.
     */
    void exitContentZoneByApp() {
        contentZoneWanted.set(false);
        contentZoneExited.set(true);
        exitContentZoneInternal();
    }

    /**
     * Leaves the content zone.
     *
     * @param awaitTimerTermination must be {@code false} when called from the zone timer's own
     *     tick, because a task cannot wait for itself to finish
     */
    private void exitContentZoneInternal(boolean awaitTimerTermination) {
        CountlyTimer timerToStop;
        synchronized (contentLock) {
            zoneActive = false;
            shouldFetch = false;
            contentShown = false;
            fetching = false;
            waitForDelay = 0;
            generation++;

            timerToStop = contentTimer;
            contentTimer = null;
        }

        // Stopped outside the lock: stopping waits for a running tick, and that tick needs the lock.
        if (timerToStop != null) {
            timerToStop.stopTimer(awaitTimerTermination);
        }

        L.i("[ModuleContent] exitContentZoneInternal, left the content zone");
    }

    /**
     * Flushes the event queue and enters the content zone again. Called by the application; ignored
     * while the {@code rcz} setting forbids refreshing and while a content block is on screen.
     */
    void refreshContentZoneInternal() {
        if (!internalConfig.getConfigurationProvider().getRefreshContentZoneEnabled()) {
            L.d("[ModuleContent] refreshContentZoneInternal, refreshing the content zone is disabled by the SDK behavior settings, ignoring the call");
            return;
        }

        synchronized (contentLock) {
            if (contentShown) {
                L.d("[ModuleContent] refreshContentZoneInternal, a content block is on screen, ignoring the call");
                return;
            }
        }

        // Push whatever is queued out first, so the trigger the developer just recorded has a
        // chance of being processed before the next fetch lands.
        flushEventQueue();

        exitContentZoneInternal();
        enterContentZoneInternal();
    }

    /**
     * Something the SDK recorded that can be a content trigger. A match, outside its gap, sends the
     * queued requests and then asks for content.
     *
     * @param trigger what was recorded
     */
    void onContentTrigger(@Nonnull ContentTriggers.Trigger trigger) {
        // once the settings of this run are in, what matches nothing is dropped here
        if (behaviorSettingsSettled.get() && matchOf(trigger) == null) {
            return;
        }
        runOnTriggerExecutor(() -> handleContentTrigger(trigger), 0);
    }

    /**
     * The settings answer of this run arrived and was applied, or none will arrive.
     *
     * @param answered whether an answer arrived and was applied
     */
    void onBehaviorSettingsSettled(boolean answered) {
        runOnTriggerExecutor(() -> settleEarlyTriggers(answered), 0);
    }

    /**
     * @param trigger what was recorded
     * @return the match of what was recorded with the triggers in use, {@code null} for none
     */
    private ContentTriggers.Match matchOf(@Nonnull ContentTriggers.Trigger trigger) {
        ConfigurationProvider configProvider = internalConfig.getConfigurationProvider();
        return ContentTriggers.match(configProvider.getContentTriggers(), contentZoneWanted.get(), configProvider.getJourneyTriggerEvents(), configProvider.getJourneyTriggerViews(), trigger);
    }

    /**
     * Runs a task of the content trigger rounds on their thread, unless the module stopped.
     *
     * @param task the task
     * @param delayMs how long to wait first, in milliseconds
     */
    private void runOnTriggerExecutor(@Nonnull Runnable task, long delayMs) {
        ScheduledExecutorService executor = triggerExecutor;
        if (executor == null) {
            return;
        }
        try {
            executor.schedule(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    L.e("[ModuleContent] runOnTriggerExecutor, a content trigger task failed, [" + e + "]");
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            L.d("[ModuleContent] runOnTriggerExecutor, the module is stopping, the task is dropped");
        }
    }

    /**
     * Trigger thread only. Starts a round for a content trigger outside its gap, when content can be
     * shown. A trigger during a running round only makes one more round follow it.
     *
     * @param trigger what was recorded
     */
    private void handleContentTrigger(@Nonnull ContentTriggers.Trigger trigger) {
        ContentTriggers.Match match = matchOf(trigger);
        if (match == null) {
            rememberEarlyTrigger(trigger);
            return;
        }

        if (!contentCanBeShown()) {
            L.d("[ModuleContent] handleContentTrigger, [" + trigger + "] is a content trigger, but content can not be shown now, so it is not asked for");
            return;
        }

        String storedGaps = internalConfig.storageProvider.getContentTriggerGaps();
        String gaps = ContentTriggers.claimGap(match, storedGaps == null ? "" : storedGaps, TimeUtils.uniqueTimestampMs());
        if (gaps == null) {
            L.d("[ModuleContent] handleContentTrigger, [" + trigger + "] started a round less than [" + match.gapMs / 1000 + "] seconds ago");
            return;
        }
        internalConfig.storageProvider.setContentTriggerGaps(gaps);

        if (triggerRoundRunning) {
            L.d("[ModuleContent] handleContentTrigger, [" + trigger + "] matched while a round is running, one more round follows it");
            triggerRoundPending = true;
            return;
        }

        L.d("[ModuleContent] handleContentTrigger, [" + trigger + "] matched, sending the queued requests and asking for content");
        startTriggerRound();
    }

    /**
     * @return whether content can be shown now: the application did not leave the content zone, a
     *     display is registered and the content consent is given
     */
    private boolean contentCanBeShown() {
        SDKCore core = SDKCore.instance;
        return !contentZoneExited.get() && display != null && core != null && core.hasConsentForFeature(CoreFeature.Content);
    }

    /**
     * Trigger thread only. Keeps what was recorded before the settings answer of this run arrived.
     *
     * @param trigger what was recorded
     */
    private void rememberEarlyTrigger(@Nonnull ContentTriggers.Trigger trigger) {
        if (behaviorSettingsSettled.get() || earlyTriggers.size() >= TRIGGER_EARLY_LIMIT) {
            return;
        }
        for (ContentTriggers.Trigger early : earlyTriggers) {
            if (early.sameAs(trigger)) {
                return;
            }
        }
        earlyTriggers.add(trigger);
    }

    /**
     * Trigger thread only. Ends the wait for the settings answer of this run. When the answer
     * arrived and no settings were stored at init, its {@code ecz} decides the content zone entry,
     * and the first remembered trigger it lists starts a round.
     *
     * @param answered whether an answer arrived and was applied
     */
    private void settleEarlyTriggers(boolean answered) {
        boolean firstAnswer = answered && !behaviorSettingsSettled.get();
        behaviorSettingsSettled.set(true);
        if (!firstAnswer) {
            earlyTriggers.clear();
            return;
        }
        List<ContentTriggers.Trigger> early = new ArrayList<>(earlyTriggers);
        earlyTriggers.clear();

        SDKCore core = SDKCore.instance;
        ModuleConfiguration configuration = core == null ? null : core.module(ModuleConfiguration.class);
        if (configuration != null && !configuration.behaviorSettingsStoredAtInit && configuration.getContentZoneEnabled() && !contentZoneWanted.get() && !contentZoneExited.get()) {
            enterContentZoneForSettings("settleEarlyTriggers");
        }

        for (ContentTriggers.Trigger trigger : early) {
            if (matchOf(trigger) != null) {
                L.d("[ModuleContent] settleEarlyTriggers, [" + trigger + "] was recorded before the behavior settings arrived");
                handleContentTrigger(trigger);
                return;
            }
        }
    }

    /**
     * Trigger thread only. Moves the recorded events into the request queue, sends the queue, and
     * asks for content once the requests queued until now are sent.
     */
    private void startTriggerRound() {
        triggerRoundRunning = true;
        triggerRoundPending = false;

        SDKCore core = SDKCore.instance;
        ModuleEvents events = core == null ? null : core.module(ModuleEvents.class);
        if (events == null) {
            waitForQueueOfRound();
            return;
        }
        events.moveEventsToRequestQueue(() -> runOnTriggerExecutor(this::waitForQueueOfRound, 0));
    }

    /**
     * Trigger thread only. Sends the queue and starts checking whether the requests queued until
     * now are sent.
     */
    private void waitForQueueOfRound() {
        List<Long> queued = Storage.list(internalConfig, Request.getStoragePrefix());
        if (queued.isEmpty()) {
            requestTriggeredContent(++contentTriggerChain, 1);
            endTriggerRound();
            return;
        }

        long lastQueued = Collections.max(queued);
        sendQueue();
        int chain = contentTriggerChain;
        runOnTriggerExecutor(() -> waitForTriggerQueue(lastQueued, queued.size(), 0, chain), TRIGGER_QUEUE_CHECK_MS);
    }

    /**
     * Trigger thread only. Checks whether the requests queued when the round started are sent, and
     * asks for content once they are. When none of them is sent for {@link #TRIGGER_QUEUE_CHECKS}
     * checks in a row, the round ends without a content request, and the content zone timer picks
     * the content up.
     *
     * @param lastQueued storage ID of the last request queued when the round started
     * @param previousRemaining how many of them were left at the previous check
     * @param checksWithoutProgress checks in a row that found none of them sent
     * @param chain the chain of content requests when the round started
     */
    private void waitForTriggerQueue(long lastQueued, int previousRemaining, int checksWithoutProgress, int chain) {
        int remaining = 0;
        for (Long id : Storage.list(internalConfig, Request.getStoragePrefix())) {
            if (id <= lastQueued) {
                remaining++;
            }
        }

        if (remaining == 0) {
            requestTriggeredContent(++contentTriggerChain, 1);
            endTriggerRound();
            return;
        }

        int withoutProgress = remaining < previousRemaining ? 0 : checksWithoutProgress + 1;
        if (withoutProgress >= TRIGGER_QUEUE_CHECKS) {
            L.d("[ModuleContent] waitForTriggerQueue, the queued requests could not be sent in time, the round ends without a content request");
            endTriggerRound();
            return;
        }

        sendQueue();
        int left = remaining;
        runOnTriggerExecutor(() -> waitForTriggerQueue(lastQueued, left, withoutProgress, chain), TRIGGER_QUEUE_CHECK_MS);
    }

    /**
     * Has the networking send the next queued request, unless one is on its way.
     */
    private void sendQueue() {
        SDKCore core = SDKCore.instance;
        if (core != null && core.networking != null) {
            core.networking.check(internalConfig);
        }
    }

    /**
     * Trigger thread only. Sends a content request of a round. When it shows nothing, it is sent
     * again {@link #TRIGGER_CONTENT_RETRY_MS} later, up to {@link #TRIGGER_CONTENT_REQUESTS}
     * requests, as the server may still be processing what triggered it. A newer round's content
     * request stops the ones left of an older round.
     *
     * @param chain the chain this request belongs to
     * @param attempt the number of this content request in its round, from 1
     */
    private void requestTriggeredContent(int chain, int attempt) {
        if (chain != contentTriggerChain) {
            return;
        }

        boolean zoneNeeded = internalConfig.getConfigurationProvider().getContentTriggers() != null;
        if (!contentCanBeShown() || zoneNeeded && !contentZoneWanted.get()) {
            L.d("[ModuleContent] requestTriggeredContent, the content zone is not wanted now, no content request is sent");
            return;
        }

        Runnable retry = () -> {
            if (attempt < TRIGGER_CONTENT_REQUESTS) {
                runOnTriggerExecutor(() -> requestTriggeredContent(chain, attempt + 1), TRIGGER_CONTENT_RETRY_MS);
            }
        };

        int currentGeneration;
        synchronized (contentLock) {
            if (contentShown) {
                L.d("[ModuleContent] requestTriggeredContent, content is already shown, no content request is sent");
                return;
            }
            if (fetching) {
                L.d("[ModuleContent] requestTriggeredContent, a content request is already on its way");
                retry.run();
                return;
            }
            fetching = true;
            currentGeneration = generation;
        }

        L.d("[ModuleContent] requestTriggeredContent, sending content request [" + attempt + "] of [" + TRIGGER_CONTENT_REQUESTS + "]");
        fetchContents(null, currentGeneration, retry);
    }

    /**
     * Trigger thread only. Ends the queue part of a round, and starts the round a trigger during it
     * asked for.
     */
    private void endTriggerRound() {
        triggerRoundRunning = false;
        if (triggerRoundPending) {
            triggerRoundPending = false;
            runOnTriggerExecutor(this::startTriggerRound, 0);
        }
    }

    void previewContentInternal(String contentId) {
        if (display == null) {
            L.w("[ModuleContent] previewContentInternal, no content display is registered, ignoring the call");
            return;
        }

        if (internalConfig.isTemporaryIdEnabled()) {
            L.w("[ModuleContent] previewContentInternal, content can't be fetched while in temporary device ID mode");
            return;
        }

        int currentGeneration;
        synchronized (contentLock) {
            if (contentShown || fetching) {
                L.d("[ModuleContent] previewContentInternal, another content block is already being fetched or shown, ignoring the call");
                return;
            }
            fetching = true;
            currentGeneration = generation;
        }

        L.i("[ModuleContent] previewContentInternal, previewing content:[" + contentId + "]");
        fetchContents(contentId, currentGeneration, null);
    }

    void recordContentEventsInternal(String eventsJson) {
        if (Utils.isEmptyOrNull(eventsJson)) {
            L.d("[ModuleContent] recordContentEventsInternal, no events to record");
            return;
        }

        SDKCore core = SDKCore.instance;
        ModuleEvents eventsModule = core == null ? null : core.runningModule(CoreFeature.Events, ModuleEvents.class);
        if (eventsModule == null) {
            L.w("[ModuleContent] recordContentEventsInternal, events are not available, content events are dropped");
            return;
        }
        ModuleEvents.Events events = eventsModule.eventsInterface;

        boolean recorded = false;
        try {
            JSONArray array = new JSONArray(eventsJson);
            for (int i = 0; i < array.length(); i++) {
                JSONObject event = array.optJSONObject(i);
                if (event == null) {
                    continue;
                }

                String key = event.optString("key", "");
                if (Utils.isEmptyOrNull(key)) {
                    L.w("[ModuleContent] recordContentEventsInternal, an event without a key was received, dropping it");
                    continue;
                }

                JSONObject segmentation = event.optJSONObject("sg");
                if (segmentation == null) {
                    segmentation = event.optJSONObject("segmentation");
                }

                events.recordEvent(key, toSegmentation(segmentation));
                recorded = true;
            }
        } catch (Throwable t) {
            L.e("[ModuleContent] recordContentEventsInternal, failed to record the content events, [" + t + "]");
        }

        if (recorded) {
            // The server has to see these before it can decide what to show next.
            flushEventQueue();
        }
    }

    private Map<String, Object> toSegmentation(JSONObject segmentation) {
        if (segmentation == null) {
            return Collections.emptyMap();
        }

        Map<String, Object> result = new HashMap<>();
        Iterator<String> keys = segmentation.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            result.put(key, segmentation.opt(key));
        }
        return result;
    }

    private void flushEventQueue() {
        if (SDKCore.instance == null) {
            return;
        }
        ModuleEvents module = SDKCore.instance.module(ModuleEvents.class);
        if (module == null) {
            L.d("[ModuleContent] flushEventQueue, events module is not available, nothing to flush");
            return;
        }
        module.checkEventQueueToSend(true);
    }

    /**
     * One poll of the content zone. Package visible so tests can drive the zone without waiting on
     * a real timer.
     */
    void onZoneTimerTick() {
        try {
            zoneTimerTick();
        } catch (Throwable t) {
            // An exception escaping a scheduled task cancels the schedule, which would kill the zone
            // for good. The SDK can be torn down under this timer at any moment, so swallow and live.
            L.e("[ModuleContent] onZoneTimerTick, the content zone poll failed, [" + t + "]");
        }
    }

    private void zoneTimerTick() {
        if (SDKCore.instance == null || !Countly.isInitialized()) {
            // The SDK was stopped while this timer was still armed; nothing left to fetch.
            return;
        }

        if (!SDKCore.instance.hasConsentForFeature(CoreFeature.Content)) {
            L.d("[ModuleContent] onZoneTimerTick, content consent was removed, leaving the content zone");
            contentZoneWanted.set(false);
            exitContentZoneInternal(false);
            return;
        }

        int currentGeneration;
        synchronized (contentLock) {
            if (waitForDelay > 0) {
                waitForDelay--;
                L.v("[ModuleContent] onZoneTimerTick, waiting for [" + waitForDelay + "] more ticks before fetching again");
                return;
            }

            if (!shouldFetch || contentShown || fetching) {
                return;
            }

            fetching = true;
            currentGeneration = generation;
        }

        fetchContents(null, currentGeneration, null);
    }

    /**
     * Asks the server for content and shows what it answers with.
     *
     * @param contentId the content to preview, {@code null} for the content the server picks
     * @param fetchGeneration the zone generation this request belongs to
     * @param onNothingShown called when the request shows nothing: no content, a failed request, or
     *     an answer without content to show; {@code null} for nothing to call
     */
    private void fetchContents(String contentId, int fetchGeneration, @Nullable Runnable onNothingShown) {
        try {
            ContentDisplay currentDisplay;
            synchronized (contentLock) {
                currentDisplay = display;
            }

            if (currentDisplay == null) {
                L.w("[ModuleContent] fetchContents, the content display went away, aborting the fetch");
                clearFetching(fetchGeneration);
                runIfSet(onNothingShown);
                return;
            }

            ContentScreen screen = currentDisplay.getScreen();
            String requestData = ModuleRequests.prepareRequiredParams(internalConfig)
                .add(ContentRequestBuilder.build(screen, contentId, L))
                .toString();

            Transport transport = SDKCore.instance.networking.getTransport();
            final boolean networkingIsEnabled = internalConfig.getNetworkingEnabled();

            L.d("[ModuleContent] fetchContents, requesting content with:[" + requestData + "]");

            internalConfig.immediateRequestGenerator.createImmediateRequestMaker()
                .doWork(requestData, "/o/sdk/content?", transport, false, networkingIsEnabled,
                    response -> onContentFetched(response, fetchGeneration, onNothingShown), L);
        } catch (Throwable t) {
            L.e("[ModuleContent] fetchContents, failed to request content, [" + t + "]");
            clearFetching(fetchGeneration);
            runIfSet(onNothingShown);
        }
    }

    /**
     * @param task the task to run, {@code null} for none
     */
    private static void runIfSet(@Nullable Runnable task) {
        if (task != null) {
            task.run();
        }
    }

    private void onContentFetched(JSONObject response, int fetchGeneration, @Nullable Runnable onNothingShown) {
        boolean shown = false;
        try {
            ContentData content = ContentParser.parse(response, L);
            if (content == null) {
                L.d("[ModuleContent] onContentFetched, nothing to show");
                return;
            }

            ContentDisplay currentDisplay;
            synchronized (contentLock) {
                if (fetchGeneration != generation) {
                    L.d("[ModuleContent] onContentFetched, the content zone changed while this fetch was in flight, discarding the content");
                    return;
                }
                currentDisplay = display;
            }

            if (currentDisplay == null) {
                L.w("[ModuleContent] onContentFetched, the content display went away, discarding the content");
                return;
            }

            L.i("[ModuleContent] onContentFetched, showing content:[" + content + "]");
            currentDisplay.present(content, this::onContentClosed);

            // Committed only once the display accepted the content: a display that throws must not
            // leave the zone believing something is on screen, which would block every later fetch.
            synchronized (contentLock) {
                if (fetchGeneration == generation) {
                    contentShown = true;
                    shouldFetch = false;
                }
            }
            shown = true;
        } catch (Throwable t) {
            L.e("[ModuleContent] onContentFetched, the content display failed to show the content, [" + t + "]");
        } finally {
            clearFetching(fetchGeneration);
            if (!shown) {
                runIfSet(onNothingShown);
            }
        }
    }

    private void onContentClosed(Map<String, Object> contentData) {
        L.d("[ModuleContent] onContentClosed, content closed with:[" + contentData + "]");

        synchronized (contentLock) {
            contentShown = false;
            if (zoneActive) {
                shouldFetch = true;
                waitForDelay = POST_CLOSE_SKIPPED_TICKS;
            }
        }

        ContentCallback callback = globalContentCallback;
        if (callback == null) {
            return;
        }

        try {
            callback.onContentCallback(ContentStatus.CLOSED, contentData == null ? Collections.emptyMap() : contentData);
        } catch (Throwable t) {
            L.e("[ModuleContent] onContentClosed, the global content callback threw, [" + t + "]");
        }
    }

    /**
     * Releases the single fetch slot, but only for the generation that took it, so a stale fetch
     * completing late cannot release a newer one.
     *
     * @param fetchGeneration the generation the finished fetch was started in
     */
    private void clearFetching(int fetchGeneration) {
        synchronized (contentLock) {
            if (fetchGeneration == generation) {
                fetching = false;
            }
        }
    }

    /**
     * Retrieves and displays Countly content.
     *
     * @apiNote This is an EXPERIMENTAL feature, and it can have breaking changes
     */
    public class Content {

        /**
         * The interface a call goes to: this one while its module runs, the running module's when
         * this one was handed out before that started or kept from one that stopped, and
         * {@code null} while the feature does not run.
         *
         * @param call the call, for the log line
         * @return the interface, {@code null} for none
         */
        private Content target(String call) {
            ModuleContent running = runningInstance(call);
            return running == null ? null : running.contentInterface;
        }

        /**
         * Register the display that draws content blocks. Required before entering a content zone.
         * Pass {@code null} to unregister. While the SDK behavior settings ask for the content zone,
         * registering a display enters it. A display registered before the content consent is given
         * is used once it is.
         *
         * @param contentDisplay the display to draw content with
         * @apiNote This is an EXPERIMENTAL feature, and it can have breaking changes
         */
        public void setContentDisplay(@Nullable ContentDisplay contentDisplay) {
            synchronized (Countly.instance()) {
                SDKCore core = SDKCore.instance;
                if (core == null) {
                    return;
                }
                core.contentDisplay = contentDisplay;
                ModuleContent running = core.module(ModuleContent.class);
                if (running != null) {
                    running.setContentDisplayInternal(contentDisplay);
                }
            }
        }

        /**
         * Start asking the server for content to show. Ignored while already in a content zone.
         * Once this is called, the zone stays even when the SDK behavior settings turn the content
         * zone off.
         *
         * @apiNote This is an EXPERIMENTAL feature, and it can have breaking changes
         */
        public void enterContentZone() {
            Content target = target("[Content] enterContentZone");
            if (target != this) {
                if (target != null) {
                    target.enterContentZone();
                }
                return;
            }
            synchronized (Countly.instance()) {
                L.i("[Content] enterContentZone, entering the content zone");
                enterContentZoneInternal();
            }
        }

        /**
         * Stop asking the server for content. A content block that is already on screen stays
         * there, so the user can finish with it.
         *
         * @apiNote This is an EXPERIMENTAL feature, and it can have breaking changes
         */
        public void exitContentZone() {
            Content target = target("[Content] exitContentZone");
            if (target != this) {
                if (target != null) {
                    target.exitContentZone();
                }
                return;
            }
            synchronized (Countly.instance()) {
                L.i("[Content] exitContentZone, leaving the content zone");
                exitContentZoneByApp();
            }
        }

        /**
         * Re-enter the content zone right away, after flushing the event queue. Use it when a
         * trigger condition just changed. Ignored while a content block is on screen and while the
         * SDK behavior settings do not allow refreshing the content zone.
         *
         * @apiNote This is an EXPERIMENTAL feature, and it can have breaking changes
         */
        public void refreshContentZone() {
            Content target = target("[Content] refreshContentZone");
            if (target != this) {
                if (target != null) {
                    target.refreshContentZone();
                }
                return;
            }
            synchronized (Countly.instance()) {
                L.i("[Content] refreshContentZone, refreshing the content zone");
                refreshContentZoneInternal();
            }
        }

        /**
         * Fetch and show one specific content block, bypassing the server's targeting. Meant for
         * previewing content while building it.
         *
         * @param contentId the ID of the content block to show
         * @apiNote This is an EXPERIMENTAL feature, and it can have breaking changes
         */
        public void previewContent(@Nullable String contentId) {
            Content target = target("[Content] previewContent");
            if (target != this) {
                if (target != null) {
                    target.previewContent(contentId);
                }
                return;
            }
            synchronized (Countly.instance()) {
                L.i("[Content] previewContent, previewing content:[" + contentId + "]");
                if (Utils.isEmptyOrNull(contentId)) {
                    L.w("[Content] previewContent, content ID is null or empty, ignoring the call");
                    return;
                }
                previewContentInternal(contentId);
            }
        }

        /**
         * Record the events a content block asked for, and push the event queue to the server so it
         * can act on them straight away. Called by a {@link ContentDisplay} when the content sends
         * an {@code action=event} signal.
         *
         * @param eventsJson a JSON array of {@code {key, sg}} objects, as sent by the content
         * @apiNote This is an EXPERIMENTAL feature, and it can have breaking changes
         */
        public void recordContentEvents(@Nullable String eventsJson) {
            Content target = target("[Content] recordContentEvents");
            if (target != this) {
                if (target != null) {
                    target.recordContentEvents(eventsJson);
                }
                return;
            }
            synchronized (Countly.instance()) {
                L.d("[Content] recordContentEvents, recording the events of a content block");
                recordContentEventsInternal(eventsJson);
            }
        }
    }
}
