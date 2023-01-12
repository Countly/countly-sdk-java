package ly.count.sdk.java.internal;

import ly.count.sdk.java.Config;
import org.json.JSONObject;

import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.Future;

public abstract class SDKCore implements SDKInterface {

    protected static SDKCore instance;
    ModuleDeviceIdCore deviceModule = new ModuleDeviceIdCore();
    ModuleRequests requestModule = new ModuleRequests();
    ModuleViews viewsModule = new ModuleViews();
    ModuleSessions sessionsModule = new ModuleSessions();
    ModuleCrash crashModule = new ModuleCrash();
    ModuleViews backendModeModule = new ModuleViews();

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

    public interface Modulator {
        void run(int feature, Module module);
    }

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


        deviceModule.stop(ctx, clear);
        requestModule.stop(ctx, clear);
        viewsModule.stop(ctx, clear);
        sessionsModule.stop(ctx, clear);
        crashModule.stop(ctx, clear);
        backendModeModule.stop(ctx, clear);

        modules.clear();
        moduleMappings.clear();
        user = null;
        config = null;
        instance = null;
    }

    /**
     * Callback to add consents to the list
     *
     * @param consent consents to add
     */
    public void onConsent(CtxCore ctx, int consent) {
        if (!config().requiresConsent()) {
            L.e("[SDKModules] onConsent() shouldn't be called when Config.requiresConsent() is false");
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
        //TODO
        for (Module m : modules.values()) {
            m.onSessionEnded(session, ctx);
        }

        if (sessionsModule != null) {
            sessionsModule.forgetSession();
        }
        return session;
    }

    @Override
    public SessionImpl getSession() {
        if (sessionsModule != null) {
            return sessionsModule.getSession();
        }
        return null;
    }

    @Override
    public SessionImpl session(CtxCore ctx, Long id) {
        if (sessionsModule != null) {
            return sessionsModule.session(ctx, id);
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
        L.i("[SDKCore] [SDKCore] Initializing Countly in " + (ctx.getConfig().isLimited() ? "limited" : "full") + " mode");

        config = prepareConfig(ctx);
        Utils.reflectiveSetField(ctx, "config", config);

        this.init(ctx);

        requestQueueMemory = new ArrayDeque<>(config.getRequestQueueMaxSize());

        final List<Integer> failed = new ArrayList<>();

        deviceModule.init(config, logger);
        requestModule.init(config, logger);
        viewsModule.init(config, logger);
        sessionsModule.init(config, logger);
        crashModule.init(config, logger);
        backendModeModule.init(config, logger);

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
        deviceModule.onLimitedContextAcquired(ctx);
        requestModule.onLimitedContextAcquired(ctx);
        viewsModule.onLimitedContextAcquired(ctx);
        sessionsModule.onLimitedContextAcquired(ctx);
        crashModule.onLimitedContextAcquired(ctx);
        backendModeModule.onLimitedContextAcquired(ctx);
    }

    protected void onContextAcquired(final CtxCore ctx) {
        deviceModule.onContextAcquired(ctx);
        requestModule.onContextAcquired(ctx);
        viewsModule.onContextAcquired(ctx);
        sessionsModule.onContextAcquired(ctx);
        crashModule.onContextAcquired(ctx);
        backendModeModule.onContextAcquired(ctx);
    }


    @Override
    public UserImpl user() {
        return user;
    }

    TimedEvents timedEvents() {
        return sessionsModule.timedEvents();
    }

    @Override
    public InternalConfig config() {
        return config;
    }

    @Override
    public void onCrash(CtxCore ctx, Throwable t, boolean fatal, String name, Map<String, String> segments, String[] logs) {
        L.i("[SDKCore] [SDKCore] onCrash: t: " + t.toString() + " fatal: " + fatal + " name: " + name + " segments: " + segments);
        if (crashModule != null) {
            crashModule.onCrash(ctx, t, fatal, name, segments, logs);
        }
    }

    @Override
    public void onUserChanged(final CtxCore ctx, final JSONObject changes, final Set<String> cohortsAdded, final Set<String> cohortsRemoved) {
        deviceModule.onUserChanged(ctx, changes, cohortsAdded, cohortsRemoved);
        requestModule.onUserChanged(ctx, changes, cohortsAdded, cohortsRemoved);
        viewsModule.onUserChanged(ctx, changes, cohortsAdded, cohortsRemoved);
        sessionsModule.onUserChanged(ctx, changes, cohortsAdded, cohortsRemoved);
        crashModule.onUserChanged(ctx, changes, cohortsAdded, cohortsRemoved);
        backendModeModule.onUserChanged(ctx, changes, cohortsAdded, cohortsRemoved);
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
        return deviceModule.acquireId(ctx, holder, fallbackAllowed, callback);
    }

    public void login(CtxCore ctx, String id) {
        deviceModule.login(ctx, id);
    }

    public void logout(CtxCore ctx) {
        deviceModule.logout(ctx);
    }

    public void changeDeviceIdWithoutMerge(CtxCore ctx, String id) {
        deviceModule.changeDeviceId(ctx, id, false);
    }

    public void changeDeviceIdWithMerge(CtxCore ctx, String id) {
        deviceModule.changeDeviceId(ctx, id, true);
    }

    public static boolean enabled(CoreFeature feature) {
        return instance.config().getFeatures().contains(feature);
    }

    public boolean hasConsentForFeature(CoreFeature feature) {
        if (!instance.config.requiresConsent()) {
            //if no consent required, return true
            return true;
        }

        return enabled(feature);
    }

    public Boolean isRequestReady(Request request) {
        return true;
    }

    /**
     * After a network request has been finished
     * propagate that response to the module
     * that owns the request
     *
     * @param request the request that was sent, used to identify the request
     */
    public void onRequestCompleted(Request request, String response, int responseCode) {
        // request call back
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
