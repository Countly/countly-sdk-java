package ly.count.sdk.java.internal;

interface ImmediateRequestI {
    void doWork(String requestData, String customEndpoint, Transport cp, boolean requestShouldBeDelayed, boolean networkingIsEnabled, ImmediateRequestMaker.InternalImmediateRequestCallback callback, Log log);
}
