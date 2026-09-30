package ly.count.sdk.java.internal;

import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class DefaultNetworking implements Networking {
    /**
     * Name of the thread that ends a backoff of the request queue.
     */
    static final String BACKOFF_THREAD_NAME = "network-backoff";
    private static final String TIMESTAMP_PARAM = "timestamp";
    private static final long MS_IN_HOUR = 60L * 60L * 1000L;

    private Log L = null;

    private Transport transport;
    private Tasks tasks;
    private volatile boolean shutdown;
    private volatile boolean backedOff;
    private boolean backendMode;
    private ScheduledExecutorService backoffScheduler;
    IStorageForRequestQueue storageForRequestQueue;

    /**
     * Prepares the transport, the thread requests are sent on and the scheduler that ends a
     * backoff, which starts its daemon thread only when a first backoff is scheduled.
     *
     * @param config configuration of the SDK being initialized
     * @param storageForRequestQueue the request queue to drain
     */
    @Override
    public void init(InternalConfig config, IStorageForRequestQueue storageForRequestQueue) {
        L = config.getLogger();
        shutdown = false;
        backedOff = false;
        backendMode = config.isBackendModeEnabled();
        transport = new Transport();
        transport.init(config);
        tasks = new Tasks("network", L);
        backoffScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, BACKOFF_THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });
        this.storageForRequestQueue = storageForRequestQueue;
    }

    @Override
    public boolean isSending() {
        return tasks.isRunning();
    }

    /**
     * Starts draining the request queue unless the SDK stopped, a request is being handled, there is
     * no device ID yet or the backoff mechanism holds the queue back.
     *
     * @param config configuration of the running SDK
     * @return whether a request is being handled
     */
    @Override
    public boolean check(InternalConfig config) {
        L.d("[Networking] [check] state: shutdown [" + shutdown + "], backed off [" + backedOff + "], tasks running [" + tasks.isRunning() + "], net running [" + tasks.isRunning() + "], device id [" + config.getDeviceId() + "]");
        if (!shutdown && !backedOff && !tasks.isRunning() && config.getDeviceId() != null) {
            tasks.run(submit(config));
        }
        return tasks.isRunning();
    }

    /**
     * Sends the oldest queued request, unless networking is off or the backoff mechanism holds the
     * queue back. Queued requests older than the request drop age are dropped first, unsent. Once
     * the server accepts the request the next one follows, unless the server answered so slowly
     * that the backoff mechanism holds the queue back.
     *
     * @param config configuration of the running SDK
     * @return the task, resolving to whether a request was handed over for sending or removed
     */
    protected Tasks.Task<Boolean> submit(final InternalConfig config) {
        return new Tasks.Task<Boolean>(Tasks.ID_STRICT) {
            @Override
            public Boolean call() throws Exception {
                if (!config.getNetworkingEnabled()) {
                    L.d("[Networking] submit, networking disabled by SDK behavior settings; skipping queue drain");
                    return false;
                }
                if (backedOff) {
                    L.d("[Networking] submit, the backoff mechanism holds the request queue back; skipping queue drain");
                    return false;
                }
                final Request request = nextRequestWithinDropAge(config);
                if (request == null) {
                    return false;
                } else {
                    L.d("[Networking] Preparing request: " + request);
                    final Boolean check = SDKCore.instance.isRequestReady(request);
                    if (check == null) {
                        L.d("[Networking] Request is not ready yet: " + request);
                        return false;
                    } else if (check.equals(Boolean.FALSE)) {
                        L.d("[Networking] Request won't be ready, removing: " + request);
                        Storage.remove(config, request);
                        return true;
                    } else {
                        if (request.params.has("rr")) {
                            request.params.remove("rr");
                        }
                        request.params.add("rr", storageForRequestQueue.remaningRequests());
                        final AtomicLong responseTimeMs = new AtomicLong(-1L);
                        tasks.run(transport.send(request, responseTimeMs::set), result -> {
                            L.d("[Networking] Request " + request.storageId() + " sent?: " + result + ", response time [" + responseTimeMs.get() + "] ms");
                            if (result) {
                                storageForRequestQueue.removeRequest(request);
                                if (!backOffIfTheServerIsSlow(config, request, responseTimeMs.get())) {
                                    check(config);
                                }
                            }
                        });
                        return true;
                    }
                }
            }
        };
    }

    /**
     * Reads the oldest queued request, first dropping, unsent, every queued request older than the
     * request drop age of the SDK behavior settings.
     *
     * @param config configuration of the running SDK
     * @return the request to send next, {@code null} when the queue is empty or a request could not be dropped
     */
    @Nullable
    private Request nextRequestWithinDropAge(@Nonnull InternalConfig config) {
        Request request = storageForRequestQueue.getNextRequest();
        while (request != null && isOlderThanDropAge(config, request)) {
            if (!dropWithoutSending(config, request)) {
                return null;
            }
            request = storageForRequestQueue.getNextRequest();
        }
        return request;
    }

    /**
     * Whether a queued request is older than the request drop age of the SDK behavior settings, a
     * drop age of {@code 0} keeping every request. Never in backend mode, where the settings are
     * inert and a request carries the time the application recorded it at.
     *
     * @param config configuration of the running SDK
     * @param request the queued request
     * @return {@code true} when the request has to be dropped without being sent
     */
    private boolean isOlderThanDropAge(@Nonnull InternalConfig config, @Nonnull Request request) {
        if (backendMode) {
            return false;
        }

        int dropAgeHours = config.getConfigurationProvider().getRequestDropAgeHours();
        return dropAgeHours > 0 && requestAgeMs(request) > dropAgeHours * MS_IN_HOUR;
    }

    /**
     * Removes a queued request without sending it, then tells the module that owns it, with a
     * {@code null} response and {@link Transport#NO_RESPONSE_CODE}, so nothing keeps waiting for
     * its response.
     *
     * @param config configuration of the running SDK
     * @param request the request to drop
     * @return whether the request was removed
     */
    private boolean dropWithoutSending(@Nonnull InternalConfig config, @Nonnull Request request) {
        L.w("[Networking] dropWithoutSending, request [" + request.storageId() + "] is older than the request drop age of [" + config.getConfigurationProvider().getRequestDropAgeHours() + "] hours set by the SDK behavior settings, dropping it without sending");
        if (!Boolean.TRUE.equals(storageForRequestQueue.removeRequest(request))) {
            L.e("[Networking] dropWithoutSending, failed to remove request [" + request.storageId() + "], the queue drain stops here");
            return false;
        }

        Class<? extends ModuleBase> requestOwner = request.owner();
        SDKCore core = SDKCore.instance;
        if (requestOwner != null && core != null) {
            try {
                core.onRequestCompleted(request, null, Transport.NO_RESPONSE_CODE, requestOwner);
            } catch (Exception e) {
                L.e("[Networking] dropWithoutSending, failed to tell the owner of request [" + request.storageId() + "] that it was dropped, [" + e + "]");
            }
        }
        return true;
    }

    /**
     * How long ago a request was recorded, read from its {@code timestamp} parameter, or from its
     * storage ID, which is taken from the clock when the request is created, when it has none.
     *
     * @param request the request
     * @return the age in milliseconds, negative for a request dated in the future
     */
    private long requestAgeMs(@Nonnull Request request) {
        Long recordedAtMs = request.storageId();
        String timestamp = request.params == null ? null : request.params.get(TIMESTAMP_PARAM);
        if (timestamp != null) {
            try {
                recordedAtMs = Long.parseLong(timestamp);
            } catch (NumberFormatException e) {
                L.w("[Networking] requestAgeMs, request [" + request.storageId() + "] has a timestamp that is not a number [" + timestamp + "], dating it by its storage ID");
            }
        }

        long nowMs = System.currentTimeMillis();
        return recordedAtMs == null ? 0L : nowMs - recordedAtMs;
    }

    /**
     * Holds the request queue back for the backoff duration of the SDK behavior settings when the
     * backoff mechanism is on and the server answered slowly: the response took at least the
     * accepted timeout, the request is not older than the backoff request age, and the requests
     * still queued are at most the given share of the request queue size. Never in backend mode,
     * where the settings are inert.
     *
     * @param config configuration of the running SDK
     * @param request the request the server just accepted, already removed from the queue
     * @param responseTimeMs how long the server took to answer it, in milliseconds
     * @return whether the queue is now held back
     */
    private boolean backOffIfTheServerIsSlow(@Nonnull InternalConfig config, @Nonnull Request request, long responseTimeMs) {
        if (backendMode) {
            return false;
        }

        ConfigurationProvider provider = config.getConfigurationProvider();
        if (!provider.getBOMEnabled() || responseTimeMs < provider.getBOMAcceptedTimeoutSeconds() * 1000L) {
            return false;
        }

        if (requestAgeMs(request) > provider.getBOMRequestAge() * MS_IN_HOUR) {
            L.v("[Networking] backOffIfTheServerIsSlow, the server took [" + responseTimeMs + "] ms to answer request [" + request.storageId() + "], but the request is older than [" + provider.getBOMRequestAge() + "] hours, not backing off");
            return false;
        }

        int queuedRequests = queuedRequestCount();
        if (queuedRequests < 0) {
            return false;
        }

        double queueShare = provider.getRequestQueueMaxSize() * provider.getBOMRQPercentage();
        if (queuedRequests > queueShare) {
            L.v("[Networking] backOffIfTheServerIsSlow, the server took [" + responseTimeMs + "] ms to answer request [" + request.storageId() + "], but [" + queuedRequests + "] requests remain queued, more than [" + queueShare + "], not backing off");
            return false;
        }

        int durationSeconds = provider.getBOMDuration();
        L.i("[Networking] backOffIfTheServerIsSlow, the server took [" + responseTimeMs + "] ms to answer request [" + request.storageId() + "] and [" + queuedRequests + "] requests remain queued, holding the request queue back for [" + durationSeconds + "] seconds");
        return startBackoff(config, durationSeconds);
    }

    /**
     * How many requests the queue holds.
     *
     * @return the number of queued requests, {@code -1} when they could not be counted
     */
    private int queuedRequestCount() {
        try {
            Integer remaining = storageForRequestQueue.remaningRequests();
            return remaining == null ? -1 : remaining + 1;
        } catch (RuntimeException e) {
            L.w("[Networking] queuedRequestCount, failed to count the queued requests, not backing off, [" + e + "]");
            return -1;
        }
    }

    /**
     * Holds the request queue back for the given time, after which it drains again.
     *
     * @param config configuration of the running SDK
     * @param durationSeconds how long to hold the queue back
     * @return whether the queue is held back, {@code false} when the SDK is stopping
     */
    private boolean startBackoff(@Nonnull final InternalConfig config, int durationSeconds) {
        backedOff = true;
        try {
            backoffScheduler.schedule(() -> endBackoff(config), durationSeconds, TimeUnit.SECONDS);
            return true;
        } catch (RejectedExecutionException e) {
            backedOff = false;
            L.d("[Networking] startBackoff, the SDK is stopping, the request queue is not held back");
            return false;
        }
    }

    /**
     * Ends a backoff and lets the request queue drain again, unless the SDK stopped meanwhile.
     *
     * @param config configuration of the running SDK
     */
    private void endBackoff(@Nonnull InternalConfig config) {
        backedOff = false;
        if (shutdown) {
            return;
        }

        L.d("[Networking] endBackoff, the backoff is over, the request queue drains again");
        try {
            check(config);
        } catch (RuntimeException e) {
            L.w("[Networking] endBackoff, failed to restart the request queue drain, [" + e + "]");
        }
    }

    /**
     * Stops sending. A backoff that is still running never ends in a drain, and a request that is
     * being sent may finish.
     *
     * @param config configuration of the SDK being stopped
     */
    @Override
    public void stop(InternalConfig config) {
        shutdown = true;
        if (backoffScheduler != null) {
            backoffScheduler.shutdownNow();
            try {
                if (!backoffScheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                    L.w("[Networking] stop, the backoff scheduler did not stop in time");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        tasks.shutdown();
    }

    @Override
    public Transport getTransport() {
        return transport;
    }
}
