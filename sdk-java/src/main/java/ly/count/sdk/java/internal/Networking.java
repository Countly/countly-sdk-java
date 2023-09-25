package ly.count.sdk.java.internal;

public interface Networking {
    void init(CtxCore ctx, IStorageForRequestQueue storageForRequestQueue);

    boolean isSending();

    boolean check(CtxCore ctx);

    void stop(CtxCore ctx);

    Transport getTransport();
}
