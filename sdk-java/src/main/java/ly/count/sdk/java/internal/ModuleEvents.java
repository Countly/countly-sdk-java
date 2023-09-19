package ly.count.sdk.java.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import ly.count.sdk.java.Countly;
import org.json.JSONArray;

public class ModuleEvents extends ModuleBase {
    protected InternalConfig internalConfig = null;
    protected CtxCore ctx = null;

    //disabled is set when a empty module is created
    //in instances when the rating feature was not enabled
    //when a module is disabled, developer facing functions do nothing
    protected boolean disabledModule = false;
    protected final Queue<EventImpl> eventQueues = new ArrayDeque<>();

    private ScheduledExecutorService executor = null;

    protected Events eventsInterface = new Events();

    @Override
    public void init(InternalConfig config, Log logger) {
        super.init(config, logger);
        internalConfig = config;
        L.d("[ModuleEvents] init: config = " + config);
    }

    @Override
    public void onContextAcquired(CtxCore ctx) {
        this.ctx = ctx;
        L.d("[ModuleEvents] onContextAcquired: " + ctx.toString());

        if (ctx.getConfig().getSendUpdateEachSeconds() > 0 && executor == null) {
            executor = Executors.newScheduledThreadPool(1);
            executor.scheduleWithFixedDelay(new Runnable() {
                @Override
                public void run() {
                    addEventsToRequestQ();
                }
            }, ctx.getConfig().getSendUpdateEachSeconds(), ctx.getConfig().getSendUpdateEachSeconds(), TimeUnit.SECONDS);
        }
    }

    private synchronized void addEventsToRequestQ() {
        L.d("[ModuleEvents] addEventsToRequestQ");
        JSONArray events = new JSONArray();

        if (eventQueues.isEmpty()) {
            return;
        }

        for (EventImpl event : eventQueues) {
            events.put(event.toJSON(L));
        }

        Request request = new Request();
        request.params.add("device_id", Countly.instance().getDeviceId());
        request.params.add("events", events);
        addTimeInfoIntoRequest(request, System.currentTimeMillis());
        request.own(ModuleEvents.class);
        addRequestToRequestQ(request);

        eventQueues.clear();
    }

    private void addTimeInfoIntoRequest(Request request, Long timestamp) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timestamp);

        final int hour = calendar.get(Calendar.HOUR_OF_DAY);
        final int dow = calendar.get(Calendar.DAY_OF_WEEK) - 1;

        request.params.add("dow", dow);
        request.params.add("hour", hour);
        request.params.add("timestamp", timestamp);
        request.params.add("tz", Device.dev.getTimezoneOffset());
    }

    private void addRequestToRequestQ(Request request) {
        synchronized (SDKCore.instance.lockBRQStorage) {
            L.d("[ModuleEvents] addRequestToRequestQ");
            if (internalConfig.getRequestQueueMaxSize() == SDKCore.instance.requestQueueMemory.size()) {
                L.d("[ModuleEvents] addRequestToRequestQ: In Memory request queue is full, dropping oldest request: " + request.params.toString());
                SDKCore.instance.requestQueueMemory.remove();
            }

            SDKCore.instance.requestQueueMemory.add(request);
            SDKCore.instance.networking.check(ctx);
        }
    }

    protected Map<String, Object> removeInvalidDataFromSegments(Map<String, Object> segments) {

        if (segments == null || segments.isEmpty()) {
            return segments;
        }

        int i = 0;
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, Object> item : segments.entrySet()) {
            Object type = item.getValue();

            boolean isValidDataType = (type instanceof Boolean
                || type instanceof Integer
                || type instanceof Long
                || type instanceof String
                || type instanceof Double
                || type instanceof Float
            );

            if (!isValidDataType) {
                toRemove.add(item.getKey());
                L.w("[ModuleEvents] RemoveSegmentInvalidDataTypes: In segmentation Data type '" + type + "' of item '" + item.getValue() + "' isn't valid.");
            }
        }

        for (String k : toRemove) {
            segments.remove(k);
        }

        return segments;
    }

    private void recordEventInternal(String key, int count, double sum, Map<String, Object> segmentation, double dur) {
        removeInvalidDataFromSegments(segmentation);
        if (count <= 0) {
            L.w("[ModuleEvents] recordEventInternal: Count can't be less than 1, ignoring this event.");
            return;
        }

        if (key == null || key.isEmpty()) {
            L.w("[ModuleEvents] recordEventInternal: Key can't be null or empty, ignoring this event.");
            return;
        }

        Map<String, String> mappedSegmentation = new HashMap<>();

        if (segmentation != null) {
            for (Map.Entry<String, Object> entry : segmentation.entrySet()) {
                mappedSegmentation.put(entry.getKey(), entry.getValue().toString());
            }
        }

        EventImpl event = new EventImpl(key, count, sum, dur, mappedSegmentation, L);
        eventQueues.add(event);

        if (eventQueues.size() >= internalConfig.getEventsBufferSize()) {
            addEventsToRequestQ();
        }
    }

    private boolean startEventInternal(String key) {
        return false;
    }

    private boolean endEventInternal(String key) {
        return false;
    }

    private boolean cancelEventInternal(String key) {
        return false;
    }

    public class Events {

        /**
         * Record an event.
         *
         * @param key key for this event, cannot be null or empty
         * @param count how many of these events have occurred, default value is "1", must be greater than 0
         * @param sum set sum parameter of the event default value is "0"
         * @param dur set duration of event, default value is "0"
         * @param segmentation additional segmentation data that you want to set, leave null if you don't want to add anything
         */
        public void recordEvent(String key, int count, double sum, Map<String, Object> segmentation, double dur) {
            if (!Countly.isInitialized()) {
                L.e("[Events] recordEvent: Countly.instance().init must be called before recordEvent");
                return;
            }

            recordEventInternal(key, count, sum, segmentation, dur);
        }

        /**
         * Record an event with "duration" 0
         *
         * @param key key for this event, cannot be null or empty
         * @param count how many of these events have occurred, default value is "1", must be greater than 0
         * @param sum set sum parameter of the event default value is "0"
         * @param segmentation additional segmentation data that you want to set, leave null if you don't want to add anything
         */
        public void recordEvent(String key, int count, double sum, Map<String, Object> segmentation) {
            recordEvent(key, count, sum, segmentation, 0.0);
        }

        /**
         * Record an event with "segmentation","key" and "count" value only
         * "duration" is zero by default
         *
         * @param key key for this event, cannot be null or empty
         * @param count how many of these events have occurred, default value is "1", must be greater than 0
         * @param segmentation additional segmentation data that you want to set, leave null if you don't want to add anything
         */
        public void recordEvent(String key, int count, Map<String, Object> segmentation) {
            recordEvent(key, count, 0.0, segmentation);
        }

        /**
         * Record an event with "segmentation" and "key" value only
         * "sum" and "duration" is zero by default
         *
         * @param key key for this event, cannot be null or empty
         * @param segmentation additional segmentation data that you want to set, leave null if you don't want to add anything
         */
        public void recordEvent(String key, Map<String, Object> segmentation) {
            recordEvent(key, 1, 0.0, segmentation);
        }

        /**
         * Record an event with "key" only
         * "sum" and "duration" is zero by default
         * "count" is 1 by default
         *
         * @param key key for this event, cannot be null or empty
         */
        public void recordEvent(String key) {
            recordEvent(key, 1, 0, null);
        }

        /**
         * Record an event with "key" and "count" only
         * "sum" and "duration" is zero by default
         *
         * @param key key for this event, cannot be null or empty
         * @param count how many of these events have occurred, default value is "1", must be greater than 0
         */
        public void recordEvent(String key, int count) {
            recordEvent(key, count, 0, null);
        }

        /**
         * Record an event with "key", "sum" and "count" only
         * "duration" is zero by default
         *
         * @param key key for this event, cannot be null or empty
         * @param count how many of these events have occurred, default value is "1", must be greater than 0
         * @param sum set sum parameter of the event default value is "0"
         */
        public void recordEvent(String key, double sum, int count) {
            recordEvent(key, count, sum, null);
        }

        /**
         * Start timed event with a specified key
         *
         * @param key name of the custom event, required, must not be the empty string or null
         * @return true if no event with this key existed before and event is started, false otherwise
         */
        public boolean startEvent(final String key) {
            return startEventInternal(key);
        }

        /**
         * End timed event with a specified key
         *
         * @param key name of the custom event, required, must not be the empty string or null
         * @return true if event with this key has been previously started, false otherwise
         */
        public boolean endEvent(final String key) {
            return endEventInternal(key);
        }

        /**
         * Cancel timed event with a specified key
         *
         * @return true if event with this key has been previously started, false otherwise
         **/
        public boolean cancelEvent(final String key) {
            return cancelEventInternal(key);
        }
    }
}
