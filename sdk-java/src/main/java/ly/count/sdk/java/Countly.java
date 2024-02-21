package ly.count.sdk.java;

import java.io.File;
import java.util.Map;
import ly.count.sdk.java.internal.Device;
import ly.count.sdk.java.internal.DeviceIdType;
import ly.count.sdk.java.internal.InternalConfig;
import ly.count.sdk.java.internal.Log;
import ly.count.sdk.java.internal.ModuleBackendMode;
import ly.count.sdk.java.internal.ModuleCrashes;
import ly.count.sdk.java.internal.ModuleDeviceIdCore;
import ly.count.sdk.java.internal.ModuleEvents;
import ly.count.sdk.java.internal.ModuleFeedback;
import ly.count.sdk.java.internal.ModuleLocation;
import ly.count.sdk.java.internal.ModuleRemoteConfig;
import ly.count.sdk.java.internal.ModuleUserProfile;
import ly.count.sdk.java.internal.ModuleViews;
import ly.count.sdk.java.internal.SDKCore;

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
    protected SDKCore sdk;
    protected Log L;

    private static class SingletonHolder {
        private static final Countly INSTANCE = new Countly();

        private static boolean isNull() {
            return INSTANCE.sdk == null;
        }

        private static void empty() {
            INSTANCE.L = null;
            INSTANCE.sdk = null;
        }
    }

    protected Countly(SDKCore sdk, final Log logger) {
        L = logger;
        this.sdk = sdk;
    }

    public Countly() {
    }

    /**
     * Initialize Countly.
     * To be called only once on application start.
     *
     * @param config configuration object
     */
    public void init(final Config config) {
        File directory = config.sdkStorageRootDirectory;

        if (config == null) {
            System.out.println("[ERROR][Countly] Config cannot be null");
            return;
        }

        InternalConfig internalConfig;

        if (config instanceof InternalConfig) {
            internalConfig = (InternalConfig) config;
        } else {
            internalConfig = new InternalConfig(config);
        }

        Log L = new Log(internalConfig.loggingLevel, internalConfig.logListener);
        internalConfig.setLogger(L);

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
            L.e("[Countly] Countly shouldn't be initialized twice. Please either use Countly.isInitialized() to check status or call Countly.stop() before second Countly.init(). Calling 'init' the second time will now do nothing");
            return;
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
        sdk.init(internalConfig);

        this.sdk = sdk;
        this.L = L;
    }

    /**
     * Initialize Countly.
     * To be called only once on application start.
     *
     * @param sdkStorageRootDirectory storage location for Countly
     * @param config configuration object
     * @deprecated use {@link #init(Config)} instead via instance() call
     */
    public static void init(final File sdkStorageRootDirectory, final Config config) {
        config.sdkStorageRootDirectory = sdkStorageRootDirectory;
        SingletonHolder.INSTANCE.init(config);
    }

    /**
     * Stop Countly SDK. Stops all tasks and releases resources.
     * Waits for some tasks to complete, might block for some time.
     * Also clears all the data if called with {@code clearData = true}.
     *
     * @param clearData whether to clear all Countly data or not
     * @deprecated use {@link #halt()} instead via instance() call to clear data
     * or {@link #stop()} to keep data
     */
    public static void stop(boolean clearData) {
        if (isInitialized()) {
            if (cly.L != null) {
                cly.L.i("[Countly] Stopping SDK");
            }
            cly.sdk.stop(clearData);
            SingletonHolder.empty();
        } else {
            //todo fix in the future
            //if(cly.L != null) {
            //    cly.L.e("[Countly] Countly isn't initialized to stop it");
            //}
        }
    }

    /**
     * Stop Countly SDK. Stops all tasks.
     */
    public void stop() {
        stop(false);
    }

    /**
     * Stop Countly SDK. Stops all tasks and releases resources.
     */
    public void halt() {
        stop(true);
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
     * So either do not store {@link Session} instances in any static variables and use this method every time you need it,
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
        return cly.sdk.session(null);
    }

    /**
     * Returns Backend Mode interface to use backend mode feature.
     *
     * @return {@link ModuleBackendMode.BackendMode} instance
     * @deprecated use {@link #backendM()} instead via instance() call
     */
    public static ModuleBackendMode.BackendMode backendMode() {
        return Countly.instance().backendM();
    }

    /**
     * Returns Backend Mode interface to use backend mode feature.
     *
     * @return {@link ModuleBackendMode.BackendMode} instance
     */
    public ModuleBackendMode.BackendMode backendM() {
        if (!isInitialized()) {
            if (cly.L != null) {
                cly.L.e("[Countly] SDK is not initialized yet.");
            }
            return null;
        } else {
            ModuleBackendMode mbm = cly.sdk.module(ModuleBackendMode.class);
            if (cly.sdk.config.enableBackendMode && mbm != null) {
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
     * Alternative to {@link #session()} method for accessing Countly SDK API.
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
        sdk.login(id);
        return this;
    }

    @Override
    public Usage logout() {
        if (L != null) {
            L.d("[Countly] logout");
        }
        sdk.logout();
        return this;
    }

    /**
     * Get current device id.
     *
     * @return device id string
     * @deprecated use "getID()" via deviceId() call
     */
    @Override
    public String getDeviceId() {
        return deviceId().getID();
    }

    /*
     * Get device ID type.
     *
     * @return see {@link DeviceIdType}
     */
    public DeviceIdType getDeviceIdType() {
        return DeviceIdType.fromInt(sdk.config.getDeviceId().strategy, L);
    }

    /**
     * Change device id with merging
     *
     * @param id new user / device id string, cannot be empty
     * @return {@link Usage} instance
     * @deprecated use "changeWithMerge(String)" via deviceId() call
     */
    @Override
    public Usage changeDeviceIdWithMerge(String id) {
        if (L != null) {
            L.d("[Countly] changeDeviceIdWithoutMerge: id = " + id);
        }
        deviceId().changeWithMerge(id);
        return this;
    }

    /**
     * Change device id without merging
     *
     * @param id new user / device id string, cannot be empty
     * @return {@link Usage} instance
     * @deprecated use "changeWithoutMerge(String)" via deviceId() call
     */
    @Override
    public Usage changeDeviceIdWithoutMerge(String id) {
        if (L != null) {
            L.d("[Countly] changeDeviceIdWithoutMerge: id = " + id);
        }
        deviceId().changeWithoutMerge(id);
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
            cly.sdk.onConsent(ftrs);
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
            cly.sdk.onConsentRemoval(cly.sdk.config, ftrs);
        }
    }

    /**
     * <code>Feedback</code> interface to use feedback widgets nps,rating and forms.
     * And do manual reporting of feedbacks.
     *
     * @return {@link ModuleFeedback.Feedback} instance.
     */
    public ModuleFeedback.Feedback feedback() {
        if (!isInitialized()) {
            if (L != null) {
                L.e("[Countly] SDK is not initialized yet.");
            }
            return null;
        }
        return sdk.feedback();
    }

    /**
     * <code>RemoteConfig</code> interface to use remote config feature.
     *
     * @return {@link ModuleRemoteConfig.RemoteConfig} instance.
     */
    public ModuleRemoteConfig.RemoteConfig remoteConfig() {
        if (!isInitialized()) {
            if (L != null) {
                L.e("[Countly] remoteConfig, SDK is not initialized yet.");
            }
            return null;
        }
        return sdk.remoteConfig();
    }

    /**
     * <code>DeviceId</code> interface to use device id functionalities.
     *
     * @return {@link ModuleDeviceIdCore.DeviceId} instance.
     */
    public ModuleDeviceIdCore.DeviceId deviceId() {
        if (!isInitialized()) {
            if (L != null) {
                L.e("[Countly] SDK is not initialized yet.");
            }
            return null;
        }
        return sdk.deviceId();
    }

    /**
     * Record event with provided key.
     *
     * @param key key for this event, cannot be null or empty
     * @return Builder object for this event
     * @deprecated use {@link #events()} instead via instance() call
     */
    @Override
    public Event event(String key) {
        L.d("[Countly] event: key = " + key);
        return ((Session) sdk.session(null)).event(key);
    }

    /**
     * Event module calls
     *
     * @return event module otherwise null if SDK is not initialized
     */
    public ModuleEvents.Events events() {
        if (!isInitialized()) {
            if (L != null) {
                L.e("[Countly] SDK is not initialized yet.");
            }
            return null;
        }
        return sdk.events();
    }

    /**
     * Crash module calls
     *
     * @return crash module otherwise null if SDK is not initialized
     */
    public ModuleCrashes.Crashes crashes() {
        if (!isInitialized()) {
            if (L != null) {
                L.e("[Countly] SDK is not initialized yet.");
            }
            return null;
        }
        return sdk.crashes();
    }

    /**
     * <code>Views</code> interface to use views feature.
     *
     * @return {@link ModuleViews.Views} instance.
     */
    public ModuleViews.Views views() {
        if (!isInitialized()) {
            if (L != null) {
                L.e("[Countly] SDK is not initialized yet.");
            }
            return null;
        }
        return sdk.views();
    }

    /**
     * Get existing or create new timed event object, don't record it.
     *
     * @param key key for this event, cannot be null or empty
     * @return timed Event instance.
     * @deprecated use {@link ModuleEvents.Events#startEvent(String)}} instead via <code>instance().events()</code> call
     */
    @Override
    public Event timedEvent(String key) {
        L.d("[Countly] timedEvent: key = " + key);
        return ((Session) sdk.session(null)).timedEvent(key);
    }

    /**
     * <code>UserProfile</code> interface to use user profile feature.
     *
     * @return {@link ModuleUserProfile.UserProfile} instance.
     */
    public ModuleUserProfile.UserProfile userProfile() {
        if (!isInitialized()) {
            if (L != null) {
                L.e("[Countly] userProfile, SDK is not initialized yet.");
            }
            return null;
        }
        return sdk.userProfile();
    }

    /**
     * <code>Location</code> interface to use location feature.
     *
     * @return {@link ModuleLocation.Location} instance.
     */
    public ModuleLocation.Location location() {
        if (!isInitialized()) {
            L.e("Countly.sharedInstance().init must be called before accessing location");
            return null;
        }

        return sdk.location();
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
        L.d("[Countly] user");
        return ((Session) sdk.session(null)).user();
    }

    @Override
    public Usage addParam(String key, Object value) {
        L.d("[Countly] addParam: key = " + key + " value = " + value);
        return ((Session) sdk.session(null)).addParam(key, value);
    }

    @Override
    public Usage addCrashReport(Throwable t, boolean fatal) {
        L.d("[Countly] addCrashReport: t = " + t + " fatal = " + fatal);
        return ((Session) sdk.session(null)).addCrashReport(t, fatal);
    }

    @Override
    public Usage addCrashReport(Throwable t, boolean fatal, String name, Map<String, String> segments, String... logs) {
        L.d("[Countly] addCrashReport: t = " + t + " fatal = " + fatal + " name = " + name + " segments = " + segments + " logs = " + logs);
        return ((Session) sdk.session(null)).addCrashReport(t, fatal, name, segments, logs);
    }

    @Override
    public Usage addLocation(double latitude, double longitude) {
        L.d("[Countly] addLocation: latitude = " + latitude + " longitude = " + longitude);
        return ((Session) sdk.session(null)).addLocation(latitude, longitude);
    }

    /**
     * {@inheritDoc}
     *
     * @deprecated use {@link ModuleViews.Views#startView(String)} instead via {@link Countly#views()} instance() call
     */
    @Override
    public View view(String name, boolean start) {
        L.d("[Countly] view: name = " + name + " start = " + start);
        return ((Session) sdk.session(null)).view(name, start);
    }

    /**
     * {@inheritDoc}
     *
     * @deprecated use {@link ModuleViews.Views#startView(String)} instead via {@link Countly#views()} instance() call
     */
    @Override
    public View view(String name) {
        L.d("[Countly] view: name = " + name);
        return ((Session) sdk.session(null)).view(name);
    }
}
