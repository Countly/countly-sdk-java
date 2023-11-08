package ly.count.sdk.java.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;

/**
 * Main device id manipulation class.
 * Contract:
 * <ul>
 *     <li>Must be device id strategy agnostic.</li>
 *     <li>Must give developer an ability to override any id.</li>
 *     <li>Must be able to manage different branches of ids: at least Countly device id & push device token.</li>
 * </ul>
 */
public class ModuleDeviceIdCore extends ModuleBase {

    /**
     * Tasks instance for async execution
     */
    private Tasks tasks;

    private static final class UUIDGenerator implements DeviceIdGenerator {

        private final static String DEVICE_ID_PREFIX = "CLY_";

        @Override
        public String generate(InternalConfig config) {
            return DEVICE_ID_PREFIX + UUID.randomUUID();
        }
    }

    private static final class CustomIDGenerator implements DeviceIdGenerator {
        @Override
        public String generate(InternalConfig config) {
            String customId = config.getCustomDeviceId();
            if (customId == null || customId.isEmpty()) {
                config.getLogger().e("[ModuleDeviceIdCore] Device ID should never be empty or null for CustomIDGenerator");
            }

            return customId;
        }
    }

    private static final Map<Integer, DeviceIdGenerator> generators = new HashMap<>();

    protected DeviceId deviceIdInterface;

    @Override
    public void init(InternalConfig config) throws IllegalArgumentException {
        super.init(config);

        generators.put(Config.DID.STRATEGY_UUID, new UUIDGenerator());
        generators.put(Config.DID.STRATEGY_CUSTOM, new CustomIDGenerator());

        DeviceIdGenerator generator = generators.get(config.getDeviceIdStrategy());
        if (generator == null) {
            L.e("[ModuleDeviceIdCore] Device id strategy [" + config.getDeviceIdStrategy() + "] is not supported by SDK.");
        }
        deviceIdInterface = new DeviceId();
    }

    /**
     * Regular logic of acquiring id of specified strategy and migration from legacy SDK.
     *
     * @param config InternalConfig
     */
    @Override
    public void initFinished(final InternalConfig config) {
        L.i("[ModuleDeviceIdCore] initFinished, Starting device ID acquisition");
        if (config.getDeviceId() == null) {
            // either fresh install, or migration from legacy SDK
            L.i("[ModuleDeviceIdCore] initFinished, Acquiring device id");

            Config.DID did;
            if (Utils.isNotEmpty(config.getCustomDeviceId())) {
                // developer specified id on SDK init
                did = new Config.DID(Config.DID.STRATEGY_CUSTOM, config.getCustomDeviceId());
                L.d("[ModuleDeviceIdCore] initFinished, Got developer id [" + did + "]");
            } else {
                // regular flow - acquire id using specified strategy
                did = acquireId(config);
            }

            config.setDeviceId(did);
            config.storageProvider.setDeviceID(did.id);
            config.storageProvider.setDeviceIdType(DeviceIdType.fromInt(did.strategy, L).name());
        } else {
            Config.DID loadedDid = config.getDeviceId();
            L.d("[ModuleDeviceIdCore] initFinished, Loading previously saved device id:[" + loadedDid.id + "] strategy:[" + loadedDid.strategy + "]");
        }
    }

    @Override
    public void deviceIdChanged(String oldDeviceId, boolean withMerge) {
        L.d("[ModuleDeviceIdCore] deviceIdChanged, oldDeviceId [" + oldDeviceId + "] withMerge [" + withMerge + "]");
        Config.DID deviceId = internalConfig.getDeviceId();
        SessionImpl session = SDKCore.instance.getSession();

        if (deviceId != null && oldDeviceId != null && !deviceId.id.equals(oldDeviceId)) {
            // device id changed
            if (session != null && session.isActive()) {
                // end previous session
                L.d("[ModuleDeviceIdCore] deviceIdChanged, Ending session because device id was changed from [" + oldDeviceId + "]");
                session.end(null, null, oldDeviceId);
            }

            // add device id change request
            Request request = ModuleRequests.nonSessionRequest(internalConfig);

            //if we are missing the device ID, add it
            if (!request.params.has(Params.PARAM_DEVICE_ID)) {
                request.params.add(Params.PARAM_DEVICE_ID, deviceId.id);
            }
            //add the old device ID every time
            request.params.add(Params.PARAM_OLD_DEVICE_ID, oldDeviceId);

            ModuleRequests.pushAsync(internalConfig, request);

            sendDIDSignal(internalConfig, deviceId);
        } else if (deviceId == null && oldDeviceId != null) {
            // device id is unset
            if (session != null) {
                L.d("[ModuleDeviceIdCore] deviceIdChanged, Ending session because device id was unset from [" + oldDeviceId + "]");
                session.end(null, null, oldDeviceId);
            }

            sendDIDSignal(internalConfig, null);
        } else if (deviceId != null && oldDeviceId == null) {
            // device id just acquired
            if (this.tasks == null) {
                this.tasks = new Tasks("deviceId", L);
            }
            tasks.run(new Tasks.Task<Object>(0L) {
                @Override
                public Object call() {
                    // put device_id parameter into existing requests
                    L.i("[ModuleDeviceIdCore] deviceIdChanged, Adding device_id to previous requests");
                    boolean success = transformRequests(internalConfig, deviceId.id);
                    if (success) {
                        L.i("[ModuleDeviceIdCore] deviceIdChanged, First transform: success");
                    } else {
                        L.w("[ModuleDeviceIdCore] deviceIdChanged, First transform: failure");
                    }

                    // do it second time in case new requests were added during first attempt
                    success = transformRequests(internalConfig, deviceId.id);
                    if (!success) {
                        L.e("[ModuleDeviceIdCore] deviceIdChanged, Failed to put device_id into existing requests, following behaviour for unhandled requests is undefined.");
                    } else {
                        L.i("[ModuleDeviceIdCore] deviceIdChanged, Second transform: success");
                    }
                    sendDIDSignal(internalConfig, deviceId);
                    return null;
                }
            });
        }
    }

    /**
     * Puts {@code "device_id"} parameter into all requests which don't have it yet
     *
     * @param config InternalConfig to run in
     * @param deviceId deviceId string
     * @return {@code true} if {@link Request}s changed successfully, {@code false} otherwise
     */
    private boolean transformRequests(final InternalConfig config, final String deviceId) {
        return Storage.transform(config, Request.getStoragePrefix(), (id, data) -> {
            Request request = new Request(id);
            if (request.restore(data, L) && !request.params.has(Params.PARAM_DEVICE_ID)) {
                request.params.add(Params.PARAM_DEVICE_ID, deviceId);
                return request.store(L);
            }
            return null;
        });
    }

    /**
     * Just a wrapper around {@link SDKCore#onSignal(InternalConfig, int)}} for {@link SDKCore.Signal#DID} case
     *
     * @param config InternalConfig to run in
     * @param id new {@link Config.DID} if any
     */
    private void sendDIDSignal(InternalConfig config, Config.DID id) {
        L.d("[ModuleDeviceIdCore] Sending device id signal: [" + id + "]");
        SDKCore.instance.onSignal(config, SDKCore.Signal.DID.getIndex());
    }

    /**
     * Logging into app-specific account:
     * - reset device id and notify modules;
     * - send corresponding request to server.
     *
     * @param config InternalConfig to run in
     * @param id device id to change to
     */
    public void login(InternalConfig config, String id) {
        if (Utils.isEmptyOrNull(id)) {
            L.e("[ModuleDeviceIdCore] login, Empty id passed to login method");
        } else {
            final Config.DID old = config.getDeviceId();
            if (id.equals(old.id)) {
                L.w("[ModuleDeviceIdCore] login, Same id passed to login method, ignoring");
                return;
            }
            config.setDeviceId(new Config.DID(Config.DID.STRATEGY_CUSTOM, id));
            config.storageProvider.setDeviceIdType(DeviceIdType.DEVELOPER_SUPPLIED.name());
            config.storageProvider.setDeviceID(id);

            // old session end & new session begin requests are supposed to happen here
            SDKCore.instance.notifyModulesDeviceIdChanged(old.id, true);
        }
    }

    /**
     * Logging out from app-specific account and reverting back to previously used id if any:
     * - nullify device id and notify modules;
     * - send corresponding request to server.
     *
     * @param config context to run in
     */
    public void logout(final InternalConfig config) {
        final Config.DID old = config.getDeviceId();
        config.removeDeviceId(old);
        SDKCore.instance.notifyModulesDeviceIdChanged(old.id, false);

        Config.DID newId = acquireId(config);
        config.setDeviceId(newId);
        config.storageProvider.setDeviceID(newId.id);
        config.storageProvider.setDeviceIdType(DeviceIdType.fromInt(newId.strategy, L).name());
        SDKCore.instance.notifyModulesDeviceIdChanged(null, false);
    }

    /**
     * Resetting id without merging profiles on server:
     * <ul>
     *     <li>End current session if any</li>
     *     <li>Begin new session with new id if previously ended a session</li>
     * </ul>
     *
     * @param config context to run in
     * @param id new user id
     */
    protected void changeDeviceIdInternal(InternalConfig config, String id, boolean withMerge) {
        if (Utils.isEmptyOrNull(id)) {
            L.d("[ModuleDeviceIdCore] changeDeviceIdInternal, Empty id passed to changeDeviceId method");
            return;
        }
        final Config.DID old = config.getDeviceId();
        if (old.id.equals(id)) {
            L.d("[ModuleDeviceIdCore] changeDeviceIdInternal, Same id passed to changeDeviceId method, ignoring");
            return;
        }

        internalConfig.storageProvider.setDeviceIdType(DeviceIdType.DEVELOPER_SUPPLIED.name());
        internalConfig.storageProvider.setDeviceID(id);
        if (!withMerge) {
            config.removeDeviceId(old);
            SDKCore.instance.notifyModulesDeviceIdChanged(null, false);
        }
        internalConfig.setDeviceId(new Config.DID(Config.DID.STRATEGY_CUSTOM, id));
        SDKCore.instance.notifyModulesDeviceIdChanged(old.id, withMerge);
    }

    /**
     * Gets id of the strategy supplied. In case strategy is not available, returns a fallback strategy.
     * In case strategy is available but id cannot be acquired right now, returns null.
     *
     * @param config InternalConfig to run in
     */
    protected Config.DID acquireId(final InternalConfig config) {
        L.i("[ModuleDeviceIdCore] acquireId, Acquiring device id of strategy [" + config.getDeviceIdStrategy() + "]");
        Config.DID did = null;

        int index = config.getDeviceIdStrategy();

        while (index >= 0) {
            DeviceIdGenerator generator = generators.get(index);
            if (generator == null) {
                L.w("[ModuleDeviceIdCore] Device id strategy [" + index + "] is not available. Falling back to next one.");
                index--;
            } else {
                String id = generator.generate(config);
                if (Utils.isNotEmpty(id)) {
                    did = new Config.DID(index, id);
                    break;
                } else {
                    L.w("[ModuleDeviceIdCore] Device id strategy [" + index + "] didn't return. Falling back to next one.");
                    index--;
                }
            }
        }

        if (did != null) {
            if (did.strategy == Config.DID.STRATEGY_UUID) {
                L.i("[ModuleDeviceIdCore] acquireId, no custom device id. SDK has generated a random device id.");
            }
            L.d("[ModuleDeviceIdCore] acquireId, Got device id: " + did);
        } else {
            L.i("[ModuleDeviceIdCore] acquireId, No device id of strategy [" + config.getDeviceIdStrategy() + "] is available yet");
        }

        return did;
    }

    @Override
    public void stop(InternalConfig config, boolean clear) {
        if (tasks != null) {
            tasks.shutdown();
            tasks = null;
        }
        deviceIdInterface = null;
    }

    protected DeviceIdType getTypeInternal() {
        return DeviceIdType.fromInt(internalConfig.getDeviceId().strategy, L);
    }

    protected String getIDInternal() {
        return internalConfig.getDeviceId().id;
    }

    public class DeviceId {

        /**
         * Returns current device id.
         *
         * @return device id string
         */
        public String getID() {
            synchronized (Countly.instance()) {
                L.i("[DeviceId] getID, Getting device id");
                return getIDInternal();
            }
        }

        /**
         * Returns current device id type.
         *
         * @return device id type
         */
        public DeviceIdType getType() {
            synchronized (Countly.instance()) {
                L.i("[DeviceId] getType, Getting device id type");
                return getTypeInternal();
            }
        }

        /**
         * Change device id with merging profiles on server, just set device id to new one.
         *
         * @param id new device id string, cannot be empty
         */
        public void changeWithMerge(String id) {
            synchronized (Countly.instance()) {
                L.i("[DeviceId] changeWithMerge, Changing device id with merge to [" + id + "]");
                changeDeviceIdInternal(internalConfig, id, true);
            }
        }

        /**
         * Change device id without merging profiles on server, just set device id to new one.
         *
         * @param id new device id string, cannot be empty
         */
        public void changeWithoutMerge(String id) {
            synchronized (Countly.instance()) {
                L.i("[DeviceId] changeWithMerge, Changing device id without merge to [" + id + "]");
                changeDeviceIdInternal(internalConfig, id, false);
            }
        }
    }
}
