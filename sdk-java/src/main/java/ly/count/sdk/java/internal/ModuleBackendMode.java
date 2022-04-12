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
    protected final Map<String, JSONArray> eventQueues = new HashMap<>();

    private ScheduledExecutorService executor = null;

    String[] userPredefinedKeys = {"name", "username", "email", "organization", "phone", "gender", "byear"};

    @Override
    public void init(InternalConfig config) {
        internalConfig = config;
        L.d("init: config = " + config);
    }

    @Override
    public void onContextAcquired(CtxCore ctx) {
        this.ctx = ctx;
        L.d("onContextAcquired: " + ctx.toString());

        if (ctx.getConfig().isBackendModeEnabled() && ctx.getConfig().getSendUpdateEachSeconds() > 0 && executor == null) {
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
        executor.shutdownNow();
    }

    public void disableModule() {
        disabledModule = true;
    }


    private void recordEventInternal(String deviceID, String key, int count, Double sum, double dur, Map<String, Object> segmentation, Long timestamp) {
        L.d(String.format("recordEventInternal: deviceID = %s, key = %s,, count = %d, sum = %f, dur = %f, segmentation = %s, timestamp = %d", deviceID, key, count, sum, dur, segmentation, timestamp));

        if (timestamp == null || timestamp < 1) {
            timestamp = DeviceCore.dev.uniqueTimestamp();
        }

        removeInvalidDataFromSegments(segmentation);

        JSONObject jsonObject = buildEventJSONObject(key, count, sum, dur, segmentation, timestamp);

        if (!eventQueues.containsKey(deviceID)) {
            eventQueues.put(deviceID, new JSONArray());
        }
        eventQueues.get(deviceID).put(jsonObject);
        ++eventQSize;

        if (eventQSize >= internalConfig.getEventsBufferSize()) {
            addEventsToRequestQ();
        }
    }

    private void sessionBeginInternal(String deviceID, Map<String, String> metrics, Long timestamp) {
        L.d(String.format("sessionBeginInternal: deviceID = %s, timestamp = %d", deviceID, timestamp));

        if (timestamp == null || timestamp < 1) {
            timestamp = DeviceCore.dev.uniqueTimestamp();
        }

        Request request = new Request();
        request.params.add("device_id", deviceID);
        request.params.add("begin_session", 1);

        JSONObject metricsJson = new JSONObject(metrics);
        request.params.add("metrics", metricsJson);

        addTimeInfoIntoRequest(request, timestamp);

        addRequestToRequestQ(request);
    }

    private void sessionUpdateInternal(String deviceID, Double duration, Long timestamp) {
        L.d(String.format("sessionUpdateInternal: deviceID = %s, duration = %f, timestamp = %d", deviceID, duration, timestamp));

        if (timestamp == null || timestamp < 1) {
            timestamp = DeviceCore.dev.uniqueTimestamp();
        }

        Request request = new Request();
        request.params.add("device_id", deviceID);
        request.params.add("session_duration", duration);

        addTimeInfoIntoRequest(request, timestamp);
        addRequestToRequestQ(request);
    }

    private void sessionEndInternal(String deviceID, double duration, Long timestamp) {
        L.d(String.format("sessionEndInternal: deviceID = %s, duration = %f, timestamp = %d", deviceID, duration, timestamp));

        if (timestamp == null || timestamp < 1) {
            timestamp = DeviceCore.dev.uniqueTimestamp();
        }

        //Add events against device ID to request Q
        addEventsAgainstDeviceIdToRequestQ(deviceID);
        eventQueues.remove(deviceID);

        Request request = new Request();
        request.params.add("device_id", deviceID);
        request.params.add("end_session", 1);
        request.params.add("session_duration", duration);

        addTimeInfoIntoRequest(request, timestamp);
        addRequestToRequestQ(request);
    }

    public void recordExceptionInternal(String deviceID, String message, String stacktrace, Map<String, Object> segmentation, Map<String, String> crashDetails, Long timestamp) {
        L.d(String.format("recordExceptionInternal: deviceID = %s, message = %s, stacktrace = %s, segmentation = %s, timestamp = %d", deviceID, message, stacktrace, segmentation, timestamp));

        if (timestamp == null || timestamp < 1) {
            timestamp = DeviceCore.dev.uniqueTimestamp();
        }

        removeInvalidDataFromSegments(segmentation);

        JSONObject crash = new JSONObject();
        crash.put("_error", stacktrace);
        crash.put("_custom", segmentation);
        crash.put("_name", message);

        if (crashDetails != null && !crashDetails.isEmpty()) {
            removeInvalidDataFromSegments(segmentation);
            for (Map.Entry<String, String> entry : crashDetails.entrySet()) {
                crash.put(entry.getKey(), entry.getValue());
            }
        }

        Request request = new Request();
        request.params.add("device_id", deviceID);
        request.params.add("crash", crash);

        addTimeInfoIntoRequest(request, timestamp);

        addRequestToRequestQ(request);
    }

    private void recordUserPropertiesInternal(String deviceID, Map<String, Object> userProperties, Long timestamp) {
        L.d(String.format("recordUserPropertiesInternal: deviceID = %s, userProperties = %s, timestamp = %d", deviceID, userProperties, timestamp));

        if (timestamp == null || timestamp < 1) {
            timestamp = DeviceCore.dev.uniqueTimestamp();
        }

        removeInvalidDataFromSegments(userProperties);

        Map<String, Object> userDetail = new HashMap<>();
        Map<String, Object> customDetail = new HashMap<>();
        for (Map.Entry<String, Object> item : userProperties.entrySet()) {
            if (Arrays.stream(userPredefinedKeys).anyMatch(item.getKey()::equalsIgnoreCase)) {
                userDetail.put(item.getKey(), item.getValue());
            } else {
                Object v = item.getValue();
                if (v instanceof String) {
                    String value = (String) v;
                    if (!value.isEmpty() && value.charAt(0) == '{') {
                        try {
                            v = new JSONObject(value);
                        } catch (Exception ignored) {
                        }
                    }
                }
                customDetail.put(item.getKey(), v);
            }
        }

        userDetail.put("custom", customDetail);

        Request request = new Request();
        JSONObject properties = new JSONObject(userDetail);

        request.params.add("device_id", deviceID);
        request.params.add("user_details", properties);

        addTimeInfoIntoRequest(request, timestamp);
        addRequestToRequestQ(request);
    }

    void recordDirectRequestInternal(String deviceID, Map<String, String> requestData, Long timestamp) {
        L.d(String.format("recordDirectRequestInternal: deviceID = %s, requestJson = %s, timestamp = %d", deviceID, requestData, timestamp));

        if (timestamp == null || timestamp < 1) {
            timestamp = DeviceCore.dev.uniqueTimestamp();
        }

        Request request = new Request();
        request.params.add("device_id", deviceID);
        addTimeInfoIntoRequest(request, timestamp);

        //remove checksum, will add before sending request to server
        requestData.remove("checksum");
        requestData.remove("checksum256");
        requestData.remove("sdk_name");
        requestData.remove("sdk_version");

        for (Map.Entry<String, String> item : requestData.entrySet()) {
            request.params.add(item.getKey(), item.getValue());
        }

        addRequestToRequestQ(request);
    }

    private JSONObject buildEventJSONObject(String key, int count, Double sum, double dur, Map<String, Object> segmentation, Long timestamp) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timestamp);
        final int hour = calendar.get(Calendar.HOUR_OF_DAY);
        final int dow = calendar.get(Calendar.DAY_OF_WEEK) - 1;

        JSONObject jsonObject = new JSONObject();
        jsonObject.put("key", key);

        jsonObject.put("sum", sum);

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

        L.d(String.format("buildEventJSONObject: jsonObject = %s", jsonObject));

        return jsonObject;
    }

    private void addTimeInfoIntoRequest(Request request, Long timestamp) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timestamp);

        final int hour = calendar.get(Calendar.HOUR_OF_DAY);
        final int dow = calendar.get(Calendar.DAY_OF_WEEK) - 1;

        request.params.add("dow", dow);
        request.params.add("hour", hour);
        request.params.add("timestamp", timestamp);
        request.params.add("tz", DeviceCore.dev.getTimezoneOffset());
    }

    private void addEventsAgainstDeviceIdToRequestQ(String deviceID) {
        JSONArray events = eventQueues.get(deviceID);
        if (events == null || events.isEmpty()) {
            return;
        }

        eventQSize -= events.length();

        Request request = new Request();
        request.params.add("device_id", deviceID);
        request.params.add("events", events);
        addTimeInfoIntoRequest(request, System.currentTimeMillis());
        request.own(ModuleBackendMode.class);
        addRequestToRequestQ(request);
    }

    private void addEventsToRequestQ() {
        L.d("addEventsToRequestQ");

        for (String s : eventQueues.keySet()) {
            addEventsAgainstDeviceIdToRequestQ(s);
        }

        eventQSize = 0;
        eventQueues.clear();

    }

    private void addRequestToRequestQ(Request request) {
        L.d("addRequestToRequestQ");
        if (internalConfig.getRequestQueueMaxSize() == SDKCore.instance.requestQueueMemory.size()) {
            L.d("addRequestToRequestQ: In Memory request queue is full, dropping oldest request: " + request.params.toString());
            SDKCore.instance.requestQueueMemory.remove();
        }

        SDKCore.instance.requestQueueMemory.add(request);
        SDKCore.instance.networking.check(ctx);
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
                L.w("RemoveSegmentInvalidDataTypes: In segmentation Data type '" + type + "' of item '" + item.getValue() + "' isn't valid.");
            }
        }

        for (String k : toRemove) {
            segments.remove(k);
        }

        return segments;
    }

    public class BackendMode {
        public void recordView(String deviceID, String name, Map<String, Object> segmentation, Long timestamp) {
            L.i(String.format(":recordView: deviceID = %s, key = %s, segmentation = %s, timestamp = %d", deviceID, name, segmentation, timestamp));
            if (!internalConfig.isBackendModeEnabled()) {
                L.e("recordView: BackendMode is not enabled.");
                return;
            }

            if (deviceID == null || deviceID.isEmpty()) {
                L.e("recordView: DeviceID can not be null or empty.");
                return;
            }

            if (name == null || name.isEmpty()) {
                L.e("recordView: Name can not be null or empty.");
                return;
            }

            if (segmentation == null) {
                segmentation = new HashMap<>();
            }

            segmentation.put("name", name);

            recordEventInternal(deviceID, "[CLY]_view", 1, null, -1, segmentation, timestamp);
        }

        public void recordEvent(String deviceID, String key, int count, Double sum, double dur, Map<String, Object> segmentation, Long timestamp) {
            L.i(String.format("recordEvent: deviceID = %s, key = %s, count = %d, sum = %f, dur = %f, segmentation = %s, timestamp = %d", deviceID, key, count, sum, dur, segmentation, timestamp));

            if (!internalConfig.isBackendModeEnabled()) {
                L.e("recordEvent: BackendMode is not enabled.");
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

            if (count < 1) {
                count = 1;
            }

            recordEventInternal(deviceID, key, count, sum, dur, segmentation, timestamp);
        }

        public void sessionBegin(String deviceID, Map<String, String> metrics, Long timestamp) {
            L.i(String.format("sessionBegin: deviceID = %s, timestamp = %d", deviceID, timestamp));

            if (!internalConfig.isBackendModeEnabled()) {
                L.e("sessionBegin: BackendMode is not enabled.");
                return;
            }

            if (deviceID == null || deviceID.isEmpty()) {
                L.e("sessionBegin: DeviceID can not be null or empty.");
                return;
            }

            sessionBeginInternal(deviceID, metrics, timestamp);
        }

        public void sessionUpdate(String deviceID, double duration, Long timestamp) {
            L.i(String.format("sessionUpdate: deviceID = %s, duration = %f, timestamp = %d", deviceID, duration, timestamp));

            if (!internalConfig.isBackendModeEnabled()) {
                L.e("sessionUpdate: BackendMode is not enabled.");
                return;
            }

            if (deviceID == null || deviceID.isEmpty()) {
                L.e("sessionUpdate: DeviceID can not be null or empty.");
                return;
            }

            if (duration < 0) {
                duration = 0;
            }

            sessionUpdateInternal(deviceID, duration, timestamp);
        }

        public void sessionEnd(String deviceID, double duration, Long timestamp) {
            L.i(String.format("sessionEnd: deviceID = %s, duration = %f, timestamp = %d", deviceID, duration, timestamp));

            if (!internalConfig.isBackendModeEnabled()) {
                L.e("sessionEnd: BackendMode is not enabled.");
                return;
            }

            if (deviceID == null || deviceID.isEmpty()) {
                L.e("sessionEnd: DeviceID can not be null or empty.");
                return;
            }

            if (duration < 0) {
                duration = 0;
            }

            sessionEndInternal(deviceID, duration, timestamp);
        }

        public void recordException(String deviceID, Throwable throwable, Map<String, Object> segmentation, Map<String, String> crashDetails, Long timestamp) {
            L.i(String.format("recordException: deviceID = %s, throwable = %s, segmentation = %s, timestamp = %d", deviceID, throwable, segmentation, timestamp));

            if (!internalConfig.isBackendModeEnabled()) {
                L.e("recordException BackendMode is not enabled.");
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

            recordExceptionInternal(deviceID, throwable.getMessage(), sw.toString(), segmentation, crashDetails, timestamp);
        }

        public void recordException(String deviceID, String message, String stacktrace, Map<String, Object> segmentation, Map<String, String> crashDetails, Long timestamp) {
            L.i(String.format("recordException: deviceID = %s, message = %s, stacktrace = %s, segmentation = %s, timestamp = %d", deviceID, message, stacktrace, segmentation, timestamp));

            if (!internalConfig.isBackendModeEnabled()) {
                L.e("recordException: BackendMode is not enabled.");
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

            if (stacktrace == null || stacktrace.isEmpty()) {
                L.e("recordException: stacktrace can not be null.");
                return;
            }

            recordExceptionInternal(deviceID, message, stacktrace, segmentation, crashDetails, timestamp);
        }

        public void recordUserProperties(String deviceID, Map<String, Object> userProperties, Long timestamp) {
            L.i(String.format("recordUserProperties: deviceID = %s, userProperties = %s, timestamp = %d", deviceID, userProperties, timestamp));

            if (!internalConfig.isBackendModeEnabled()) {
                L.e("recordUserProperties: BackendMode is not enabled.");
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

            recordUserPropertiesInternal(deviceID, userProperties, timestamp);
        }

        public void recordDirectRequest(String deviceID, Map<String, String> requestData, Long timestamp) {
            if (!internalConfig.isBackendModeEnabled()) {
                L.e("recordDirectRequest: BackendMode is not enabled.");
                return;
            }

            if (deviceID == null || deviceID.isEmpty()) {
                L.e("recordDirectRequest: DeviceID can not be null or empty.");
                return;
            }
            if (requestData == null || requestData.isEmpty()) {
                L.e("recordDirectRequest: requestData can not be null or empty.");
                return;
            }
            recordDirectRequestInternal(deviceID, requestData, timestamp);
        }

        public int getQueueSize() {
            int queueSize = 0;
            int eSize = eventQueues.size();
            int rSize = SDKCore.instance.requestQueueMemory.size();

            return rSize + eSize;
        }

        protected ModuleBase getModule() {
            return ModuleBackendMode.this;
        }
    }
}
