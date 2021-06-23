package ly.count.sdk.java;

import java.io.File;

import ly.count.sdk.java.Cly;
import ly.count.sdk.java.Crash;
import ly.count.sdk.java.CrashProcessor;
import ly.count.sdk.java.Event;
import ly.count.sdk.java.Session;
import ly.count.sdk.java.Usage;
import ly.count.sdk.java.User;
import ly.count.sdk.java.UserEditor;
import ly.count.sdk.java.internal.CtxImpl;
import ly.count.sdk.java.internal.Device;
import ly.count.sdk.java.internal.SDK;

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

    protected Countly(SDK sdk, CtxImpl ctx) {
        super();
        cly = this;
        super.sdkInterface = this.sdk = sdk;
        this.ctx = ctx;
    }

    private static CtxImpl ctx(File directory) {
        return new CtxImpl(cly.sdk, cly.sdk.config(), directory);
    }

    private static CtxImpl ctx(File directory, String view) {
        return new CtxImpl(cly.sdk, cly.sdk.config(), directory, view);
    }

    /**
     * Returns active {@link Session} if any or creates new {@link Session} instance.
     *
     * NOTE: {@link Session} instances can expire, for example when {@link ly.count.sdk.ConfigCore.DID} changes.
     * {@link Session} also holds application context.
     * So either do not store {@link Session} instances in any static variables and use this method or {@link #getSession()} every time you need it,
     * or check {@link Session#isActive()} before using it.
     *
     * @return active {@link Session} instance
     */
    public static Session session(){
        if (!isInitialized()) {
            L.wtf("Countly SDK is not initialized yet.");
        }
        return Cly.session(cly.ctx);
    }

    /**
     * Returns active {@link Session} if any or {@code null} otherwise.
     *
     * NOTE: {@link Session} instances can expire, for example when {@link ly.count.sdk.ConfigCore.DID} changes.
     * {@link Session} also holds application context.
     * So either do not store {@link Session} instances in any static variables and use this method or {@link #session()} every time you need it,
     * or check {@link Session#isActive()} before using it.
     *
     * @return active {@link Session} instance if there is one, {@code null} otherwise
     */
    public static Session getSession(){
        if (!isInitialized()) {
            L.wtf("Countly SDK is not initialized yet.");
        }
        return Cly.getSession();
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
        sdk.login(ctx, id);
        return this;
    }

    @Override
    public Usage logout() {
        sdk.logout(ctx);
        return this;
    }

    @Override
    public Usage resetDeviceId(String id) {
        sdk.resetDeviceId(ctx, id);
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
            L.wtf("Countly SDK is not initialized yet.");
        } else {
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
        if (!isInitialized()) {
            L.wtf("Countly SDK is not initialized yet.");
        } else {
            int ftrs = 0;
            for (Config.Feature f : features) {
                ftrs = ftrs | f.getIndex();
            }
            cly.sdk.onConsentRemoval(cly.ctx, ftrs);
        }
    }


}
