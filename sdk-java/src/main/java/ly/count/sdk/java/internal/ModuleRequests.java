package ly.count.sdk.java.internal;

import java.util.concurrent.Future;

import ly.count.sdk.java.Config;
import ly.count.sdk.java.User;

/**
 * Centralized place for all requests construction & handling.
 */

public class ModuleRequests extends ModuleBase {

    private static Params metrics;

    public interface ParamsInjector {
        void call(Params params);
    }

    @Override
    public void init(InternalConfig config, Log logger) {
        super.init(config, logger);
    }

    @Override
    public void onContextAcquired(CtxCore ctx) {
        super.onContextAcquired(ctx);
        ModuleRequests.metrics = DeviceCore.dev.buildMetrics(ctx);
    }

    private static Request sessionRequest(CtxCore ctx, SessionImpl session, String type, Long value) {
        Request request = Request.build();

        if (session != null && session.hasConsent(Config.Feature.Sessions)) {
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
                if (session.events.size() > 0 && session.hasConsent(Config.Feature.Events)) {
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

        if (ctx.getConfig().getDeviceId() != null) {
            request.params.add(Params.PARAM_DEVICE_ID, ctx.getConfig().getDeviceId().id);
        }

        if (((session != null && session.hasConsent(Config.Feature.Location)) || (session == null && SDKCore.enabled(Config.Feature.Location)))
                && request.params.has("begin_session")) {
            User user = SDKCore.instance.user();
            if (user.country() != null) {
                request.params.add("country_code", user.country());
            }
            if (user.city() != null) {
                request.params.add("city", user.city());
            }
            if (user.location() != null) {
                request.params.add("location", user.location());
            }
        }

        return request;
    }

    public static Future<Boolean> sessionBegin(CtxCore ctx, SessionImpl session) {
        Request request = sessionRequest(ctx, session, "begin_session", 1L);
        return request.isEmpty() ? null : pushAsync(ctx, request);
    }

    public static Future<Boolean> sessionUpdate(CtxCore ctx, SessionImpl session, Long seconds) {
        Request request = sessionRequest(ctx, session, "session_duration", seconds);
        return request.isEmpty() ? null : pushAsync(ctx, request);
    }

    public static Future<Boolean> sessionEnd(CtxCore ctx, SessionImpl session, Long seconds, String did, Tasks.Callback<Boolean> callback) {
        Request request = sessionRequest(ctx, session, "end_session", 1L);

        if (did != null && Utils.isNotEqual(did, request.params.get(Params.PARAM_DEVICE_ID))) {
            request.params.remove(Params.PARAM_DEVICE_ID);
            request.params.add(Params.PARAM_DEVICE_ID, did);
        }

        if (seconds != null && seconds > 0 && SDKCore.enabled(Config.Feature.Sessions)) {
            request.params.add("session_duration", seconds);
        }

        if (request.isEmpty()) {
            if (callback != null) {
                try {
                    callback.call(false);
                } catch (Throwable t) {
                    System.out.println("[ModuleRequests] Shouldn't happen " + t);
                }
            }
            return null;
        } else {
            return pushAsync(ctx, request, callback);
        }
    }

    public static Future<Boolean> location(CtxCore ctx, double latitude, double longitude) {
        if (!SDKCore.enabled(Config.Feature.Location)) {
            return null;
        }



        Request request = sessionRequest(ctx, null, null, null);
        request.params.add("location", latitude + "," + longitude);
        return pushAsync(ctx, request);
    }

    public static Future<Boolean> changeId(CtxCore ctx, InternalConfig config, CtxCore context, String oldId) {
        // TODO
        return null;
    }

    public static Request nonSessionRequest(CtxCore ctx) {
        return sessionRequest(ctx, null, null, null);
    }

    public static Request nonSessionRequest(CtxCore ctx, Long timestamp) {
        return new Request(timestamp);
    }

    public static void injectParams(CtxCore ctx, ParamsInjector injector) {
        SessionImpl session = SDKCore.instance.getSession();
        if (session == null) {
            Request request = nonSessionRequest(ctx);
            injector.call(request.params);
            pushAsync(ctx, request);
        } else {
            injector.call(session.params);
        }
    }

    static Request addRequired(InternalConfig config, Request request) {

        if (request.isEmpty()) {
            //if nothing was in the request, no need to add these mandatory fields
            return request;
        }

        //check if it has the device ID
        if (!request.params.has(Params.PARAM_DEVICE_ID)) {
            if (config.getDeviceId() == null) {
                //no ID possible, no reason to send a request that is not tied to a user, return null
                return null;
            } else {
                //ID possible, add it to the request
                request.params.add(Params.PARAM_DEVICE_ID, config.getDeviceId().id);
            }
        }

        //add app key if needed
        if(!request.params.has("app_key")){
            request.params.add("app_key", config.getServerAppKey());
        }

        //add other missing fields
        if (!request.params.has("sdk_name")) {
            request.params.add("sdk_name", config.getSdkName())
                    .add("sdk_version", config.getSdkVersion());
        }

        return request;
    }

    public static Request remoteConfigUpdate(CtxCore ctx, String keysInclude, String keysExclude, Class<? extends Module> module){
        Request req = Request.build("method", "fetch_remote_config", "app_key", ctx.getConfig().getServerAppKey());

        if(keysInclude != null){
            req.params.add("keys", keysInclude);
        } else if(keysExclude != null) {
            req.params.add("omit_keys", keysExclude);
        }

       // req.own(module);
        req.endpoint("/o/sdk?");

        return req;
    }

    /**
     * Common store-request logic: store & send a ping to the service.
     *
     * @param ctx Ctx to run in
     * @param request Request to store
     * @return {@link Future} which resolves to {@code} true if stored successfully, false otherwise
     */
    public static Future<Boolean> pushAsync(CtxCore ctx, Request request) {
        return pushAsync(ctx, request, null);
    }

    /**
     * Common store-request logic: store & send a ping to the service.
     *
     * @param ctx Ctx to run in
     * @param request Request to store
     * @param callback Callback (nullable) to call when storing is done, called in {@link Storage} {@link Thread}
     * @return {@link Future} which resolves to {@code} true if stored successfully, false otherwise
     */
    public static Future<Boolean> pushAsync(final CtxCore ctx, final Request request, final Tasks.Callback<Boolean> callback) {
        System.out.println("New request " + request.storageId() + ": " + request);

        if (request.isEmpty()) {
            if (callback != null) {
                try {
                    callback.call(null);
                } catch (Exception e) {
                    System.out.println("[ModuleRequests] Exception in a callback " + e);
                }
            }
            return null;
        }

        request.params.add("timestamp", DeviceCore.dev.uniqueTimestamp())
                .add("tz", DeviceCore.dev.getTimezoneOffset())
                .add("hour", DeviceCore.dev.currentHour())
                .add("dow", DeviceCore.dev.currentDayOfWeek());

        return Storage.pushAsync(ctx, request, new Tasks.Callback<Boolean>() {
            @Override
            public void call(Boolean param) throws Exception {
                SDKCore.instance.onRequest(ctx, request);
                if (callback != null) {
                    callback.call(param);
                }
            }
        });
    }
}
