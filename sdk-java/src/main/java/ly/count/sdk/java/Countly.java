package ly.count.sdk.java;

import java.io.File;
import java.util.Map;

import ly.count.sdk.java.internal.*;

/**
 * Main Countly SDK API class.
 * <ul>
 *     <li>Initialize Countly SDK using {@code #init(Application, Config)}.</li>
 *     <li>Stop Countly SDK with {@link #stop(boolean)} if needed.</li>
 *     <li>Use {@link #session()} to get a {@link Session} instance.</li>
 *     <li>Use {@link #login(String)} & {@link #logout()} when user logs in & logs out.</li>
 * </ul>
 */

public class Countly implements Usage {

    /**
     * A class responsible for storage of device information sent to Countly server.
     * Call respective setters (i.e. {@link Device#setAppVersion(String)} prior to
     * initializing the SDK to make sure data is reported.
     */
    public static Device device = Device.dev;

    protected static Countly cly;
    protected SDK sdk;
    protected CtxCore ctx;
    protected Log L;

    protected Countly(SDK sdk, CtxCore ctx, Log logger) {
        cly = this;
        L = logger;
        this.sdk = sdk;
        this.ctx = ctx;
    }

//    private static CtxImpl ctx(File directory) {
//        return new CtxImpl(cly.sdk, cly.sdk.config(), directory);
//    }
//
//    private static CtxImpl ctx(File directory, String view) {
//        return new CtxImpl(cly.sdk, cly.sdk.config(), directory, view);
//    }

    //protected CtxImpl ctx;

    /**
     * Initialize Countly.
     * To be called only once on application start.
     *
     * @param directory storage location for Countly
     * @param config configuration object
     */
    public static void init (final File directory, final Config config) {
        if (config == null) {
            System.out.println("[ERROR][Countly] Config cannot be null");
        }
        else if (directory == null) {
            System.out.println("[ERROR][Countly] File cannot be null");
        } else if (!directory.isDirectory()) {
            System.out.println("[ERROR][Countly] File must be a directory");
        } else if (!directory.exists()) {
            System.out.println("[ERROR][Countly] File must exist");
        } else {
            if (cly != null) {
                System.out.println("[ERROR][Countly] Countly shouldn't be initialized twice. Please either use Countly.isInitialized() to check status or call Countly.stop() before second Countly.init().");
                stop(false);
            }

            if(config.enableBackendMode) {
                config.sdkName = "java-native-backend";
            }

            if(config.requestQueueMaxSize < 1) {
                System.out.println("[ERROR][Countly] init: Request queue max size can not be less than 1.");
                config.requestQueueMaxSize = 1;
            }

            InternalConfig internalConfig = new InternalConfig(config);
            Log L = new Log(internalConfig.loggingLevel, internalConfig.logListener);
            SDK sdk = new SDK();
            sdk.init(new CtxCore(sdk, internalConfig, L, directory), L);

            // config has been changed, thus recreating ctx
            cly = new Countly(sdk, new CtxCore(sdk, sdk.config(), L, directory), L);
        }
    }

    /**
     * Stop Countly SDK. Stops all tasks and releases resources.
     * Waits for some tasks to complete, might block for some time.
     * Also clears all the data if called with {@code clearData = true}.
     *
     * @param clearData whether to clear all Countly data or not
     */
    public static void stop (boolean clearData) {
        if (cly != null) {
            System.out.println("[ERROR][Countly] Stopping SDK");
            cly.sdk.stop(cly.ctx, clearData);
            cly = null;
        } else {
            System.out.println("[ERROR][Countly] Countly isn't initialized to stop it");
        }
    }

    /**
     * Returns whether Countly SDK has been already initialized or not.
     *
     * @return true if already initialized
     */
    public static boolean isInitialized() { return cly != null; }

    /**
     * Returns whether Countly SDK has been given consent to record data for a particular {@link Config.Feature} or not.
     *
     * @return true if consent has been given
     */
    public static boolean isTracking(Config.Feature feature) { return isInitialized() && ((Countly)cly).sdk.isTracking(feature.getIndex()); }


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
    public static Session session(){
        if (!isInitialized()) {
            System.out.println("[ERROR][Countly] SDK is not initialized yet.");
        }
        return session(cly.ctx);
    }

    public static ModuleBackendMode.BackendMode backendMode(){
        if (!isInitialized()) {
            System.out.println("[ERROR][Countly] SDK is not initialized yet.");
            return null;
        } else {
            ModuleBackendMode mbm = cly.sdk.module(ModuleBackendMode.class);
            if (cly.ctx.getConfig().enableBackendMode && mbm != null) {
                return mbm.new BackendMode();
            }
            //if it is null, feature was not enabled, return mock
            System.out.println("[WARNING][Countly] BackendMode was not enabled, returning dummy module");
            ModuleBackendMode emptyMbm = new ModuleBackendMode();
            emptyMbm.disableModule();
            return emptyMbm.new BackendMode();
        }
    }

    /**
     * Returns active {@link Session} if any or {@code null} otherwise.
     *
     * NOTE: {@link Session} instances can expire, for example when {@link Config.DID} changes.
     * {@link Session} also holds application context.
     * So either do not store {@link Session} instances in any static variables and use this method or {@link #session()} every time you need it,
     * or check {@link Session#isActive()} before using it.
     *
     * @return active {@link Session} instance if there is one, {@code null} otherwise
     */

    /**
     * @deprecated
     * This method deprecated, please
     * <p> use {@link #session()} instead.
     */
    public static Session getSession(){
        if (!isInitialized()) {
            System.out.println("[ERROR][Countly] SDK is not initialized yet.");
        }
        return session(cly.ctx);
    }

    /**
     * Alternative to {@link #getSession()} & {@link #session()} method for accessing Countly SDK API.
     *
     * @return {@link Usage} instance
     */
    public static Usage api() {
        return cly;
    }

    @Override
    public Usage login(String id) {
        L.d("login");
        sdk.login(ctx, id);
        return this;
    }

    @Override
    public Usage logout() {
        L.d("logout");
        sdk.logout(ctx);
        return this;
    }

    @Override
    public String getDeviceId() {
        return ctx.getConfig().getDeviceId().id;
    }

    @Override
    public Usage resetDeviceId(String id) {
        L.d("resetDeviceId: id = " + id);
        sdk.changeDeviceIdWithoutMerge(ctx, id);
        return this;
    }

    @Override
    public Usage changeDeviceIdWithMerge(String id) {
        L.d("changeDeviceIdWithoutMerge: id = " + id);
        sdk.changeDeviceIdWithMerge(ctx, id);
        return this;
    }

    @Override
    public Usage changeDeviceIdWithoutMerge(String id) {
        L.d("changeDeviceIdWithoutMerge: id = " + id);
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
            System.out.println("[ERROR][Countly] SDK is not initialized yet.");
        } else {
            System.out.println("[DEBUG][Countly] onConsent: features = " + features);

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
        System.out.println("[DEBUG][Countly] onConsentRemoval: features = " + features);
        if (!isInitialized()) {
            System.out.println("[ERROR][Countly] onConsentRemoval: SDK is not initialized yet.");
        } else {
            int ftrs = 0;
            for (Config.Feature f : features) {
                ftrs = ftrs | f.getIndex();
            }
            cly.sdk.onConsentRemoval(cly.ctx, ftrs);
        }
    }

    protected static Session session(CtxCore ctx) {
        return cly.sdk.session(ctx, null);
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
     * @see User#edit() to get {@link UserEditor} object
     * @see UserEditor#commit() to submit changes to the server
     * @return current User Profile instance
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
