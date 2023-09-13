package ly.count.sdk.java.internal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ly.count.sdk.java.Config;

/**
 * Internal to Countly SDK configuration class. Can and should contain options hidden from outside.
 * Only members of {@link InternalConfig} can be changed, members of {@link Config} are non-modifiable.
 */
public final class InternalConfig extends Config implements Storable {
    // private static final Log.Module L = Log.module("InternalConfig");

    /**
     * Running in limited mode, started by itself rather than by developer
     */
    private boolean limited = false;

    /**
     * Whether to use default networking, meaning networking in the same process with SDK
     */
    private boolean defaultNetworking = true;

    /**
     * {@link DID} instances generated from Countly SDK (currently maximum 2: Countly device id + FCM).
     * Stored to be able to refresh them.
     */
    private List<DID> dids = new ArrayList<>();

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

    public InternalConfig setLimited(boolean limited) {
        this.limited = limited;
        return this;
    }

    public boolean isLimited() {
        return limited;
    }

    @Override
    public Long storageId() {
        return 0L;
    }

    @Override
    public String storagePrefix() {
        return getStoragePrefix();
    }

    @Override
    public void setId(Long id) {
        //do nothing
    }

    public static String getStoragePrefix() {
        return "config";
    }

    @Override
    public byte[] store(Log L) {
        ByteArrayOutputStream bytes = null;
        ObjectOutputStream stream = null;
        try {
            bytes = new ByteArrayOutputStream();
            stream = new ObjectOutputStream(bytes);
            stream.writeUTF(serverURL.toString());
            stream.writeUTF(serverAppKey);
            stream.writeInt(features);
            stream.writeUTF("Countly");//for keeping backwards compatibility, remove in the future
            stream.writeInt(loggingLevel.getLevel());
            stream.writeUTF(sdkName);
            stream.writeUTF(sdkVersion);
            stream.writeObject("name");
            stream.writeObject(applicationVersion);
            stream.writeBoolean(usePOST);
            stream.writeObject(salt);
            stream.writeInt(networkConnectionTimeout);
            stream.writeInt(networkReadTimeout);
            stream.writeInt(publicKeyPins == null ? 0 : publicKeyPins.size());
            if (publicKeyPins != null) {
                for (String key : publicKeyPins) {
                    stream.writeUTF(key);
                }
            }
            stream.writeInt(certificatePins == null ? 0 : certificatePins.size());
            if (certificatePins != null) {
                for (String key : certificatePins) {
                    stream.writeUTF(key);
                }
            }
            stream.writeInt(sendUpdateEachSeconds);
            stream.writeInt(eventsBufferSize);
            stream.writeInt(0);//for keeping backwards compatibility, remove in the future. sessionCooldownPeriod
            stream.writeBoolean(false);//for keeping backwards compatibility, remove in the future
            stream.writeInt(5);//for keeping backwards compatibility, remove in the future (crashReportingANRCheckingPeriod)
            stream.writeObject(crashProcessorClass);
            stream.writeInt(moduleOverrides == null ? 0 : moduleOverrides.size());
            if (moduleOverrides != null && moduleOverrides.size() > 0) {
                for (Integer feature : moduleOverrides.keySet()) {
                    stream.writeInt(feature);
                    stream.writeUTF(moduleOverrides.get(feature).getName());
                }
            }
            stream.writeInt(dids.size());
            for (DID did : dids) {
                byte[] b = did.store(L);
                if (b != null) {
                    stream.writeInt(b.length);
                    stream.write(b);
                }
            }
            stream.close();
            return bytes.toByteArray();
        } catch (IOException e) {
            if (L != null) {
                L.e("[InternalCore][Store] Cannot serialize config " + e);
            }
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException e) {
                    if (L != null) {
                        L.e("[InternalCore][Store] Cannot happen " + e);
                    }
                }
            }
            if (bytes != null) {
                try {
                    bytes.close();
                } catch (IOException e) {
                    if (L != null) {
                        L.e("[InternalCore][Store] Cannot happen " + e);
                    }
                }
            }
        }
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean restore(byte[] data, Log L) {
        ByteArrayInputStream bytes = null;
        ObjectInputStream stream = null;

        try {
            bytes = new ByteArrayInputStream(data);
            stream = new ObjectInputStream(bytes);

            try {
                this.serverURL = new URL(stream.readUTF());
                this.serverAppKey = stream.readUTF();
            } catch (Exception e) {
                if (L != null) {
                    L.e("[InternalCore][Restore] Cannot happen " + e);
                }
            }

            features = stream.readInt();
            String throwawayCountlyLoggingTag = stream.readUTF();//we are only reading this for backwards compatibility. Throw away in the future
            int l = stream.readInt();
            for (LoggingLevel level : LoggingLevel.values()) {
                if (level.getLevel() == l) {
                    loggingLevel = level;
                    break;
                }
            }

            sdkName = stream.readUTF();
            sdkVersion = stream.readUTF();
            String throwawayApplicationName = (String) stream.readObject();//we are only reading this for backwards compatibility. Throw away in the future
            applicationVersion = (String) stream.readObject();
            usePOST = stream.readBoolean();
            salt = (String) stream.readObject();
            networkConnectionTimeout = stream.readInt();
            networkReadTimeout = stream.readInt();
            l = stream.readInt();
            publicKeyPins = l == 0 ? null : new HashSet<String>();
            for (int i = 0; i < l; i++) {
                publicKeyPins.add(stream.readUTF());
            }
            l = stream.readInt();
            certificatePins = l == 0 ? null : new HashSet<String>();
            for (int i = 0; i < l; i++) {
                certificatePins.add(stream.readUTF());
            }
            sendUpdateEachSeconds = stream.readInt();
            eventsBufferSize = stream.readInt();
            int throwawaySessionCooldownPeriod = stream.readInt();//we are only reading this for backwards compatibility. Throw away in the future
            boolean throwawayCountlyTestMode = stream.readBoolean();//we are only reading this for backwards compatibility. Throw away in the future
            int throwawayCrashReportingANRCheckingPeriod = stream.readInt();//we are only reading this for backwards compatibility. Throw away in the future. crashReportingANRCheckingPeriod
            crashProcessorClass = (String) stream.readObject();

            l = stream.readInt();
            if (l > 0) {
                moduleOverrides = new HashMap<>();
                while (l-- > 0) {
                    int f = stream.readInt();
                    String cls = stream.readUTF();
                    try {
                        moduleOverrides.put(f, (Class<? extends ModuleBase>) Class.forName(cls));
                    } catch (Throwable t) {
                        if (L != null) {
                            L.e("[InternalCore][Restore] Cannot get class " + cls + " for feature override " + f);
                        }
                    }
                }
            }

            dids.clear();
            l = stream.readInt();
            while (l-- > 0) {
                DID did = new DID(DID.REALM_DID, DID.STRATEGY_UUID, null);
                byte[] b = new byte[stream.readInt()];
                stream.readFully(b);
                did.restore(b, configLog);
                dids.add(did);
            }

            return true;
        } catch (IOException | ClassNotFoundException e) {
            if (L != null) {
                L.e("[InternalCore][Restore] Cannot deserialize config " + e);
            }
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException e) {
                    if (L != null) {
                        L.e("[InternalCore][Restore] Cannot happen " + e);
                    }
                }
            }
            if (bytes != null) {
                try {
                    bytes.close();
                } catch (IOException e) {
                    if (L != null) {
                        L.e("[InternalCore][Restore] Cannot happen " + e);
                    }
                }
            }
        }

        return false;
    }

    public DID getDeviceId() {
        return getDeviceId(DID.REALM_DID);
    }

    public DID getDeviceId(int realm) {
        for (DID DID : dids) {
            if (DID.realm == realm) {
                return DID;
            }
        }
        return null;
    }

    public DID setDeviceId(DID id) {
        if (id == null) {
            if (configLog != null) {
                configLog.e("DID cannot be null");
            }
        }
        DID old = null;
        for (DID did : dids) {
            if (did.realm == id.realm) {
                old = did;
            }
        }
        if (old != null) {
            dids.remove(old);
        }
        dids.add(id);
        return old;
    }

    public boolean removeDeviceId(DID did) {
        return this.dids.remove(did);
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

    //region rating module
    public long getRatingWidgetTimeout() {
        return ratingWidgetTimeout;
    }

    public Integer getStarRatingSessionLimit() {
        return starRatingSessionLimit;
    }

    public String getStarRatingTextTitle() {
        return starRatingTextTitle;
    }

    public String getStarRatingTextMessage() {
        return starRatingTextMessage;
    }

    public String getStarRatingTextDismiss() {
        return starRatingTextDismiss;
    }

    public Boolean getAutomaticStarRatingShouldBeShown() {
        return automaticStarRatingShouldBeShown;
    }

    public Boolean getStarRatingDialogIsCancelable() {
        return starRatingIsDialogCancelable;
    }

    public Boolean getStarRatingDisabledForNewVersion() {
        return starRatingDisabledAutomaticForNewVersions;
    }

    //endregion

    //region remote config
    public Boolean getRemoteConfigAutomaticUpdateEnabled() {
        return enableAutomaticRemoteConfig;
    }

    public Long getRemoteConfigUpdateTimeoutLength() {
        return remoteConfigUpdateRequestTimeout;
    }
    //endregion

    public Map<String, String> getMetricOverride() {
        return metricOverride;
    }
}
