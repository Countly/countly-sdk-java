package ly.count.sdk.java.internal;

import org.json.JSONObject;

import java.util.Set;

import ly.count.sdk.java.Config;
import ly.count.sdk.java.Session;

/**
 * Created by artem on 05/01/2017.
 */

public abstract class ModuleBase implements Module {
    private boolean active = false;

    protected Log L = null;
    InternalConfig internalConfig = null;

    @Override
    public void init(InternalConfig config, Log logger) {
        L = logger;
        internalConfig = config;
    }

    @Override
    public void onDeviceId(CtxCore ctx, Config.DID deviceId, Config.DID oldDeviceId) {
    }

    @Override
    public void stop(CtxCore ctx, boolean clear) {
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public void setActive(boolean active) {
        this.active = active;
    }

    @Override
    public void onContextAcquired(CtxCore ctx) {
    }

    @Override
    public void onLimitedContextAcquired(CtxCore ctx) {
    }

    @Override
    public void onSessionBegan(Session session, CtxCore ctx) {
    }

    @Override
    public void onSessionEnded(Session session, CtxCore ctx) {
    }

    @Override
    public void onUserChanged(CtxCore ctx, JSONObject changes, Set<String> cohortsAdded, Set<String> cohortsRemoved) {
    }

    @Override
    public Boolean onRequest(Request request) {
        return false;
    }

    @Override
    public void onRequestCompleted(Request request, String response, int responseCode) {

    }
}
