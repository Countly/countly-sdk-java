package ly.count.sdk.java.internal;

public class DefaultNetworking implements Networking {
    private Log L = null;

    private Transport transport;
    private Tasks tasks;
    private boolean shutdown;
    IStorageForRequestQueue storageForRequestQueue;

    @Override
    public void init(CtxCore ctx, IStorageForRequestQueue storageForRequestQueue) {
        L = ctx.getLogger();
        shutdown = false;
        transport = new Transport();
        transport.init(ctx.getConfig(), ctx.getLogger());
        tasks = new Tasks("network", L);
        this.storageForRequestQueue = storageForRequestQueue;
    }

    @Override
    public boolean isSending() {
        return tasks.isRunning();
    }

    @Override
    public boolean check(CtxCore ctx) {
        L.d("[Networking] [check] state: shutdown [" + shutdown + "], tasks running [" + tasks.isRunning() + "], net running [" + tasks.isRunning() + "], device id [" + ctx.getConfig().getDeviceId() + "]");
        if (!shutdown && !tasks.isRunning() && ctx.getConfig().getDeviceId() != null) {
            tasks.run(submit(ctx));
        }
        return tasks.isRunning();
    }

    protected Tasks.Task<Boolean> submit(final CtxCore ctx) {
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
                        Storage.remove(ctx, request);
                        return true;
                    } else {
                        tasks.run(transport.send(request), result -> {
                            L.d("[Networking] Request " + request.storageId() + " sent?: " + result);
                            if (result) {
                                storageForRequestQueue.removeRequest(request);
                                check(ctx);
                            }
                        });
                        return true;
                    }
                }
            }
        };
    }

    @Override
    public void stop(CtxCore ctx) {
        shutdown = true;
        tasks.shutdown();
    }

    @Override
    public Transport getTransport() {
        return transport;
    }
}
