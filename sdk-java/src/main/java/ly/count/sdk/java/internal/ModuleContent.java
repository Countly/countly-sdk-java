package ly.count.sdk.java.internal;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
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
     * How long the first fetch of a zone waits, so a zone entered right after init does not race
     * the rest of the SDK coming up.
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
    private String[] categories = null;

    private int zoneTimerInterval = ConfigContent.DEFAULT_ZONE_TIMER_INTERVAL;
    private ContentCallback globalContentCallback = null;

    ModuleContent() {
    }

    @Override
    public void init(InternalConfig config) {
        super.init(config);
        L.v("[ModuleContent] Initializing");

        zoneTimerInterval = config.content.zoneTimerInterval;
        globalContentCallback = config.content.globalContentCallback;
        contentInterface = new Content();
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

    void setContentDisplayInternal(ContentDisplay contentDisplay) {
        L.d("[ModuleContent] setContentDisplayInternal, display set:[" + (contentDisplay != null) + "]");
        synchronized (contentLock) {
            display = contentDisplay;
        }
    }

    void enterContentZoneInternal(@Nullable String[] requestedCategories) {
        if (display == null) {
            L.w("[ModuleContent] enterContentZoneInternal, no content display is registered, ignoring the call");
            return;
        }

        if (internalConfig.isTemporaryIdEnabled()) {
            L.w("[ModuleContent] enterContentZoneInternal, content can't be fetched while in temporary device ID mode");
            return;
        }

        synchronized (contentLock) {
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
            categories = requestedCategories == null ? null : requestedCategories.clone();

            contentTimer = new CountlyTimer(L);
            contentTimer.startTimer(zoneTimerInterval, START_DELAY_MS, this::onZoneTimerTick);
        }

        L.i("[ModuleContent] enterContentZoneInternal, entered the content zone, fetch interval:[" + zoneTimerInterval + "] seconds");
    }

    void exitContentZoneInternal() {
        exitContentZoneInternal(true);
    }

    /**
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
            categories = null;

            timerToStop = contentTimer;
            contentTimer = null;
        }

        // Stopped outside the lock: stopping waits for a running tick, and that tick needs the lock.
        if (timerToStop != null) {
            timerToStop.stopTimer(awaitTimerTermination);
        }

        L.i("[ModuleContent] exitContentZoneInternal, left the content zone");
    }

    void refreshContentZoneInternal() {
        String[] previousCategories;
        synchronized (contentLock) {
            if (contentShown) {
                L.d("[ModuleContent] refreshContentZoneInternal, a content block is on screen, ignoring the call");
                return;
            }
            previousCategories = categories == null ? null : categories.clone();
        }

        // Push whatever is queued out first, so the trigger the developer just recorded has a
        // chance of being processed before the next fetch lands.
        flushEventQueue();

        exitContentZoneInternal();
        enterContentZoneInternal(previousCategories);
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
        fetchContents(null, contentId, currentGeneration);
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

        String[] currentCategories;
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
            currentCategories = categories == null ? null : categories.clone();
        }

        fetchContents(currentCategories, null, currentGeneration);
    }

    private void fetchContents(String[] fetchCategories, String contentId, int fetchGeneration) {
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
                .add(ContentRequestBuilder.build(screen, fetchCategories, contentId, L))
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
         * Pass {@code null} to unregister.
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
         *
         * @apiNote This is an EXPERIMENTAL feature, and it can have breaking changes
         */
        public void enterContentZone() {
            enterContentZone(null);
        }

        /**
         * Start asking the server for content to show, limited to the given categories. Ignored
         * while already in a content zone.
         *
         * @param categories the content categories to ask for, {@code null} or empty for all
         * @apiNote This is an EXPERIMENTAL feature, and it can have breaking changes
         */
        public void enterContentZone(@Nullable String[] categories) {
            synchronized (Countly.instance()) {
                L.i("[Content] enterContentZone, entering the content zone");
                enterContentZoneInternal(categories);
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
         * trigger condition just changed. Ignored while a content block is on screen.
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
