package ly.count.sdk.java.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;
import javax.annotation.Nullable;
import ly.count.sdk.java.Config;

public class SDKCore {

    protected static SDKCore instance;

    protected final SDKStorage sdkStorage;
    private UserImpl user;

    public InternalConfig config;
    protected Networking networking;
    protected Queue<Request> requestQueueMemory = null;

    protected final Object lockBRQStorage = new Object();

    private CountlyTimer countlyTimer;

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
    protected static ModuleBase testDummyModule = null;//set during testing when trying to check the SDK's lifecycle

    protected static void registerDefaultModuleMappings() {
        moduleMappings.put(CoreFeature.DeviceId.getIndex(), ModuleDeviceIdCore.class);
        moduleMappings.put(CoreFeature.Requests.getIndex(), ModuleRequests.class);
        //moduleMappings.put(CoreFeature.Logs.getIndex(), Log.class);
        moduleMappings.put(CoreFeature.Views.getIndex(), ModuleViews.class);
        moduleMappings.put(CoreFeature.Sessions.getIndex(), ModuleSessions.class);
        moduleMappings.put(CoreFeature.CrashReporting.getIndex(), ModuleCrashes.class);
        moduleMappings.put(CoreFeature.BackendMode.getIndex(), ModuleBackendMode.class);
        moduleMappings.put(CoreFeature.Feedback.getIndex(), ModuleFeedback.class);
        moduleMappings.put(CoreFeature.Events.getIndex(), ModuleEvents.class);
        moduleMappings.put(CoreFeature.RemoteConfig.getIndex(), ModuleRemoteConfig.class);
        moduleMappings.put(CoreFeature.UserProfiles.getIndex(), ModuleUserProfile.class);
        moduleMappings.put(CoreFeature.Location.getIndex(), ModuleLocation.class);
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
    protected final Map<Integer, ModuleBase> modules;

    /**
     * Check if consent has been given for a feature
     *
     * @param feat feature to test against, pass null to test if any consent given
     * @return {@code true} if consent has been given
     */
    public boolean isTracking(Integer feat) {
        return modules != null && modules.containsKey(feat);
    }

    private void onTimer() {
        modules.forEach((feature, module) -> module.onTimer());
    }

    /**
     * Stop sdk core
     *
     * @param clear if true, clear all data
     * @deprecated use {@link #halt()} instead
     */
    public void stop(final boolean clear) {
        if (instance == null) {
            return;
        }

        if (networking != null) {
            networking.stop(config);
        }

        countlyTimer.stopTimer();

        L.i("[SDKCore] Stopping Countly SDK" + (clear ? " and clearing all data" : ""));

        modules.forEach((feature, module) -> {
            try {
                module.stop(config, clear);
                module.setActive(false);
            } catch (Throwable e) {
                L.e("[SDKCore] Exception while stopping " + module.getClass() + " " + e);
            }
        });

        modules.clear();
        moduleMappings.clear();
        sdkStorage.stop(config, clear);//from original super class

        user = null;
        config = null;
        instance = null;
    }

    /**
     * Stop sdk core
     */
    public void halt() {
        stop(true);
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
    public void onConsent(final int consent) {
        if (!config().requiresConsent()) {
            L.e("[SDKModules] onConsent() shouldn't be called when Config.requiresConsent() is false");
            return;
        }

        if (addingConsent(consent, CoreFeature.Sessions)) {
            SessionImpl session = module(ModuleSessions.class).getSession();
            if (session != null) {
                session.end();
            }

            consents = consents | (consent & config.getFeatures1());

            module(ModuleSessions.class).session(config, null).begin();
        }

        consents = consents | (consent & config.getFeatures1());

        for (Integer feature : moduleMappings.keySet()) {
            ModuleBase existing = module(moduleMappings.get(feature));
            if (SDKCore.enabled(feature) && existing == null) {
                ModuleBase module = instantiateModule(feature);
                if (module == null) {
                    L.e("[SDKCore] Cannot instantiate module " + feature);
                } else {
                    module.init(config);
                    module.initFinished(config);
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
    public void onConsentRemoval(final InternalConfig config, int noConsent) {
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
                existing.stop(config, true);
                modules.remove(feature);
            }
        }
    }

    /**
     * Create instances of {@link ModuleBase}s required by {@link #config}.
     * Uses {@link #moduleMappings} for {@code Config.Feature} / {@link CoreFeature}
     * - Class&lt;ModuleBase&gt; mapping to enable overriding by app developer.
     *
     * @param config {@link InternalConfig} object containing config with mapping overrides
     * @throws IllegalArgumentException in case some {@link ModuleBase} finds {@link #config} inconsistent.
     * @throws IllegalStateException when this module is run second time on the same {@code Core} instance.
     */
    protected void prepareMappings(InternalConfig config) throws IllegalStateException {
        if (!modules.isEmpty()) {
            throw new IllegalStateException("Modules can only be built once");
        }

        moduleMappings.clear();
        registerDefaultModuleMappings();

        for (int feature : config.getModuleOverrides()) {
            registerModuleMapping(feature, config.getModuleOverride(feature));
        }
    }

    /**
     * Create instances of {@link ModuleBase}s required by {@link #config}.
     * Uses {@link #moduleMappings} for {@code Config.Feature} / {@link CoreFeature}
     * - Class&lt;ModuleBase&gt; mapping to enable overriding by app developer.
     *
     * @param config {@link InternalConfig} object
     * @param features consents bitmask to check against
     * @throws IllegalArgumentException in case some {@link ModuleBase} finds {@link #config} inconsistent.
     * @throws IllegalStateException when this module is run second time on the same {@code Core} instance.
     */
    protected void buildModules(InternalConfig config, int features) throws IllegalArgumentException, IllegalStateException {
        // override module mappings in native/Android parts, overriding by Config ones if necessary

        if (!modules.isEmpty()) {
            throw new IllegalStateException("Modules can only be built once");
        }

        //        if (config.getLoggingLevel() != Config.LoggingLevel.OFF) {
        //            modules.put(-10, instantiateModule(moduleMappings.get(CoreFeature.Logs.getIndex()), L));
        //        }

        // standard required internal features
        modules.put(-3, new ModuleDeviceIdCore());
        modules.put(-2, new ModuleRequests());
        modules.put(CoreFeature.Sessions.getIndex(), new ModuleSessions());
        modules.put(CoreFeature.UserProfiles.getIndex(), new ModuleUserProfile());

        if (config.requiresConsent()) {
            consents = 0;
        } else {
            consents = config.getFeatures1();
        }

        if (!config.requiresConsent()) {
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

    /**
     * Notify all {@link ModuleBase} instances about new session has just been started
     *
     * @param session session to begin
     * @return supplied session for method chaining
     */
    public SessionImpl onSessionBegan(final InternalConfig config, SessionImpl session) {
        for (ModuleBase m : modules.values()) {
            m.onSessionBegan(session, config);
        }
        return session;
    }

    /**
     * Notify all {@link ModuleBase} instances session was ended
     *
     * @param session session to end
     * @return supplied session for method chaining
     */
    public SessionImpl onSessionEnded(final InternalConfig config, SessionImpl session) {
        for (ModuleBase m : modules.values()) {
            m.onSessionEnded(session, config);
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

    public ModuleFeedback.Feedback feedback() {

        if (!hasConsentForFeature(CoreFeature.Feedback)) {
            L.v("[SDKCore] feedback, Feedback feature has no consent, returning null");
            return null;
        }

        return module(ModuleFeedback.class).feedbackInterface;
    }

    public ModuleCrashes.Crashes crashes() {
        if (!hasConsentForFeature(CoreFeature.CrashReporting)) {
            L.v("[SDKCore] crash, Crash Reporting feature has no consent, returning null");
            return null;
        }

        return module(ModuleCrashes.class).crashInterface;
    }

    public ModuleViews.Views views() {
        if (!hasConsentForFeature(CoreFeature.Views)) {
            L.v("[SDKCore] views, Views feature has no consent, returning null");
            return null;
        }

        return module(ModuleViews.class).viewsInterface;
    }

    public ModuleDeviceIdCore.DeviceId deviceId() {
        return module(ModuleDeviceIdCore.class).deviceIdInterface;
    }

    public ModuleRemoteConfig.RemoteConfig remoteConfig() {
        if (!hasConsentForFeature(CoreFeature.RemoteConfig)) {
            L.v("[SDKCore] remoteConfig, RemoteConfig feature has no consent, returning null");
            return null;
        }

        return module(ModuleRemoteConfig.class).remoteConfigInterface;
    }

    public ModuleUserProfile.UserProfile userProfile() {
        return module(ModuleUserProfile.class).userProfileInterface;
    }

    public ModuleLocation.Location location() {
        if (!hasConsentForFeature(CoreFeature.Location)) {
            L.v("[SDKCore] location, Location feature has no consent, returning null");
            return null;
        }
        ModuleLocation module = module(ModuleLocation.class);
        if (module == null) {
            return null;
        }
        return module.locationInterface;
    }

    /**
     * Get current {@link SessionImpl} or create new one if current is {@code null}.
     *
     * @param id ID of new {@link SessionImpl} if needed
     * @return current {@link SessionImpl} instance
     */
    public SessionImpl session(final Long id) {
        ModuleSessions sessions = (ModuleSessions) module(CoreFeature.Sessions.getIndex());
        if (sessions != null) {
            return sessions.session(config, id);
        }
        return null;
    }

    protected void setDeviceIdFromStorageIfExist(InternalConfig config) {
        String deviceId = sdkStorage.getDeviceID();
        String deviceIdType = sdkStorage.getDeviceIdType();

        if (Utils.isEmptyOrNull(deviceId) || Utils.isEmptyOrNull(deviceIdType)) {
            return;
        }

        config.setDeviceId(new Config.DID(DeviceIdType.toInt(deviceIdType), deviceId));
    }

    public void init(final InternalConfig givenConfig) {
        config = givenConfig;
        L = givenConfig.getLogger();
        L.i("[SDKCore] Initializing Countly");

        config.sdk = this;
        sdkStorage.init(config);
        config.storageProvider = sdkStorage;

        if (config.immediateRequestGenerator == null) {
            config.immediateRequestGenerator = ImmediateRequestMaker::new;
        }

        //setup module mapping
        prepareMappings(config);

        //create internal timer
        countlyTimer = new CountlyTimer(L);
        countlyTimer.startTimer(config.getSendUpdateEachSeconds(), this::onTimer);

        //setup and perform migrations
        MigrationHelper migrationHelper = new MigrationHelper(L);
        migrationHelper.setupMigrations(config.storageProvider);
        Map<String, Object> migrationParams = new HashMap<>();
        migrationParams.put("sdk_path", config.getSdkStorageRootDirectory());
        migrationHelper.applyMigrations(migrationParams);

        setDeviceIdFromStorageIfExist(config);

        requestQueueMemory = new ArrayDeque<>(config.getRequestQueueMaxSize());
        // ModuleSessions is always enabled, even without consent
        int consents = config.getFeatures1() | CoreFeature.Sessions.getIndex();
        // build modules
        buildModules(config, consents);

        final List<Integer> failed = new ArrayList<>();

        modules.forEach((feature, module) -> {
            try {
                module.init(config);
                module.setActive(true);
            } catch (IllegalArgumentException | IllegalStateException e) {
                L.e("[SDKCore] Error during module initialization" + e);
                failed.add(feature);
            }
        });

        for (Integer feature : failed) {
            modules.remove(feature);
        }

        recover(config);

        if (config.isDefaultNetworking()) {
            networking = new DefaultNetworking();

            if (config.isBackendModeEnabled()) {
                //Backend mode is enabled, we will use memory only request queue.
                networking.init(config, new IStorageForRequestQueue() {
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

                    @Override
                    public Integer remaningRequests() {
                        synchronized (SDKCore.instance.lockBRQStorage) {
                            return requestQueueMemory.size() - 1;
                        }
                    }
                });
            } else {
                // Backend mode isn't enabled, we use persistent file storage.
                networking.init(config, new IStorageForRequestQueue() {
                    @Override
                    public Request getNextRequest() {
                        return Storage.readOne(config, new Request(0L), true);
                    }

                    @Override
                    public Boolean removeRequest(Request request) {
                        return Storage.remove(config, request);
                    }

                    @Override
                    public Integer remaningRequests() {
                        return Storage.list(config, Request.getStoragePrefix()).size() - 1;
                    }
                });
            }
        }

        try {
            user = Storage.read(config, new UserImpl(config));
            if (user == null) {
                user = new UserImpl(config);
            }
        } catch (Throwable e) {
            L.e("[SDKCore] Cannot happen" + e);
            user = new UserImpl(config);
        }

        initFinished(config);
    }

    private void initFinished(final InternalConfig config) {
        modules.forEach((feature, module) -> module.initFinished(config));
        if (config.isDefaultNetworking()) {
            networking.check(config);
        }
    }

    public UserImpl user() {
        return user;
    }

    /**
     * @return timedEvents interface
     * @deprecated use {@link ModuleEvents.Events#startEvent(String)} instead via <code>instance().events()</code> call
     */
    TimedEvents timedEvents() {
        return ((ModuleSessions) module(CoreFeature.Sessions.getIndex())).timedEvents();
    }

    public ModuleEvents.Events events() {
        return ((ModuleEvents) module(CoreFeature.Events.getIndex())).eventsInterface;
    }

    public InternalConfig config() {
        return config;
    }

    public void notifyModulesDeviceIdChanged(@Nullable String old, final boolean withMerge) {
        L.d("[SDKCore] notifyModulesDeviceIdChanged, newDeviceId:[" + config.getDeviceId() + "], oldDeviceId:[ " + old + "]");
        Config.DID id = config.getDeviceId();
        if (id.id.equals(old)) {
            L.d("[SDKCore] notifyModulesDeviceIdChanged, newDeviceId is the same as oldDeviceId, skipping");
            return;
        }
        modules.forEach((feature, module) -> module.deviceIdChanged(old, withMerge));
        user.id = id.id;
    }

    public void login(String id) {
        ((ModuleDeviceIdCore) module(CoreFeature.DeviceId.getIndex())).login(id);
    }

    public void logout() {
        ((ModuleDeviceIdCore) module(CoreFeature.DeviceId.getIndex())).logout();
    }

    /**
     * Change device ID
     *
     * @param config to configure
     * @param id to change to
     * @deprecated use {@link ModuleDeviceIdCore.DeviceId#changeWithoutMerge(String)}
     */
    public void changeDeviceIdWithoutMerge(InternalConfig config, String id) {
        deviceId().changeWithoutMerge(id);
    }

    /**
     * Change device ID
     *
     * @param config to configure
     * @param id to change to
     * @deprecated use {@link ModuleDeviceIdCore.DeviceId#changeWithMerge(String)} instead
     */
    public void changeDeviceIdWithMerge(InternalConfig config, String id) {
        deviceId().changeWithMerge(id);
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

    protected void recover(InternalConfig config) {
        List<Long> crashes = Storage.list(config, CrashImpl.getStoragePrefix());

        for (Long id : crashes) {
            L.i("[SDKCore] Found unprocessed crash " + id);
            onSignal(config, Signal.Crash.getIndex(), id.toString());
        }

        List<Long> sessions = Storage.list(config, SessionImpl.getStoragePrefix());
        for (Long id : sessions) {
            L.d("[SDKCore] recovering session " + id);
            SessionImpl session = Storage.read(config, new SessionImpl(config, id));
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

    public void onSignal(InternalConfig config, int id) {
        if (id == Signal.DID.getIndex()) {
            networking.check(config);
        }
    }

    public void onSignal(InternalConfig config, int id, String param) {
        if (id == Signal.Ping.getIndex()) {
            networking.check(config);
        } else if (id == Signal.Crash.getIndex()) {
            processCrash(config, Long.parseLong(param));
        }
    }

    private boolean processCrash(InternalConfig config, Long id) {
        CrashImpl crash = new CrashImpl(id, L);
        crash = Storage.read(config, crash);

        if (crash == null) {
            L.e("Cannot read crash from storage, skipping");
            return false;
        }

        Request request = ModuleRequests.nonSessionRequest(config);
        request.params.add("crash", crash.getJSON());

        ModuleRequests.addRequiredParametersToParams(config, request.params);
        ModuleRequests.addRequiredTimeParametersToParams(request.params);

        if (Storage.push(config, request)) {
            L.i("[SDKCore] Added request " + request.storageId() + " instead of crash " + crash.storageId());
            networking.check(config);
            Boolean success = Storage.remove(config, crash);
            return (success != null) && success;
        } else {
            L.e("[SDKCore] Couldn't write request " + request.storageId() + " instead of crash " + crash.storageId());
            return false;
        }
    }

    //transferred from original subclass
    public void onRequest(InternalConfig config, Request request) {
        onSignal(config, SDKCore.Signal.Ping.getIndex(), null);
    }
}
