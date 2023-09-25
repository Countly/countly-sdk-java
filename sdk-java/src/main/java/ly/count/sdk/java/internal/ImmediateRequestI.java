package ly.count.sdk.java.internal;

public interface ImmediateRequestI {
    void doWork(String requestData, String customEndpoint, Transport cp, boolean requestShouldBeDelayed, boolean networkingIsEnabled, ImmediateRequestMaker.InternalImmediateRequestCallback callback, Log log);
}
