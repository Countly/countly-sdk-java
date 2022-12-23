package ly.count.sdk.java;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import ly.count.sdk.java.internal.*;
import ly.count.sdk.java.internal.Module;

/**
 * Countly configuration object.
 */
public class Config {
    private static final Log.Module L = Log.module("ConfigCore");
    /**
     * Logging level for {@link Log} module
     */
    public enum LoggingLevel {
        DEBUG(0),
        INFO(1),
        WARN(2),
        ERROR(3),
        OFF(4);

        private final int level;

        LoggingLevel(int level){ this.level = level; }

        public int getLevel(){ return level; }

        public boolean prints(LoggingLevel l) {
            return level <= l.level;
        }
    }

    public enum DeviceIdStrategy {
        UUID(0),
        CUSTOM_ID(10);

        private final int index;

        DeviceIdStrategy(int level) {
            this.index = level;
        }

        public int getIndex() {
            return index;
        }

        public static DeviceIdStrategy fromIndex(int index) {
            if (index == UUID.index) {
                return UUID;
            }
            if (index == CUSTOM_ID.index) {
                return CUSTOM_ID;
            }
            return null;
        }
    }

    public enum Feature {
        Events(CoreFeature.Events.getIndex()),
        Sessions(CoreFeature.Sessions.getIndex()),
        Views(CoreFeature.Views.getIndex()),
        CrashReporting(CoreFeature.CrashReporting.getIndex()),
        Location(CoreFeature.Location.getIndex()),
        UserProfiles(CoreFeature.UserProfiles.getIndex());
//        StarRating(1 << 12),
//        RemoteConfig(1 << 13),
//        PerformanceMonitoring(1 << 14);

        private final int index;

        Feature(int index) {
            this.index = index;
        }

        public int getIndex() {
            return index;
        }

        public static Config.Feature byIndex(int index) {
            if (index == Events.index) {
                return Events;
            } else if (index == Sessions.index) {
                return Sessions;
            } else if (index == Views.index) {
                return Views;
            } else if (index == CrashReporting.index) {
                return CrashReporting;
            } else if (index == Location.index) {
                return Location;
            } else if (index == UserProfiles.index) {
                return UserProfiles;
//            } else if (index == StarRating.index) {
//                return StarRating;
//            } else if (index == RemoteConfig.index) {
//                return RemoteConfig;
//            } else if (index == PerformanceMonitoring.index) {
//                return PerformanceMonitoring;
            } else {
                return null;
            }
        }
    }

    /**
     * Holder class for various ids metadata and id itself. Final, unmodifiable.
     */
    public static final class DID implements Byteable {
        public static final int STRATEGY_UUID = 0;
        public static final int STRATEGY_CUSTOM = 10;
        public static final int REALM_DID = 0;

        public final int realm;
        public final int strategy;
        public final String id;

        public DID(int realm, int strategy, String id) {
            this.realm = realm;
            this.strategy = strategy;
            this.id = id;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null || !(obj instanceof DID)) { return false; }
            DID did = (DID) obj;
            return did.realm == realm && did.strategy == strategy &&
                    (did.id == null ? id == null : did.id.equals(id));
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }

        @Override
        public String toString() {
            return "DID " + id + " (" + realm + ", " + strategy + ")";
        }

        @Override
        public byte[] store() {
            ByteArrayOutputStream bytes = null;
            ObjectOutputStream stream = null;
            try {
                bytes = new ByteArrayOutputStream();
                stream = new ObjectOutputStream(bytes);
                stream.writeInt(realm);
                stream.writeInt(strategy);
                stream.writeObject(id);
                stream.close();
                return bytes.toByteArray();
            } catch (IOException e) {
                L.wtf("Cannot serialize config", e);
            } finally {
                if (stream != null) {
                    try {
                        stream.close();
                    } catch (IOException e) {
                        L.wtf("Cannot happen", e);
                    }
                }
                if (bytes != null) {
                    try {
                        bytes.close();
                    } catch (IOException e) {
                        L.wtf("Cannot happen", e);
                    }
                }
            }
            return null;
        }

        @Override
        public boolean restore(byte[] data) {
            ByteArrayInputStream bytes = null;
            ObjectInputStream stream = null;

            try {
                bytes = new ByteArrayInputStream(data);
                stream = new ObjectInputStream(bytes);

                Utils.reflectiveSetField(this, "realm", stream.readInt());
                Utils.reflectiveSetField(this, "strategy",stream.readInt());
                Utils.reflectiveSetField(this, "id", stream.readObject());

                return true;
            } catch (IOException | ClassNotFoundException e) {
                L.wtf("Cannot deserialize config", e);
            } finally {
                if (stream != null) {
                    try {
                        stream.close();
                    } catch (IOException e) {
                        L.wtf("Cannot happen", e);
                    }
                }
                if (bytes != null) {
                    try {
                        bytes.close();
                    } catch (IOException e) {
                        L.wtf("Cannot happen", e);
                    }
                }
            }

            return false;
        }
    }

    /**
     * URL of Countly server
     */
    protected final URL serverURL;

    /**
     * Application key of Countly server
     */
    protected final String serverAppKey;

    /**
     * Set of Countly SDK features enabled
     */
    protected int features = 0;

    /**
     * Device id generation strategy, UUID by default
     */
    protected int deviceIdStrategy = 0;

    /**
     * Allow fallback from specified device id strategy to any other available strategy
     */
    protected boolean deviceIdFallbackAllowed = true;

    /**
     * Developer specified device id
     */
    protected String customDeviceId;

    /**
     * Tag used for logging
     */
    protected String loggingTag = "Countly";

    /**
     * Logging level
     */
    protected LoggingLevel loggingLevel = LoggingLevel.OFF;

    /**
     * Countly SDK name to be sent in HTTP requests
     */
    protected String sdkName = "java-native";

    /**
     * Countly SDK version to be sent in HTTP requests
     */
    protected String sdkVersion = "20.11.5";

    /**
     * Countly SDK name to be sent in HTTP requests
     */
    protected String applicationName;

    /**
     * Countly SDK version to be sent in HTTP requests
     */
    protected String applicationVersion;

    /**
     * Force usage of POST method for all requests
     */
    protected boolean usePOST = false;

    /**
     * This would be a special state where the majority of the SDK calls don't work anymore and only a few special calls work.
     */
    protected boolean enableBackendMode = false;

    /**
     * Salt string for parameter tampering protection
     */
    protected String salt = null;

    /**
     * Connection timeout in seconds for HTTP requests SDK sends to Countly server. Defaults to 30.
     */
    protected int networkConnectionTimeout = 30;

    /**
     * Read timeout in seconds for HTTP requests SDK sends to Countly server. Defaults to 30.
     */
    protected int networkReadTimeout = 30;

    /**
     * How long to wait between requests in milliseconds. Used to decrease CPU & I/O load on the device.
     */
    protected int networkRequestCooldown = 1000;

    /**
     * How long to wait between Device ID change & push token requests, in milliseconds.
     * Required by Countly server, don't change unless you know what you're doing!
     */
    protected int networkImportantRequestCooldown = 5000;

    /**
     * Enable SSL public key pinning. Improves HTTPS security by not allowing MiM attacks
     * based on SSL certificate replacing somewhere between Android device and Countly server.
     * Here you can set one or more PEM-encoded public keys which Countly SDK verifies against
     * public keys provided by Countly's web server for each HTTPS connection. At least one match
     * results in connection being established, no matches result in request not being sent stored for next try.
     *
     * NOTE: Public key pinning is preferred over certificate pinning due to the fact
     * that public keys are usually not changed when certificate expires and you generate new one.
     * This ensures pinning continues to work after certificate prolongation.
     * Certificates ({@link #certificatePins}) on the other hand have specific expiry date.
     * In case you chose this way of pinning, you MUST ensure that ALL installs of your app
     * have both certificates (old & new) until expiry date.
     */
    protected Set<String> publicKeyPins = null;

    /**
     * Enable SSL certificate pinning. Improves HTTPS security by not allowing MiM attacks
     * based on SSL certificate replacing somewhere between Android device and Countly server.
     * Here you can set one or more PEM-encoded certificates which Countly SDK verifies against
     * certificates provided by Countly's web server for each HTTPS connection. At least one match
     * results in connection being established, no matches result in request not being sent stored for next try.
     *
     * NOTE: Public key pinning ({@link #publicKeyPins}) is preferred over certificate pinning due to the fact
     * that public keys are usually not changed when certificate expires and you generate new one.
     * This ensures pinning continues to work after certificate prolongation.
     * Certificates on the other hand have specific expiry date.
     * In case you chose this way of pinning, you MUST ensure that ALL installs of your app
     * have both certificates (old & new) until expiry date.
     */
    protected Set<String> certificatePins = null;

    /**
     * Maximum amount of time in seconds between two update requests to the server
     * reporting session duration and other parameters if any added between update requests.
     *
     * Update request is also sent when number of unsent events reached {@link #eventsBufferSize}.
     *
     * Set to 0 to disable update requests based on time.
     */
    protected int sendUpdateEachSeconds = 30;

    /**
     * Maximum number of events to hold until request is to be sent to the server
     *
     * Events are also sent along with session update each {@link #sendUpdateEachSeconds}.
     *
     * Set to 0 to disable buffering.
     */
    protected int eventsBufferSize = 10;

    /**
     * Minimal amount of time between sessions in seconds.
     * For now used only when recovering from a crash as a session extension period.
     */
    protected int sessionCooldownPeriod = 30;

    /**
     * How much time of user inactivity Countly should wait until automatically ending session.
     * Works only with {@link #autoSessionsTracking} set to {@code true}.
     */
    protected int sessionAutoCloseAfter = "Android".equals(System.getProperty("os.name")) ? 10 : 0;

    /**
     * Enable test mode:
     * <ul>
     *     <li>Raise exceptions when SDK is in inconsistent state as opposed to silently
     *     trying to ignore it when testMode is off</li>
     *     <li>Put Firebase token under {@code test} devices if {@code Feature.Push} is enabled.</li>
     * </ul>
     */
    protected boolean testMode = false;

    /**
     * When not {@code null}, more than {@code 0} and {@code Feature.CrashReporting} is enabled,
     * Countly watches main thread for unresponsiveness.
     * When main thread doesn't respond for time more than this property in seconds,
     * SDK reports ANR crash back to Countly server.
     */
    protected int crashReportingANRCheckingPeriod = 5;

    /**
     * {@link CrashProcessor}-implementing class which is instantiated when application
     * crashes or crash is reported programmatically using {@link Session#addCrashReport(Throwable, boolean, String, Map, String...)}.
     * Crash processor helps you to add custom data in event of a crash: custom crash segments & crash logs.
     */
    protected String crashProcessorClass = null;

    /**
     * Feature-Class map which sets Module overrides.
     */
    protected Map<Integer, Class<? extends Module>> moduleOverrides = null;

    /**
     * String-String map with custom parameters sent in each request, persistent.
     */
    protected Map<String, String> persistentParams = null;

    /**
     * Requires GDPR-compliance calls.
     * If {@code true}, SDK waits for corresponding consent calls before recording any data.
     */
    protected boolean requiresConsent = false;

    /**
     * Automatically start session on app launch and stop it before it terminates
     */
    protected boolean autoSessionsTracking = true;

    /**
     * Automatically start a view on each activity start and stop it once activity is stopped
     */
    protected boolean autoViewsTracking = true;

    /**
     * If star rating dialog should be cancellable
     */
    protected Boolean starRatingIsDialogCancelable = null;

    /**
     * if star rating should be shown for each new version
     */
    protected Boolean starRatingDisabledAutomaticForNewVersions = null;

    //region Rating Module related fields

    /**
     * After how much time the timeout error is returned when showing rating widget
     */
    protected long ratingWidgetTimeout = 3000L;

    /**
     * After how many sessions the automatic star rating is shown
     */
    protected int starRatingSessionLimit = -1;

    /**
     * Star rating dialog title
     */
    protected String starRatingTextTitle = null;

    /**
     * Star rating dialog message
     */
    protected String starRatingTextMessage = null;

    /**
     * Star rating dialog dismiss message
     */
    protected String starRatingTextDismiss = null;

    /**
     * If automatic star rating should be shown
     */
    protected Boolean automaticStarRatingShouldBeShown = null;

    //endregion

    //region Remote Config Module fields

    /**
     * If remote config automatic fetching should be enabled
     */
    protected Boolean enableAutomaticRemoteConfig = null;

    /**
     * After how much time the request is canceled and timeout error returned
     */
    protected Long remoteConfigUpdateRequestTimeout = null;

    /**
     * Maximum in memory request queue size.
     */
    protected int requestQueueMaxSize = 1000;

    /**
    * Maximum size of all string keys
    */
    protected int maxKeyLength = 128;

    /**
    * Maximum size of all values in our key-value pairs
    */
    protected int maxValueSize = 256;

    /**
    * Max amount of custom (dev provided) segmentation in one event
    */
    protected int maxSegmentationValues = 30;

    /**
    * Limits how many stack trace lines would be recorded per thread
    */
    protected int maxStackTraceLinesPerThread = 30;

    /**
    * Limits how many characters are allowed per stack trace line
    */
    protected int maxStackTraceLineLength = 200;

    /**
    * Set the maximum amount of breadcrumbs.
    */
    protected int totalBreadcrumbsAllowed = 100;

    //endregion

    public int getMaxKeyLength() {
        return maxKeyLength;
    }

    public void setMaxKeyLength(int maxKeyLength) {
        this.maxKeyLength = maxKeyLength;
    }

    public int getMaxValueSize() {
        return maxValueSize;
    }

    public void setMaxValueSize(int maxValueSize) {
        this.maxValueSize = maxValueSize;
    }

    public int getMaxSegmentationValues() {
        return maxSegmentationValues;
    }

    public void setMaxSegmentationValues(int maxSegmentationValues) {
        this.maxSegmentationValues = maxSegmentationValues;
    }

    public int getMaxStackTraceLinesPerThread() {
        return maxStackTraceLinesPerThread;
    }

    public void setMaxStackTraceLinesPerThread(int maxStackTraceLinesPerThread) {
        this.maxStackTraceLinesPerThread = maxStackTraceLinesPerThread;
    }

    public int getMaxStackTraceLineLength() {
        return maxStackTraceLineLength;
    }

    public void setMaxStackTraceLineLength(int maxStackTraceLineLength) {
        this.maxStackTraceLineLength = maxStackTraceLineLength;
    }

    public int getTotalBreadcrumbsAllowed() {
        return totalBreadcrumbsAllowed;
    }

    public void setTotalBreadcrumbsAllowed(int totalBreadcrumbsAllowed) {
        this.totalBreadcrumbsAllowed = totalBreadcrumbsAllowed;
    }


    // TODO: storage limits & configuration
//    protected int maxRequestsStored = 0;
//    protected int storageDirectory = "";
//    protected int storagePrefix = "[CLY]_";
    /**
     * The only ConfigCore constructor.
     *
     * @param serverURL valid {@link URL} of Countly server
     * @param serverAppKey App Key from Management -> Applications section of your Countly Dashboard
     */
    public Config(String serverURL, String serverAppKey) {
        //the last '/' should be deleted
        if(serverURL != null && serverURL.length() > 0 && serverURL.charAt(serverURL.length() - 1) == '/') {
            serverURL = serverURL.substring(0, serverURL.length() - 1);
        }

        try {
            this.serverURL = new URL(serverURL);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(e);
        }
        this.serverAppKey = serverAppKey;
    }

    /**
     * Whether to allow fallback from unavailable device id strategy to Countly OpenUDID derivative.
     *
     * @param deviceIdFallbackAllowed true if fallback is allowed
     * @return {@code this} instance for method chaining
     */
    public Config setDeviceIdFallbackAllowed(boolean deviceIdFallbackAllowed) {
        this.deviceIdFallbackAllowed = deviceIdFallbackAllowed;
        return this;
    }

    /**
     * Enable one or many features of Countly SDK instead of {@link #setFeatures(Config.Feature...)}.
     *
     * @param features features to enable
     * @return {@code this} instance for method chaining
     */
    public Config enableFeatures(Config.Feature... features) {
        if (features == null) {
            L.wtf("Features array cannot be null");
        } else {
            for (Config.Feature f : features) {
                if (f == null) {
                    L.wtf("Feature cannot be null");
                } else {
                    this.features = this.features | f.getIndex();
                }
            }
        }
        return this;
    }

    /**
     * Disable one or many features of Countly SDK instead of {@link #setFeatures(Config.Feature...)}.
     *
     * @param features features to disable
     * @return {@code this} instance for method chaining
     */
    public Config disableFeatures(Config.Feature... features) {
        if (features == null) {
            L.wtf("Features array cannot be null");
        } else {
            for (Config.Feature f : features) {
                if (f == null) {
                    L.wtf("Feature cannot be null");
                } else {
                    this.features = this.features & ~f.getIndex();
                }
            }
        }
        return this;
    }

    /**
     * Set enabled features all at once instead of {@link #setFeatures(Config.Feature...)}.
     *
     * @param features variable args of features to enable
     * @return {@code this} instance for method chaining
     */
    public Config setFeatures(Config.Feature... features) {
        this.features = 0;

        if (features != null && features.length > 0) {
            for (int i = 0; i < features.length; i++) {
                if (features[i] == null) {
                    L.wtf(i + "-th feature is null in setFeatures");
                } else {
                    this.features = this.features | features[i].index;
                }
            }
        }
        return this;
    }

    /**
     * Set device id generation strategy:
     * - {@link DeviceIdStrategy#UUID} to use standard java random UUID. Default.
     * - {@link DeviceIdStrategy#CUSTOM_ID} to use your own device id for Countly.
     *
     * @param strategy       strategy to use instead of default OpenUDID
     * @param customDeviceId device id for use with {@link DeviceIdStrategy#CUSTOM_ID}
     * @return {@code this} instance for method chaining
     */
    public Config setDeviceIdStrategy(DeviceIdStrategy strategy, String customDeviceId) {
        if (strategy == null) {
            L.wtf("DeviceIdStrategy cannot be null");
        } else {
            if (strategy == DeviceIdStrategy.CUSTOM_ID) {
                return setCustomDeviceId(customDeviceId);
            }
            this.deviceIdStrategy = strategy.index;
        }
        return this;
    }

    /**
     * Shorthand method for {@link #setDeviceIdStrategy(DeviceIdStrategy, String)}
     *
     * @param strategy strategy to use instead of default OpenUDID
     * @return {@code this} instance for method chaining
     */
    public Config setDeviceIdStrategy(DeviceIdStrategy strategy) {
        return setDeviceIdStrategy(strategy, null);
    }

    /**
     * Set device id to specific string and set generation strategy to {@link DeviceIdStrategy#CUSTOM_ID}.
     *
     * @param customDeviceId device id for use with {@link DeviceIdStrategy#CUSTOM_ID}
     * @return {@code this} instance for method chaining
     */
    public Config setCustomDeviceId(String customDeviceId) {
        if (Utils.isEmpty(customDeviceId)) {
            L.wtf("DeviceIdStrategy.CUSTOM_ID strategy cannot be used without device id specified");
        } else {
            this.customDeviceId = customDeviceId;
            this.deviceIdStrategy = DeviceIdStrategy.CUSTOM_ID.index;
        }
        return this;
    }

    /**
     * Getter for {@link #deviceIdStrategy}
     *
     * @return {@link #deviceIdStrategy} value as enum
     */
    public DeviceIdStrategy getDeviceIdStrategyEnum() {
        return DeviceIdStrategy.fromIndex(deviceIdStrategy);
    }

    /**
     * Force usage of POST method for all requests
     *
     * @return {@code this} instance for method chaining
     */
    public Config enableUsePOST() {
        this.usePOST = true;
        return this;
    }

    /**
     * Force usage of POST method for all requests.
     *
     * @param usePOST whether to force using POST method for all requests or not
     * @return {@code this} instance for method chaining
     */
    public Config setUsePOST(boolean usePOST) {
        this.usePOST = usePOST;
        return this;
    }

    /**
     * Enable SDK's backend mode.
     * @return {@code this} instance for method chaining
     */
    public Config enableBackendMode() {
        this.enableBackendMode = true;
        return this;
    }

    public int getRequestQueueMaxSize() {
        return requestQueueMaxSize;
    }

    /**
     * In backend mode set the in memory request queue size.
     * @param requestQueueMaxSize int to set request queue maximum size for backend mode
     * @return {@code this} instance for method chaining
     */
    public Config setRequestQueueMaxSize(int requestQueueMaxSize) {
        this.requestQueueMaxSize = requestQueueMaxSize;
        return this;
    }

    /**
     * Enable parameter tampering protection
     *
     * @param salt String to add to each request before calculating checksum
     * @return {@code this} instance for method chaining
     */
    public Config enableParameterTamperingProtection(String salt) {
        if (Utils.isEmpty(salt)) {
            L.wtf("Salt cannot be empty in enableParameterTamperingProtection");
        } else {
            this.salt = salt;
        }
        return this;
    }

    /**
     * Tag used for logging
     *
     * @param loggingTag tag string to use
     * @return {@code this} instance for method chaining
     */
    public Config setLoggingTag(String loggingTag) {
        if (loggingTag == null || loggingTag.equals("")) {
            L.wtf("Logging tag cannot be empty");
        } else {
            this.loggingTag = loggingTag;
        }
        return this;
    }

    /**
     * Logging level for Countly SDK
     *
     * @param loggingLevel log level to use
     * @return {@code this} instance for method chaining
     */
    public Config setLoggingLevel(LoggingLevel loggingLevel) {
        if (loggingLevel == null) {
            L.wtf("Logging level cannot be null");
        } else {
            this.loggingLevel = loggingLevel;
        }
        return this;
    }

    /**
     * Enable test mode:
     * <ul>
     *     <li>Raise exceptions when SDK is in inconsistent state as opposed to silently
     *     trying to ignore them when testMode is off</li>
     *     <li>Put Firebase token under {@code test} devices if {@code Feature.Push} is enabled.</li>
     * </ul>
     * Note: this method automatically sets {@link #loggingLevel} to {@link LoggingLevel#INFO} in
     * case it was {@link LoggingLevel#OFF} (default).
     *
     * @return {@code this} instance for method chaining
     */
    public Config enableTestMode() {
        this.testMode = true;
        this.loggingLevel = this.loggingLevel == LoggingLevel.OFF ? LoggingLevel.INFO : this.loggingLevel;
        return this;
    }

    /**
     * Disable test mode, so SDK will silently avoid raising exceptions whenever possible.
     * Test mode is disabled by default.
     *
     * @return {@code this} instance for method chaining
     */
    public Config disableTestMode() {
        this.testMode = false;
        return this;
    }

    /**
     * Set maximum amount of time in seconds between two update requests to the server
     * reporting session duration and other parameters if any added between update requests.
     *
     * Update request is also sent when number of unsent events reached {@link #setEventsBufferSize(int)}.
     *
     * @param sendUpdateEachSeconds max time interval between two update requests, set to 0 to disable update requests based on time.
     * @return {@code this} instance for method chaining
     */
    public Config setSendUpdateEachSeconds(int sendUpdateEachSeconds) {
        if (sendUpdateEachSeconds < 0) {
            L.wtf("sendUpdateEachSeconds cannot be negative");
        } else {
            this.sendUpdateEachSeconds = sendUpdateEachSeconds;
        }
        return this;
    }

    /**
     * Sets maximum number of events to hold until forcing update request to be sent to the server
     *
     * Update request is also sent when last update request was sent more than {@link #setSendUpdateEachSeconds(int)} seconds ago.
     *
     * @param eventsBufferSize max number of events between two update requests, set to 0 to disable update requests based on events.
     * @return {@code this} instance for method chaining
     */
    public Config setEventsBufferSize(int eventsBufferSize) {
        if (eventsBufferSize < 0) {
            L.wtf("eventsBufferSize cannot be negative");
        } else {
            this.eventsBufferSize = eventsBufferSize;
        }
        return this;
    }

    /**
     * Disable update requests completely. Only begin & end requests will be sent + some special
     * cases if applicable like User Profile change or Push token updated.
     *
     * @see #setSendUpdateEachSeconds(int)
     * @see #setEventsBufferSize(int)
     * @return {@code this} instance for method chaining
     */
    public Config disableUpdateRequests() {
        this.sendUpdateEachSeconds = 0;
        return this;
    }

    /**
     * Set minimal amount of time between sessions in seconds.
     * For now used only when recovering from a crash as a session extension period.
     *
     * @param sessionCooldownPeriod min time interval between two sessions
     * @return {@code this} instance for method chaining
     */
    public Config setSessionCooldownPeriod(int sessionCooldownPeriod) {
        if (sessionCooldownPeriod < 0) {
            L.wtf("sessionCooldownPeriod cannot be negative");
        } else {
            this.sessionCooldownPeriod = sessionCooldownPeriod;
        }
        return this;
    }

    /**
     * Change name of SDK used in HTTP requests
     *
     * @param sdkName new name of SDK
     * @return {@code this} instance for method chaining
     */
    public Config setSdkName(String sdkName) {
        if (Utils.isEmpty(sdkName)) {
            L.wtf("sdkName cannot be empty");
        } else {
            this.sdkName = sdkName;
        }
        return this;
    }

    /**
     * Change version of SDK used in HTTP requests
     *
     * @param sdkVersion new version of SDK
     * @return {@code this} instance for method chaining
     */
    public Config setSdkVersion(String sdkVersion) {
        if (Utils.isEmpty(sdkVersion)) {
            L.wtf("sdkVersion cannot be empty");
        } else {
            this.sdkVersion = sdkVersion;
        }
        return this;
    }

    /**
     * Change application name reported to Countly server
     *
     * @param name new name
     * @return {@code this} instance for method chaining
     */
    public Config setApplicationName(String name) {
        if (Utils.isEmpty(name)) {
            L.wtf("name cannot be empty");
        } else {
            this.applicationName = name;
        }
        return this;
    }

    /**
     * Change application version reported to Countly server
     *
     * @param version new version
     * @return {@code this} instance for method chaining
     */
    public Config setApplicationVersion(String version) {
        if (Utils.isEmpty(version)) {
            L.wtf("version cannot be empty");
        } else {
            this.applicationVersion = version;
        }
        return this;
    }

    /**
     * Set connection timeout in seconds for HTTP requests SDK sends to Countly server. Defaults to 30.
     *
     * @param seconds network timeout in seconds
     * @return {@code this} instance for method chaining
     */
    public Config setNetworkConnectTimeout(int seconds) {
        if (seconds <= 0 || seconds > 300) {
            L.wtf("Connection timeout must be between 0 and 300");
        } else {
            networkConnectionTimeout = seconds;
        }
        return this;
    }

    /**
     * Set read timeout in seconds for HTTP requests SDK sends to Countly server. Defaults to 30.
     *
     * @param seconds read timeout in seconds
     * @return {@code this} instance for method chaining
     */
    public Config setNetworkReadTimeout(int seconds) {
        if (seconds <= 0 || seconds > 300) {
            L.wtf("Read timeout must be between 0 and 300");
        } else {
            networkReadTimeout = seconds;
        }
        return this;
    }

    /**
     * How long to wait between requests in seconds.
     * Used to decrease CPU & I/O load on the device in case of batch requests.
     *
     * @param milliseconds cooldown period in seconds
     * @return {@code this} instance for method chaining
     */
    public Config setNetworkRequestCooldown(int milliseconds) {
        if (milliseconds < 0 || milliseconds > 30000) {
            L.wtf("Request cooldown must be between 0 and 30000");
        } else {
            networkRequestCooldown = milliseconds;
        }
        return this;
    }

    /**
     * Set read timeout in seconds for HTTP requests SDK sends to Countly server. Defaults to 30.
     * Used to decrease CPU & I/O load on the device in case of batch requests.
     *
     * @param milliseconds read timeout in milliseconds
     * @return {@code this} instance for method chaining
     */
    public Config setNetworkImportantRequestCooldown(int milliseconds) {
        if (milliseconds < 0 || milliseconds > 30) {
            L.wtf("Important request cooldown must be between 0 and 30");
        } else {
            networkImportantRequestCooldown = milliseconds;
        }
        return this;
    }

    /**
     * Enable SSL public key pinning. Improves HTTPS security by not allowing MiM attacks
     * based on SSL certificate replacing somewhere between Android device and Countly server.
     * Here you can set one or more PEM-encoded public keys which Countly SDK verifies against
     * public keys provided by Countly's web server for each HTTPS connection. At least one match
     * results in connection being established, no matches result in request not being sent stored for next try.
     *
     * NOTE: Public key pinning is preferred over certificate pinning due to the fact
     * that public keys are usually not changed when certificate expires and you generate new one.
     * This ensures pinning continues to work after certificate prolongation.
     * Certificates ({@link #certificatePins}) on the other hand have specific expiry date.
     * In case you chose this way of pinning, you MUST ensure that ALL installs of your app
     * have both certificates (old & new) until expiry date.
     *
     * NOTE: when {@link #serverURL} doesn't have {@code "https://"} public key pinning doesn't work
     *
     * @param pemEncodedPublicKey PEM-encoded SSL public key string to add
     * @return {@code this} instance for method chaining
     */
    public Config addPublicKeyPin(String pemEncodedPublicKey) {
        if (Utils.isEmpty(pemEncodedPublicKey)) {
            L.wtf("pemEncodedPublicKey cannot be empty");
        } else {
            if (publicKeyPins == null) {
                publicKeyPins = new HashSet<>();
            }

            publicKeyPins.add(pemEncodedPublicKey);
        }
        return this;
    }

    /**
     * Enable SSL certificate pinning. Improves HTTPS security by not allowing MiM attacks
     * based on SSL certificate replacing somewhere between Android device and Countly server.
     * Here you can set one or more PEM-encoded certificates which Countly SDK verifies against
     * certificates provided by Countly's web server for each HTTPS connection. At least one match
     * results in connection being established, no matches result in request not being sent stored for next try.
     *
     * NOTE: Public key pinning ({@link #publicKeyPins}) is preferred over certificate pinning due to the fact
     * that public keys are usually not changed when certificate expires and you generate new one.
     * This ensures pinning continues to work after certificate prolongation.
     * Certificates on the other hand have specific expiry date.
     * In case you chose this way of pinning, you MUST ensure that ALL installs of your app
     * have both certificates (old & new) until expiry date.
     *
     * NOTE: when {@link #serverURL} doesn't have {@code "https://"} certificate pinning doesn't work
     *
     * @param pemEncodedCertificate PEM-encoded SSL certificate string to add
     * @return {@code this} instance for method chaining
     */
    public Config addCertificatePin(String pemEncodedCertificate) {
        if (Utils.isEmpty(pemEncodedCertificate)) {
            L.wtf("pemEncodedCertificate cannot be empty");
        } else {
            if (certificatePins == null) {
                certificatePins = new HashSet<>();
            }

            certificatePins.add(pemEncodedCertificate);
        }
        return this;
    }

    /**
     * Change period when a check for ANR is made. ANR reporting is enabled by default once you enable {@code Feature.CrashReporting}.
     * Default period is 5 seconds. This is *NOT* a timeout for any possible time frame within app running time, it's a checking period.
     * Meaning *some* ANRs are to be recorded if main thread is blocked for slightly more than {@link #crashReportingANRCheckingPeriod}.
     * Statistically it should be good enough as you don't really need all ANRs on the server.
     * *More* ANRs will be recorded in case main thread is blocked for {@code 1.5 * crashReportingANRCheckingPeriod}. Almost all ANRs
     * are going to be recorded once main thread is blocked for {@code 2 * crashReportingANRCheckingPeriod} or more seconds.
     *
     * To disable ANR reporting, use {@link #disableANRCrashReporting()}.
     *
     * @param periodInSeconds how much time the SDK waits between individual ANR checks
     * @return {@code this} instance for method chaining
     */
    public Config setCrashReportingANRCheckingPeriod(int periodInSeconds) {
        if (periodInSeconds < 0) {
            L.wtf("ANR timeout less than zero doesn't make sense");
        } else {
            this.crashReportingANRCheckingPeriod = periodInSeconds;
        }
        return this;
    }

    /**
     * Disable ANR detection and thus reporting to Countly server.
     *
     * @return {@code this} instance for method chaining
     */
    public Config disableANRCrashReporting() {
        this.crashReportingANRCheckingPeriod = 0;
        return this;
    }

    /**
     * Set crash processor class responsible .
     * Defaults automatically to main activity class.
     *
     * @param crashProcessorClass {@link CrashProcessor}-implementing class
     * @return {@code this} instance for method chaining
     */
    public Config setCrashProcessorClass(Class<? extends CrashProcessor> crashProcessorClass) {
        if (crashProcessorClass == null) {
            L.wtf("crashProcessorClass cannot be null");
        } else {
            this.crashProcessorClass = crashProcessorClass.getName();
        }
        return this;
    }

    /**
     * Override some {@link Module} functionality with your own class.
     *
     * @param feature feature index to override
     * @param cls {@link Class} to use instead of Countly SDK standard class
     * @return {@code this} instance for method chaining
     */
    protected Config overrideModule(Integer feature, Class<? extends Module> cls) {
        if (feature == null || cls == null) {
            L.wtf("Feature & class cannot be null");
        } else {
            if (moduleOverrides == null) {
                moduleOverrides = new HashMap<>();
            }
            moduleOverrides.put(feature, cls);
        }
        return this;
    }

    /**
     * Getter for {@link #features}
     *
     * @return {@link #features} value
     */
    public Set<Config.Feature> getFeatures() {
        Set<Config.Feature> ftrs = new HashSet<>();
        for (Config.Feature f : Config.Feature.values()) {
            if ((f.index & features) > 0) {
                ftrs.add(f);
            }
        }
        return ftrs;
    }

    public int getFeaturesMap() { return features; }

    /**
     * Whether a feature is enabled in this config, that is exists in {@link #features}
     *
     * @return {@code true} if {@link #features} contains supplied argument, {@code false} otherwise
     */
    public boolean isFeatureEnabled(Config.Feature feature) {
        return (features & feature.index) > 0;
    }

    /**
     * Getter for {@link #moduleOverrides}
     *
     * @return {@link #moduleOverrides} value for {@code Feature} specified
     */
    public Class<? extends Module> getModuleOverride(Config.Feature feature) {
        return moduleOverrides == null ? null : moduleOverrides.get(feature.index);
    }


    /**
     * Enable GDPR compliance by disallowing SDK to record any data until corresponding consent
     * calls are made.
     *
     * @param requiresConsent {@code true} to enable GDPR compliance
     * @return {@code this} instance for method chaining
     */
    public Config setRequiresConsent(boolean requiresConsent) {
        this.requiresConsent = requiresConsent;
        return this;
    }

    /**
     * Enable auto views tracking
     *
     * @see #autoViewsTracking
     * @param autoViewsTracking whether to enable it or disable
     * @return {@code this} instance for method chaining
     */
    public Config setAutoViewsTracking(boolean autoViewsTracking) {
        this.autoViewsTracking = autoViewsTracking;
        return this;
    }

    /**
     * Enable auto sessions tracking
     *
     * @see #autoSessionsTracking
     * @param autoSessionsTracking whether to enable it or disable
     * @return {@code this} instance for method chaining
     */
    public Config setAutoSessionsTracking(boolean autoSessionsTracking) {
        this.autoSessionsTracking = autoSessionsTracking;
        return this;
    }

    /**
     * Wait this much time before ending session in auto session tracking mode
     *
     * @see #autoSessionsTracking
     * @param sessionAutoCloseAfter time in seconds
     * @return {@code this} instance for method chaining
     */
    public Config setSessionAutoCloseAfter(int sessionAutoCloseAfter) {
        this.sessionAutoCloseAfter = sessionAutoCloseAfter;
        return this;
    }

    /**
     * Getter for {@link #autoSessionsTracking}
     * @return {@link #autoSessionsTracking} value
     */
    public boolean isAutoViewsTrackingEnabled() {
        return autoViewsTracking;
    }

    /**
     * Getter for {@link #autoSessionsTracking}
     * @return {@link #autoSessionsTracking} value
     */
    public boolean isAutoSessionsTrackingEnabled() {
        return autoSessionsTracking;
    }

    /**
     * Getter for {@link #sessionAutoCloseAfter}
     * @return {@link #sessionAutoCloseAfter} value
     */
    public int getSessionAutoCloseAfter() {
        return sessionAutoCloseAfter;
    }

    /**
     * Getter for {@link #serverURL}
     * @return {@link #serverURL} value
     */
    public URL getServerURL() {
        return serverURL;
    }

    /**
     * Getter for {@link #serverAppKey}
     * @return {@link #serverAppKey} value
     */
    public String getServerAppKey() {
        return serverAppKey;
    }

    /**
     * Getter for {@link #deviceIdStrategy}
     * @return {@link #deviceIdStrategy} value
     */
    public int getDeviceIdStrategy() {
        return deviceIdStrategy;
    }

    /**
     * Whether to allow fallback from unavailable device id strategy to any other available.
     *
     * @return true if fallback is allowed
     */
    public boolean isDeviceIdFallbackAllowed() {
        return deviceIdFallbackAllowed;
    }

    /**
     * Getter for {@link #customDeviceId}
     * @return {@link #customDeviceId} value
     */
    public String getCustomDeviceId() {
        return customDeviceId;
    }

    /**
     * Getter for {@link #usePOST}
     * @return {@link #usePOST} value
     */
    public boolean isUsePOST() {
        return usePOST;
    }

    /**
     * Getter for {@link #enableBackendMode}
     * @return {@link #enableBackendMode} value
     */
    public boolean isBackendModeEnabled() {
        return enableBackendMode;
    }

    /**
     * Getter for {@link #salt}
     * @return {@link #salt} value
     */
    public String getParameterTamperingProtectionSalt() {
        return salt;
    }

    /**
     * Getter for {@link #sdkName}
     * @return {@link #sdkName} value
     */
    public String getSdkName() {
        return sdkName;
    }

    /**
     * Getter for {@link #sdkVersion}
     * @return {@link #sdkVersion} value
     */
    public String getSdkVersion() {
        return sdkVersion;
    }

    /**
     * Check if particular feature is enabled
     * @param feature index of feature to check
     * @return {@code true} if the feature is enabled
     */
    public boolean isFeatureEnabled(int feature) {
        return (features & feature) > 0;
    }

    /**
     * Getter for {@link #applicationName}
     * @return {@link #applicationName} value
     */
    public String getApplicationName() {
        return applicationName;
    }

    /**
     * Getter for {@link #applicationVersion}
     * @return {@link #applicationVersion} value
     */
    public String getApplicationVersion() {
        return applicationVersion;
    }

    /**
     * Getter for {@link #loggingTag}
     * @return {@link #loggingTag} value
     */
    public String getLoggingTag() {
        return loggingTag;
    }

    /**
     * Getter for {@link #loggingLevel}
     * @return {@link #loggingLevel} value
     */
    public LoggingLevel getLoggingLevel() {
        return loggingLevel;
    }

    /**
     * Getter for {@link #testMode}
     * @return {@link #testMode} value
     */
    public boolean isTestModeEnabled() {
        return testMode;
    }

    /**
     * Getter for {@link #sendUpdateEachSeconds}
     * @return {@link #sendUpdateEachSeconds} value
     */
    public int getSendUpdateEachSeconds() {
        return sendUpdateEachSeconds;
    }

    /**
     * Getter for {@link #sessionCooldownPeriod}
     * @return {@link #sessionCooldownPeriod} value
     */
    public int getSessionCooldownPeriod() {
        return sessionCooldownPeriod;
    }

    /**
     * Getter for {@link #eventsBufferSize}
     * @return {@link #eventsBufferSize} value
     */
    public int getEventsBufferSize() {
        return eventsBufferSize;
    }

    /**
     * Getter for {@link #networkConnectionTimeout}
     * @return {@link #networkConnectionTimeout} value
     */
    public int getNetworkConnectionTimeout() {
        return networkConnectionTimeout;
    }

    /**
     * Getter for {@link #networkReadTimeout}
     * @return {@link #networkReadTimeout} value
     */
    public int getNetworkReadTimeout() {
        return networkReadTimeout;
    }

    /**
     * Getter for {@link #networkRequestCooldown}
     * @return {@link #networkRequestCooldown} value
     */
    public int getNetworkRequestCooldown() {
        return networkRequestCooldown;
    }

    /**
     * Getter for {@link #networkImportantRequestCooldown}
     * @return {@link #networkImportantRequestCooldown} value
     */
    public int getNetworkImportantRequestCooldown() {
        return networkImportantRequestCooldown;
    }

    /**
     * Getter for {@link #publicKeyPins}
     * @return {@link #publicKeyPins} value
     */
    public Set<String> getPublicKeyPins() { return publicKeyPins; }

    /**
     * Getter for {@link #certificatePins}
     * @return {@link #certificatePins} value
     */
    public Set<String> getCertificatePins() { return certificatePins; }

    /**
     * Getter for {@link #crashReportingANRCheckingPeriod}
     * @return {@link #crashReportingANRCheckingPeriod} value
     */
    public int getCrashReportingANRCheckingPeriod() {
        return crashReportingANRCheckingPeriod;
    }

    /**
     * Getter for {@link #crashProcessorClass}
     * @return {@link #crashProcessorClass} value
     */
    public String getCrashProcessorClass() {
        return crashProcessorClass;
    }

    /**
     * Getter for {@link #moduleOverrides}
     * @return {@link #moduleOverrides} value for {@code Feature} specified
     */
    public Class<? extends Module> getModuleOverride(int feature) {
        return moduleOverrides == null ? null : moduleOverrides.get(feature);
    }

    /**
     * Getter for {@link #requiresConsent}
     * @return {@link #requiresConsent} value
     */
    public boolean requiresConsent() {
        return requiresConsent;
    }
}

