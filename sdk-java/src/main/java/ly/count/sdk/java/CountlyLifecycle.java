package ly.count.sdk.java;

import java.io.File;

import ly.count.sdk.java.internal.InternalConfig;
import ly.count.sdk.java.internal.Log;
import ly.count.sdk.java.internal.CtxImpl;
import ly.count.sdk.java.internal.SDK;

/**
 * Lifecycle-related methods.
 */

public abstract class CountlyLifecycle extends Cly {
    protected CountlyLifecycle(Log logger) {
        super(logger);
    }

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
            Log L = new Log(internalConfig);
            SDK sdk = new SDK();
            sdk.init(new CtxImpl(sdk, internalConfig, L, directory), L);

            // config has been changed, thus recreating ctx
            cly = new Countly(sdk, new CtxImpl(sdk, sdk.config(), L, directory), L);
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
            ((Countly)cly).sdk.stop(((Countly) cly).ctx, clearData);
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

}
