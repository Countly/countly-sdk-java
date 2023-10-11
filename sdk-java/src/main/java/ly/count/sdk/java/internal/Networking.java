package ly.count.sdk.java.internal;

public interface Networking {
    void init(InternalConfig config, IStorageForRequestQueue storageForRequestQueue);

    boolean isSending();

    boolean check(InternalConfig config);

    void stop(InternalConfig config);

    Transport getTransport();
}
