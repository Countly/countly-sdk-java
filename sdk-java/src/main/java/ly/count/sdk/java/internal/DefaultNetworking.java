package ly.count.sdk.java.internal;

public class DefaultNetworking implements Networking {
    private Log L = null;

    private Transport transport;
    private Tasks tasks;
    private boolean shutdown;
    IStorageForRequestQueue storageForRequestQueue;

    @Override
    public void init(InternalConfig config, IStorageForRequestQueue storageForRequestQueue) {
        L = config.getLogger();
        shutdown = false;
        transport = new Transport();
        transport.init(config, config.getLogger());
        tasks = new Tasks("network", L);
        this.storageForRequestQueue = storageForRequestQueue;
    }

    @Override
    public boolean isSending() {
        return tasks.isRunning();
    }

    @Override
    public boolean check(InternalConfig config) {
        L.d("[Networking] [check] state: shutdown [" + shutdown + "], tasks running [" + tasks.isRunning() + "], net running [" + tasks.isRunning() + "], device id [" + config.getDeviceId() + "]");
        if (!shutdown && !tasks.isRunning() && config.getDeviceId() != null) {
            tasks.run(submit(config));
        }
        return tasks.isRunning();
    }

    protected Tasks.Task<Boolean> submit(final InternalConfig config) {
        return new Tasks.Task<Boolean>(Tasks.ID_STRICT) {
            @Override
            public Boolean call() throws Exception {
                final Request request = storageForRequestQueue.getNextRequest();
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
                        request.params.add("rr", storageForRequestQueue.remaningRequests());
                        if (Utils.isEmptyOrNull(config.getApplicationVersion())) {
                            request.params.add("av", Utils.urlencode(config.getApplicationVersion(), L));
                        }
                        tasks.run(transport.send(request), result -> {
                            L.d("[Networking] Request " + request.storageId() + " sent?: " + result);
                            if (result) {
                                storageForRequestQueue.removeRequest(request);
                                check(config);
                            }
                        });
                        return true;
                    }
                }
            }
        };
    }

    @Override
    public void stop(InternalConfig config) {
        shutdown = true;
        tasks.shutdown();
    }

    @Override
    public Transport getTransport() {
        return transport;
    }
}
