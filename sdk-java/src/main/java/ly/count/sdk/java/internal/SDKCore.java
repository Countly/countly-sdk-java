package ly.count.sdk.java.internal;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import javax.annotation.Nonnull;
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
    private final Object lockTimer = new Object();
    //guarded by lockTimer for writes
    volatile CountlyTimer countlyTimer;
    private ModuleConfiguration moduleConfiguration;

    protected Log L = null;
    protected static ModuleBase testDummyModule = null;//set during testing when trying to check the SDK's lifecycle
    /**
     * Currently enabled features with consents
     */
    protected volatile int consents = 0;

    /**
     * Selected by config map of module mappings
     */
    private static final Map<CoreFeature, Class<? extends ModuleBase>> moduleMappings = new ConcurrentHashMap<>();

    /**
     * The view IDs events carry while no views module runs.
     */
    static final ViewIdProvider NO_VIEW_IDS = new ViewIdProvider() {
        /**
         * No view runs, so there is no current view ID.
         */
        @Nonnull public String getCurrentViewId() {
            return "";
        }

        /**
         * No view runs, so there is no previous view ID.
         */
        @Nonnull public String getPreviousViewId() {
            return "";
        }
    };

    /**
     * Interfaces of module instances the SDK never runs, handed out in place of the interface of a
     * feature that does not run: before init, while its consent is not given, or when it is not
     * enabled, so a caller never gets {@code null}. Like every module interface, each sends its
     * calls to the module of its feature once that runs and ignores them until then.
     */
    public static final ModuleEvents.Events dormantEvents = new ModuleEvents().new Events();
    public static final ModuleFeedback.Feedback dormantFeedback = new ModuleFeedback().new Feedback();
    public static final ModuleContent.Content dormantContent = new ModuleContent().new Content();
    public static final ModuleCrashes.Crashes dormantCrashes = new ModuleCrashes().new Crashes();
    public static final ModuleViews.Views dormantViews = new ModuleViews().new Views();
    public static final ModuleDeviceIdCore.DeviceId dormantDeviceId = new ModuleDeviceIdCore().new DeviceId();
    public static final ModuleRemoteConfig.RemoteConfig dormantRemoteConfig = new ModuleRemoteConfig().new RemoteConfig();
    public static final ModuleUserProfile.UserProfile dormantUserProfile = new ModuleUserProfile().new UserProfile();
    public static final ModuleLocation.Location dormantLocation = new ModuleLocation().new Location();

    /**
     * The remote config download callbacks, those of the configuration and those registered through
     * the remote config interface, kept beyond the module, so one built when the consent is given,
     * or given again, calls them too.
     */
    final List<RCDownloadCallback> remoteConfigDownloadCallbacks = new CopyOnWriteArrayList<>();

    /**
     * The display registered through the content interface, kept beyond the module, so one built
     * when the consent is given, or given again, draws with it.
     */
    volatile ContentDisplay contentDisplay = null;

    // sorted by feature index, and safe to iterate while a consent change adds or removes a module
    protected final Map<Integer, ModuleBase> modules;

    /**
     * Set once {@link #stop(boolean)} starts, after which no settings change reaches the modules.
     */
    private volatile boolean stopping = false;

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
        this.modules = new ConcurrentSkipListMap<>();
        instance = this;
        sdkStorage = new SDKStorage();
    }

    protected static void registerDefaultModuleMappings() {
        moduleMappings.put(CoreFeature.DeviceId, ModuleDeviceIdCore.class);
        moduleMappings.put(CoreFeature.Requests, ModuleRequests.class);
        //moduleMappings.put(CoreFeature.Logs.getIndex(), Log.class);
        moduleMappings.put(CoreFeature.Views, ModuleViews.class);
        moduleMappings.put(CoreFeature.Sessions, ModuleSessions.class);
        moduleMappings.put(CoreFeature.CrashReporting, ModuleCrashes.class);
        moduleMappings.put(CoreFeature.BackendMode, ModuleBackendMode.class);
        moduleMappings.put(CoreFeature.Feedback, ModuleFeedback.class);
        moduleMappings.put(CoreFeature.Content, ModuleContent.class);
        moduleMappings.put(CoreFeature.Events, ModuleEvents.class);
        moduleMappings.put(CoreFeature.RemoteConfig, ModuleRemoteConfig.class);
        moduleMappings.put(CoreFeature.UserProfiles, ModuleUserProfile.class);
        moduleMappings.put(CoreFeature.Location, ModuleLocation.class);
    }

    /**
     * Check if consent has been given for a feature
     *
     * @param feat feature to test against, pass null to test if any consent given
     * @return {@code true} if consent has been given
     */
    public boolean isTracking(Integer feat) {
        return modules.containsKey(feat);
    }

    private void onTimer() {
        modules.forEach((feature, module) -> {
            try {
                module.onTimer();
            } catch (Throwable t) {
                //one failing module does not hold back the others on the same tick
                L.e("[SDKCore] onTimer, " + module.getClass().getSimpleName() + " failed on a tick, [" + t + "]");
            }
        });
        L.flushGatheredLogs();
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
        stopping = true;

        if (networking != null) {
            networking.stop(config);
        }

        CountlyTimer timerToStop;
        synchronized (lockTimer) {
            timerToStop = countlyTimer;
            countlyTimer = null;
        }
        if (timerToStop != null) {
            timerToStop.stopTimer();
        }

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
        L.endLogGathering(!clear);
        sdkStorage.stop(config, clear);//from original super class

        user = null;
        moduleConfiguration = null;
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
        L.releaseConsentedLogLines();

        for (CoreFeature feature : moduleMappings.keySet()) {
            ModuleBase existing = module(moduleMappings.get(feature));
            if (SDKCore.enabled(feature) && existing == null) {
                ModuleBase module = instantiateModule(feature);
                if (module == null) {
                    L.e("[SDKCore] Cannot instantiate module " + feature);
                } else {
                    module.init(config);
                    module.initFinished(config);
                    module.setActive(true);
                    modules.put(feature.getIndex(), module);
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
            ModuleLocation location = module(ModuleLocation.class);
            if (location != null) {
                location.eraseLocation();
            }
        }

        consents = consents & ~noConsent;
        L.dropLinesWithoutConsent();

        for (CoreFeature feature : moduleMappings.keySet()) {
            if (feature == CoreFeature.Sessions || (noConsent & feature.getIndex()) == 0) {
                continue;
            }
            ModuleBase existing = module(moduleMappings.get(feature));
            if (existing != null) {
                existing.stop(config, true);
                modules.remove(feature.getIndex());
            }
        }
    }

    /**
     * Create instances of {@link ModuleBase}s required by {@link #config}.
     * Uses {@link #moduleMappings} for {@code Config.Feature} / {@link CoreFeature}
     * - Class&lt;ModuleBase&gt; mapping to enable overriding by app developer.
     *
     * @throws IllegalArgumentException in case some {@link ModuleBase} finds {@link #config} inconsistent.
     * @throws IllegalStateException when this module is run second time on the same {@code Core} instance.
     */
    protected void prepareMappings() throws IllegalStateException {
        if (!modules.isEmpty()) {
            throw new IllegalStateException("Modules can only be built once");
        }

        moduleMappings.clear();
        registerDefaultModuleMappings();
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
        if (moduleConfiguration != null) {
            modules.put(-1, moduleConfiguration);
        }
        modules.put(0, new ModuleConnectionTest());
        modules.put(CoreFeature.Sessions.getIndex(), new ModuleSessions());
        modules.put(CoreFeature.UserProfiles.getIndex(), new ModuleUserProfile());

        if (config.requiresConsent()) {
            consents = 0;
        } else {
            consents = config.getFeatures1();
        }

        if (!config.requiresConsent()) {
            for (CoreFeature feature : moduleMappings.keySet()) {
                Class<? extends ModuleBase> cls = moduleMappings.get(feature);
                if (cls == null) {
                    continue;
                }
                ModuleBase existing = module(cls);
                if ((features & feature.getIndex()) > 0 && existing == null) {
                    ModuleBase m = instantiateModule(feature);
                    if (m != null) {
                        modules.put(feature.getIndex(), m);
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
     * @param coreFeature int value of feature
     * @return {@link ModuleBase} instance or null if {@link ModuleBaseCreator} is not set for {@code feature}
     */
    private ModuleBase instantiateModule(CoreFeature coreFeature) {
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
    protected ModuleBase module(CoreFeature feature) {
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
        ModuleSessions sessions = (ModuleSessions) module(CoreFeature.Sessions);
        if (sessions != null) {
            sessions.forgetSession();
        }
        return session;
    }

    public SessionImpl getSession() {
        ModuleSessions sessions = (ModuleSessions) module(CoreFeature.Sessions);
        if (sessions != null) {
            return sessions.getSession();
        }
        return null;
    }

    /**
     * Get current {@link SessionImpl} or create new one if current is {@code null}.
     *
     * @param id ID of new {@link SessionImpl} if needed
     * @return current {@link SessionImpl} instance
     */
    public SessionImpl session(final Long id) {
        ModuleSessions sessions = (ModuleSessions) module(CoreFeature.Sessions);
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
        prepareMappings();

        //setup and perform migrations
        MigrationHelper migrationHelper = new MigrationHelper(L);
        migrationHelper.setupMigrations(config.storageProvider);
        Map<String, Object> migrationParams = new ConcurrentHashMap<>();
        migrationParams.put("sdk_path", config.getSdkStorageRootDirectory());
        migrationHelper.applyMigrations(migrationParams);

        setDeviceIdFromStorageIfExist(config);

        //resolve the SDK behavior settings before anything reads them
        moduleConfiguration = new ModuleConfiguration();
        moduleConfiguration.init(config);
        applyLoggingSetting(config, moduleConfiguration);
        applyConsentRequirementAtInit(config, moduleConfiguration.getConsentRequired());
        L.setLogGatheringProviders(moduleConfiguration, this::givenLogLineConsents, gatheredLogBatchQueue);

        //create internal timer
        synchronized (lockTimer) {
            startTimer(moduleConfiguration.getSessionUpdateInterval());
        }

        requestQueueMemory = new ArrayDeque<>(config.getRequestQueueMaxSize());
        remoteConfigDownloadCallbacks.addAll(config.getRemoteConfigGlobalCallbackList());

        if (config.viewIdGenerator == null) {
            config.viewIdGenerator = Utils::safeRandomVal;
        }

        if (config.eventIdGenerator == null) {
            config.eventIdGenerator = Utils::safeRandomVal;
        }

        if (config.viewIdProvider == null) {
            config.viewIdProvider = NO_VIEW_IDS;
        }

        // ModuleSessions is always enabled, even without consent
        int consents = config.getFeatures1() | CoreFeature.Sessions.getIndex();
        // build modules
        buildModules(config, consents);

        final List<Integer> failed = new ArrayList<>();

        modules.forEach((feature, module) -> {
            try {
                if (module != moduleConfiguration) {
                    module.init(config);
                }
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

        recover(config);

        user = new UserImpl(config);
        initFinished(config);
    }

    private void checkNetworking(InternalConfig config) {
        if (networking != null) {
            networking.check(config);
        }
    }

    private void initFinished(final InternalConfig config) {
        modules.forEach((feature, module) -> module.initFinished(config));
        checkNetworking(config);
        L.onSdkInitFinished();
    }

    /**
     * The features whose consent lets a gathered log line go: every feature while no consent is
     * required, otherwise the ones consent was given for. Read inside log calls, so it never logs.
     *
     * @return the {@link CoreFeature} indexes, every bit while no consent is required
     */
    private int givenLogLineConsents() {
        InternalConfig current = config;
        if (current == null) {
            return 0;
        }
        if (!current.requiresConsent()) {
            return ~0;
        }
        int given = consents;
        //a feature the app never enabled holds no data and can never get consent, so it holds no line back
        return given == 0 ? 0 : given | (~current.getFeatures1() & Log.UNATTRIBUTED_LINE_CONSENT);
    }

    /**
     * The request queue as the log gathering uses it: one request per batch of gathered log lines,
     * waiting until it is sent or dropped.
     */
    private final Log.LogBatchQueue gatheredLogBatchQueue = new Log.LogBatchQueue() {
        /**
         * Stores the batch as a request of its own, unless the SDK stopped.
         */
        @Nullable
        @Override
        public Long queueLogBatch(@Nonnull String payload) {
            InternalConfig current = config;
            return current == null ? null : ModuleRequests.pushSdkLogs(current, payload);
        }

        /**
         * Whether the request file is still stored, which it is until the request is sent or dropped.
         */
        @Override
        public boolean isQueued(@Nonnull Long requestId) {
            InternalConfig current = config;
            return current != null && sdkStorage.storableExists(current, Request.getStoragePrefix(), requestId);
        }
    };

    /**
     * Makes the consent requirement the SDK behavior settings resolved the one every module reads
     * through {@link InternalConfig#requiresConsent()}. It is applied here, before the modules are
     * built, and only here: a value that arrives later takes effect on the next init.
     *
     * @param config configuration of the SDK being initialized
     * @param consentRequired the resolved consent requirement
     */
    private void applyConsentRequirementAtInit(@Nonnull InternalConfig config, boolean consentRequired) {
        if (consentRequired != config.requiresConsent()) {
            L.i("[SDKCore] applyConsentRequirementAtInit, SDK behavior settings set the consent requirement to [" + consentRequired + "]");
            config.setRequiresConsent(consentRequired);
        }
    }

    /**
     * Starts the global timer, replacing the running one. The caller holds {@link #lockTimer}.
     *
     * @param intervalSeconds seconds between two ticks
     */
    private void startTimer(int intervalSeconds) {
        countlyTimer = new CountlyTimer(L);
        countlyTimer.startTimer(intervalSeconds, this::onTimer);
    }

    /**
     * Restarts the global timer when its interval differs from the given one. Never waits for the
     * replaced timer, as a settings change can be applied from a timer task.
     *
     * @param intervalSeconds the session update interval now in effect
     */
    private void restartTimerIfIntervalChanged(int intervalSeconds) {
        synchronized (lockTimer) {
            CountlyTimer runningTimer = countlyTimer;
            if (runningTimer == null || runningTimer.getTimerDelaySeconds() == intervalSeconds) {
                return;
            }

            L.d("[SDKCore] restartTimerIfIntervalChanged, session update interval changed from [" + runningTimer.getTimerDelaySeconds() + "] to [" + intervalSeconds + "] seconds, restarting the timer");
            runningTimer.stopTimer(false);
            startTimer(intervalSeconds);
        }
    }

    /**
     * Applies the {@code log} switch of the SDK behavior settings to what the SDK logger prints.
     *
     * @param config configuration whose logger prints
     * @param provider the settings in effect
     */
    private void applyLoggingSetting(@Nonnull InternalConfig config, @Nonnull ConfigurationProvider provider) {
        Log logger = config.getLogger();
        if (logger == null) {
            return;
        }

        logger.setLoggingEnabled(provider.getLoggingEnabled());
        logger.d("[SDKCore] applyLoggingSetting, logging enabled:[" + provider.getLoggingEnabled() + "], printing from level:[" + logger.getPrintLevel() + "]");
    }

    /**
     * Applies SDK behavior settings that a server response changed while the SDK runs: sets what the
     * logger prints, restarts the global timer when the session update interval changed, lets the
     * request queue drain when networking is allowed, then notifies every module, each on its own.
     * A change that arrives once the SDK started stopping is ignored, as its modules are being stopped.
     *
     * @param changedConfig configuration whose settings changed
     */
    void onSdkConfigurationChanged(@Nonnull InternalConfig changedConfig) {
        if (stopping || config == null || config != changedConfig) {
            L.d("[SDKCore] onSdkConfigurationChanged, the SDK is stopping or was stopped before the change arrived, ignoring it");
            return;
        }

        L.i("[SDKCore] onSdkConfigurationChanged");
        ConfigurationProvider provider = changedConfig.getConfigurationProvider();
        applyLoggingSetting(changedConfig, provider);
        restartTimerIfIntervalChanged(provider.getSessionUpdateInterval());

        if (provider.getNetworkingEnabled()) {
            checkNetworking(changedConfig);
        }

        List<ModuleBase> modulesToNotify = new ArrayList<>(modules.values());
        for (ModuleBase module : modulesToNotify) {
            try {
                module.onSdkConfigurationChanged(changedConfig);
            } catch (Exception e) {
                L.e("[SDKCore] onSdkConfigurationChanged, " + module.getClass().getSimpleName() + " failed to apply the change, [" + e + "]");
            }
        }
    }

    public UserImpl user() {
        return user;
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
        ((ModuleDeviceIdCore) module(CoreFeature.DeviceId)).login(id);
    }

    public void logout() {
        ((ModuleDeviceIdCore) module(CoreFeature.DeviceId)).logout();
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

    /**
     * The logger the SDK was configured with, so code outside of this package (a
     * {@link ContentDisplay} implementation, for example) can log through the same level and
     * listener the integrator set up.
     *
     * @return the SDK logger, or {@code null} while the SDK is not initialized
     */
    public static Log logger() {
        SDKCore core = instance;
        return core == null ? null : core.L;
    }

    /**
     * The application's link handler, so code outside this package (a display implementation) can
     * offer it a link before opening it itself.
     *
     * @return the handler, or {@code null} when none was configured or the SDK is not initialized
     */
    public static ContentUrlHandler contentUrlHandler() {
        SDKCore core = instance;
        if (core == null || core.config == null) {
            return null;
        }
        return core.config.content.contentUrlHandler;
    }

    /**
     * The directory the SDK keeps its files in, so a display implementation outside this package can
     * cache what it needs to show a widget without waiting for the network.
     *
     * @return the directory, or {@code null} when the SDK is not initialized
     */
    public static File sdkStorageDirectory() {
        SDKCore core = instance;
        if (core == null || core.config == null) {
            return null;
        }
        return core.config.getSdkStorageRootDirectory();
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
            // The owner stays on the request: Transport.send reads it to hand over the response, then removes it before sending
            ModuleBase module = module(cls);
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
     * that owns the request. Neither looking the owner up nor the owner failing to handle the
     * response ever fails the send.
     *
     * @param request the request that was sent, used to identify the request
     */
    public void onRequestCompleted(Request request, String response, int responseCode, Class<? extends ModuleBase> requestOwner) {
        if (requestOwner == null) {
            return;
        }

        try {
            ModuleBase module = module(requestOwner);
            if (module != null) {
                module.onRequestCompleted(request, response, responseCode);
            }
        } catch (Exception e) {
            L.e("[SDKCore] onRequestCompleted, " + requestOwner.getSimpleName() + " failed to handle the response of request [" + (request == null ? null : request.storageId()) + "], [" + e + "]");
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
            checkNetworking(config);
        }
    }

    public void onSignal(InternalConfig config, int id, String param) {
        if (id == Signal.Ping.getIndex()) {
            checkNetworking(config);
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

        if (ModuleRequests.pushWithinQueueLimit(config, request)) {
            L.i("[SDKCore] Added request " + request.storageId() + " instead of crash " + crash.storageId());
            checkNetworking(config);
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

    //Module functions

    /**
     * @return timedEvents interface
     * @deprecated use {@link ModuleEvents.Events#startEvent(String)} instead via <code>instance().events()</code> call
     */
    TimedEvents timedEvents() {
        return ((ModuleSessions) module(CoreFeature.Sessions)).timedEvents();
    }

    /**
     * The events interface: the running module's, or one that ignores calls until the events feature runs, as its consent is not given or it is not enabled.
     *
     * @return the interface, never {@code null}
     */
    public ModuleEvents.Events events() {
        return runningInterface(CoreFeature.Events, ModuleEvents.class, module -> module.eventsInterface, dormantEvents);
    }

    /**
     * The feedback interface: the running module's, or one that ignores calls until the feedback feature runs, as its consent is not given or it is not enabled.
     *
     * @return the interface, never {@code null}
     */
    public ModuleFeedback.Feedback feedback() {
        return runningInterface(CoreFeature.Feedback, ModuleFeedback.class, module -> module.feedbackInterface, dormantFeedback);
    }

    /**
     * The content interface: the running module's, or one that ignores calls until the content feature runs, as its consent is not given or it is not enabled.
     *
     * @return the interface, never {@code null}
     */
    public ModuleContent.Content content() {
        return runningInterface(CoreFeature.Content, ModuleContent.class, module -> module.contentInterface, dormantContent);
    }

    /**
     * The crashes interface: the running module's, or one that ignores calls until crash reporting runs, as its consent is not given or it is not enabled.
     *
     * @return the interface, never {@code null}
     */
    public ModuleCrashes.Crashes crashes() {
        return runningInterface(CoreFeature.CrashReporting, ModuleCrashes.class, module -> module.crashInterface, dormantCrashes);
    }

    /**
     * The views interface: the running module's, or one that ignores calls until the views feature runs, as its consent is not given or it is not enabled.
     *
     * @return the interface, never {@code null}
     */
    public ModuleViews.Views views() {
        return runningInterface(null, ModuleViews.class, module -> module.viewsInterface, dormantViews);
    }

    /**
     * The device ID interface: the running module's, or one that ignores calls until the device ID module runs.
     *
     * @return the interface, never {@code null}
     */
    public ModuleDeviceIdCore.DeviceId deviceId() {
        return runningInterface(null, ModuleDeviceIdCore.class, module -> module.deviceIdInterface, dormantDeviceId);
    }

    /**
     * The remote config interface: the running module's, or one that ignores calls until remote config runs, as its consent is not given or it is not enabled.
     *
     * @return the interface, never {@code null}
     */
    public ModuleRemoteConfig.RemoteConfig remoteConfig() {
        return runningInterface(CoreFeature.RemoteConfig, ModuleRemoteConfig.class, module -> module.remoteConfigInterface, dormantRemoteConfig);
    }

    /**
     * The user profile interface: the running module's, or one that ignores calls until the user profile module runs.
     *
     * @return the interface, never {@code null}
     */
    public ModuleUserProfile.UserProfile userProfile() {
        return runningInterface(null, ModuleUserProfile.class, module -> module.userProfileInterface, dormantUserProfile);
    }

    /**
     * The location interface: the running module's, or one that ignores calls until the location feature runs, as its consent is not given or it is not enabled.
     *
     * @return the interface, never {@code null}
     */
    public ModuleLocation.Location location() {
        return runningInterface(CoreFeature.Location, ModuleLocation.class, module -> module.locationInterface, dormantLocation);
    }

    /**
     * The interface of a running module, or the one handed out while it does not run, whose calls
     * go to the module once it runs and are ignored until then.
     *
     * @param consent the feature whose consent the module needs, {@code null} when it needs none
     * @param moduleClass the class of the module
     * @param interfaceOf reads the interface of a module
     * @param dormant the interface handed out while the module does not run
     * @param <M> the module type
     * @param <I> the interface type
     * @return the interface, never {@code null}
     */
    private <M extends ModuleBase, I> I runningInterface(@Nullable CoreFeature consent, @Nonnull Class<M> moduleClass, @Nonnull Function<M, I> interfaceOf, @Nonnull I dormant) {
        M module = consent == null ? module(moduleClass) : runningModule(consent, moduleClass);
        I running = module == null ? null : interfaceOf.apply(module);
        if (running == null) {
            L.v("[SDKCore] runningInterface, " + moduleClass.getSimpleName() + " does not run, handing out an interface that ignores calls until it does");
            return dormant;
        }
        return running;
    }

    /**
     * The module of a feature while it runs: it was built and the feature's consent is given.
     *
     * @param feature the feature
     * @param moduleClass the class of its module
     * @param <M> the module type
     * @return the module, {@code null} while it does not run
     */
    @Nullable
    <M extends ModuleBase> M runningModule(@Nonnull CoreFeature feature, @Nonnull Class<M> moduleClass) {
        M module = module(moduleClass);
        return module != null && hasConsentForFeature(feature) ? module : null;
    }
}
