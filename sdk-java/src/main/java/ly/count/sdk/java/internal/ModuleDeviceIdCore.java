package ly.count.sdk.java.internal;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    private static final Map<Integer, DeviceIdGenerator> generators = new ConcurrentHashMap<>();
    protected DeviceId deviceIdInterface;

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
                did = acquireId();
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

        if (Utils.isEmptyOrNull(deviceId.id) || Utils.isEmptyOrNull(oldDeviceId)) {
            L.w("[ModuleDeviceIdCore] deviceIdChanged, Empty id passed to changeDeviceId method");
            return;
        }

        //if without merge then we should not send this request
        if (withMerge) {
            // add device id change request and add the old device ID every time
            ModuleRequests.pushAsync(internalConfig, new Request(Params.PARAM_OLD_DEVICE_ID, oldDeviceId));
        }
    }

    /**
     * Logging into app-specific account:
     * - reset device id and notify modules;
     * - send corresponding request to server.
     *
     * @param id device id to change to
     */
    public void login(String id) {
        changeDeviceIdInternal(id, DeviceIdType.DEVELOPER_SUPPLIED, true);
    }

    /**
     * Logging out from app-specific account and reverting back to previously used id if any:
     * - nullify device id and notify modules;
     * - send corresponding request to server.
     */
    public void logout() {
        Config.DID did = acquireId();
        changeDeviceIdInternal(did.id, DeviceIdType.fromInt(did.strategy, L), false);
    }

    /**
     * Resetting id without merging profiles on server:
     * <ul>
     *     <li>End current session if any</li>
     *     <li>Begin new session with new id if previously ended a session</li>
     * </ul>
     *
     * @param id new user id
     */
    protected void changeDeviceIdInternal(String id, DeviceIdType type, boolean withMerge) {
        if (Utils.isEmptyOrNull(id)) {
            L.w("[ModuleDeviceIdCore] changeDeviceId, Empty id passed to changeDeviceId method");
            return;
        }
        final Config.DID old = internalConfig.getDeviceId();
        if (old.id.equals(id)) {
            L.w("[ModuleDeviceIdCore] changeDeviceId, Same id passed to changeDeviceId method, ignoring");
            return;
        }

        internalConfig.storageProvider.setDeviceIdType(type.name());
        internalConfig.storageProvider.setDeviceID(id);
        internalConfig.setDeviceId(new Config.DID(type.index, id));

        SDKCore.instance.notifyModulesDeviceIdChanged(old.id, withMerge);
    }

    /**
     * Gets id of the strategy supplied. In case strategy is not available, returns a fallback strategy.
     * In case strategy is available but id cannot be acquired right now, returns null.
     */
    protected Config.DID acquireId() {
        L.i("[ModuleDeviceIdCore] acquireId, Acquiring device id of strategy [" + internalConfig.getDeviceIdStrategy() + "]");
        Config.DID did = null;

        int index = internalConfig.getDeviceIdStrategy();

        while (index >= 0) {
            DeviceIdGenerator generator = generators.get(index);
            if (generator == null) {
                L.w("[ModuleDeviceIdCore] Device id strategy [" + index + "] is not available. Falling back to next one.");
                index--;
            } else {
                String id = generator.generate(internalConfig);
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
            L.i("[ModuleDeviceIdCore] acquireId, No device id of strategy [" + internalConfig.getDeviceIdStrategy() + "] is available yet");
        }

        return did;
    }

    private void setIDInternal(String newDeviceID) {
        if (Utils.isEmptyOrNull(newDeviceID)) {
            L.w("[ModuleDeviceIdCore] setID, Empty id passed to setID method");
            return;
        }

        if (getIDInternal().equals(newDeviceID)) {
            L.w("[ModuleDeviceIdCore] setID, Same id passed to setID method, ignoring");
            return;
        }

        // if current type is DEVELOPER_SUPPLIED
        // an ID was provided by the host app previously
        // we can assume that a device ID change with merge was executed previously
        // now we change it without merging
        // else
        // SDK generated ID
        // we change device ID with merge so that data is combined
        changeDeviceIdInternal(newDeviceID, DeviceIdType.DEVELOPER_SUPPLIED, !getTypeInternal().equals(DeviceIdType.DEVELOPER_SUPPLIED));
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
                return getIDInternal();
            }
        }

        /**
         * Sets current device id to the new one.
         *
         * @param newDeviceID device id to set
         */
        public void setID(String newDeviceID) {
            synchronized (Countly.instance()) {
                setIDInternal(newDeviceID);
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
                changeDeviceIdInternal(id, DeviceIdType.DEVELOPER_SUPPLIED, true);
            }
        }

        /**
         * Change device id without merging profiles on server, just set device id to new one.
         *
         * @param id new device id string, cannot be empty
         */
        public void changeWithoutMerge(String id) {
            synchronized (Countly.instance()) {
                changeDeviceIdInternal(id, DeviceIdType.DEVELOPER_SUPPLIED, false);
            }
        }
    }
}
