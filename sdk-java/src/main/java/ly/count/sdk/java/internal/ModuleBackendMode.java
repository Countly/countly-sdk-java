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


    private void recordEventInternal(String deviceID, String key, int count, double sum, double dur, Map<String, Object> segmentation, long timestamp) {
        L.d(String.format("recordEventInternal: deviceID = %s, key = %s,, count = %d, sum = %f, dur = %f, segmentation = %s, timestamp = %d", deviceID, key, count, sum, dur, segmentation, timestamp));

        if (timestamp < 1) {
            timestamp = DeviceCore.dev.uniqueTimestamp();
        }

        removeInvalidDataFromSegments(segmentation, false);

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

    private void sessionBeginInternal(String deviceID, Map<String, String> metrics, long timestamp) {
        L.d(String.format("sessionBeginInternal: deviceID = %s, timestamp = %d", deviceID, timestamp));

        if (timestamp < 1) {
            timestamp = DeviceCore.dev.uniqueTimestamp();
        }

        Request request = new Request();
        request.params.add("device_id", deviceID);
        request.params.add("begin_session", 1);

        JSONObject metricsJson = new JSONObject(metrics);
        request.params.add("metrics", metricsJson);

        addTimeInfoIntoRequest(request, timestamp);

        SDKCore.instance.requestQ.add(request);
    }

    private void sessionUpdateInternal(String deviceID, double duration, long timestamp) {
        L.d(String.format("sessionUpdateInternal: deviceID = %s, duration = %f, timestamp = %d", deviceID, duration, timestamp));

        if (timestamp < 1) {
            timestamp = DeviceCore.dev.uniqueTimestamp();
        }

        Request request = new Request();
        request.params.add("device_id", deviceID);
        request.params.add("session_duration", duration);

        addTimeInfoIntoRequest(request, timestamp);
        SDKCore.instance.requestQ.add(request);

        addEventsToRequestQ();
    }

    private void sessionEndInternal(String deviceID, double duration, long timestamp) {
        L.d(String.format("sessionEndInternal: deviceID = %s, duration = %f, timestamp = %d", deviceID, duration, timestamp));

        if (timestamp < 1) {
            timestamp = DeviceCore.dev.uniqueTimestamp();
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
        request.params.add("end_session", 1);
        request.params.add("session_duration", duration);

        addTimeInfoIntoRequest(request, timestamp);
        SDKCore.instance.requestQ.add(request);
    }

    public void recordExceptionInternal(String deviceID, String message, String stacktrace, Map<String, Object> segmentation, long timestamp) {
        L.d(String.format("recordExceptionInternal: deviceID = %s, message = %s, stacktrace = %s, segmentation = %s, timestamp = %d", deviceID, message, stacktrace, segmentation, timestamp));

        if (timestamp < 1) {
            timestamp = DeviceCore.dev.uniqueTimestamp();
        }

        removeInvalidDataFromSegments(segmentation, false);

        JSONObject crash = new JSONObject();
        crash.put("_error", stacktrace);
        crash.put("_custom", segmentation);
        crash.put("_name", message);
        //crash.put("_nonfatal", true);

        Request request = new Request();
        request.params.add("device_id", deviceID);
        request.params.add("crash", crash);

        addTimeInfoIntoRequest(request, timestamp);

        SDKCore.instance.requestQ.add(request);
    }

    private void recordUserPropertiesInternal(String deviceID, Map<String, Object> userProperties, long timestamp) {
        L.d(String.format("recordUserPropertiesInternal: deviceID = %s, userProperties = %s, timestamp = %d", deviceID, userProperties, timestamp));

        if (timestamp < 1) {
            timestamp = DeviceCore.dev.uniqueTimestamp();
        }

        List<String> userPredefinedKeys = Arrays.asList("name", "username", "email", "organization", "phone", "gender", "byear");

        Map<String, Object> userDetail = new HashMap<>();
        Map<String, Object> customDetail = new HashMap<>();
        for (Map.Entry<String, Object> item : userProperties.entrySet()) {
            if (userPredefinedKeys.contains(item.getKey().toLowerCase())) {
                userDetail.put(item.getKey(), item.getValue());
            } else {
                if (item.getValue() instanceof Map) {
                    if ("custom".equals(item.getKey())) {
                        customDetail.putAll((Map) item.getValue());
                        continue;
                    }
                }
                customDetail.put(item.getKey(), item.getValue());
            }
        }
        
        userDetail.put("custom", customDetail);
        removeInvalidDataFromSegments(userDetail, true);

        Request request = new Request();
        JSONObject properties = new JSONObject(userDetail);

        request.params.add("device_id", deviceID);
        request.params.add("user_details", properties);

        addTimeInfoIntoRequest(request, timestamp);
        SDKCore.instance.requestQ.add(request);
    }

    private JSONObject buildEventJSONObject(String key, int count, double sum, double dur, Map<String, Object> segmentation, long timestamp) {
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

        L.d(String.format("buildEventJSONObject: jsonObject = %s", jsonObject));

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
        L.d(String.format("addEventsAgainstDeviceIdToRequestQ: deviceID = %s, events = %s", deviceID, events));

        Request request = new Request();
        request.params.add("device_id", deviceID);
        request.params.add("events", events);
        addTimeInfoIntoRequest(request, System.currentTimeMillis());
        request.own(ModuleBackendMode.class);
        SDKCore.instance.requestQ.add(request);
    }

    private void addEventsToRequestQ() {
        L.d(String.format("addEventsToRequestQ"));

        for (Map.Entry<String, JSONArray> entry : eventQueues.entrySet()) {
            addEventsAgainstDeviceIdToRequestQ(entry.getKey(), entry.getValue());
        }
        eventQSize = 0;
        eventQueues.clear();
    }

    protected Map<String, Object> removeInvalidDataFromSegments(Map<String, Object> segments, boolean userProperties) {

        if (segments == null || segments.isEmpty()) {
            return segments;
        }

        int i = 0;
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, Object> item : segments.entrySet()) {
            Object type = item.getValue();

            boolean isValidDataType;
            if (userProperties && type instanceof Map) {
                isValidDataType = true;
                removeInvalidDataFromSegments((Map<String, Object>) type, true);
            } else {
                isValidDataType = (type instanceof Boolean
                        || type instanceof Integer
                        || type instanceof Long
                        || type instanceof String
                        || type instanceof Double
                        || type instanceof Float
                );
            }

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
        public void recordView(String deviceID, String name, Map<String, Object> segmentation, long timestamp) {
            L.i(String.format(":recordView: deviceID = %s, key = %s, segmentation = %s, timestamp = %d", deviceID, name, segmentation, timestamp));
            if (!internalConfig.isBackendModeEnabled()) {
                L.e("recordView: BackendMode is not enable.");
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

            recordEventInternal(deviceID, "[CLY]_view", 1, -1, -1, segmentation, timestamp);
        }

        public void recordEvent(String deviceID, String key, int count, double sum, double dur, Map<String, Object> segmentation, long timestamp) {
            L.i(String.format("recordEvent: deviceID = %s, key = %s, count = %d, sum = %f, dur = %f, segmentation = %s, timestamp = %d", deviceID, key, count, sum, dur, segmentation, timestamp));

            if (!internalConfig.isBackendModeEnabled()) {
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

        public void sessionBegin(String deviceID, Map<String, String> metrics, long timestamp) {
            L.i(String.format("sessionBegin: deviceID = %s, timestamp = %d", deviceID, timestamp));

            if (!internalConfig.isBackendModeEnabled()) {
                L.e("sessionBegin: BackendMode is not enable.");
                return;
            }

            if (deviceID == null || deviceID.isEmpty()) {
                L.e("sessionBegin: DeviceID can not be null or empty.");
                return;
            }

            sessionBeginInternal(deviceID, metrics, timestamp);
        }

        public void sessionUpdate(String deviceID, double duration, long timestamp) {
            L.i(String.format("sessionUpdate: deviceID = %s, duration = %f, timestamp = %d", deviceID, duration, timestamp));

            if (!internalConfig.isBackendModeEnabled()) {
                L.e("sessionUpdate: BackendMode is not enable.");
                return;
            }

            if (deviceID == null || deviceID.isEmpty()) {
                L.e("sessionUpdate: DeviceID can not be null or empty.");
                return;
            }

            sessionUpdateInternal(deviceID, duration, timestamp);
        }

        public void sessionEnd(String deviceID, double duration, long timestamp) {
            L.i(String.format("sessionEnd: deviceID = %s, duration = %f, timestamp = %d", deviceID, duration, timestamp));

            if (!internalConfig.isBackendModeEnabled()) {
                L.e("sessionEnd: BackendMode is not enable.");
                return;
            }

            if (deviceID == null || deviceID.isEmpty()) {
                L.e("sessionEnd: DeviceID can not be null or empty.");
                return;
            }

            sessionEndInternal(deviceID, duration, timestamp);
        }

        public void recordException(String deviceID, Throwable throwable, Map<String, Object> segmentation, long timestamp) {
            L.i(String.format("recordException: deviceID = %s, throwable = %s, segmentation = %s, timestamp = %d", deviceID, throwable, segmentation, timestamp));

            if (!internalConfig.isBackendModeEnabled()) {
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

            recordExceptionInternal(deviceID, throwable.getMessage(), sw.toString(), segmentation, timestamp);
        }

        public void recordException(String deviceID, String message, String stacktrace, Map<String, Object> segmentation, long timestamp) {
            L.i(String.format("recordException: deviceID = %s, message = %s, stacktrace = %s, segmentation = %s, timestamp = %d", deviceID, message, stacktrace, segmentation, timestamp));

            if (!internalConfig.isBackendModeEnabled()) {
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

            if (stacktrace == null || stacktrace.isEmpty()) {
                L.e("recordException: stacktrace can not be null.");
                return;
            }

            recordExceptionInternal(deviceID, message, stacktrace, segmentation, timestamp);
        }

        public void recordUserProperties(String deviceID, Map<String, Object> userProperties, long timestamp) {
            L.i(String.format("recordUserProperties: deviceID = %s, userProperties = %s, timestamp = %d", deviceID, userProperties, timestamp));

            if (!internalConfig.isBackendModeEnabled()) {
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

            recordUserPropertiesInternal(deviceID, userProperties, timestamp);
        }

        protected ModuleBase getModule() {
            return ModuleBackendMode.this;
        }
    }
}
