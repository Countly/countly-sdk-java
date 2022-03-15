package ly.count.sdk.java.internal;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ModuleBackendMode extends ModuleBase {

    protected static final Log.Module L = Log.module("BackendMode");
    protected InternalConfig internalConfig = null;
    protected CtxCore ctx = null;

    //disabled is set when a empty module is created
    //in instances when the rating feature was not enabled
    //when a module is disabled, developer facing functions do nothing
    protected boolean disabledModule = false;

    protected int eventQSize = 0;
    protected boolean defferUpload = false;
    protected final Queue<Request> requestQ = new LinkedList<>();
    protected final Map<String, JSONArray> eventQueues = new HashMap<>();

    private Tasks tasks;
    private Transport transport;
    private ScheduledExecutorService executor = null;

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

        if (ctx.getConfig().isBackendModeEnable() && ctx.getConfig().getSendUpdateEachSeconds() > 0 && executor == null) {
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
        executor.shutdownNow();
    }

    public void disableModule() {
        disabledModule = true;
    }


    private void recordEventInternal(String deviceID, String key, int count, double sum, double dur, Map<String, String> segmentation, long timestamp) {
        L.i(String.format("recordEventInternal: deviceID = %s, key = %s,, count = %d, sum = %f, dur = %f, segmentation = %s, timestamp = %d", deviceID, key, count, sum, dur, segmentation, timestamp));

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
        if (count > 0) {
            jsonObject.put("count", count);
        }
        if (dur >= 0) {
            jsonObject.put("dur", dur);
        }

        jsonObject.put("segmentation", segmentation);
        jsonObject.put("dow", dow);
        jsonObject.put("hour", hour);
        jsonObject.put("timestamp", timestamp);

        L.i(String.format("buildEventJSONObject: jsonObject = %s", jsonObject));

        return jsonObject;
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

    private void addEventsAgainstDeviceIdToRequestQ(String deviceID, JSONArray events) {
        L.i(String.format("addEventsAgainstDeviceIdToRequestQ: deviceID = %s, events = %s", deviceID, events));

        Request request = new Request();
        request.params.add("device_id", deviceID);
        request.params.add("events", events);
        addTimeInfoIntoRequest(request, System.currentTimeMillis());
        request.own(ModuleBackendMode.class);
        requestQ.add(request);
    }

    private void addEventsToRequestQ() {
        L.i(String.format("addEventsToRequestQ"));

        for (Map.Entry<String, JSONArray> entry : eventQueues.entrySet()) {
            addEventsAgainstDeviceIdToRequestQ(entry.getKey(), entry.getValue());
        }
        eventQSize = 0;
        eventQueues.clear();

        processRequestQ();
    }

    private void processRequestQ() {
        L.i(String.format("processRequestQ: requestQ-size = %d", requestQ.size()));

        if (defferUpload) {
            return;
        }

        if (tasks.isRunning() || requestQ.size() == 0) {
            return;
        }

        Request request = requestQ.remove();
        tasks.run(sendRequest(request));
    }

    private Tasks.Task<Boolean> sendRequest(final Request request) {
        L.i(String.format("sendRequest: request = %s", request));

        return new Tasks.Task<Boolean>(Tasks.ID_STRICT) {
            @Override
            public Boolean call() throws Exception {
                if (request == null) {
                    return false;
                } else {
                    L.i("sendRequest: Preparing request: " + request);
                    final Boolean check = SDKCore.instance.isRequestReady(request);
                    if (check == null) {
                        L.i("sendRequest: Request is not ready yet: " + request);
                        return false;
                    } else if (check.equals(Boolean.FALSE)) {
                        L.d("sendRequest: Request won't be ready, removing: " + request);
                        return true;
                    } else {
                        tasks.run(transport.send(request), new Tasks.Callback<Boolean>() {
                            @Override
                            public void call(Boolean result) throws Exception {
                                L.d("sendRequest: Request " + request.storageId() + " sent?: " + result);
                                processRequestQ();
                            }
                        });
                        return true;
                    }
                }
            }
        };
    }

    public class BackendMode {
        public void recordView(String deviceID, String key, Map<String, String> segmentation, long timestamp) {
            L.d(String.format(":recordView: deviceID = %s, key = %s, segmentation = %s, timestamp = %d", deviceID, key, segmentation, timestamp));
            if (!internalConfig.isBackendModeEnable()) {
                L.e("recordView: BackendMode is not enable.");
                return;
            }

            if (deviceID == null || deviceID.isEmpty()) {
                L.e("recordView: DeviceID can not be null or empty.");
                return;
            }

            if (key == null || key.isEmpty()) {
                L.e("recordView: Key can not be null or empty.");
                return;
            }

            recordEventInternal(deviceID, key, 1, -1, -1, segmentation, timestamp);
        }

        public void recordEvent(String deviceID, String key, int count, double sum, double dur, Map<String, String> segmentation, long timestamp) {
            L.d(String.format("recordEvent: deviceID = %s, key = %s, count = %d, sum = %f, dur = %f, segmentation = %s, timestamp = %d", deviceID, key, count, sum, dur, segmentation, timestamp));

            if (!internalConfig.isBackendModeEnable()) {
                L.e("recordEvent: BackendMode is not enable.");
                return;
            }

            if (deviceID == null || deviceID.isEmpty()) {
                L.e("recordEvent: DeviceID can not be null or empty.");
                return;
            }

            if (key == null || key.isEmpty()) {
                L.e("recordEvent: Event key can not be null or empty.");
                return;
            }

            recordEventInternal(deviceID, key, count, sum, dur, segmentation, timestamp);
        }

        public void sessionBegin(String deviceID, long timestamp) {
            L.d(String.format("sessionBegin: deviceID = %s, timestamp = %d", deviceID, timestamp));

            if (!internalConfig.isBackendModeEnable()) {
                L.e("sessionBegin: BackendMode is not enable.");
                return;
            }

            if (deviceID == null || deviceID.isEmpty()) {
                L.e("sessionBegin: DeviceID can not be null or empty.");
                return;
            }

            sessionBeginInternal(deviceID, timestamp < 1 ? System.currentTimeMillis() : timestamp);
        }

        public void sessionUpdate(String deviceID, double duration, long timestamp) {
            L.d(String.format("sessionUpdate: deviceID = %s, duration = %f, timestamp = %d", deviceID, duration, timestamp));

            if (!internalConfig.isBackendModeEnable()) {
                L.e("sessionUpdate: BackendMode is not enable.");
                return;
            }

            if (deviceID == null || deviceID.isEmpty()) {
                L.e("sessionUpdate: DeviceID can not be null or empty.");
                return;
            }

            sessionUpdateInternal(deviceID, duration, timestamp < 1 ? System.currentTimeMillis() : timestamp);
        }

        public void sessionEnd(String deviceID, double duration, long timestamp) {
            L.d(String.format("sessionEnd: deviceID = %s, duration = %f, timestamp = %d", deviceID, duration, timestamp));

            if (!internalConfig.isBackendModeEnable()) {
                L.e("sessionEnd: BackendMode is not enable.");
                return;
            }

            if (deviceID == null || deviceID.isEmpty()) {
                L.e("sessionEnd: DeviceID can not be null or empty.");
                return;
            }

            sessionEndInternal(deviceID, duration, timestamp < 1 ? System.currentTimeMillis() : timestamp);
        }

        public void recordException(String deviceID, Throwable throwable, Map<String, String> segmentation, long timestamp) {
            L.d(String.format("recordException: deviceID = %s, throwable = %s, segmentation = %s, timestamp = %d", deviceID, throwable, segmentation, timestamp));

            if (!internalConfig.isBackendModeEnable()) {
                L.e("recordException BackendMode is not enable.");
                return;
            }

            if (deviceID == null || deviceID.isEmpty()) {
                L.e("recordException: DeviceID can not be null or empty.");
                return;
            }

            if (throwable == null) {
                L.e("recordException: throwable can not be null.");
                return;
            }

            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            throwable.printStackTrace(pw);

            recordExceptionInternal(deviceID, throwable.getMessage(), sw.toString(), segmentation, timestamp < 1 ? System.currentTimeMillis() : timestamp);
        }

        public void recordException(String deviceID, String message, String stacktrace, Map<String, String> segmentation, long timestamp) {
            L.d(String.format("recordException: deviceID = %s, message = %s, stacktrace = %s, segmentation = %s, timestamp = %d", deviceID, message, stacktrace, segmentation, timestamp));

            if (!internalConfig.isBackendModeEnable()) {
                L.e("[Countly][BackendMode][recordException] BackendMode is not enable.");
                return;
            }

            if (deviceID == null || deviceID.isEmpty()) {
                L.e("recordException: DeviceID can not be null or empty.");
                return;
            }

            if (message == null || message.isEmpty()) {
                L.e("recordException: message can not be null or empty.");
                return;
            }

            if (stacktrace == null) {
                L.e("recordException: stacktrace can not be null.");
                return;
            }

            recordExceptionInternal(deviceID, message, stacktrace, segmentation, timestamp < 1 ? System.currentTimeMillis() : timestamp);
        }

        public void recordUserProperties(String deviceID, Map<String, Object> userProperties, long timestamp) {
            L.d(String.format("recordUserProperties: deviceID = %s, userProperties = %s, timestamp = %d", deviceID, userProperties, timestamp));

            if (!internalConfig.isBackendModeEnable()) {
                L.e("recordUserProperties: BackendMode is not enable.");
                return;
            }

            if (deviceID == null || deviceID.isEmpty()) {
                L.e("recordUserProperties: DeviceID can not be null or empty.");
                return;
            }
            if (userProperties == null || userProperties.isEmpty()) {
                L.e("recordUserProperties: userProperties can not be null or empty.");
                return;
            }

            recordUserPropertiesInternal(deviceID, userProperties, timestamp < 1 ? System.currentTimeMillis() : timestamp);
        }

        private void sessionBeginInternal(String deviceID, long timestamp) {
            L.i(String.format("sessionBeginInternal: deviceID = %s, timestamp = %d", deviceID, timestamp));

            Request request = new Request();
            request.params.add("device_id", deviceID);
            request.params.add("begin_session", 1);

            addTimeInfoIntoRequest(request, timestamp);

            requestQ.add(request);
            processRequestQ();
        }

        private void sessionUpdateInternal(String deviceID, double duration, long timestamp) {
            L.i(String.format("sessionUpdateInternal: deviceID = %s, duration = %f, timestamp = %d", deviceID, duration, timestamp));

            Request request = new Request();
            request.params.add("device_id", deviceID);
            request.params.add("session_duration", duration);

            addTimeInfoIntoRequest(request, timestamp);
            requestQ.add(request);

            addEventsToRequestQ();
        }

        private void sessionEndInternal(String deviceID, double duration, long timestamp) {
            L.i(String.format("sessionEndInternal: deviceID = %s, duration = %f, timestamp = %d", deviceID, duration, timestamp));

            //Add events against device ID to request Q
            JSONArray events = eventQueues.get(deviceID);
            if (events != null && events.length() > 0) {
                addEventsAgainstDeviceIdToRequestQ(deviceID, events);
                eventQSize -= events.length();
                eventQueues.remove(deviceID);
            }

            Request request = new Request();
            request.params.add("device_id", deviceID);
            request.params.add("end_session", 1);
            request.params.add("session_duration", duration);

            addTimeInfoIntoRequest(request, timestamp);
            requestQ.add(request);

            processRequestQ();
        }

        public void recordExceptionInternal(String deviceID, String message, String stacktrace, Map<String, String> segmentation, long timestamp) {
            L.i(String.format("recordExceptionInternal: deviceID = %s, message = %s, stacktrace = %s, segmentation = %s, timestamp = %d", deviceID, message, stacktrace, segmentation, timestamp));

            JSONObject crash = new JSONObject();
            crash.put("_error", stacktrace);
            crash.put("_custom", segmentation);
            crash.put("_name", message);
            //crash.put("_nonfatal", true);

            Request request = new Request();
            request.params.add("device_id", deviceID);
            request.params.add("crash", crash);

            addTimeInfoIntoRequest(request, timestamp);

            requestQ.add(request);
        }

        private void recordUserPropertiesInternal(String deviceID, Map<String, Object> userProperties, long timestamp) {
            L.i(String.format("recordUserPropertiesInternal: deviceID = %s, userProperties = %s, timestamp = %d", deviceID, userProperties, timestamp));

            Request request = new Request();
            JSONObject properties = new JSONObject(userProperties);

            request.params.add("device_id", deviceID);
            request.params.add("user_details", properties);

            addTimeInfoIntoRequest(request, timestamp);
            requestQ.add(request);
        }

        protected ModuleBase getModule() {
            return ModuleBackendMode.this;
        }
    }
}
