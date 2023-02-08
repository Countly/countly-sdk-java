package ly.count.sdk.java.internal;

public interface IStorageForRequestQueue {
    Request getNextRequest();

    Boolean removeRequest(Request request);
}
