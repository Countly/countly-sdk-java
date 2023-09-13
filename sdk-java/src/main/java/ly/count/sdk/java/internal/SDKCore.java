package ly.count.sdk.java.internal;

import ly.count.sdk.java.Config;
import org.json.JSONObject;

import java.util.*;
import java.util.concurrent.Future;

public class SDKCore {

    protected static SDKCore instance;

    protected SDKStorage sdkStorage;
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

    public SDKCore() {
        this.modules = new TreeMap<>();
        instance = this;
        sdkStorage = new SDKStorage();
    }

    protected Log L = null;
    private static ModuleBase testDummyModule = null;//set during testing when trying to check the SDK's lifecycle

    protected static void registerDefaultModuleMappings() {
        moduleMappings.put(CoreFeature.DeviceId.getIndex(), ModuleDeviceIdCore.class);
        moduleMappings.put(CoreFeature.Requests.getIndex(), ModuleRequests.class);
        //moduleMappings.put(CoreFeature.Logs.getIndex(), Log.class);
        moduleMappings.put(CoreFeature.Views.getIndex(), ModuleViews.class);
        moduleMappings.put(CoreFeature.Sessions.getIndex(), ModuleSessions.class);
        moduleMappings.put(CoreFeature.CrashReporting.getIndex(), ModuleCrash.class);
        moduleMappings.put(CoreFeature.BackendMode.getIndex(), ModuleBackendMode.class);
    }

    public interface Modulator {
        void run(int feature, ModuleBase module);
    }

    /**
     * Currently enabled features with consents
     */
    protected int consents = 0;

    /**
     * Selected by config map of module mappings
     */
    private static final Map<Integer, Class<? extends ModuleBase>> moduleMappings = new HashMap<>();

    protected static void registerModuleMapping(int feature, Class<? extends ModuleBase> cls) {
        if (cls != null) {
            moduleMappings.put(feature, cls);
        }
    }

    // TreeMap to keep modules sorted by their feature indexes
    protected Map<Integer, ModuleBase> modules;

    /**
     * Check if consent has been given for a feature
     *
     * @param feat feature to test against, pass null to test if any consent given
     * @return {@code true} if consent has been given
     */
    public boolean isTracking(Integer feat) {
        return modules != null && modules.containsKey(feat);
    }

    public void init(CtxCore ctx) {
        prepareMappings(ctx);
    }

    public void stop(final CtxCore ctx, final boolean clear) {
        if (instance == null) {
            return;
        }

        if (networking != null) {
            networking.stop(ctx);
        }

        L.i("[SDKCore] Stopping Countly SDK" + (clear ? " and clearing all data" : ""));

        eachModule((feature, module) -> {
            try {
                module.stop(ctx, clear);
                module.setActive(false);
            } catch (Throwable e) {
                L.e("[SDKCore] Exception while stopping " + module.getClass() + " " + e);
            }
        });
        modules.clear();
        moduleMappings.clear();
        user = null;
        config = null;
        instance = null;

        sdkStorage.stop(ctx, clear);//from original super class
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
            ModuleBase existing = module(moduleMappings.get(feature));
            if (SDKCore.enabled(feature) && existing == null) {
                ModuleBase module = instantiateModule(feature);
                if (module == null) {
                    L.e("[SDKCore] Cannot instantiate module " + feature);
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
            ModuleBase existing = module(moduleMappings.get(feature));
            if (feature != CoreFeature.Sessions.getIndex() && existing != null) {
                existing.stop(ctx, true);
                modules.remove(feature);
            }
        }
    }

    /**
     * Create instances of {@link ModuleBase}s required by {@link #config}.
     * Uses {@link #moduleMappings} for {@code Config.Feature} / {@link CoreFeature}
     * - Class&lt;ModuleBase&gt; mapping to enable overriding by app developer.
     *
     * @param ctx {@link CtxCore} object containing config with mapping overrides
     * @throws IllegalArgumentException in case some {@link ModuleBase} finds {@link #config} inconsistent.
     * @throws IllegalStateException when this module is run second time on the same {@code Core} instance.
     */
    protected void prepareMappings(CtxCore ctx) throws IllegalStateException {
        if (!modules.isEmpty()) {
            throw new IllegalStateException("Modules can only be built once");
        }

        moduleMappings.clear();
        registerDefaultModuleMappings();

        for (int feature : ctx.getConfig().getModuleOverrides()) {
            registerModuleMapping(feature, ctx.getConfig().getModuleOverride(feature));
        }
    }

    /**
     * Create instances of {@link ModuleBase}s required by {@link #config}.
     * Uses {@link #moduleMappings} for {@code Config.Feature} / {@link CoreFeature}
     * - Class&lt;ModuleBase&gt; mapping to enable overriding by app developer.
     *
     * @param ctx {@link CtxCore} object
     * @param features consents bitmask to check against
     * @throws IllegalArgumentException in case some {@link ModuleBase} finds {@link #config} inconsistent.
     * @throws IllegalStateException when this module is run second time on the same {@code Core} instance.
     */
    protected void buildModules(CtxCore ctx, int features) throws IllegalArgumentException, IllegalStateException {
        // override module mappings in native/Android parts, overriding by Config ones if necessary

        if (!modules.isEmpty()) {
            throw new IllegalStateException("Modules can only be built once");
        }

        //        if (ctx.getConfig().getLoggingLevel() != Config.LoggingLevel.OFF) {
        //            modules.put(-10, instantiateModule(moduleMappings.get(CoreFeature.Logs.getIndex()), L));
        //        }

        // standard required internal features
        modules.put(-3, new ModuleDeviceIdCore());
        modules.put(-2, new ModuleRequests());
        modules.put(CoreFeature.Sessions.getIndex(), new ModuleSessions());

        if (ctx.getConfig().requiresConsent()) {
            consents = 0;
        } else {
            consents = ctx.getConfig().getFeatures1();
        }

        if (!ctx.getConfig().requiresConsent()) {
            for (int feature : moduleMappings.keySet()) {
                Class<? extends ModuleBase> cls = moduleMappings.get(feature);
                if (cls == null) {
                    continue;
                }
                ModuleBase existing = module(cls);
                if ((features & feature) > 0 && existing == null) {
                    ModuleBase m = instantiateModule(feature);
                    if (m != null) {
                        modules.put(feature, m);
                    }
                }
            }
        }
        modules.put(CoreFeature.BackendMode.getIndex(), new ModuleBackendMode());

        // dummy module for tests if any
        if (testDummyModule != null) {
            modules.put(CoreFeature.TestDummy.getIndex(), testDummyModule);
        }
    }

    /**
     * Create {@link ModuleBase} by executing its default constructor.
     *
     * @param feature int value of feature
     * @return {@link ModuleBase} instance or null if {@link ModuleBaseCreator} is not set for {@code feature}
     */
    private ModuleBase instantiateModule(int feature) {
        CoreFeature coreFeature = CoreFeature.byIndex(feature);

        if (coreFeature.getCreator() == null) {
            return null;
        }
        return coreFeature.getCreator().create();
    }

    /**
     * Return module instance by {@code Config.Feature}
     *
     * @param feature to get a {@link ModuleBase} instance for
     * @return {@link ModuleBase} instance or null if no such module is instantiated
     */
    protected ModuleBase module(int feature) {
        return module(moduleMappings.get(feature));
    }

    /**
     * Return module instance by {@link ModuleBase} class
     *
     * @param cls class to get a {@link ModuleBase} instance for
     * @return {@link ModuleBase} instance or null if no such module is instantiated
     */
    public <T extends ModuleBase> T module(Class<T> cls) {
        for (ModuleBase module : modules.values()) {
            if (module.getClass().isAssignableFrom(cls)) {
                return (T) module;
            }
        }
        return null;
    }

    protected void eachModule(Modulator modulator) {
        for (Integer feature : modules.keySet()) {
            modulator.run(feature, modules.get(feature));
        }
    }

    /**
     * Notify all {@link ModuleBase} instances about new session has just been started
     *
     * @param session session to begin
     * @return supplied session for method chaining
     */
    public SessionImpl onSessionBegan(CtxCore ctx, SessionImpl session) {
        for (ModuleBase m : modules.values()) {
            m.onSessionBegan(session, ctx);
        }
        return session;
    }

    /**
     * Notify all {@link ModuleBase} instances session was ended
     *
     * @param session session to end
     * @return supplied session for method chaining
     */
    public SessionImpl onSessionEnded(CtxCore ctx, SessionImpl session) {
        for (ModuleBase m : modules.values()) {
            m.onSessionEnded(session, ctx);
        }
        ModuleSessions sessions = (ModuleSessions) module(CoreFeature.Sessions.getIndex());
        if (sessions != null) {
            sessions.forgetSession();
        }
        return session;
    }

    public SessionImpl getSession() {
        ModuleSessions sessions = (ModuleSessions) module(CoreFeature.Sessions.getIndex());
        if (sessions != null) {
            return sessions.getSession();
        }
        return null;
    }

    /**
     * Get current {@link SessionImpl} or create new one if current is {@code null}.
     *
     * @param ctx Ctx to create new {@link SessionImpl} in
     * @param id ID of new {@link SessionImpl} if needed
     * @return current {@link SessionImpl} instance
     */
    public SessionImpl session(CtxCore ctx, Long id) {
        ModuleSessions sessions = (ModuleSessions) module(CoreFeature.Sessions.getIndex());
        if (sessions != null) {
            return sessions.session(ctx, id);
        }
        return null;
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
        L.i("[SDKCore] Initializing Countly in " + (ctx.getConfig().isLimited() ? "limited" : "full") + " mode");

        sdkStorage.init(ctx, logger);
        config = prepareConfig(ctx);
        ctx.setConfig(config);

        this.init(ctx);

        requestQueueMemory = new ArrayDeque<>(config.getRequestQueueMaxSize());
        // ModuleSessions is always enabled, even without consent
        int consents = ctx.getConfig().getFeatures1() | CoreFeature.Sessions.getIndex();
        // build modules
        buildModules(ctx, consents);

        final List<Integer> failed = new ArrayList<>();
        eachModule((feature, module) -> {
            try {
                module.init(config, logger);
                module.setActive(true);
            } catch (IllegalArgumentException | IllegalStateException e) {
                L.e("[SDKCore] Error during module initialization" + e);
                failed.add(feature);
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
                        synchronized (SDKCore.instance.lockBRQStorage) {
                            if (requestQueueMemory.isEmpty()) {
                                return null;
                            }

                            return requestQueueMemory.element();
                        }
                    }

                    @Override
                    public Boolean removeRequest(Request request) {
                        synchronized (SDKCore.instance.lockBRQStorage) {
                            return requestQueueMemory.remove(request);
                        }
                    }
                });
            } else {
                // Backend mode isn't enabled, we use persistent file storage.
                networking.init(ctx, new IStorageForRequestQueue() {
                    @Override
                    public Request getNextRequest() {
                        return Storage.readOne(ctx, new Request(0L), true, L);
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
        eachModule((feature, module) -> module.onLimitedContextAcquired(ctx));
    }

    protected void onContextAcquired(final CtxCore ctx) {
        eachModule((feature, module) -> module.onContextAcquired(ctx));
    }

    public UserImpl user() {
        return user;
    }

    TimedEvents timedEvents() {
        return ((ModuleSessions) module(CoreFeature.Sessions.getIndex())).timedEvents();
    }

    public InternalConfig config() {
        return config;
    }

    public void onCrash(CtxCore ctx, Throwable t, boolean fatal, String name, Map<String, String> segments, String[] logs) {
        L.i("[SDKCore] onCrash: t: " + t.toString() + " fatal: " + fatal + " name: " + name + " segments: " + segments);
        ModuleCrash module = (ModuleCrash) module(CoreFeature.CrashReporting.getIndex());
        if (module != null) {
            module.onCrash(ctx, t, fatal, name, segments, logs);
        }
    }

    public void onUserChanged(final CtxCore ctx, final JSONObject changes) {
        eachModule((feature, module) -> module.onUserChanged(ctx, changes));
    }

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

        for (ModuleBase module : modules.values()) {
            module.onDeviceId(ctx, id, old);
        }

        if (config.isLimited()) {
            config = Storage.read(ctx, new InternalConfig());
            if (config == null) {
                L.e("[SDKCore] Config reload gave null instance");
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
            ModuleBase module = module(cls);
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
    public void onRequestCompleted(Request request, String response, int responseCode, Class<? extends ModuleBase> requestOwner) {
        if (requestOwner != null) {
            ModuleBase module = module(requestOwner);

            if (module != null) {
                module.onRequestCompleted(request, response, responseCode);
            }
        }
    }

    protected void recover(CtxCore ctx) {
        List<Long> crashes = Storage.list(ctx, CrashImpl.getStoragePrefix());

        for (Long id : crashes) {
            L.i("[SDKCore] Found unprocessed crash " + id);
            onSignal(ctx, Signal.Crash.getIndex(), id.toString());
        }

        List<Long> sessions = Storage.list(ctx, SessionImpl.getStoragePrefix());
        for (Long id : sessions) {
            L.d("[SDKCore] recovering session " + id);
            SessionImpl session = Storage.read(ctx, new SessionImpl(ctx, id));
            if (session == null) {
                L.e("[SDKCore] no session with id " + id + " found while recovering");
            } else {
                Boolean success = session.recover(config, L);
                L.d("[SDKCore] session " + id + " recovery " + (success == null ? "won't recover" : success ? "success" : "failure"));
            }
        }
    }

    /**
     * Core instance config
     */

    public void onSignal(CtxCore ctx, int id, Byteable param1, Byteable param2) {
        if (id == Signal.DID.getIndex()) {
            networking.check(ctx);
        }
    }

    public void onSignal(CtxCore ctx, int id, String param) {
        if (id == Signal.Ping.getIndex()) {
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
            L.i("[SDKCore] Added request " + request.storageId() + " instead of crash " + crash.storageId());
            networking.check(ctx);
            Boolean success = Storage.remove(ctx, crash);
            return (success != null) && success;
        } else {
            L.e("[SDKCore] Couldn't write request " + request.storageId() + " instead of crash " + crash.storageId());
            return false;
        }
    }

    //transferred from original subclass
    public void onRequest(ly.count.sdk.java.internal.CtxCore ctx, Request request) {
        onSignal(ctx, SDKCore.Signal.Ping.getIndex(), null);
    }
}
