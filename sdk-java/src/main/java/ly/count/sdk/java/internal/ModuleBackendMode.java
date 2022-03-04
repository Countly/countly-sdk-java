package ly.count.sdk.java.internal;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

public class ModuleBackendMode extends ModuleBase {

    protected static final Log.Module L = Log.module("BackendMode");
    protected InternalConfig internalConfig = null;
    protected CtxCore ctx = null;

    //disabled is set when a empty module is created
    //in instances when the rating feature was not enabled
    //when a module is disabled, developer facing functions do nothing
    protected boolean disabledModule = false;

    private Tasks tasks;
    private Transport transport;

    @Override
    public void init(InternalConfig config) {
        internalConfig = config;
        transport = new Transport();
        transport.init(ctx.getConfig());
        tasks = new Tasks("request-queue");
        L.d("[ModuleBackendMode][init]");
    }

    @Override
    public void onContextAcquired(CtxCore ctx) {
        this.ctx = ctx;
        L.d("[ModuleBackendMode][onContextAcquired]");
//        ratingWidgetTimeout = internalConfig.getRatingWidgetTimeout();
    }

    @Override
    public Integer getFeature() {
        return CoreFeature.BackendMode.getIndex();
    }

    @Override
    public Boolean onRequest(Request request) {
        return true;
    }

    @Override
    public void onRequestCompleted(Request request, String response, int responseCode) {

    }

    public void disableModule() {
        disabledModule = true;
    }

    private void processRequestQ () {

    }

    public class BackendMode {

        private int eventQSize = 0;
        private final Queue<Request> requestQ = new LinkedList<>();
        private final Map<String, JSONArray> eventQueues = new HashMap<>();

        public BackendMode() {
        }

        public String test(String txt) {
            L.d("[BackendMode][test]");
            if (disabledModule) {
                return null;
            }
            return "123" + txt;
        }

        public void recordView(String deviceID, String key, Map<String, String> segmentation, long timestamp) {
            recordEventInternal(deviceID, key, 1, -1, -1, segmentation, timestamp);
        }

        public void recordEvent(String deviceID, String key, int count, double sum, double dur, Map<String, String> segmentation, long timestamp) {
            recordEventInternal(deviceID, key, count, sum, dur, segmentation, timestamp);
        }

        public void sessionBegin(String deviceID, long timestamp) {

        }

        public void sessionUpdate(String deviceID, Double duration, long timestamp) {

        }

        public void sessionEnd(String deviceID, String duration, long timestamp) {

        }

        public void recordException(String deviceID, Throwable stacktrace, Map<String, String> segmentation, long timestamp) {

        }

        public void recordException(String deviceID, String exceptionMessage, String exceptionStacktrace, Map<String, String> segmentation, long timestamp) {

        }

        public void recordUserProperties(String deviceID, Map<String, String> userProperties) {

        }

        private void recordEventInternal(String deviceID, String key, int count, double sum, double dur, Map<String, String> segmentation, long timestamp) {
            JSONObject jsonObject = buildEventJSONObject(key, count, sum, dur, segmentation, timestamp < 1 ? DeviceCore.dev.uniqueTimestamp() : timestamp);

            if (!eventQueues.containsKey(deviceID)) {
                eventQueues.put(deviceID, new JSONArray());
            }
            eventQueues.get(deviceID).put(jsonObject);
            ++eventQSize;

            if (eventQSize >= internalConfig.getEventsBufferSize()) {
                addEventsToRequestQueue();
            }
        }

        private JSONObject buildEventJSONObject(String key, int count, double sum, double dur, Map<String, String> segmentation, long timestamp) {

            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(timestamp);
            final int hour = DeviceCore.dev.getHourFromCalendar(calendar);
            final int dow = calendar.get(Calendar.DAY_OF_WEEK) - 1;

            JSONObject jsonObject = new JSONObject();
            jsonObject.put("key", key);

            if (sum > 0) {
                jsonObject.put("sum", sum);
            }
            if (sum >= 0) {
                jsonObject.put("count", count);
            }
            if (dur >= 0) {
                jsonObject.put("dur", dur);
            }

            jsonObject.put("segmentation", segmentation);
            jsonObject.put("dow", dow);
            jsonObject.put("hour", hour);
            jsonObject.put("timestamp", timestamp);

            return jsonObject;
        }

        private void addEventsToRequestQueue() {
            //TODO: Need to verify order of events.
            for (Map.Entry<String, JSONArray> entry : eventQueues.entrySet()) {

                Request request = new Request();
                request.params.add("device_id", entry.getKey());
                request.params.add("events", entry.getValue());
                request.own(ModuleBackendMode.class);
                requestQ.add(request);
            }


        }

    }
}
