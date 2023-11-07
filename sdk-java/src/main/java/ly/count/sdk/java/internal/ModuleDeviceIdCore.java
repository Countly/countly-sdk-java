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

            if (Utils.isNotEmpty(config.getCustomDeviceId())) {
                // developer specified id on SDK init
                Config.DID did = new Config.DID(Config.DID.REALM_DID, Config.DID.STRATEGY_CUSTOM, config.getCustomDeviceId());
                L.d("[ModuleDeviceIdCore] initFinished, Got developer id [" + did + "]");
                SDKCore.instance.onDeviceId(config, did, null);
            } else {
                // regular flow - acquire id using specified strategy
                Config.DID did = new Config.DID(Config.DID.REALM_DID, config.getDeviceIdStrategy(), null);
                acquireId(config, did);
            }
        } else {
            // second or next app launch, notify id is available
            Config.DID loadedDid = config.getDeviceId();
            L.d("[ModuleDeviceIdCore] initFinished, Loading previously saved device id:[" + loadedDid.id + "] realm:[" + loadedDid.realm + "] strategy:[" + loadedDid.strategy + "]");
            SDKCore.instance.onDeviceId(config, loadedDid, loadedDid);
        }
    }

    @Override
    public void deviceIdChanged(Config.DID oldDeviceId, boolean withMerge) {
        L.d("[ModuleDeviceIdCore] deviceIdChanged, oldDeviceId [" + oldDeviceId + "] withMerge [" + withMerge + "]");
        Config.DID deviceId = internalConfig.getDeviceId();
        SessionImpl session = SDKCore.instance.getSession();

        if (deviceId != null && oldDeviceId != null && deviceId.realm == Config.DID.REALM_DID && !deviceId.equals(oldDeviceId)) {
            // device id changed
            if (session != null && session.isActive()) {
                // end previous session
                L.d("[ModuleDeviceIdCore] Ending session because device id was changed from [" + oldDeviceId.id + "]");
                session.end(null, null, oldDeviceId.id);
            }

            // add device id change request
            Request request = ModuleRequests.nonSessionRequest(internalConfig);

            //if we are missing the device ID, add it
            if (!request.params.has(Params.PARAM_DEVICE_ID)) {
                request.params.add(Params.PARAM_DEVICE_ID, deviceId.id);
            }
            //add the old device ID every time
            request.params.add(Params.PARAM_OLD_DEVICE_ID, oldDeviceId.id);

            ModuleRequests.pushAsync(internalConfig, request);

            sendDIDSignal(internalConfig, deviceId, oldDeviceId);
        }
    }

    @Override
    public void onDeviceId(InternalConfig config, final Config.DID deviceId, final Config.DID oldDeviceId) {
        L.d("[ModuleDeviceIdCore] onDeviceId [" + deviceId + "]");

        SessionImpl session = SDKCore.instance.getSession();

        if (deviceId == null && oldDeviceId != null && oldDeviceId.realm == Config.DID.REALM_DID) {
            // device id is unset
            if (session != null) {
                L.d("[ModuleDeviceIdCore] Ending session because device id was unset from [" + oldDeviceId.id + "]");
                session.end(null, null, oldDeviceId.id);
            }

            sendDIDSignal(config, null, oldDeviceId);
        } else if (deviceId != null && oldDeviceId == null && deviceId.realm == Config.DID.REALM_DID) {
            // device id just acquired
            if (this.tasks == null) {
                this.tasks = new Tasks("deviceId", L);
            }
            tasks.run(new Tasks.Task<Object>(0L) {
                @Override
                public Object call() throws Exception {
                    // put device_id parameter into existing requests
                    L.i("[ModuleDeviceIdCore] Adding device_id to previous requests");
                    boolean success = transformRequests(config, deviceId.id);
                    if (success) {
                        L.i("[ModuleDeviceIdCore] First transform: success");
                    } else {
                        L.w("[ModuleDeviceIdCore] First transform: failure");
                    }

                    // do it second time in case new requests were added during first attempt
                    success = transformRequests(config, deviceId.id);
                    if (!success) {
                        L.e("[ModuleDeviceIdCore] Failed to put device_id into existing requests, following behaviour for unhandled requests is undefined.");
                    } else {
                        L.i("[ModuleDeviceIdCore] Second transform: success");
                    }
                    sendDIDSignal(config, deviceId, null);
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
     * Just a wrapper around {@link SDKCore#onSignal(InternalConfig, int, Byteable, Byteable)}} for {@link SDKCore.Signal#DID} case
     *
     * @param config InternalConfig to run in
     * @param id new {@link Config.DID} if any
     * @param old old {@link Config.DID} if any
     */
    private void sendDIDSignal(InternalConfig config, Config.DID id, Config.DID old) {
        L.d("[ModuleDeviceIdCore] Sending device id signal: [" + id + "], was [" + old + "]");
        SDKCore.instance.onSignal(config, SDKCore.Signal.DID.getIndex(), id, old);
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
            L.e("[ModuleDeviceIdCore] Empty id passed to login method");
        } else {
            final Config.DID old = config.getDeviceId();
            config.setDeviceId(new Config.DID(Config.DID.REALM_DID, Config.DID.STRATEGY_CUSTOM, id));
            Storage.push(config, config);

            // old session end & new session begin requests are supposed to happen here
            SDKCore.instance.onDeviceId(config, config.getDeviceId(), old);
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
        Storage.push(config, config);

        SDKCore.instance.onDeviceId(config, null, old);
        acquireId(config, new Config.DID(Config.DID.REALM_DID, config.getDeviceIdStrategy(), null));
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
            L.w("[ModuleDeviceIdCore] changeDeviceId, Empty id passed to changeDeviceId method");
            return;
        }

        final Config.DID old = config.getDeviceId();
        config.setDeviceId(new Config.DID(Config.DID.REALM_DID, Config.DID.STRATEGY_CUSTOM, id));
        Storage.push(config, config);
        SDKCore.instance.deviceIdChanged(old, withMerge);
    }

    /**
     * Gets id of the strategy supplied. In case strategy is not available, returns a fallback strategy.
     * In case strategy is available but id cannot be acquired right now, returns null.
     *
     * @param config InternalConfig to run in
     * @param holder DID object which holds strategy and possibly other info for id generation
     */
    protected void acquireId(final InternalConfig config, final Config.DID holder) {
        L.i("[ModuleDeviceIdCore] acquireId, Acquiring device id of strategy [" + holder.strategy + " / " + holder.realm + "]");
        Config.DID did = null;

        int index = holder.strategy;

        while (index >= 0) {
            DeviceIdGenerator generator = generators.get(index);
            if (generator == null) {
                L.w("[ModuleDeviceIdCore] Device id strategy [" + index + "] is not available. Falling back to next one.");
                index--;
            } else {
                String id = generator.generate(config);
                if (Utils.isNotEmpty(id)) {
                    did = new Config.DID(holder.realm, index, id);
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
            SDKCore.instance.onDeviceId(config, did, null);
        } else {
            L.i("[ModuleDeviceIdCore] acquireId, No device id of strategy [" + config.getDeviceIdStrategy() + "] is available yet");
        }
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
        //todo impl after merge, delete the Countly one
        return null;
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
                changeDeviceIdInternal(internalConfig, id, false);
            }
        }
    }
}
