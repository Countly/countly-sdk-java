package ly.count.sdk.java.internal;

import ly.count.sdk.java.Config;

public class ModuleSessions extends ModuleBase {
    /**
     * Current Session instance
     */
    private SessionImpl session = null;
    private TimedEvents timedEvents;

    public SessionImpl getSession() {
        return session;
    }

    public synchronized SessionImpl session(InternalConfig config, Long id) {
        if (session == null) {
            session = new SessionImpl(config, id);
        }
        return session;
    }

    public void forgetSession() {
        session = null;
    }

    @Override
    public void initFinished(final InternalConfig config) {
        try {
            timedEvents = Storage.read(config, new TimedEvents(L));
            if (timedEvents == null) {
                timedEvents = new TimedEvents(L);
            }
        } catch (Throwable e) {
            L.e("[ModuleSessions] initFinished,  Cannot happen" + e);
            timedEvents = new TimedEvents(L);
        }
    }

    @Override
    protected void onTimer() {
        if (!internalConfig.isBackendModeEnabled() && isActive() && getSession() != null) {
            L.i("[ModuleSessions] onTimer, updating session");
            getSession().update();
        }
    }

    @Override
    public void stop(InternalConfig config, boolean clear) {
        if (!clear) {
            Storage.pushAsync(config, timedEvents);
        }
        timedEvents = null;

        if (clear) {
            config.sdk.sdkStorage.storablePurge(config, SessionImpl.getStoragePrefix());
        }
    }

    /**
     * Handles one single case of device id change with auto sessions handling, see first {@code if} here:
     *
     * @see ModuleDeviceIdCore#onDeviceId(InternalConfig, Config.DID, Config.DID)
     */
    public void onDeviceId(final InternalConfig config, final Config.DID deviceId, final Config.DID oldDeviceId) {
        L.d("[ModuleSessions] onDeviceId " + deviceId + ", old " + oldDeviceId);
        if (deviceId != null && oldDeviceId != null && deviceId.realm == Config.DID.REALM_DID && !deviceId.equals(oldDeviceId) && getSession() == null) {
            session(config, null).begin();
        }
    }

    public TimedEvents timedEvents() {
        return timedEvents;
    }
}
