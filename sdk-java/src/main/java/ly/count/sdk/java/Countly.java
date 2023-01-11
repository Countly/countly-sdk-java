package ly.count.sdk.java;

import java.io.File;

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

public class Countly extends CountlyLifecycle {

    /**
     * A class responsible for storage of device information sent to Countly server.
     * Call respective setters (i.e. {@link Device#setAppVersion(String)} prior to
     * initializing the SDK to make sure data is reported.
     */
    public static Device device = Device.dev;

    protected static Countly cly;
    protected SDK sdk;

    protected Countly(SDK sdk, CtxImpl ctx, Log logger) {
        super(logger);
        cly = this;
        super.sdkInterface = this.sdk = sdk;
        this.ctx = ctx;
    }

//    private static CtxImpl ctx(File directory) {
//        return new CtxImpl(cly.sdk, cly.sdk.config(), directory);
//    }
//
//    private static CtxImpl ctx(File directory, String view) {
//        return new CtxImpl(cly.sdk, cly.sdk.config(), directory, view);
//    }

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
        return Cly.session(cly.ctx);
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
        return Cly.session(cly.ctx);
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
            System.out.println("[ERROR][Countly]Countly SDK is not initialized yet.");
        } else {
            int ftrs = 0;
            for (Config.Feature f : features) {
                ftrs = ftrs | f.getIndex();
            }
            cly.sdk.onConsentRemoval(cly.ctx, ftrs);
        }
    }


}
