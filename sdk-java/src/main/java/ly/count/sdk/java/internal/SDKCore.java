package ly.count.sdk.java.internal;

import ly.count.sdk.java.Config;
import org.json.JSONObject;

import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.Future;

public abstract class SDKCore implements SDKInterface {
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
        this.modules = new TreeMap<>();
        instance = this;

    }

    protected Log L = null;
    private static Module testDummyModule = null;//set during testing when trying to check the SDK's lifecycle

    /**
     * All known mappings of {@code ConfigCore.Feature} to {@link Module} class.
     */
    private static final Map<Integer, Class<? extends Module>> DEFAULT_MAPPINGS = new HashMap<>();

    protected static void registerDefaultModuleMapping(int feature, Class<? extends Module> cls) {
        DEFAULT_MAPPINGS.put(feature, cls);
    }

    static {
        registerDefaultModuleMapping(CoreFeature.DeviceId.getIndex(), ModuleDeviceIdCore.class);
        registerDefaultModuleMapping(CoreFeature.Requests.getIndex(), ModuleRequests.class);
        //registerDefaultModuleMapping(CoreFeature.Logs.getIndex(), Log.class);
        registerDefaultModuleMapping(CoreFeature.Views.getIndex(), ModuleViews.class);
        registerDefaultModuleMapping(CoreFeature.Sessions.getIndex(), ModuleSessions.class);
        registerDefaultModuleMapping(CoreFeature.CrashReporting.getIndex(), ModuleCrash.class);
        registerDefaultModuleMapping(CoreFeature.BackendMode.getIndex(), ModuleBackendMode.class);
    }

    public interface Modulator {
        void run(int feature, Module module);
    }

    /**
     * Currently enabled features with consents
     */
    protected int consents = 0;

    /**
     * Selected by config map of module mappings
     */
    private static final Map<Integer, Class<? extends Module>> moduleMappings = new HashMap<>();

    protected static void registerModuleMapping(int feature, Class<? extends Module> cls) {
        if (cls != null) {
            moduleMappings.put(feature, cls);
        }
    }

    // TreeMap to keep modules sorted by their feature indexes
    protected Map<Integer, Module> modules;

    /**
     * Check if consent has been given for a feature
     *
     * @param feat feature to test against, pass null to test if any consent given
     * @return {@code true} if consent has been given
     */
    public boolean isTracking(Integer feat) {
        return modules != null && modules.containsKey(feat);
    }

    @Override
    public void init(CtxCore ctx) {
        prepareMappings(ctx);
    }

    @Override
    public void stop(final CtxCore ctx, final boolean clear) {
        if (instance == null) {
            return;
        }

        if (networking != null) {
            networking.stop(ctx);
        }

        L.i("[SDKCore] [SDKCore] Stopping Countly SDK" + (clear ? " and clearing all data" : ""));


        eachModule(new Modulator() {
            @Override
            public void run(int feature, Module module) {
                try {
                    module.stop(ctx, clear);
                    Utils.reflectiveSetField(module, "active", false);
                } catch (Throwable e) {
                    L.e("[SDKModules] Exception while stopping " + module.getClass() + " " + e);
                }
            }
        });
        modules.clear();
        moduleMappings.clear();
        user = null;
        config = null;
        instance = null;
    }

    private boolean addingConsent(int adding, CoreFeature feature) {
        return (consents & feature.getIndex()) == 0 && (adding & feature.getIndex()) > 0;
    }

    private boolean removingConsent(int removing, CoreFeature feature) {
        return (consents & feature.getIndex()) == feature.getIndex() && (removing & feature.getIndex()) == feature.getIndex();
    }

    /**
     * Callback to add consents to the list
     *
     * @param consent consents to add
     */
    public void onConsent(CtxCore ctx, int consent) {
        if (!config().requiresConsent()) {
            L.e("[SDKModules] onConsent() shouldn't be called when Config.requiresConsent() is false");
            return;
        }

        if (addingConsent(consent, CoreFeature.Sessions)) {
            SessionImpl session = module(ModuleSessions.class).getSession();
            if (session != null) {
                session.end();
            }

            consents = consents | (consent & ctx.getConfig().getFeatures1());

            module(ModuleSessions.class).session(ctx, null).begin();
        }

        consents = consents | (consent & ctx.getConfig().getFeatures1());

        for (Integer feature : moduleMappings.keySet()) {
            Module existing = module(moduleMappings.get(feature));
            if (SDKCore.enabled(feature) && existing == null) {
                Class<? extends Module> cls = moduleMappings.get(feature);
                if (cls == null) {
                    L.i("[SDKModules] No module mapping for feature " + feature);
                    continue;
                }

                Module module = instantiateModule(cls, L);
                if (module == null) {
                    L.e("[SDKModules] Cannot instantiate module " + feature);
                } else {
                    module.init(ctx.getConfig(), L);
                    module.onContextAcquired(ctx);
                    modules.put(feature, module);
                }
            }
        }
    }

    /**
     * Callback to remove consents from the list
     *
     * @param noConsent consents to remove
     */
    public void onConsentRemoval(CtxCore ctx, int noConsent) {
        if (!config().requiresConsent()) {
            L.e("[SDKModules] onConsentRemoval() shouldn't be called when Config.requiresConsent() is false");
            return;
        }

        if (removingConsent(noConsent, CoreFeature.Sessions)) {
            SessionImpl session = module(ModuleSessions.class).getSession();
            if (session != null) {
                session.end();
            }
        }

        if (removingConsent(noConsent, CoreFeature.Location)) {
            user().edit().optOutFromLocationServices();
        }

        consents = consents & ~noConsent;

        for (Integer feature : moduleMappings.keySet()) {
            Module existing = module(moduleMappings.get(feature));
            if (feature != CoreFeature.Sessions.getIndex() && existing != null) {
                existing.stop(ctx, true);
                modules.remove(feature);
            }
        }
    }

    /**
     * Create instances of {@link Module}s required by {@link #config}.
     * Uses {@link #moduleMappings} for {@code ConfigCore.Feature} / {@link CoreFeature}
     * - Class&lt;Module&gt; mapping to enable overriding by app developer.
     *
     * @param ctx {@link CtxCore} object containing config with mapping overrides
     * @throws IllegalArgumentException in case some {@link Module} finds {@link #config} inconsistent.
     * @throws IllegalStateException when this module is run second time on the same {@code Core} instance.
     */
    protected void prepareMappings(CtxCore ctx) throws IllegalStateException {
        if (modules.size() > 0) {
            throw new IllegalStateException("Modules can only be built once");
        }

        moduleMappings.clear();
        moduleMappings.putAll(DEFAULT_MAPPINGS);

        for (int feature : ctx.getConfig().getModuleOverrides()) {
            registerModuleMapping(feature, ctx.getConfig().getModuleOverride(feature));
        }
    }


    /**
     * Create instances of {@link Module}s required by {@link #config}.
     * Uses {@link #moduleMappings} for {@code ConfigCore.Feature} / {@link CoreFeature}
     * - Class&lt;Module&gt; mapping to enable overriding by app developer.
     *
     * @param ctx {@link CtxCore} object
     * @param features consents bitmask to check against
     * @throws IllegalArgumentException in case some {@link Module} finds {@link #config} inconsistent.
     * @throws IllegalStateException when this module is run second time on the same {@code Core} instance.
     */
    protected void buildModules(CtxCore ctx, int features) throws IllegalArgumentException, IllegalStateException {
        // override module mappings in native/Android parts, overriding by ConfigCore ones if necessary

        if (modules.size() > 0) {
            throw new IllegalStateException("Modules can only be built once");
        }

//        if (ctx.getConfig().getLoggingLevel() != Config.LoggingLevel.OFF) {
//            modules.put(-10, instantiateModule(moduleMappings.get(CoreFeature.Logs.getIndex()), L));
//        }

        // standard required internal features
        modules.put(-3, instantiateModule(moduleMappings.get(CoreFeature.DeviceId.getIndex()), L));
        modules.put(-2, instantiateModule(moduleMappings.get(CoreFeature.Requests.getIndex()), L));
        modules.put(CoreFeature.Sessions.getIndex(), instantiateModule(moduleMappings.get(CoreFeature.Sessions.getIndex()), L));

        if (ctx.getConfig().requiresConsent()) {
            consents = 0;
        } else {
            consents = ctx.getConfig().getFeatures1();
        }

        if (!ctx.getConfig().requiresConsent()) {
            for (int feature : moduleMappings.keySet()) {
                Class<? extends Module> cls = moduleMappings.get(feature);
                if (cls == null) {
                    continue;
                }
                Module existing = module(cls);
                if ((features & feature) > 0 && existing == null) {
                    Module m = instantiateModule(cls, L);
                    if (m != null) {
                        modules.put(feature, m);
                    }
                }
            }
        }
        modules.put(CoreFeature.BackendMode.getIndex(), instantiateModule(moduleMappings.get(CoreFeature.BackendMode.getIndex()), L));

        // dummy module for tests if any
        if (testDummyModule != null) {
            modules.put(CoreFeature.TestDummy.getIndex(), testDummyModule);
        }
    }

    /**
     * Create {@link Module} by executing its default constructor.
     *
     * @param cls class of {@link Module}
     * @return {@link Module} instance or null in case of error
     */
    private static Module instantiateModule(Class<? extends Module> cls, Log L) {
        try {
            return (Module)cls.getConstructors()[0].newInstance();
        } catch (InstantiationException e) {
            L.e("[SDKModules] Module cannot be instantiated" + e);
        } catch (IllegalAccessException e) {
            L.e("[SDKModules] Module constructor cannot be accessed" + e);
        } catch (InvocationTargetException e) {
            L.e("[SDKModules] Module constructor cannot be invoked" + e);
        } catch (IllegalArgumentException e) {
            try {
                return (Module)cls.getConstructors()[0].newInstance((Object)null);
            } catch (InstantiationException e1) {
                L.e("[SDKModules] Module cannot be instantiated" + e);
            } catch (IllegalAccessException e1) {
                L.e("[SDKModules] Module constructor cannot be accessed" + e);
            } catch (InvocationTargetException e1) {
                L.e("[SDKModules] Module constructor cannot be invoked" + e);
            }
        }
        return null;
    }

    /**
     * Return module instance by {@code ConfigCore.Feature}
     *
     * @param feature to get a {@link Module} instance for
     * @return {@link Module} instance or null if no such module is instantiated
     */
    protected Module module(int feature) {
        return module(moduleMappings.get(feature));
    }

    /**
     * Return module instance by {@link Module} class
     *
     * @param cls class to get a {@link Module} instance for
     * @return {@link Module} instance or null if no such module is instantiated
     */
    @SuppressWarnings("unchecked")
    public  <T extends Module> T module(Class<T> cls) {
        for (Module module: modules.values()) {
            if (module.getClass().isAssignableFrom(cls)) {
                return (T) module;
            }
        }
        return null;
    }

    protected void eachModule(Modulator modulator) {
        for (Integer feature: modules.keySet()) {
            modulator.run(feature, modules.get(feature));
        }
    }

    @Override
    public SessionImpl onSessionBegan(CtxCore ctx, SessionImpl session){
        for (Module m : modules.values()) {
            m.onSessionBegan(session, ctx);
        }
        return session;
    }

    @Override
    public SessionImpl onSessionEnded(CtxCore ctx, SessionImpl session){
        for (Module m : modules.values()) {
            m.onSessionEnded(session, ctx);
        }
        ModuleSessions sessions = (ModuleSessions) module(CoreFeature.Sessions.getIndex());
        if (sessions != null) {
            sessions.forgetSession();
        }
        return session;
    }

    @Override
    public SessionImpl getSession() {
        ModuleSessions sessions = (ModuleSessions) module(CoreFeature.Sessions.getIndex());
        if (sessions != null) {
            return sessions.getSession();
        }
        return null;
    }

    @Override
    public SessionImpl session(CtxCore ctx, Long id) {
        ModuleSessions sessions = (ModuleSessions) module(CoreFeature.Sessions.getIndex());
        if (sessions != null) {
            return sessions.session(ctx, id);
        }
        return null;
    }

    interface ModuleCallback {
        void call(Module module);
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

        this.init(ctx);

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

    /**
     * Core instance config
     */


    @Override
    public void onSignal(CtxCore ctx, int id, Byteable param1, Byteable param2) {
        if (id == Signal.DID.getIndex()) {
            networking.check(ctx);
        }
    }

    @Override
    public void onSignal(CtxCore ctx, int id, String param) {
        if (id == Signal.Ping.getIndex()){
            networking.check(ctx);
        } else if (id == Signal.Crash.getIndex()) {
            processCrash(ctx, Long.parseLong(param));
        }
    }

    private boolean processCrash(CtxCore ctx, Long id) {
        CrashImpl crash = new CrashImpl(id, L);
        crash = Storage.read(ctx, crash);

        if (crash == null) {
            L.e("Cannot read crash from storage, skipping");
            return false;
        }

        Request request = ModuleRequests.nonSessionRequest(ctx);
        ModuleCrash.putCrashIntoParams(crash, request.params);
        if (Storage.push(ctx, request)) {
            L.i("[SDKLifecycle] Added request " + request.storageId() + " instead of crash " + crash.storageId());
            networking.check(ctx);
            Boolean success = Storage.remove(ctx, crash);
            return success == null ? false : success;
        } else {
            L.e("[SDKLifecycle] Couldn't write request " + request.storageId() + " instead of crash " + crash.storageId());
            return false;
        }
    }
}
