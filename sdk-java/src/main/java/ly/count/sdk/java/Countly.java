package ly.count.sdk.java;

import java.io.File;
import java.util.Map;

import ly.count.sdk.java.internal.*;

/**
 * Main Countly SDK API class.
 * <ul>
 *     <li>Initialize Countly SDK using {@code #init(Application, Config)}.</li>
 *     <li>Stop Countly SDK with {@link #stop(boolean)} if needed.</li>
 * </ul>
 */

public class Countly implements Usage {

    /**
     * A class responsible for storage of device information sent to Countly server.
     * Call respective setters (i.e. {@link Device#setAppVersion(String)} prior to
     * initializing the SDK to make sure data is reported.
     */
    public static final Device device = Device.dev;

    private static final Countly cly = SingletonHolder.INSTANCE;

    private static class SingletonHolder {
        private static final Countly INSTANCE = new Countly();

        private static boolean isNull() {
            return INSTANCE.sdk == null && INSTANCE.ctx == null;
        }

        private static void empty() {
            INSTANCE.L = null;
            INSTANCE.sdk = null;
            INSTANCE.ctx = null;
        }
    }

    protected SDKCore sdk;
    protected CtxCore ctx;
    protected Log L;

    protected Countly(SDKCore sdk, CtxCore ctx, Log logger) {
        L = logger;
        this.sdk = sdk;
        this.ctx = ctx;
    }

    private Countly() {
    }

    /**
     * Initialize Countly.
     * To be called only once on application start.
     *
     * @param config configuration object
     */
    public void init(final Config config) {
        File directory = config.targetFolder;

        if (config == null) {
            System.out.println("[ERROR][Countly] Config cannot be null");
            return;
        }

        InternalConfig internalConfig = new InternalConfig(config);
        Log L = new Log(internalConfig.loggingLevel, internalConfig.logListener);

        if (directory == null) {
            L.e("[Countly] File cannot be null");
            return;
        }
        if (!directory.isDirectory()) {
            L.e("[Countly] File must be a directory");
            return;
        }
        if (!directory.exists()) {
            L.e("[Countly] File must exist");
            return;
        }

        if (isInitialized()) {
            L.e("[Countly] Countly shouldn't be initialized twice. Please either use Countly.isInitialized() to check status or call Countly.stop() before second Countly.init().");
            stop(false);
        }

        if (internalConfig.enableBackendMode) {
            internalConfig.sdkName = "java-native-backend";
        }

        if (internalConfig.requestQueueMaxSize < 1) {
            L.e("[Countly] init: Request queue max size can not be less than 1.");
            internalConfig.requestQueueMaxSize = 1;
        }

        device.setMetricOverride(internalConfig.getMetricOverride());
        if (internalConfig.getApplicationVersion() != null) {
            device.setAppVersion(internalConfig.getApplicationVersion());
        }

        SDKCore sdk = new SDKCore();
        sdk.init(new CtxCore(sdk, internalConfig, L, directory), L);

        // config has been changed, thus recreating ctx
        this.sdk = sdk;
        this.ctx = new CtxCore(sdk, sdk.config(), L, directory);
        this.L = L;
    }

    /**
     * Initialize Countly.
     * To be called only once on application start.
     *
     * @param directory storage location for Countly
     * @param config configuration object
     * @deprecated use {@link #init(Config)} instead via instance() call
     */
    public static void init(final File directory, final Config config) {
        config.targetFolder = directory;
        SingletonHolder.INSTANCE.init(config);
    }

    /**
     * Stop Countly SDK. Stops all tasks and releases resources.
     * Waits for some tasks to complete, might block for some time.
     * Also clears all the data if called with {@code clearData = true}.
     *
     * @param clearData whether to clear all Countly data or not
     */
    public static void stop(boolean clearData) {
        if (isInitialized()) {
            if (cly.L != null) {
                cly.L.i("[Countly] Stopping SDK");
            }
            cly.sdk.stop(cly.ctx, clearData);
            SingletonHolder.empty();
        } else {
            //todo fix in the future
            //if(cly.L != null) {
            //    cly.L.e("[Countly] Countly isn't initialized to stop it");
            //}
        }
    }

    /**
     * Returns whether Countly SDK has been already initialized or not.
     *
     * @return true if already initialized
     */
    public static boolean isInitialized() {
        return !SingletonHolder.isNull();
    }

    /**
     * Returns whether Countly SDK has been given consent to record data for a particular {@link Config.Feature} or not.
     *
     * @return true if consent has been given
     */
    public static boolean isTracking(Config.Feature feature) {
        return isInitialized() && ((Countly) cly).sdk.isTracking(feature.getIndex());
    }

    /**
     * Returns active {@link Session} if any or creates new {@link Session} instance.
     *
     * NOTE: {@link Session} instances can expire, for example when {@link Config.DID} changes.
     * {@link Session} also holds application context.
     * So either do not store {@link Session} instances in any static variables and use this method or {@link #getSession()} every time you need it,
     * or check {@link Session#isActive()} before using it.
     *
     * @return active {@link Session} instance
     */
    public static Session session() {
        if (!isInitialized()) {
            if (cly.L != null) {
                cly.L.e("[Countly] SDK is not initialized yet.");
            }

            return null;
        }
        return cly.sdk.session(cly.ctx, null);
    }

    public static ModuleBackendMode.BackendMode backendMode() {
        if (!isInitialized()) {
            if (cly.L != null) {
                cly.L.e("[Countly] SDK is not initialized yet.");
            }
            return null;
        } else {
            ModuleBackendMode mbm = cly.sdk.module(ModuleBackendMode.class);
            if (cly.ctx.getConfig().enableBackendMode && mbm != null) {
                return mbm.new BackendMode();
            }
            //if it is null, feature was not enabled, return mock
            if (cly.L != null) {
                cly.L.w("[Countly] BackendMode was not enabled, returning dummy module");
            }
            ModuleBackendMode emptyMbm = new ModuleBackendMode();
            emptyMbm.disableModule();
            return emptyMbm.new BackendMode();
        }
    }

    /**
     * Alternative to {@link #getSession()} & {@link #session()} method for accessing Countly SDK API.
     *
     * @return {@link Usage} instance
     */
    public static Usage api() {
        return instance();
    }

    /**
     * Alternative to {@link #api()} & {@link #session()} method for accessing Countly Shared Instance.
     *
     * @return {@link Countly} instance
     */
    public static Countly instance() {
        return SingletonHolder.INSTANCE;
    }

    @Override
    public Usage login(String id) {
        if (L != null) {
            L.d("[Countly] login");
        }
        sdk.login(ctx, id);
        return this;
    }

    @Override
    public Usage logout() {
        if (L != null) {
            L.d("[Countly] logout");
        }
        sdk.logout(ctx);
        return this;
    }

    @Override
    public String getDeviceId() {
        return ctx.getConfig().getDeviceId().id;
    }

    @Override
    public Usage changeDeviceIdWithMerge(String id) {
        if (L != null) {
            L.d("[Countly] changeDeviceIdWithoutMerge: id = " + id);
        }
        sdk.changeDeviceIdWithMerge(ctx, id);
        return this;
    }

    @Override
    public Usage changeDeviceIdWithoutMerge(String id) {
        if (L != null) {
            L.d("[Countly] changeDeviceIdWithoutMerge: id = " + id);
        }
        sdk.changeDeviceIdWithoutMerge(ctx, id);
        return this;
    }

    /**
     * Consent function which enables corresponding features of SDK with respect to GDPR.
     * Activates corresponding SDK features.
     * Works only when {@link Config#setRequiresConsent(boolean)} is {@code true}.
     *
     * @param features features to turn on
     */
    public static void onConsent(Config.Feature... features) {
        if (!isInitialized()) {
            if (cly.L != null) {
                cly.L.e("[Countly] SDK is not initialized yet.");
            }
        } else {
            if (cly.L != null) {
                cly.L.e("[Countly] onConsent: features = " + features);
            }
            int ftrs = 0;
            for (Config.Feature f : features) {
                ftrs = ftrs | f.getIndex();
            }
            cly.sdk.onConsent(cly.ctx, ftrs);
        }
    }

    /**
     * Consent function which disables corresponding features of SDK with respect to GDPR.
     * Gracefully deactivates corresponding SDK features. Closes session if needed.
     * Works only when {@link Config#setRequiresConsent(boolean)} is {@code true}.
     *
     * @param features features to turn offf
     */
    public static void onConsentRemoval(Config.Feature... features) {
        if (cly.L != null) {
            cly.L.e("[Countly] onConsentRemoval: features = " + features);
        }
        if (!isInitialized()) {
            if (cly.L != null) {
                cly.L.e("[Countly] onConsentRemoval: SDK is not initialized yet.");
            }
        } else {
            int ftrs = 0;
            for (Config.Feature f : features) {
                ftrs = ftrs | f.getIndex();
            }
            cly.sdk.onConsentRemoval(cly.ctx, ftrs);
        }
    }

    @Override
    public Event event(String key) {
        L.d("[Cly] event: key = " + key);
        return ((Session) sdk.session(ctx, null)).event(key);
    }

    @Override
    public Event timedEvent(String key) {
        L.d("[Cly] timedEvent: key = " + key);
        return ((Session) sdk.session(ctx, null)).timedEvent(key);
    }

    /**
     * Get current User Profile object.
     *
     * @return current User Profile instance
     * @see User#edit() to get {@link UserEditor} object
     * @see UserEditor#commit() to submit changes to the server
     */
    @Override
    public User user() {
        L.d("[Cly] user");
        return ((Session) sdk.session(ctx, null)).user();
    }

    @Override
    public Usage addParam(String key, Object value) {
        L.d("[Cly] addParam: key = " + key + " value = " + value);
        return ((Session) sdk.session(ctx, null)).addParam(key, value);
    }

    @Override
    public Usage addCrashReport(Throwable t, boolean fatal) {
        L.d("[Cly] addCrashReport: t = " + t + " fatal = " + fatal);
        return ((Session) sdk.session(ctx, null)).addCrashReport(t, fatal);
    }

    @Override
    public Usage addCrashReport(Throwable t, boolean fatal, String name, Map<String, String> segments, String... logs) {
        L.d("[Cly] addCrashReport: t = " + t + " fatal = " + fatal + " name = " + name + " segments = " + segments + " logs = " + logs);
        return ((Session) sdk.session(ctx, null)).addCrashReport(t, fatal, name, segments, logs);
    }

    @Override
    public Usage addLocation(double latitude, double longitude) {
        L.d("[Cly] addLocation: latitude = " + latitude + " longitude = " + longitude);
        return ((Session) sdk.session(ctx, null)).addLocation(latitude, longitude);
    }

    @Override
    public View view(String name, boolean start) {
        L.d("[Cly] view: name = " + name + " start = " + start);
        return ((Session) sdk.session(ctx, null)).view(name, start);
    }

    @Override
    public View view(String name) {
        L.d("[Cly] view: name = " + name);
        return ((Session) sdk.session(ctx, null)).view(name);
    }
}
