package ly.count.sdk.java.internal;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import ly.count.sdk.java.Config;

public class ModuleSessions extends ModuleBase {

    private int activityCount = 0;
    private ScheduledExecutorService executor = null;

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
    public Integer getFeature() {
        return CoreFeature.Sessions.getIndex();
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

            if (ctx.getConfig().getSendUpdateEachSeconds() > 0 && executor == null) {
                executor = Executors.newScheduledThreadPool(1);
                executor.scheduleWithFixedDelay(new Runnable() {
                    @Override
                    public void run() {
                        if (!ctx.getConfig().isBackendModeEnabled() && isActive() && getSession() != null) {
                            L.i("[ModuleSessions] updating session");
                            getSession().update();
                        }
                    }
                }, ctx.getConfig().getSendUpdateEachSeconds(), ctx.getConfig().getSendUpdateEachSeconds(), TimeUnit.SECONDS);
            }
        } catch (Throwable e) {
            L.e("[ModuleSessions] Cannot happen" + e);
            timedEvents = new TimedEvents(L);
        }
    }

    @Override
    public boolean isActive() {
        return super.isActive() || executor != null;
    }

    @Override
    public void stop(CtxCore ctx, boolean clear) {
        if (!clear) {
            Storage.pushAsync(ctx, timedEvents);
        }
        timedEvents = null;

        if (executor != null) {
            try {
                executor.shutdown();
                if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                        L.e("[ModuleSessions] Sessions update thread must be locked");
                    }
                }
            } catch (Throwable t) {
                L.e("[ModuleSessions] Error while stopping session update thread " + t);
            }
            executor = null;
        }

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

    @Override
    public synchronized void onActivityStarted(CtxCore ctx) {
        if (ctx.getConfig().isAutoSessionsTrackingEnabled() && activityCount == 0) {
            if (getSession() == null) {
                L.i("[ModuleSessions] starting new session");
                session(ctx, null).begin();
            }
        }
        activityCount++;
    }

    @Override
    public synchronized void onActivityStopped(CtxCore ctx) {
        activityCount--;
        if (activityCount == 0) {
            if (executor == null && ctx.getConfig().isAutoSessionsTrackingEnabled()) {
                executor = Executors.newScheduledThreadPool(1);
            }
            if (executor != null) {
                L.i("[ModuleSessions] stopping session");
                try {
                    executor.schedule(new Runnable() {
                        @Override
                        public void run() {
                            L.i("[ModuleSessions] ending session? activities " + activityCount + " session null? " + (getSession() == null) + " active? " + (getSession() != null && getSession().isActive()));
                            if (activityCount == 0 && getSession() != null && getSession().isActive()) {
                                getSession().end();
                            }
                        }
                    }, ctx.getConfig().getSessionAutoCloseAfter(), TimeUnit.SECONDS);

                    executor.shutdown();

                    if (!executor.awaitTermination(Math.min(31, ctx.getConfig().getSessionAutoCloseAfter() + 1), TimeUnit.SECONDS)) {
                        executor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    L.w("[ModuleSessions] Interrupted while waiting for session update executor to stop" + e);
                    executor.shutdownNow();
                }
                executor = null;
            }
        }
    }

    public TimedEvents timedEvents() {
        return timedEvents;
    }
}
