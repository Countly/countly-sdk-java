package ly.count.sdk.java.internal;

public class SDK extends SDKCore {

    private static final String FILE_NAME_PREFIX = "[CLY]";
    private static final String FILE_NAME_SEPARATOR = "_";

    @Override
    public void stop(ly.count.sdk.java.internal.CtxCore ctx, boolean clear) {
        super.stop(ctx, clear);
        clyStorage.stop(ctx, clear);
    }

    @Override
    public void onRequest(ly.count.sdk.java.internal.CtxCore ctx, Request request) {
        onSignal(ctx, SDKCore.Signal.Ping.getIndex(), null);
    }
}
