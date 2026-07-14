package ly.count.sdk.java.internal;

/**
 * Source of truth for the runtime feature toggles that come from
 * the server-driven SDK behavior settings ({@code /o/sdk?method=sc}).
 *
 * The default implementation lives in {@link ModuleConfiguration}. When no
 * behavior settings have been received, every getter returns {@code true}
 * (the safe default) so the SDK behaves identically to a pre-feature build.
 */
public interface ConfigurationProvider {

    boolean getNetworkingEnabled();

    boolean getTrackingEnabled();

    boolean getSessionTrackingEnabled();

    boolean getViewTrackingEnabled();

    boolean getCustomEventTrackingEnabled();

    boolean getCrashReportingEnabled();

    boolean getLocationTrackingEnabled();
}
