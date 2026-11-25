package ly.count.sdk.java.internal;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import ly.count.sdk.java.Countly;
import ly.count.sdk.java.Session;
import ly.count.sdk.java.View;

public class ModuleEvents extends ModuleBase {
    /**
     * The keys of the events the SDK records itself, which the custom event settings of the SDK
     * behavior settings do not apply to. Any other key is a custom event, one starting with
     * {@code [CLY]_} too.
     */
    static final Set<String> INTERNAL_EVENT_KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        ModuleViews.KEY_VIEW_EVENT, FeedbackWidgetType.nps.eventKey, FeedbackWidgetType.survey.eventKey, FeedbackWidgetType.rating.eventKey)));

    protected EventQueue eventQueue = null;
    final Map<String, EventImpl> timedEvents = new ConcurrentHashMap<>();
    protected Events eventsInterface = null;
    IdGenerator idGenerator = null;
    String previousEventId = null;
    /**
     * The thread ending the timed events and the view of the previous device ID during a device ID
     * change without merge; its sends wait, so those events go out in one request under that ID.
     */
    private volatile Thread threadEndingEventsOfThePreviousDeviceId = null;

    @Override
    public void init(InternalConfig config) {
        super.init(config);
        L.d("[ModuleEvents] init: config = " + config);
        eventQueue = new EventQueue(L, config.getEventsBufferSize());
        eventQueue.restoreFromDisk();
        eventsInterface = new Events();

        idGenerator = config.eventIdGenerator;
    }

    @Override
    protected void onTimer() {
        addEventsToRequestQ(null);
    }

    @Override
    public Boolean onRequest(Request request) {
        return true;
    }

    @Override
    public void deviceIdChanged(String oldDeviceId, boolean withMerge) {
        super.deviceIdChanged(oldDeviceId, withMerge);
        L.d("[ModuleEvents] deviceIdChanged: oldDeviceId = " + oldDeviceId + ", withMerge = " + withMerge);
        if (!withMerge) {
            //the new device ID is already set, so the events ended here are sent below under the old one, never on their own
            threadEndingEventsOfThePreviousDeviceId = Thread.currentThread();
            try {
                for (Map.Entry<String, EventImpl> timedEventEntry : timedEvents.entrySet()) {
                    L.d("[ModuleEvents] deviceIdChanged, Ending timed event: [" + timedEventEntry.getKey() + "]");
                    endEventInternal(timedEventEntry.getKey(), timedEventEntry.getValue().segmentation, timedEventEntry.getValue().count, timedEventEntry.getValue().sum);
                }

                // this part is to end and record the current view if exists
                Session session = Countly.session();
                if (session != null && session.isActive()) {
                    View currentView = ((SessionImpl) session).currentView;
                    if (currentView != null) {
                        currentView.stop(true);
                    } else {
                        Storage.pushAsync(internalConfig, (SessionImpl) Countly.session());
                    }
                }
            } finally {
                threadEndingEventsOfThePreviousDeviceId = null;
            }

            addEventsToRequestQ(oldDeviceId);
        }
    }

    @Override
    public void stop(InternalConfig config, final boolean clear) {
        super.stop(config, clear);
        if (clear) {
            eventQueue.clear();
            timedEvents.clear();
        }
    }

    /**
     * Moves every queued event into one request.
     *
     * @param deviceId the device ID the events belong to, {@code null} for the current one
     */
    private synchronized void addEventsToRequestQ(@Nullable String deviceId) {
        L.d("[ModuleEvents] addEventsToRequestQ");

        if (eventQueue.getEQ().isEmpty()) {
            L.d("[ModuleEvents] addEventsToRequestQ, eventQueueMemoryCache is empty, skipping");
            return;
        }

        Request request = eventsRequest(deviceId);
        eventQueue.clear();
        ModuleRequests.pushAsync(internalConfig, request);
    }

    /**
     * Moves every queued event into one request, for a content trigger round, which sends the
     * request queue once this request is stored.
     *
     * @param onStored called once the request is stored, or right away when no event is queued
     */
    synchronized void moveEventsToRequestQueue(@Nonnull Runnable onStored) {
        if (eventQueue.getEQ().isEmpty()) {
            onStored.run();
            return;
        }

        Request request = eventsRequest(null);
        eventQueue.clear();
        ModuleRequests.pushAsync(internalConfig, request, false, stored -> onStored.run());
    }

    /**
     * @param deviceId the device ID the events belong to, {@code null} for the current one
     * @return a request with every queued event
     */
    @Nonnull
    private Request eventsRequest(@Nullable String deviceId) {
        Request request = new Request();
        if (deviceId != null) {
            request.params.add("device_id", deviceId);
        }
        request.params.arr("events").put(eventQueue.getEQ()).add();
        request.own(ModuleEvents.class);
        return request;
    }

    /**
     * Drops the queued events, unsent; timed events are kept. Takes the lock of moving the queued
     * events into a request, so a request they were already moved into is queued first.
     */
    synchronized void clearEventQueue() {
        eventQueue.clear();
    }

    protected void recordEventInternal(String key, int count, Double sum, Double dur, Map<String, Object> segmentation, String eventIdOverride) {
        ConfigurationProvider configProvider = internalConfig.getConfigurationProvider();
        if (!configProvider.getTrackingEnabled()) {
            L.w("[ModuleEvents] recordEventInternal, tracking is disabled by the SDK behavior settings, event [" + key + "] will not be recorded");
            L.recordLogCode("w505", HealthCheckCounter.AREA_EVENTS);
            return;
        }

        if (count <= 0) {
            L.w("[ModuleEvents] recordEventInternal, Count can't be less than 1, ignoring this event.");
            L.recordLogCode("w750", HealthCheckCounter.AREA_EVENTS);
            return;
        }

        if (key == null || key.isEmpty()) {
            L.w("[ModuleEvents] recordEventInternal, Key can't be null or empty, ignoring this event.");
            L.recordLogCode("w701", HealthCheckCounter.AREA_EVENTS);
            return;
        }

        boolean isCustomEvent = !INTERNAL_EVENT_KEYS.contains(key);
        if (isCustomEvent) {
            if (!configProvider.getCustomEventTrackingEnabled()) {
                L.d("[ModuleEvents] recordEventInternal, custom event tracking is disabled by the SDK behavior settings, event [" + key + "] will not be recorded");
                return;
            }

            if (!UtilsListingFilters.applyEventFilter(key, configProvider)) {
                L.w("[ModuleEvents] recordEventInternal, event [" + key + "] is filtered out by the event filter of the SDK behavior settings, it will not be recorded");
                return;
            }

            segmentation = filteredCustomEventSegmentation(key, segmentation, configProvider);
        }

        String keyAsGiven = key;

        L.d("[ModuleEvents] recordEventInternal, Recording event with key: [" + key + "] and provided event ID of:[" + eventIdOverride + "] and segmentation with:[" + (segmentation == null ? "null" : segmentation.size()) + "] keys");

        Utils.removeInvalidDataFromSegments(segmentation, L);

        if (isCustomEvent) {
            key = UtilsInternalLimits.truncateKey(key, configProvider.getMaxKeyLength(), L, "[ModuleEvents] recordEventInternal");
            segmentation = UtilsInternalLimits.applySegmentationLimits(segmentation, configProvider, L, "[ModuleEvents] recordEventInternal");
        }

        ModuleUserProfile.saveIfSentAutomatically(internalConfig);

        String eventId, pvid = null, cvid = null;
        if (Utils.isEmptyOrNull(eventIdOverride)) {
            L.d("[ModuleEvents] recordEventInternal, Generating new event id because it was null or empty");
            eventId = idGenerator.generateId();
        } else {
            eventId = eventIdOverride;
        }

        ViewIdProvider viewIdProvider = internalConfig.viewIdProvider;
        if (key.equals(ModuleViews.KEY_VIEW_EVENT)) {
            pvid = viewIdProvider.getPreviousViewId();
        } else {
            cvid = viewIdProvider.getCurrentViewId();
        }

        String previousEventIdToSend = this.previousEventId;
        if (key.equals(FeedbackWidgetType.nps.eventKey) || key.equals(FeedbackWidgetType.survey.eventKey) || key.equals(ModuleViews.KEY_VIEW_EVENT) || key.equals(FeedbackWidgetType.rating.eventKey)) {
            previousEventIdToSend = null;
        } else {
            this.previousEventId = eventId;
        }

        addEventToQueue(new EventImpl(key, count, sum, dur, segmentation, L, eventId, pvid, cvid, previousEventIdToSend));
        notifyContentTrigger(keyAsGiven, eventId, segmentation);
    }

    /**
     * Tells the content module about a recorded event, which can be a content trigger: a view event
     * by its name, and any other event by its key, both as the app passed them, before the limits
     * cut them.
     *
     * @param keyAsGiven the event key as the app passed it
     * @param eventId the ID of the event, which is the view ID for a view event
     * @param segmentation the segmentation of the event, {@code null} for none
     */
    private void notifyContentTrigger(@Nonnull String keyAsGiven, @Nonnull String eventId, @Nullable Map<String, Object> segmentation) {
        SDKCore core = SDKCore.instance;
        ModuleContent content = core == null ? null : core.module(ModuleContent.class);
        if (content == null) {
            return;
        }

        if (!ModuleViews.KEY_VIEW_EVENT.equals(keyAsGiven)) {
            content.onContentTrigger(ContentTriggers.Trigger.event(keyAsGiven, !keyAsGiven.startsWith("[CLY]_")));
            return;
        }

        ModuleViews views = core.module(ModuleViews.class);
        String viewName = views == null ? null : views.viewNameFor(eventId);
        if (viewName == null && segmentation != null && segmentation.get(ModuleViews.KEY_NAME) instanceof String) {
            viewName = (String) segmentation.get(ModuleViews.KEY_NAME);
        }
        if (viewName != null) {
            content.onContentTrigger(ContentTriggers.Trigger.view(viewName, segmentation == null || !segmentation.containsKey(ModuleViews.KEY_VISIT)));
        }
    }

    /**
     * Applies the segmentation filter and then the segmentation filter of this event to the
     * segmentation of a custom event. The filters work on a copy, as the map belongs to the caller.
     *
     * @param key the key of the custom event
     * @param segmentation the segmentation the caller passed, {@code null} for none
     * @param configProvider the settings in effect
     * @return the filtered copy, {@code null} when there was no segmentation
     */
    @Nullable
    private Map<String, Object> filteredCustomEventSegmentation(@Nonnull String key, @Nullable Map<String, Object> segmentation, @Nonnull ConfigurationProvider configProvider) {
        if (segmentation == null) {
            return null;
        }

        Map<String, Object> filtered = new HashMap<>(segmentation);
        UtilsListingFilters.applySegmentationFilter(filtered, configProvider, L);
        UtilsListingFilters.applyEventSegmentationFilter(key, filtered, configProvider, L);
        return filtered;
    }

    /**
     * Adds an event to the event queue and sends the queue when it is full.
     *
     * @param event the event
     */
    private void addEventToQueue(@Nonnull EventImpl event) {
        L.d("[ModuleEvents] addEventToQueue");
        eventQueue.addEvent(event);
        checkEventQueueToSend(false);
    }

    /**
     * Sends the queued events when asked to or when the queue reached its threshold.
     *
     * @param forceSend whether to send whatever is queued
     */
    void checkEventQueueToSend(boolean forceSend) {
        if (Thread.currentThread() == threadEndingEventsOfThePreviousDeviceId) {
            L.d("[ModuleEvents] checkEventQueueToSend, the events of the previous device ID are being ended, they are sent together under that ID");
            return;
        }
        L.d("[ModuleEvents] queue size:[" + eventQueue.eqSize() + "] || forceSend: " + forceSend);
        if (forceSend || eventQueue.eqSize() >= internalConfig.getConfigurationProvider().getEventQueueSizeThreshold()) {
            addEventsToRequestQ(null);
        }
    }

    boolean startEventInternal(final String key) {
        if (key == null || key.isEmpty()) {
            L.w("[ModuleEvents] Can't start event with a null or empty key");
            L.recordLogCode("w700", HealthCheckCounter.AREA_EVENTS);
            return false;
        }
        if (timedEvents.containsKey(key)) {
            L.w("[ModuleEvents] startEventInternal, a timed event with the key [" + key + "] is already running, the call is ignored");
            L.recordLogCode("w722", HealthCheckCounter.AREA_EVENTS);
            return false;
        }

        L.d("[ModuleEvents] Starting event: [" + key + "]");
        timedEvents.put(key, new EventImpl(event -> {
            EventImpl eventImpl = timedEvents.remove(key);
            L.d("[ModuleEvents] Ending event: [" + key + "]");
            if (eventImpl == null) {
                L.w("startEventInternal, eventRecorder, No timed event with the name [" + key + "] is started, nothing to end. Will ignore call.");
                return;
            }
            recordEventInternal(eventImpl.key, eventImpl.count, eventImpl.sum, eventImpl.duration, eventImpl.segmentation, eventImpl.id);
        }, key, L));

        return true;
    }

    boolean endEventInternal(final String key, final Map<String, Object> segmentation, int count, final Double sum) {
        L.d("[ModuleEvents] Ending event: [" + key + "]");

        if (key == null || key.isEmpty()) {
            L.w("[ModuleEvents] Can't end event with a null or empty key");
            L.recordLogCode("w700", HealthCheckCounter.AREA_EVENTS);
            return false;
        }

        EventImpl event = timedEvents.remove(key);

        if (event == null) {
            L.w("[ModuleEvents] endEventInternal, No timed event with the name [" + key + "] is started, nothing to end. Will ignore call.");
            L.recordLogCode("w720", HealthCheckCounter.AREA_EVENTS);
            return false;
        }

        if (count < 1) {
            L.w("[ModuleEvents] endEventInternal, Countly event count should be greater than zero [" + count + "]. Changing value to 1");
            L.recordLogCode("w750", HealthCheckCounter.AREA_EVENTS);
            count = 1;
        }

        L.d("[ModuleEvents] Ending event: [" + key + "]");

        long currentTimestamp = TimeUtils.timestampMs();
        double duration = (currentTimestamp - event.timestamp) / 1000.0;

        recordEventInternal(key, count, sum, duration, segmentation, null);
        return true;
    }

    boolean cancelEventInternal(final String key) {
        if (key == null || key.isEmpty()) {
            L.w("[ModuleEvents] Can't cancel event with a null or empty key");
            L.recordLogCode("w700", HealthCheckCounter.AREA_EVENTS);
            return false;
        }

        EventImpl event = timedEvents.remove(key);

        if (event == null) {
            L.w("[ModuleEvents] cancelEventInternal, no timed event with the key [" + key + "] is running, the call is ignored");
            L.recordLogCode("w721", HealthCheckCounter.AREA_EVENTS);
            return false;
        }

        return true;
    }

    public class Events {

        /**
         * The interface a call goes to: this one while its module runs, the running module's when
         * this one was handed out before that started or kept from one that stopped, and
         * {@code null} while the feature does not run.
         *
         * @param call the call, for the log line
         * @return the interface, {@code null} for none
         */
        private Events target(String call) {
            ModuleEvents running = runningInstance(call);
            return running == null ? null : running.eventsInterface;
        }

        /**
         * Record an event.
         *
         * @param key key for this event, cannot be null or empty
         * @param count how many of these events have occurred, default value is "1", must be greater than 0
         * @param sum set sum parameter of the event, can be null
         * @param dur set duration of event, can be null
         * @param segmentation additional segmentation data that you want to set, leave null if you don't want to add anything
         */
        public void recordEvent(String key, Map<String, Object> segmentation, int count, Double sum, Double dur) {
            Events target = target("[Events] recordEvent");
            if (target != this) {
                if (target != null) {
                    target.recordEvent(key, segmentation, count, sum, dur);
                }
                return;
            }
            L.i("[Events] recordEvent: key = " + key + ", count = " + count + ", sum = " + sum + ", segmentation = " + segmentation + ", dur = " + dur);
            L.recordUsage(HealthCheckCounter.AREA_EVENTS, "record");
            recordEventInternal(key, count, sum, dur, segmentation, null);
        }

        /**
         * Record an event with "duration" null by default
         *
         * @param key key for this event, cannot be null or empty
         * @param count how many of these events have occurred, default value is "1", must be greater than 0
         * @param sum set sum parameter of the event, can be null
         * @param segmentation additional segmentation data that you want to set, leave null if you don't want to add anything
         */
        public void recordEvent(String key, Map<String, Object> segmentation, int count, Double sum) {
            recordEvent(key, segmentation, count, sum, null);
        }

        /**
         * Record an event with "segmentation","key" and "count" value only
         * "duration" is null by default
         *
         * @param key key for this event, cannot be null or empty
         * @param count how many of these events have occurred, default value is "1", must be greater than 0
         * @param segmentation additional segmentation data that you want to set, leave null if you don't want to add anything
         */
        public void recordEvent(String key, Map<String, Object> segmentation, int count) {
            recordEvent(key, segmentation, count, null);
        }

        /**
         * Record an event with "segmentation" and "key" value only
         * "sum" and "duration" is null by default
         *
         * @param key key for this event, cannot be null or empty
         * @param segmentation additional segmentation data that you want to set, leave null if you don't want to add anything
         */
        public void recordEvent(String key, Map<String, Object> segmentation) {
            recordEvent(key, segmentation, 1, null);
        }

        /**
         * Record an event with "key" only
         * "sum" and "duration" is null by default
         * "count" is 1 by default
         *
         * @param key key for this event, cannot be null or empty
         */
        public void recordEvent(String key) {
            recordEvent(key, null, 1, null);
        }

        /**
         * Record an event with "key" and "count" only
         * "sum" and "duration" is null by default
         *
         * @param key key for this event, cannot be null or empty
         * @param count how many of these events have occurred, default value is "1", must be greater than 0
         */
        public void recordEvent(String key, int count) {
            recordEvent(key, null, count, null);
        }

        /**
         * Record an event with "key", "sum" and "count" only
         * "duration" is null by default
         *
         * @param key key for this event, cannot be null or empty
         * @param count how many of these events have occurred, default value is "1", must be greater than 0
         * @param sum set sum parameter of the event, can be null
         */
        public void recordEvent(String key, int count, Double sum) {
            recordEvent(key, null, count, sum);
        }

        /**
         * Start timed event with a specified key
         *
         * @param key name of the custom event, required, must not be the empty string or null
         * @return true if no event with this key existed before and event is started, false otherwise
         */
        public boolean startEvent(final String key) {
            Events target = target("[Events] startEvent");
            if (target != this) {
                return target != null && target.startEvent(key);
            }
            L.i("[Events] startEvent: key = " + key);
            L.recordUsage(HealthCheckCounter.AREA_EVENTS, "start");
            return startEventInternal(key);
        }

        /**
         * End timed event with a specified key
         *
         * @param key name of the custom event, required, must not be the empty string or null
         * @return true if event with this key has been previously started, false otherwise
         */
        public boolean endEvent(final String key) {
            Events target = target("[Events] endEvent");
            if (target != this) {
                return target != null && target.endEvent(key);
            }
            L.i("[Events] endEvent: key = " + key);
            L.recordUsage(HealthCheckCounter.AREA_EVENTS, "end");
            return endEventInternal(key, null, 1, null);
        }

        /**
         * End timed event with a specified key
         *
         * @param key name of the custom event, required, must not be the empty string
         * @param segmentation segmentation dictionary to associate with the event, can be null
         * @param count count to associate with the event, should be more than zero, default value is 1
         * @param sum sum to associate with the event, can be null
         * @return true if event with this key has been previously started, false otherwise
         * @throws IllegalStateException if Countly SDK has not been initialized
         * @throws IllegalArgumentException if key is null or empty, count is less than 1, or if segmentation contains null or empty keys or values
         */
        public boolean endEvent(final String key, final Map<String, Object> segmentation, final int count, final Double sum) {
            Events target = target("[Events] endEvent");
            if (target != this) {
                return target != null && target.endEvent(key, segmentation, count, sum);
            }
            L.i("[Events] endEvent: key = " + key + ", segmentation = " + segmentation + ", count = " + count + ", sum = " + sum);
            L.recordUsage(HealthCheckCounter.AREA_EVENTS, "end");
            return endEventInternal(key, segmentation, count, sum);
        }

        /**
         * Cancel timed event with a specified key
         *
         * @return true if event with this key has been previously started, false otherwise
         **/
        public boolean cancelEvent(final String key) {
            Events target = target("[Events] cancelEvent");
            if (target != this) {
                return target != null && target.cancelEvent(key);
            }
            L.i("[Events] cancelEvent: key = " + key);
            L.recordUsage(HealthCheckCounter.AREA_EVENTS, "cancel");
            return cancelEventInternal(key);
        }
    }
}
