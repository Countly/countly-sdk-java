package ly.count.sdk.java.internal;

import ly.count.sdk.java.internal.Request;
import ly.count.sdk.java.internal.SDKCore;

public class SDK extends SDKStorage {
    @Override
    public void onRequest(ly.count.sdk.java.internal.CtxCore ctx, Request request) {
        onSignal(ctx, SDKCore.Signal.Ping.getIndex(), null);
    }
}
