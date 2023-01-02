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
    //protected static final Log.Module L = Log.module("Countly");

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
            Log.print("[ERROR] Config cannot be null");
        }
        else if (directory == null) {
            L.e("File cannot be null");
        } else if (!directory.isDirectory()) {
            L.e("File must be a directory");
        } else if (!directory.exists()) {
            L.e("File must exist");
        } else {
            if (cly != null) {
                L.e("Countly shouldn't be initialized twice. Please either use Countly.isInitialized() to check status or call Countly.stop() before second Countly.init().");
                stop(false);
            }

            if(config.enableBackendMode) {
                config.sdkName = "java-native-backend";
            }

            if(config.requestQueueMaxSize < 1) {
                L.e("init: Request queue max size can not be less than 1.");
                config.requestQueueMaxSize = 1;
            }

            InternalConfig internalConfig = new InternalConfig(config);
            L = new Log(internalConfig);
            SDK sdk = new SDK();
            sdk.init(new CtxImpl(sdk, internalConfig, L, directory));

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
            L.i("Stopping SDK");
            ((Countly)cly).sdk.stop(((Countly) cly).ctx, clearData);
            cly = null;
        } else {
            L.e("Countly isn't initialized to stop it");
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
