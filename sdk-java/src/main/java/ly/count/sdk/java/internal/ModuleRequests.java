package ly.count.sdk.java.internal;

import java.util.Map;
import java.util.concurrent.Future;

/**
 * Centralized place for all requests construction & handling.
 */

public class ModuleRequests extends ModuleBase {

    private static Params metrics;

    public interface ParamsInjector {
        void call(Params params);
    }

    @Override
    public void initFinished(final InternalConfig config) {
        ModuleRequests.metrics = Device.dev.buildMetrics();
    }

    private static Request sessionRequest(InternalConfig config, SessionImpl session, String type, Long value) {
        Request request = Request.build();

        if (session != null && session.hasConsent(CoreFeature.Sessions)) {
            if (value != null && value > 0) {
                request.params.add(type, value);
            }

            request.params.add("session_id", session.id);

            if ("begin_session".equals(type)) {
                request.params.add(metrics);
            }
        }

        if (session != null) {
            synchronized (session.storageId()) {
                if (session.events.size() > 0 && session.hasConsent(CoreFeature.Events)) {
                    request.params.arr("events").put(session.events).add();
                    session.events.clear();
                } else {
                    session.events.clear();
                }

                if (session.params.length() > 0) {
                    request.params.add(session.params);
                    session.params.clear();
                }
            }
        }

        if (config.getDeviceId() != null) {
            request.params.add(Params.PARAM_DEVICE_ID, config.getDeviceId().id);
        }

        return request;
    }

    public static Future<Boolean> sessionBegin(InternalConfig config, SessionImpl session) {
        Request request = sessionRequest(config, session, "begin_session", 1L);
        return request.isEmpty() ? null : pushAsync(config, request);
    }

    public static Future<Boolean> sessionUpdate(InternalConfig config, SessionImpl session, Long seconds) {
        Request request = sessionRequest(config, session, "session_duration", seconds);
        return request.isEmpty() ? null : pushAsync(config, request);
    }

    public static Future<Boolean> sessionEnd(InternalConfig config, SessionImpl session, Long seconds, String did, Tasks.Callback<Boolean> callback) {
        Request request = sessionRequest(config, session, "end_session", 1L);

        if (did != null && Utils.isNotEqual(did, request.params.get(Params.PARAM_DEVICE_ID))) {
            request.params.remove(Params.PARAM_DEVICE_ID);
            request.params.add(Params.PARAM_DEVICE_ID, did);
        }

        if (seconds != null && seconds > 0 && SDKCore.enabled(CoreFeature.Sessions)) {
            request.params.add("session_duration", seconds);
        }

        if (request.isEmpty()) {
            if (callback != null) {
                try {
                    callback.call(false);
                } catch (Throwable t) {
                    config.getLogger().e("Shouldn't happen " + t);
                }
            }
            return null;
        } else {
            return pushAsync(config, request, false, callback);
        }
    }

    public static Future<Boolean> location(InternalConfig config, double latitude, double longitude) {
        if (!SDKCore.enabled(CoreFeature.Location)) {
            return null;
        }

        Request request = sessionRequest(config, null, null, null);
        request.params.add("location", latitude + "," + longitude);
        return pushAsync(config, request);
    }

    public static Future<Boolean> changeId(InternalConfig config, String oldId) {
        // TODO
        return null;
    }

    public static Request nonSessionRequest(InternalConfig config) {
        return sessionRequest(config, null, null, null);
    }

    public static Request nonSessionRequest(InternalConfig config, Long timestamp) {
        return new Request(timestamp);
    }

    public static void injectParams(InternalConfig config, ParamsInjector injector) {
        SessionImpl session = SDKCore.instance.getSession();
        if (session == null) {
            Request request = nonSessionRequest(config);
            injector.call(request.params);
            pushAsync(config, request);
        } else {
            injector.call(session.params);
        }
    }

    static void addRequiredTimeParametersToParams(Params params) {
        TimeUtils.Instant instant = TimeUtils.getCurrentInstantUnique();
        params.add("timestamp", instant.timestamp)
            .add("tz", instant.tz)
            .add("hour", instant.hour)
            .add("dow", instant.dow);
    }

    static void addRequiredParametersToParams(InternalConfig config, Params params) {
        Map<String, String> map = params.map();
        if (map.isEmpty() || (map.size() == 1 && map.containsKey(Params.PARAM_DEVICE_ID))) {
            //if nothing was in the request, no need to add these mandatory fields
            return;
        }

        //check if it has the device ID
        if (!params.has(Params.PARAM_DEVICE_ID)) {
            if (config.getDeviceId() == null) {
                //no ID possible, no reason to send a request that is not tied to a user, return null
                return;
            } else {
                //ID possible, add it to the request
                params.add(Params.PARAM_DEVICE_ID, config.getDeviceId().id);
            }
        }

        //add app key if needed
        if (!params.has("app_key")) {
            params.add("app_key", config.getServerAppKey());
        }

        //add other missing fields
        if (!params.has("sdk_name")) {
            params.add("sdk_name", config.getSdkName())
                .add("sdk_version", config.getSdkVersion());
        }

        if (!Utils.isEmptyOrNull(config.getApplicationVersion())) {
            params.add("av", config.getApplicationVersion());
        }
    }

    public static Params prepareRequiredParams(InternalConfig config) {
        Params params = new Params();

        addRequiredTimeParametersToParams(params);
        addRequiredParametersToParams(config, params);

        return params;
    }

    public static String prepareRequiredParamsAsString(InternalConfig config, Object... paramsObj) {
        return prepareRequiredParams(config).add(paramsObj).toString();
    }

    /**
     * Common store-request logic: store & send a ping to the service.
     *
     * @param config InternalConfig to run in
     * @param request Request to store
     * @return {@link Future} which resolves to {@code} true if stored successfully, false otherwise
     */
    public static Future<Boolean> pushAsync(InternalConfig config, Request request) {
        return pushAsync(config, request, false, null);
    }

    /**
     * Common store-request logic: store & send a ping to the service.
     *
     * @param config InternalConfig to run in
     * @param request Request to store
     * @param noControl do not check empty validity of the request
     * @param callback Callback (nullable) to call when storing is done, called in {@link Storage} {@link Thread}
     * @return {@link Future} which resolves to {@code} true if stored successfully, false otherwise
     */
    public static Future<Boolean> pushAsync(final InternalConfig config, final Request request, final boolean noControl, final Tasks.Callback<Boolean> callback) {
        config.getLogger().d("New request " + request.storageId() + ": " + request);

        if (!noControl && request.isEmpty()) {
            if (callback != null) {
                try {
                    callback.call(null);
                } catch (Exception e) {
                    config.getLogger().e("[ModuleRequests] Exception in a callback " + e);
                }
            }
            return null;
        }

        addRequiredTimeParametersToParams(request.params);
        addRequiredParametersToParams(config, request.params);

        return Storage.pushAsync(config, request, param -> {
            SDKCore.instance.onRequest(config, request);
            if (callback != null) {
                callback.call(param);
            }
        });
    }
}
