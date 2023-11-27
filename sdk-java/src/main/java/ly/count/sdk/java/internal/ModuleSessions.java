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
        L.v("[ModuleSessions] initFinished");
        timedEvents = new TimedEvents();
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
        timedEvents = null;
    }

    /**
     * Handles one single case of device id change with auto sessions handling, see first {@code if} here:
     *
     * @see ModuleDeviceIdCore#deviceIdChanged(String, boolean)
     */
    @Override
    public void deviceIdChanged(String oldDeviceId, boolean withMerge) {
        Config.DID deviceId = internalConfig.getDeviceId();
        L.d("[ModuleSessions] deviceIdChanged, " + deviceId + ", old " + oldDeviceId);
        if (!withMerge && session != null && session.isActive()) {
            L.d("[ModuleSessions] deviceIdChanged, Ending session because device id was unset from [" + oldDeviceId + "]");
            session.end(null, oldDeviceId);
        }

        if (deviceId != null && oldDeviceId != null && (session == null || !session.isActive()) && !withMerge) {
            session(internalConfig, null).begin();
        }
    }

    public TimedEvents timedEvents() {
        return timedEvents;
    }
}
