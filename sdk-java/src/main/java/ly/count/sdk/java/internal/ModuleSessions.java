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

    public synchronized SessionImpl session(CtxCore ctx, Long id) {
        if (session == null) {
            session = new SessionImpl(ctx, id);
        }
        return session;
    }

    public void forgetSession() {
        session = null;
    }

    @Override
    public void init(InternalConfig config, Log logger) throws IllegalStateException {
        super.init(config, logger);
    }

    @Override
    public void onContextAcquired(CtxCore ctx) {
        super.onContextAcquired(ctx);

        try {
            timedEvents = Storage.read(ctx, new TimedEvents(L));
            if (timedEvents == null) {
                timedEvents = new TimedEvents(L);
            }
        } catch (Throwable e) {
            L.e("[ModuleSessions] Cannot happen" + e);
            timedEvents = new TimedEvents(L);
        }
    }

    @Override
    protected void onTimer() {
        if (!internalConfig.isBackendModeEnabled() && isActive() && getSession() != null) {
            L.i("[ModuleSessions] updating session");
            getSession().update();
        }
    }

    @Override
    public void stop(CtxCore ctx, boolean clear) {
        if (!clear) {
            Storage.pushAsync(ctx, timedEvents);
        }
        timedEvents = null;

        if (clear) {
            ctx.getSDK().sdkStorage.storablePurge(ctx, SessionImpl.getStoragePrefix());
        }
    }

    /**
     * Handles one single case of device id change with auto sessions handling, see first {@code if} here:
     *
     * @see ModuleDeviceIdCore#onDeviceId(CtxCore, Config.DID, Config.DID)
     */
    public void onDeviceId(final CtxCore ctx, final Config.DID deviceId, final Config.DID oldDeviceId) {
        L.d("[ModuleSessions] onDeviceId " + deviceId + ", old " + oldDeviceId);
        if (deviceId != null && oldDeviceId != null && deviceId.realm == Config.DID.REALM_DID && !deviceId.equals(oldDeviceId) && getSession() == null) {
            session(ctx, null).begin();
        }
    }

    public TimedEvents timedEvents() {
        return timedEvents;
    }
}
