package ly.count.sdk.java.internal;

import ly.count.sdk.java.Config;
import org.json.JSONObject;

import java.util.*;
import java.util.concurrent.Future;

public abstract class SDKCore extends SDKModules {
   // private static final Log.Module L = Log.module("SDKCore");

    protected static SDKCore instance;

    private UserImpl user;
    public InternalConfig config;
    protected Networking networking;
    protected Queue<Request> requestQueueMemory = null;

    protected final Object lockBRQStorage = new Object();

    public enum Signal {
        DID(1),
        Crash(2),
        Ping(3),
        Start(10);

        private final int index;

        Signal(int index) {
            this.index = index;
        }

        public int getIndex() {
            return index;
        }
    }

    protected SDKCore() {
        super();
        this.modules = new TreeMap<>();
        instance = this;

    }

    protected InternalConfig prepareConfig(CtxCore ctx) {
        InternalConfig loaded = null;
        try {
            loaded = Storage.read(ctx, new InternalConfig());
        } catch (IllegalArgumentException e) {
            L.e("[SDKCore] Cannot happen" + e);
        }

        if (loaded == null) {
            return ctx.getConfig();
        } else {
            loaded.setFrom(ctx.getConfig());
            return loaded;
        }
    }

    public void init(final CtxCore ctx, Log logger) {
        L = logger;
        L.i("[SDKCore] [SDKCore] Initializing Countly in " + (ctx.getConfig().isLimited() ? "limited" : "full") + " mode");

        config = prepareConfig(ctx);
        Utils.reflectiveSetField(ctx, "config", config);

        super.init(ctx);

        requestQueueMemory = new ArrayDeque<>(config.getRequestQueueMaxSize());
        // ModuleSessions is always enabled, even without consent
        int consents = ctx.getConfig().getFeatures1() | CoreFeature.Sessions.getIndex();
        // build modules
        buildModules(ctx, consents);

        final List<Integer> failed = new ArrayList<>();
        eachModule(new Modulator() {
            @Override
            public void run(int feature, Module module) {
                try {
                    module.init(config, logger);
                    Utils.reflectiveSetField(module, "active", true);
                } catch (IllegalArgumentException | IllegalStateException e) {
                    L.e("[SDKCore] Error during module initialization" + e);
                    failed.add(feature);
                }
            }
        });

        for (Integer feature : failed) {
            modules.remove(feature);
        }

        if (config.isDefaultNetworking()) {
            networking = new DefaultNetworking();

            if (config.isBackendModeEnabled()) {
                //Backend mode is enabled, we will use memory only request queue.
                networking.init(ctx, new IStorageForRequestQueue() {
                    @Override
                    public Request getNextRequest() {
                        synchronized(SDKCore.instance.lockBRQStorage) {
                            if (requestQueueMemory.isEmpty()) {
                                return null;
                            }

                            return requestQueueMemory.element();
                        }
                    }

                    @Override
                    public Boolean removeRequest(Request request) {
                        synchronized(SDKCore.instance.lockBRQStorage) {
                            return requestQueueMemory.remove(request);
                        }
                    }
                });
            } else {
                // Backend mode isn't enabled, we use persistent file storage.
                networking.init(ctx, new IStorageForRequestQueue() {
                    @Override
                    public Request getNextRequest() {
                        return Storage.readOne(ctx, new Request(0L), true);
                    }

                    @Override
                    public Boolean removeRequest(Request request) {
                        return Storage.remove(ctx, request);
                    }
                });
            }


            networking.check(ctx);
        }

        if (config.isLimited()) {
            onLimitedContextAcquired(ctx);
        } else {
            recover(ctx);

            try {
                user = Storage.read(ctx, new UserImpl(ctx));
                if (user == null) {
                    user = new UserImpl(ctx);
                }
            } catch (Throwable e) {
                L.e("[SDKCore] Cannot happen" + e);
                user = new UserImpl(ctx);
            }

            onContextAcquired(ctx);
        }

    }

    protected void onLimitedContextAcquired(final CtxCore ctx) {
        eachModule(new Modulator() {
            @Override
            public void run(int feature, Module module) {
                module.onLimitedContextAcquired(ctx);
            }
        });
    }

    protected void onContextAcquired(final CtxCore ctx) {
        eachModule(new Modulator() {
            @Override
            public void run(int feature, Module module) {
                module.onContextAcquired(ctx);
            }
        });
    }

    public void stop(final CtxCore ctx, final boolean clear) {
        if (instance == null) {
            return;
        }

        if (networking != null) {
            networking.stop(ctx);
        }

        L.i("[SDKCore] [SDKCore] Stopping Countly SDK" + (clear ? " and clearing all data" : ""));
        super.stop(ctx, clear);

        user = null;
        config = null;
        instance = null;
    }


    @Override
    public UserImpl user() {
        return user;
    }

    TimedEvents timedEvents() {
        return ((ModuleSessions) module(CoreFeature.Sessions.getIndex())).timedEvents();
    }

    @Override
    public InternalConfig config() {
        return config;
    }

    @Override
    public void onCrash(CtxCore ctx, Throwable t, boolean fatal, String name, Map<String, String> segments, String[] logs) {
        L.i("[SDKCore] [SDKCore] onCrash: t: " + t.toString() + " fatal: " + fatal + " name: " + name + " segments: " + segments);
        ModuleCrash module = (ModuleCrash) module(CoreFeature.CrashReporting.getIndex());
        if (module != null) {
            module.onCrash(ctx, t, fatal, name, segments, logs);
        }
    }

    @Override
    public void onUserChanged(final CtxCore ctx, final JSONObject changes, final Set<String> cohortsAdded, final Set<String> cohortsRemoved) {
        eachModule(new Modulator() {
            @Override
            public void run(int feature, Module module) {
                module.onUserChanged(ctx, changes, cohortsAdded, cohortsRemoved);
            }
        });
    }

    @Override
    public void onDeviceId(CtxCore ctx, Config.DID id, Config.DID old) {
        L.d((config.isLimited() ? "limited" : "non-limited") + " onDeviceId " + id + ", old " + old);

        if (config.isLimited()) {
            if (id != null && (!id.equals(old) || !id.equals(config.getDeviceId(id.realm)))) {
                config.setDeviceId(id);
            } else if (id == null && old != null) {
                config.removeDeviceId(old);
            }
        } else {
            if (id != null && (!id.equals(old) || !id.equals(config.getDeviceId(id.realm)))) {
                config.setDeviceId(id);
                Storage.push(ctx, instance.config);
            } else if (id == null && old != null) {
                if (config.removeDeviceId(old)) {
                    Storage.push(ctx, config);
                }
            }
        }

        for (Module module : modules.values()) {
            module.onDeviceId(ctx, id, old);
        }

        if (config.isLimited()) {
            config = Storage.read(ctx, new InternalConfig());
            if (config == null) {
                L.e("[SDKCore] ConfigCore reload gave null instance");
            } else {
                config.setLimited(true);
            }
            user = Storage.read(ctx, new UserImpl(ctx));
            if (user == null) {
                user = new UserImpl(ctx);
            }
        }

        if (!config.isLimited() && id != null && id.realm == Config.DID.REALM_DID) {
            user.id = id.id;
            L.d("[SDKCore] 5");
        }
    }


    public Future<Config.DID> acquireId(final CtxCore ctx, final Config.DID holder, final boolean fallbackAllowed, final Tasks.Callback<Config.DID> callback) {
        return ((ModuleDeviceIdCore) module(CoreFeature.DeviceId.getIndex())).acquireId(ctx, holder, fallbackAllowed, callback);
    }

    public void login(CtxCore ctx, String id) {
        ((ModuleDeviceIdCore) module(CoreFeature.DeviceId.getIndex())).login(ctx, id);
    }

    public void logout(CtxCore ctx) {
        ((ModuleDeviceIdCore) module(CoreFeature.DeviceId.getIndex())).logout(ctx);
    }

    public void changeDeviceIdWithoutMerge(CtxCore ctx, String id) {
        ((ModuleDeviceIdCore) module(CoreFeature.DeviceId.getIndex())).changeDeviceId(ctx, id, false);
    }

    public void changeDeviceIdWithMerge(CtxCore ctx, String id) {
        ((ModuleDeviceIdCore) module(CoreFeature.DeviceId.getIndex())).changeDeviceId(ctx, id, true);
    }

    public static boolean enabled(int feature) {
        return (feature & instance.consents) == feature &&
                (feature & instance.config().getFeatures1()) == feature;
    }

    public static boolean enabled(CoreFeature feature) {
        return enabled(feature.getIndex());
    }

    public boolean hasConsentForFeature(CoreFeature feature) {
        if (!instance.config.requiresConsent()) {
            //if no consent required, return true
            return true;
        }

        return enabled(feature);
    }

    public Boolean isRequestReady(Request request) {
        Class cls = request.owner();
        if (cls == null) {
            return true;
        } else {
            ModuleBase module = (ModuleBase) module(cls);
            request.params.remove(Request.MODULE);
            if (module == null) {
                return true;
            } else {
                return module.onRequest(request);
            }
        }
    }

    /**
     * After a network request has been finished
     * propagate that response to the module
     * that owns the request
     *
     * @param request the request that was sent, used to identify the request
     */
    public void onRequestCompleted(Request request, String response, int responseCode, Class<? extends Module> requestOwner) {
        if (requestOwner != null) {
            Module module = module(requestOwner);

            if (module != null) {
                module.onRequestCompleted(request, response, responseCode);
            }
        }
    }

    protected void recover(CtxCore ctx) {
        List<Long> crashes = Storage.list(ctx, CrashImplCore.getStoragePrefix());

        for (Long id : crashes) {
            L.i("[SDKCore] [SDKCore] Found unprocessed crash " + id);
            onSignal(ctx, Signal.Crash.getIndex(), id.toString());
        }

        List<Long> sessions = Storage.list(ctx, SessionImpl.getStoragePrefix());
        for (Long id : sessions) {
            L.d("[SDKCore] recovering session " + id);
            SessionImpl session = Storage.read(ctx, new SessionImpl(ctx, id));
            if (session == null) {
                L.e("[SDKCore] no session with id " + id + " found while recovering");
            } else {
                Boolean success = session.recover(config);
                L.d("[SDKCore] session " + id + " recovery " + (success == null ? "won't recover" : success ? "success" : "failure"));
            }
        }
    }
}
