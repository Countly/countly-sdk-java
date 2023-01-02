package ly.count.sdk.java.internal;

import ly.count.sdk.java.internal.Byteable;
import ly.count.sdk.java.internal.CtxCore;
import ly.count.sdk.java.internal.InternalConfig;
import ly.count.sdk.java.internal.Log;
import ly.count.sdk.java.internal.Module;
import ly.count.sdk.java.internal.ModuleCrash;
import ly.count.sdk.java.internal.ModuleRequests;
import ly.count.sdk.java.internal.Request;
import ly.count.sdk.java.internal.SDKCore;
import ly.count.sdk.java.internal.Storage;
import org.json.JSONObject;

import java.util.Map;

/**
 * Application lifecycle-related methods of {@link SDK}
 */

public abstract class SDKLifecycle extends SDKCore {

    /**
     * Core instance config
     */

    protected InternalConfig config;

    protected SDKLifecycle() {
        super();
    }

    @Override
    public void stop(ly.count.sdk.java.internal.CtxCore ctx, boolean clear) {
        super.stop(ctx, clear);
        config = null;
    }


    @Override
    public void onSignal(CtxCore ctx, int id, Byteable param1, Byteable param2) {
        if (id == Signal.DID.getIndex()) {
            networking.check(ctx);
        }
    }

    @Override
    public void onSignal(CtxCore ctx, int id, String param) {
        if (id == Signal.Ping.getIndex()){
            networking.check(ctx);
        } else if (id == Signal.Crash.getIndex()) {
            processCrash(ctx, Long.parseLong(param));
        }
    }

    private boolean processCrash(CtxCore ctx, Long id) {
        CrashImpl crash = new CrashImpl(id);
        crash = Storage.read(ctx, crash);

        if (crash == null) {
            L.e("Cannot read crash from storage, skipping");
            return false;
        }

        Request request = ModuleRequests.nonSessionRequest(ctx);
        ModuleCrash.putCrashIntoParams(crash, request.params);
        if (Storage.push(ctx, request)) {
            L.i("[SDKLifecycle] Added request " + request.storageId() + " instead of crash " + crash.storageId());
            networking.check(ctx);
            Boolean success = Storage.remove(ctx, crash);
            return success == null ? false : success;
        } else {
            L.e("[SDKLifecycle] Couldn't write request " + request.storageId() + " instead of crash " + crash.storageId());
            return false;
        }
    }

}
