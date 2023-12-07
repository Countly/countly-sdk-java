package ly.count.sdk.java.internal;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ly.count.sdk.java.Config;

/**
 * Internal to Countly SDK configuration class. Can and should contain options hidden from outside.
 * Only members of {@link InternalConfig} can be changed, members of {@link Config} are non-modifiable.
 */
public class InternalConfig extends Config {

    /**
     * Whether to use default networking, meaning networking in the same process with SDK
     */
    private boolean defaultNetworking = true;

    /**
     * {@link DID} instances generated from Countly SDK (currently maximum 2: Countly device id + FCM).
     * Stored to be able to refresh them.
     */
    private final List<DID> dids = new ArrayList<>();

    ImmediateRequestGenerator immediateRequestGenerator = null;
    public SDKCore sdk;
    public StorageProvider storageProvider;

    /**
     * Shouldn't be used!
     */
    public InternalConfig(String url, String appKey) throws IllegalArgumentException {
        super(url, appKey);
        throw new IllegalStateException("InternalConfig(url, appKey) should not be used");
    }

    public InternalConfig() throws IllegalArgumentException {
        super("http://count.ly", "not a key");
    }

    public InternalConfig(final Config config) throws IllegalArgumentException {
        super(config.getServerURL().toString(), config.getServerAppKey());
        setFrom(config);
    }

    public void setFrom(Config config) {
        List<Field> priva = Utils.reflectiveGetDeclaredFields(getClass(), false);
        List<Field> local = Utils.reflectiveGetDeclaredFields(getClass(), true);
        List<Field> remot = Utils.reflectiveGetDeclaredFields(config.getClass(), true);

        for (Field r : remot) {
            if (priva.contains(r)) {
                continue;
            }
            for (Field l : local) {
                if (r.getName().equals(l.getName())) {
                    try {
                        r.setAccessible(true);
                        if (l.isAccessible()) {
                            l.set(this, r.get(config));
                        } else {
                            l.setAccessible(true);
                            l.set(this, r.get(config));
                            l.setAccessible(false);
                        }
                    } catch (IllegalAccessException | IllegalArgumentException iae) {
                        if (configLog != null) {
                            configLog.e("[InternalConfig] Cannot access field " + r.getName() + " " + iae);
                        }
                    }
                }
            }
        }
    }

    protected String getSDKNameInternal() {
        return sdkName;
    }

    public static String getStoragePrefix() {
        return "config";
    }

    public static Long storageId() {
        return 0L;
    }

    public DID getDeviceId() {
        if (!dids.isEmpty()) {
            return dids.get(0);
        } else {
            return null;
        }
    }

    public DID setDeviceId(DID id) {
        if (id == null) {
            if (configLog != null) {
                configLog.e("DID cannot be null");
            }
        }
        DID old = getDeviceId();
        if (old != null) {
            dids.remove(old);
        }
        dids.add(id);
        return old;
    }

    public boolean removeDeviceId(DID did) {
        return this.dids.remove(did);
    }

    public boolean removeDeviceId(String id) {
        for (DID did : dids) {
            if (did.id.equals(id)) {
                return this.dids.remove(did);
            }
        }
        return false;
    }

    public int getFeatures1() {
        return features;
    }

    public Set<Integer> getModuleOverrides() {
        return moduleOverrides == null ? new HashSet<Integer>() : moduleOverrides.keySet();
    }

    public boolean isDefaultNetworking() {
        return defaultNetworking;
    }

    public void setDefaultNetworking(boolean defaultNetworking) {
        this.defaultNetworking = defaultNetworking;
    }

    /**
     * This feature is not yet implemented
     * and always return true
     *
     * @return true
     */
    public boolean getNetworkingEnabled() {
        return true;
    }

    /**
     * This feature is not yet implemented
     * If someday we decide to support temporary device ID mode
     *
     * @return false
     */
    public boolean isTemporaryIdEnabled() {
        return false;
    }

    //region remote config
    public boolean isRemoteConfigAutomaticDownloadTriggersEnabled() {
        return enableRemoteConfigAutomaticDownloadTriggers;
    }

    public boolean isRemoteConfigValueCachingEnabled() {
        return enableRemoteConfigValueCaching;
    }

    public boolean isAutoEnrollFlagEnabled() {
        return enableAutoEnrollFlag;
    }

    public List<RCDownloadCallback> getRemoteConfigGlobalCallbackList() {
        return remoteConfigGlobalCallbacks;
    }
    //endregion

    public Map<String, String> getMetricOverride() {
        return metricOverride;
    }

    public void setLogger(Log logger) {
        this.configLog = logger;
    }

    File getSdkStorageRootDirectory() {
        return sdkStorageRootDirectory;
    }

    Log getLogger() {
        return configLog;
    }

    public boolean isAutomaticViewTrackingEnabled() {
        return enableAutomaticViewTracking;
    }

    public boolean isAutoTrackingUseShortNameEnabled() {
        return autoTrackingUseShortName;
    }

    String[] getLocationParams() {
        return new String[] { country, city, location, ip };
    }

    boolean isLocationDisabled() {
        return !locationEnabled;
    }
}
