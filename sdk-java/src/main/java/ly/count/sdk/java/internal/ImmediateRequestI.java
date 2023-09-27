package ly.count.sdk.java.internal;

public interface ImmediateRequestI {

    /**
     * Do the work of making the request directly without waiting for the queue.
     *
     * @param requestData query parameters
     * @param customEndpoint custom endpoint to use instead of the default one
     * @param cp transport to use when making the request
     * @param requestShouldBeDelayed whether the request should be delayed or not
     * @param networkingIsEnabled whether networking is enabled or not
     * @param callback callback to call when the request is done
     * @param log logger to use
     */
    void doWork(String requestData, String customEndpoint, Transport cp, boolean requestShouldBeDelayed, boolean networkingIsEnabled, ImmediateRequestMaker.InternalImmediateRequestCallback callback, Log log);
}
