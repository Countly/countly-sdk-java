package ly.count.sdk.java.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import javax.annotation.Nonnull;

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

        if (!config.getConfigurationProvider().getLocationTrackingEnabled()) {
            config.getLogger().d("[ModuleRequests] location, location tracking is disabled by the SDK behavior settings, the location is not sent");
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
     * Common store-request logic: store & send a ping to the service. While the SDK behavior
     * settings set the request queue size, storing a request drops the oldest ones the queue holds
     * over that size.
     *
     * @param config InternalConfig to run in
     * @param request Request to store
     * @param noControl do not check empty validity of the request
     * @param callback Callback (nullable) to call when storing is done, called in {@link Storage} {@link Thread},
     * or on the calling thread with {@code false} when the request is not stored
     * @return {@link Future} which resolves to {@code} true if stored successfully, false otherwise
     */
    public static Future<Boolean> pushAsync(final InternalConfig config, final Request request, final boolean noControl, final Tasks.Callback<Boolean> callback) {
        config.getLogger().d("New request " + request.storageId() + ": " + request);

        if (!config.getConfigurationProvider().getTrackingEnabled()) {
            config.getLogger().d("[ModuleRequests] pushAsync, tracking disabled by SDK behavior settings; dropping request");
            if (callback != null) {
                try {
                    callback.call(false);
                } catch (Exception e) {
                    config.getLogger().e("[ModuleRequests] Exception in a callback " + e);
                }
            }
            return null;
        }

        if (!noControl && request.isEmpty()) {
            if (callback != null) {
                try {
                    callback.call(false);
                } catch (Exception e) {
                    config.getLogger().e("[ModuleRequests] Exception in a callback " + e);
                }
            }
            return null;
        }

        addRequiredTimeParametersToParams(request.params);
        addRequiredParametersToParams(config, request.params);

        return Storage.pushAsync(config, request, param -> {
            if (Boolean.TRUE.equals(param)) {
                dropOldestRequestsOverQueueLimit(config);
            }
            SDKCore.instance.onRequest(config, request);
            if (callback != null) {
                callback.call(param);
            }
        });
    }

    /**
     * Stores a request as it is, without the checks of {@link #pushAsync(InternalConfig, Request, boolean, Tasks.Callback)},
     * then drops the oldest requests the queue holds over the request queue size of the SDK behavior
     * settings. Waits until both are done.
     *
     * @param config configuration of the running SDK
     * @param request the request, with every parameter it is sent with
     * @return whether the request was stored
     */
    static boolean pushWithinQueueLimit(@Nonnull final InternalConfig config, @Nonnull final Request request) {
        try {
            return Boolean.TRUE.equals(Storage.pushAsync(config, request, stored -> {
                if (Boolean.TRUE.equals(stored)) {
                    dropOldestRequestsOverQueueLimit(config);
                }
            }).get());
        } catch (InterruptedException | ExecutionException e) {
            config.getLogger().e("[ModuleRequests] pushWithinQueueLimit, failed to store request " + request.storageId() + ", [" + e + "]");
            return false;
        }
    }

    /**
     * Drops the oldest stored requests while the queue holds more than the request queue size of the
     * SDK behavior settings. Without a size from the settings the queue has no limit. Must run on the
     * storage thread, as it works on the stored files directly. The files are removed unread, so
     * their owners are not told; the journey triggers they carried are settled instead.
     *
     * @param config configuration of the running SDK
     * @return how many requests were dropped
     */
    static int dropOldestRequestsOverQueueLimit(@Nonnull InternalConfig config) {
        ConfigurationProvider configProvider = config.getConfigurationProvider();
        if (!configProvider.isRequestQueueMaxSizeFromBehaviorSettings()) {
            return 0;
        }

        List<Long> droppedIds = new ArrayList<>();
        try {
            int maxSize = configProvider.getRequestQueueMaxSize();
            List<Long> requestIds = config.sdk.sdkStorage.storableList(config, Request.getStoragePrefix(), 0);
            int overflow = requestIds.size() - maxSize;
            if (overflow <= 0) {
                return 0;
            }

            Collections.sort(requestIds);
            for (int i = 0; i < overflow; i++) {
                Long requestId = requestIds.get(i);
                if (Boolean.TRUE.equals(config.sdk.sdkStorage.storableRemove(config, new Request(requestId)))) {
                    droppedIds.add(requestId);
                }
            }

            config.getLogger().w("[ModuleRequests] dropOldestRequestsOverQueueLimit, the request queue went over its size of [" + maxSize + "] set by the SDK behavior settings, dropped the [" + droppedIds.size() + "] oldest requests");
        } catch (Exception e) {
            config.getLogger().e("[ModuleRequests] dropOldestRequestsOverQueueLimit, failed to keep the request queue within its size, [" + e + "]");
        }

        settleJourneyTriggersOf(config, droppedIds);
        return droppedIds.size();
    }

    /**
     * Tells the events module which requests were dropped unread, so a journey trigger one of them
     * carried stops waiting for a response. Only a set of IDs is touched, which is safe on the
     * storage thread.
     *
     * @param config configuration of the running SDK
     * @param droppedIds storage IDs of the dropped requests
     */
    private static void settleJourneyTriggersOf(@Nonnull InternalConfig config, @Nonnull List<Long> droppedIds) {
        if (droppedIds.isEmpty() || config.sdk == null) {
            return;
        }

        try {
            ModuleEvents events = config.sdk.module(ModuleEvents.class);
            if (events != null) {
                events.onRequestsDroppedUnread(droppedIds);
            }
        } catch (Exception e) {
            config.getLogger().e("[ModuleRequests] settleJourneyTriggersOf, failed to settle the journey triggers of the dropped requests, [" + e + "]");
        }
    }
}
