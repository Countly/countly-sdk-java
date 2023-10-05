package ly.count.sdk.java.internal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import ly.count.sdk.java.Countly;

public class ModuleEvents extends ModuleBase {

    protected CtxCore ctx = null;
    protected EventQueue eventQueue = null;
    final Map<String, EventImpl> timedEvents = new HashMap<>();
    private ScheduledExecutorService executor = null;
    protected Events eventsInterface = null;

    @Override
    public void init(InternalConfig config, Log logger) {
        super.init(config, logger);
        L.d("[ModuleEvents] init: config = " + config);
        eventQueue = new EventQueue(L, config.getEventsBufferSize());
        eventQueue.restoreFromDisk();
        eventsInterface = new Events();
    }

    @Override
    public void onContextAcquired(@Nonnull CtxCore ctx) {
        this.ctx = ctx;
        L.d("[ModuleEvents] onContextAcquired: " + ctx);

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

    @Override
    public Boolean onRequest(Request request) {
        return true;
    }

    @Override
    public void stop(CtxCore ctx, boolean clear) {
        super.stop(ctx, clear);
        if (clear) {
            eventQueue.clear();
            timedEvents.clear();
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private synchronized void addEventsToRequestQ() {
        L.d("[ModuleEvents] addEventsToRequestQ");

        Request request = new Request();
        request.params.add("device_id", Countly.instance().getDeviceId());
        request.params.arr("events").put(eventQueue.eventQueueMemoryCache).add();
        request.own(ModuleEvents.class);

        eventQueue.clear();
        ModuleRequests.pushAsync(ctx, request);
    }

    protected void removeInvalidDataFromSegments(Map<String, Object> segments) {

        if (segments == null || segments.isEmpty()) {
            return;
        }

        List<String> toRemove = segments.entrySet().stream()
            .filter(entry -> !Utils.isValidDataType(entry.getValue()))
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());

        toRemove.forEach(key -> {
            L.w("[ModuleEvents] RemoveSegmentInvalidDataTypes: In segmentation Data type '" + segments.get(key) + "' of item '" + key + "' isn't valid.");
            segments.remove(key);
        });
    }

    protected void recordEventInternal(String key, int count, Double sum, Map<String, Object> segmentation, Double dur) {
        if (count <= 0) {
            L.w("[ModuleEvents] recordEventInternal: Count can't be less than 1, ignoring this event.");
            return;
        }

        if (key == null || key.isEmpty()) {
            L.w("[ModuleEvents] recordEventInternal: Key can't be null or empty, ignoring this event.");
            return;
        }

        removeInvalidDataFromSegments(segmentation);
        EventImpl event = new EventImpl(key, count, sum, dur, segmentation, L);
        addEventToQueue(event);
    }

    private void addEventToQueue(EventImpl event) {
        L.d("[ModuleEvents] addEventToQueue");
        eventQueue.addEvent(event);
        checkEventQueueToSend(false);
    }

    private void checkEventQueueToSend(boolean forceSend) {
        L.d("[ModuleEvents] queue size:[" + eventQueue.eqSize() + "] || forceSend: " + forceSend);
        if (forceSend || (eventQueue.eqSize() >= internalConfig.getEventsBufferSize())) {
            addEventsToRequestQ();
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
        timedEvents.put(key, new EventImpl(null, key, L));
        return true;
    }

    boolean endEventInternal(final String key, final Map<String, Object> segmentation, final int count, final Double sum) {
        L.d("[ModuleEvents] Ending event: [" + key + "]");

        if (key == null || key.isEmpty()) {
            L.e("[ModuleEvents] Can't end event with a null or empty key");
            return false;
        }

        EventImpl event = timedEvents.remove(key);

        if (event != null) {
            if (count < 1) {
                throw new IllegalArgumentException("Countly event count should be greater than zero");
            }
            L.d("[ModuleEvents] Ending event: [" + key + "]");

            long currentTimestamp = TimeUtils.uniqueTimestampMs();
            double duration = (currentTimestamp - event.timestamp) / 1000.0;

            recordEventInternal(key, count, sum, segmentation, duration);
            return true;
        } else {
            return false;
        }
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
        public void recordEvent(String key, int count, Double sum, Map<String, Object> segmentation, Double dur) {
            L.i("[Events] recordEvent: key = " + key + ", count = " + count + ", sum = " + sum + ", segmentation = " + segmentation + ", dur = " + dur);
            recordEventInternal(key, count, sum, segmentation, dur);
        }

        /**
         * Record an event with "duration" null by default
         *
         * @param key key for this event, cannot be null or empty
         * @param count how many of these events have occurred, default value is "1", must be greater than 0
         * @param sum set sum parameter of the event, can be null
         * @param segmentation additional segmentation data that you want to set, leave null if you don't want to add anything
         */
        public void recordEvent(String key, int count, Double sum, Map<String, Object> segmentation) {
            recordEvent(key, count, sum, segmentation, null);
        }

        /**
         * Record an event with "segmentation","key" and "count" value only
         * "duration" is null by default
         *
         * @param key key for this event, cannot be null or empty
         * @param count how many of these events have occurred, default value is "1", must be greater than 0
         * @param segmentation additional segmentation data that you want to set, leave null if you don't want to add anything
         */
        public void recordEvent(String key, int count, Map<String, Object> segmentation) {
            recordEvent(key, count, null, segmentation);
        }

        /**
         * Record an event with "segmentation" and "key" value only
         * "sum" and "duration" is null by default
         *
         * @param key key for this event, cannot be null or empty
         * @param segmentation additional segmentation data that you want to set, leave null if you don't want to add anything
         */
        public void recordEvent(String key, Map<String, Object> segmentation) {
            recordEvent(key, 1, null, segmentation);
        }

        /**
         * Record an event with "key" only
         * "sum" and "duration" is null by default
         * "count" is 1 by default
         *
         * @param key key for this event, cannot be null or empty
         */
        public void recordEvent(String key) {
            recordEvent(key, 1, null, null);
        }

        /**
         * Record an event with "key" and "count" only
         * "sum" and "duration" is null by default
         *
         * @param key key for this event, cannot be null or empty
         * @param count how many of these events have occurred, default value is "1", must be greater than 0
         */
        public void recordEvent(String key, int count) {
            recordEvent(key, count, null, null);
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
            recordEvent(key, count, sum, null);
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
