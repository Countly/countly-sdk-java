package ly.count.sdk.java.internal;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
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
    /**
     * Whether the active zone was entered because the {@code ecz} setting asked for it, so turning
     * that setting off leaves it. Guarded by {@link #contentLock}.
     */
    private boolean zoneEnteredBySettings = false;
    /**
     * The {@code ecz} value this module last acted on, so a settings change acts only when that
     * value changed. Guarded by {@link #contentLock}.
     */
    private boolean contentZoneEnabledApplied = false;

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
            contentZoneEnabledApplied = config.getConfigurationProvider().getContentZoneEnabled();
        }
    }

    /**
     * Enters the content zone when the {@code ecz} setting asks for it. While no display is
     * registered the zone is entered once one is.
     *
     * @param config configuration of the running SDK
     */
    @Override
    protected void initFinished(InternalConfig config) {
        super.initFinished(config);
        if (config.getConfigurationProvider().getContentZoneEnabled()) {
            enterContentZoneForSettings("initFinished");
        }
    }

    /**
     * Applies the content settings a server response changed. A changed {@code czi} restarts the
     * timer of an active zone. A changed {@code ecz} enters the zone when it turned on, and when it
     * turned off leaves the zone only if this setting entered it: a zone entered through
     * {@link Content#enterContentZone()} is left to the application.
     *
     * @param config configuration of the running SDK
     */
    @Override
    protected void onSdkConfigurationChanged(InternalConfig config) {
        ConfigurationProvider configProvider = config.getConfigurationProvider();
        boolean contentZoneEnabled = configProvider.getContentZoneEnabled();
        boolean contentZoneSettingChanged;
        synchronized (contentLock) {
            contentZoneSettingChanged = contentZoneEnabled != contentZoneEnabledApplied;
            contentZoneEnabledApplied = contentZoneEnabled;
        }

        restartZoneTimerIfIntervalChanged(configProvider.getContentZoneTimerInterval());

        if (!contentZoneSettingChanged) {
            return;
        }

        if (contentZoneEnabled) {
            enterContentZoneForSettings("onSdkConfigurationChanged");
        } else if (exitContentZoneInternal(true, true)) {
            L.i("[ModuleContent] onSdkConfigurationChanged, the SDK behavior settings turned the content zone off, left the zone they had entered");
        } else {
            L.d("[ModuleContent] onSdkConfigurationChanged, the SDK behavior settings turned the content zone off, no zone they entered is active");
        }
    }

    /**
     * Enters the content zone because the {@code ecz} setting asks for it, once a display is registered.
     *
     * @param caller the calling method, for the log
     */
    private void enterContentZoneForSettings(@Nonnull String caller) {
        boolean hasDisplay;
        synchronized (contentLock) {
            hasDisplay = display != null;
        }

        if (!hasDisplay) {
            L.d("[ModuleContent] " + caller + ", the SDK behavior settings ask for the content zone, it is entered once a content display is registered");
            return;
        }

        L.d("[ModuleContent] " + caller + ", entering the content zone as the SDK behavior settings ask");
        enterContentZoneInternal(true);
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

    @Override
    public void stop(InternalConfig config, boolean clear) {
        super.stop(config, clear);
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
        exitContentZoneInternal();
    }

    void setContentDisplayInternal(ContentDisplay contentDisplay) {
        L.d("[ModuleContent] setContentDisplayInternal, display set:[" + (contentDisplay != null) + "]");
        boolean zoneInactive;
        synchronized (contentLock) {
            display = contentDisplay;
            zoneInactive = !zoneActive;
        }

        if (contentDisplay != null && zoneInactive && internalConfig.getConfigurationProvider().getContentZoneEnabled()) {
            enterContentZoneForSettings("setContentDisplayInternal");
        }
    }

    void enterContentZoneInternal() {
        enterContentZoneInternal(false);
    }

    /**
     * Enters the content zone, fetching every {@code czi} seconds. Entering a zone that is already
     * active does nothing, except that a call made for the application keeps a zone the settings
     * entered active until the application leaves it.
     *
     * @param bySettings {@code true} when the {@code ecz} setting asks for the zone, {@code false}
     *     when the application does
     */
    private void enterContentZoneInternal(boolean bySettings) {
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
            if (zoneActive) {
                if (!bySettings && zoneEnteredBySettings) {
                    zoneEnteredBySettings = false;
                    L.d("[ModuleContent] enterContentZoneInternal, already in the content zone the SDK behavior settings entered, it now stays until it is left through exitContentZone");
                } else {
                    L.d("[ModuleContent] enterContentZoneInternal, already in a content zone, ignoring the call");
                }
                return;
            }

            zoneActive = true;
            shouldFetch = true;
            contentShown = false;
            fetching = false;
            waitForDelay = 0;
            zoneEnteredBySettings = bySettings;
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
     * @param awaitTimerTermination must be {@code false} when called from the zone timer's own
     *     tick, because a task cannot wait for itself to finish
     */
    private void exitContentZoneInternal(boolean awaitTimerTermination) {
        exitContentZoneInternal(awaitTimerTermination, false);
    }

    /**
     * Leaves the content zone.
     *
     * @param awaitTimerTermination must be {@code false} when called from the zone timer's own
     *     tick, because a task cannot wait for itself to finish
     * @param onlyIfEnteredBySettings {@code true} to leave only an active zone the {@code ecz}
     *     setting entered
     * @return whether the zone was left
     */
    private boolean exitContentZoneInternal(boolean awaitTimerTermination, boolean onlyIfEnteredBySettings) {
        CountlyTimer timerToStop;
        synchronized (contentLock) {
            if (onlyIfEnteredBySettings && !(zoneActive && zoneEnteredBySettings)) {
                return false;
            }

            zoneActive = false;
            shouldFetch = false;
            contentShown = false;
            fetching = false;
            waitForDelay = 0;
            zoneEnteredBySettings = false;
            generation++;

            timerToStop = contentTimer;
            contentTimer = null;
        }

        // Stopped outside the lock: stopping waits for a running tick, and that tick needs the lock.
        if (timerToStop != null) {
            timerToStop.stopTimer(awaitTimerTermination);
        }

        L.i("[ModuleContent] exitContentZoneInternal, left the content zone");
        return true;
    }

    /**
     * Flushes the event queue and enters the content zone again, keeping who entered it. Called by
     * the application and when the server accepted a journey trigger; ignored while the
     * {@code rcz} setting forbids refreshing and while a content block is on screen.
     */
    void refreshContentZoneInternal() {
        if (!internalConfig.getConfigurationProvider().getRefreshContentZoneEnabled()) {
            L.d("[ModuleContent] refreshContentZoneInternal, refreshing the content zone is disabled by the SDK behavior settings, ignoring the call");
            return;
        }

        boolean enteredBySettings;
        synchronized (contentLock) {
            if (contentShown) {
                L.d("[ModuleContent] refreshContentZoneInternal, a content block is on screen, ignoring the call");
                return;
            }
            enteredBySettings = zoneActive && zoneEnteredBySettings;
        }

        // Push whatever is queued out first, so the trigger the developer just recorded has a
        // chance of being processed before the next fetch lands.
        flushEventQueue();

        exitContentZoneInternal();
        enterContentZoneInternal(enteredBySettings);
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
        fetchContents(contentId, currentGeneration);
    }

    void recordContentEventsInternal(String eventsJson) {
        if (Utils.isEmptyOrNull(eventsJson)) {
            L.d("[ModuleContent] recordContentEventsInternal, no events to record");
            return;
        }

        ModuleEvents.Events events = SDKCore.instance == null ? null : SDKCore.instance.events();
        if (events == null) {
            L.w("[ModuleContent] recordContentEventsInternal, events are not available, content events are dropped");
            return;
        }

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

        fetchContents(null, currentGeneration);
    }

    private void fetchContents(String contentId, int fetchGeneration) {
        try {
            ContentDisplay currentDisplay;
            synchronized (contentLock) {
                currentDisplay = display;
            }

            if (currentDisplay == null) {
                L.w("[ModuleContent] fetchContents, the content display went away, aborting the fetch");
                clearFetching(fetchGeneration);
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
                    response -> onContentFetched(response, fetchGeneration), L);
        } catch (Throwable t) {
            L.e("[ModuleContent] fetchContents, failed to request content, [" + t + "]");
            clearFetching(fetchGeneration);
        }
    }

    private void onContentFetched(JSONObject response, int fetchGeneration) {
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
        } catch (Throwable t) {
            L.e("[ModuleContent] onContentFetched, the content display failed to show the content, [" + t + "]");
        } finally {
            clearFetching(fetchGeneration);
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
         * Register the display that draws content blocks. Required before entering a content zone.
         * Pass {@code null} to unregister. While the SDK behavior settings ask for the content zone,
         * registering a display enters it.
         *
         * @param contentDisplay the display to draw content with
         * @apiNote This is an EXPERIMENTAL feature, and it can have breaking changes
         */
        public void setContentDisplay(@Nullable ContentDisplay contentDisplay) {
            synchronized (Countly.instance()) {
                setContentDisplayInternal(contentDisplay);
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
            synchronized (Countly.instance()) {
                L.i("[Content] exitContentZone, leaving the content zone");
                exitContentZoneInternal();
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
            synchronized (Countly.instance()) {
                L.d("[Content] recordContentEvents, recording the events of a content block");
                recordContentEventsInternal(eventsJson);
            }
        }
    }
}
