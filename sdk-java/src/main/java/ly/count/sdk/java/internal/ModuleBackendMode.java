package ly.count.sdk.java.internal;

import ly.count.sdk.java.Countly;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;

public class ModuleBackendMode extends ModuleBase {

    protected static final Log.Module L = Log.module("BackendMode");
    protected InternalConfig internalConfig = null;
    protected CtxCore ctx = null;

    //disabled is set when a empty module is created
    //in instances when the rating feature was not enabled
    //when a module is disabled, developer facing functions do nothing
    protected boolean disabledModule = false;

    private int eventQSize = 0;
    private final Queue<Request> requestQ = new LinkedList<>();
    private final Map<String, JSONArray> eventQueues = new HashMap<>();

    private Tasks tasks;
    private Transport transport;

    @Override
    public void init(InternalConfig config) {
        internalConfig = config;
        transport = new Transport();
        transport.init(internalConfig);
        tasks = new Tasks("request-queue");
        L.d("[ModuleBackendMode][init]");
    }

    @Override
    public void onContextAcquired(CtxCore ctx) {
        this.ctx = ctx;
        L.d("[ModuleBackendMode][onContextAcquired]");
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

    @Override
    public void stop(CtxCore ctx, boolean clear) {
        super.stop(ctx, clear);
        tasks.shutdown();
    }

    public void disableModule() {
        disabledModule = true;
    }

    private void processRequestQ() {
        if (!Countly.isInitialized()) {
            return;
        }

        if (tasks.isRunning() || requestQ.size() == 0) {
            return;
        }

        Request request = requestQ.remove();
        tasks.run(sendRequest(request));
    }

    private Tasks.Task<Boolean> sendRequest(final Request request) {
        return new Tasks.Task<Boolean>(Tasks.ID_STRICT) {
            @Override
            public Boolean call() throws Exception {
                if (request == null) {
                    return false;
                } else {
                    L.d("Preparing request: " + request);
                    final Boolean check = SDKCore.instance.isRequestReady(request);
                    if (check == null) {
                        L.d("Request is not ready yet: " + request);
                        return false;
                    } else if (check.equals(Boolean.FALSE)) {
                        L.d("Request won't be ready, removing: " + request);
                        return true;
                    } else {
                        tasks.run(transport.send(request), new Tasks.Callback<Boolean>() {
                            @Override
                            public void call(Boolean result) throws Exception {
                                L.d("Request " + request.storageId() + " sent?: " + result);
                                processRequestQ();
                            }
                        });
                        return true;
                    }
                }
            }
        };
    }

    public int getEventQSize() {
        return eventQSize;
    }

    public void setEventQSize(int eventQSize) {
        this.eventQSize = eventQSize;
    }

    public Queue<Request> getRequestQ() {
        return requestQ;
    }

    public Map<String, JSONArray> getEventQueues() {
        return eventQueues;
    }

    public class BackendMode {
        public void recordView(String deviceID, String key, Map<String, String> segmentation, long timestamp) {
            if (SDKCore.instance == null) {
                L.wtf("Countly is not initialized");
            }

            if (deviceID == null || deviceID.isEmpty()) {
                L.wtf("DeviceID can not be null or empty.");
            }

            recordEventInternal(deviceID, key, 1, -1, -1, segmentation, timestamp);
        }

        public void recordEvent(String deviceID, String key, int count, double sum, double dur, Map<String, String> segmentation, long timestamp) {
            if (SDKCore.instance == null) {
                L.wtf("Countly is not initialized");
            }

            if (deviceID == null || deviceID.isEmpty()) {
                L.wtf("DeviceID can not be null or empty.");
            }

            recordEventInternal(deviceID, key, count, sum, dur, segmentation, timestamp);
        }

        public void sessionBegin(String deviceID, long timestamp) {
            if (SDKCore.instance == null) {
                L.wtf("Countly is not initialized");
            }

            if (deviceID == null || deviceID.isEmpty()) {
                L.wtf("DeviceID can not be null or empty.");
            }

            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(timestamp);

            final int hour = calendar.get(Calendar.HOUR_OF_DAY);
            final int dow = calendar.get(Calendar.DAY_OF_WEEK) - 1;

            Request request = new Request();
            request.params.add("device_id", deviceID);
            request.params.add("begin_session", 1);

            request.params.add("dow", dow);
            request.params.add("hour", hour);
            request.params.add("timestamp", timestamp);
            request.params.add("tz", DeviceCore.dev.getTimezoneOffset());

//            JSONObject metrics = new JSONObject();
//            metrics.put("_os", "Windows 10");
//            metrics.put("_os_version", "10.0");
//            metrics.put("_locale", "en_GB");
//            metrics.put("_store", "Windows 10");
//
//            request.params.add("metrics", metrics);
            requestQ.add(request);
            processRequestQ();
        }

        public void sessionUpdate(String deviceID, double duration, long timestamp) {
            if (SDKCore.instance == null) {
                L.wtf("Countly is not initialized");
            }

            if (deviceID == null || deviceID.isEmpty()) {
                L.wtf("DeviceID can not be null or empty.");
            }

            Request request = new Request();
            request.params.add("device_id", deviceID);
            request.params.add("session_duration", duration);

            addTimeInfoIntoRequest(request, timestamp < 1 ? System.currentTimeMillis() : timestamp);
            requestQ.add(request);

            addEventsToRequestQ();
        }

        public void sessionEnd(String deviceID, double duration, long timestamp) {
            if (SDKCore.instance == null) {
                L.wtf("Countly is not initialized");
            }

            if (deviceID == null || deviceID.isEmpty()) {
                L.wtf("DeviceID can not be null or empty.");
            }

            //Add events against device ID to request Q
            JSONArray events = eventQueues.get(deviceID);
            if (events != null && events.length() > 0) {
                addEventsAgainstDeviceIdToRequestQ(deviceID, events);
                eventQSize -= events.length();
                eventQueues.remove(deviceID);
            }

            Request request = new Request();
            request.params.add("device_id", deviceID);
            request.params.add("end_session", duration);
            request.params.add("session_duration", duration);

            addTimeInfoIntoRequest(request, timestamp < 1 ? System.currentTimeMillis() : timestamp);
            requestQ.add(request);

            processRequestQ();
        }

        public void recordException(String deviceID, Throwable stacktrace, Map<String, String> segmentation, long timestamp) {
            if (SDKCore.instance == null) {
                L.wtf("Countly is not initialized");
            }

            if (deviceID == null || deviceID.isEmpty()) {
                L.wtf("DeviceID can not be null or empty.");
            }

            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            stacktrace.printStackTrace(pw);

            recordException(deviceID, stacktrace.getMessage(), sw.toString(), segmentation, timestamp);
        }

        public void recordException(String deviceID, String exceptionMessage, String exceptionStacktrace, Map<String, String> segmentation, long timestamp) {
            if (SDKCore.instance == null) {
                L.wtf("Countly is not initialized");
            }

            if (deviceID == null || deviceID.isEmpty()) {
                L.wtf("DeviceID can not be null or empty.");
            }

            JSONObject crash = new JSONObject();
            crash.put("_error", exceptionStacktrace);
            crash.put("_custom", segmentation);
            crash.put("_name", exceptionMessage);
            //crash.put("_nonfatal", true);

            Request request = new Request();
            request.params.add("device_id", deviceID);
            request.params.add("crash", crash);

            addTimeInfoIntoRequest(request, timestamp < 1 ? System.currentTimeMillis() : timestamp);

            requestQ.add(request);
        }

        public void recordUserProperties(String deviceID, Map<String, Object> userProperties, long timestamp) {
            if (SDKCore.instance == null) {
                L.wtf("Countly is not initialized");
            }

            if (deviceID == null || deviceID.isEmpty()) {
                L.wtf("DeviceID can not be null or empty.");
            }

            Request request = new Request();
            JSONObject properties = new JSONObject(userProperties);

            request.params.add("device_id", deviceID);
            request.params.add("user_details", properties);

            addTimeInfoIntoRequest(request, timestamp < 1 ? System.currentTimeMillis() : timestamp);
            requestQ.add(request);
        }

        private void recordEventInternal(String deviceID, String key, int count, double sum, double dur, Map<String, String> segmentation, long timestamp) {
            JSONObject jsonObject = buildEventJSONObject(key, count, sum, dur, segmentation, timestamp < 1 ? DeviceCore.dev.uniqueTimestamp() : timestamp);

            if (!eventQueues.containsKey(deviceID)) {
                eventQueues.put(deviceID, new JSONArray());
            }
            eventQueues.get(deviceID).put(jsonObject);
            ++eventQSize;

            if (eventQSize >= internalConfig.getEventsBufferSize()) {
                addEventsToRequestQ();
            }
        }

        private JSONObject buildEventJSONObject(String key, int count, double sum, double dur, Map<String, String> segmentation, long timestamp) {
            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(timestamp);
            final int hour = calendar.get(Calendar.HOUR_OF_DAY);
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

        private void addEventsToRequestQ() {
            for (Map.Entry<String, JSONArray> entry : eventQueues.entrySet()) {
                addEventsAgainstDeviceIdToRequestQ(entry.getKey(), entry.getValue());
            }
            eventQSize = 0;
            eventQueues.clear();

            processRequestQ();
        }

        private void addEventsAgainstDeviceIdToRequestQ(String deviceID, JSONArray events) {
            //TODO: Need to verify order of events.
            Request request = new Request();
            request.params.add("device_id", deviceID);
            request.params.add("events", events);
            addTimeInfoIntoRequest(request, System.currentTimeMillis());
            request.own(ModuleBackendMode.class);
            requestQ.add(request);
        }

        private void addTimeInfoIntoRequest(Request request, long timestamp) {
            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(timestamp);

            final int hour = calendar.get(Calendar.HOUR_OF_DAY);
            final int dow = calendar.get(Calendar.DAY_OF_WEEK) - 1;

            request.params.add("dow", dow);
            request.params.add("hour", hour);
            request.params.add("timestamp", timestamp);
            request.params.add("tz", DeviceCore.dev.getTimezoneOffset());
        }
    }
}
