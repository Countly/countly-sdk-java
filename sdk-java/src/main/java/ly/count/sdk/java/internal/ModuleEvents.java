package ly.count.sdk.java.internal;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import ly.count.sdk.java.Countly;
import ly.count.sdk.java.Session;
import ly.count.sdk.java.View;

public class ModuleEvents extends ModuleBase {
    protected EventQueue eventQueue = null;
    final Map<String, EventImpl> timedEvents = new ConcurrentHashMap<>();
    protected Events eventsInterface = null;
    ViewIdProvider viewIdProvider = null;
    IdGenerator idGenerator = null;
    String previousEventId = null;

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
    public void initFinished(InternalConfig config) {
        super.initFinished(config);
        viewIdProvider = config.viewIdProvider;
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

    private synchronized void addEventsToRequestQ(String deviceId) {
        L.d("[ModuleEvents] addEventsToRequestQ");

        if (eventQueue.getEQ().isEmpty()) {
            L.d("[ModuleEvents] addEventsToRequestQ, eventQueueMemoryCache is empty, skipping");
            return;
        }

        Request request = new Request();
        if (deviceId != null) {
            request.params.add("device_id", deviceId);
        }
        request.params.arr("events").put(eventQueue.getEQ()).add();
        request.own(ModuleEvents.class);

        eventQueue.clear();
        ModuleRequests.pushAsync(internalConfig, request);
    }

    protected void recordEventInternal(String key, int count, Double sum, Double dur, Map<String, Object> segmentation, String eventIdOverride) {
        if (count <= 0) {
            L.w("[ModuleEvents] recordEventInternal, Count can't be less than 1, ignoring this event.");
            return;
        }

        if (key == null || key.isEmpty()) {
            L.w("[ModuleEvents] recordEventInternal, Key can't be null or empty, ignoring this event.");
            return;
        }

        L.d("[ModuleEvents] recordEventInternal, Recording event with key: [" + key + "] and provided event ID of:[" + eventIdOverride + "] and segmentation with:[" + (segmentation == null ? "null" : segmentation.size()) + "] keys");

        Utils.removeInvalidDataFromSegments(segmentation, L);

        String eventId, pvid = null, cvid = null;
        if (Utils.isEmptyOrNull(eventIdOverride)) {
            L.d("[ModuleEvents] recordEventInternal, Generating new event id because it was null or empty");
            eventId = idGenerator.generateId();
        } else {
            eventId = eventIdOverride;
        }

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
    }

    private void addEventToQueue(EventImpl event) {
        L.d("[ModuleEvents] addEventToQueue");
        eventQueue.addEvent(event);
        checkEventQueueToSend(false);
    }

    private void checkEventQueueToSend(boolean forceSend) {
        L.d("[ModuleEvents] queue size:[" + eventQueue.eqSize() + "] || forceSend: " + forceSend);
        if (forceSend || eventQueue.eqSize() >= internalConfig.getEventsBufferSize()) {
            addEventsToRequestQ(null);
        }
    }

    boolean startEventInternal(final String key) {
        if (key == null || key.isEmpty()) {
            L.e("[ModuleEvents] Can't start event with a null or empty key");
            return false;
        }
        if (timedEvents.containsKey(key)) {
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
            L.e("[ModuleEvents] Can't end event with a null or empty key");
            return false;
        }

        EventImpl event = timedEvents.remove(key);

        if (event == null) {
            L.w("endEventInternal, No timed event with the name [" + key + "] is started, nothing to end. Will ignore call.");
            return false;
        }

        if (count < 1) {
            L.e("endEventInternal, Countly event count should be greater than zero [" + count + "]. Changing value to 1");
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
            L.e("[ModuleEvents] Can't cancel event with a null or empty key");
            return false;
        }

        EventImpl event = timedEvents.remove(key);

        return event != null;
    }

    public class Events {

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
            L.i("[Events] recordEvent: key = " + key + ", count = " + count + ", sum = " + sum + ", segmentation = " + segmentation + ", dur = " + dur);
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
            L.i("[Events] startEvent: key = " + key);
            return startEventInternal(key);
        }

        /**
         * End timed event with a specified key
         *
         * @param key name of the custom event, required, must not be the empty string or null
         * @return true if event with this key has been previously started, false otherwise
         */
        public boolean endEvent(final String key) {
            L.i("[Events] endEvent: key = " + key);
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
            L.i("[Events] endEvent: key = " + key + ", segmentation = " + segmentation + ", count = " + count + ", sum = " + sum);
            return endEventInternal(key, segmentation, count, sum);
        }

        /**
         * Cancel timed event with a specified key
         *
         * @return true if event with this key has been previously started, false otherwise
         **/
        public boolean cancelEvent(final String key) {
            L.i("[Events] cancelEvent: key = " + key);
            return cancelEventInternal(key);
        }
    }
}
