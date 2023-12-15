package ly.count.sdk.java;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import ly.count.sdk.java.internal.CoreFeature;
import ly.count.sdk.java.internal.Log;
import ly.count.sdk.java.internal.LogCallback;
import ly.count.sdk.java.internal.ModuleBase;
import ly.count.sdk.java.internal.RCDownloadCallback;
import ly.count.sdk.java.internal.Utils;

/**
 * Countly configuration object.
 */
public class Config {
    /**
     * Logging level for {@link Log} module
     */
    public enum LoggingLevel {
        VERBOSE(0),
        DEBUG(1),
        INFO(2),
        WARN(3),
        ERROR(4),
        OFF(5);

        private final int level;

        LoggingLevel(int level) {
            this.level = level;
        }

        public int getLevel() {
            return level;
        }

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
        UserProfiles(CoreFeature.UserProfiles.getIndex()),
        Feedback(CoreFeature.Feedback.getIndex()),
        RemoteConfig(CoreFeature.RemoteConfig.getIndex());
        //        StarRating(1 << 12),
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
            } else if (index == RemoteConfig.index) {
                return RemoteConfig;
            } else if (index == Feedback.index) {
                return Feedback;
            } else {
                return null;
            }
        }
    }

    /**
     * Holder class for various ids met
     * adata and id itself. Final, unmodifiable.
     */
    public static final class DID {
        public static final int STRATEGY_UUID = 0;
        public static final int STRATEGY_CUSTOM = 10;
        public int strategy;
        public String id;

        public DID(int strategy, String id) {
            this.strategy = strategy;
            this.id = id;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof DID)) {
                return false;
            }
            DID did = (DID) obj;
            return did.strategy == strategy && (Objects.equals(did.id, id));
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }

        @Override
        public String toString() {
            return "DID " + id + " ( " + strategy + ")";
        }
    }

    protected Log configLog;

    /**
     * URL of Countly server
     */
    protected URL serverURL;

    /**
     * Application key of Countly server
     */
    protected String serverAppKey;

    /**
     * Set of Countly SDK features enabled
     */
    protected int features = 0;

    /**
     * Device id generation strategy, UUID by default
     */
    protected int deviceIdStrategy = 0;

    /**
     * Developer specified device id
     */
    protected String customDeviceId;

    /**
     * Logging level
     */
    protected LoggingLevel loggingLevel = LoggingLevel.OFF;

    /**
     * Log listener
     */
    protected LogCallback logListener = null;

    /**
     * Countly SDK name to be sent in HTTP requests
     */
    protected String sdkName = "java-native";

    /**
     * Countly SDK version to be sent in HTTP requests
     */
    protected String sdkVersion = "23.10.1";

    /**
     * Countly SDK version to be sent in HTTP requests
     */
    protected String applicationVersion;

    /**
     * Force usage of POST method for all requests
     */
    protected boolean forceHTTPPost = false;

    /**
     * This would be a special state where the majority of the SDK calls don't work anymore and only a few special calls work.
     */
    protected boolean enableBackendMode = false;

    protected final Map<String, String> metricOverride = new HashMap<>();

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
     * Update request is also sent when number of unsent events reached {@link #eventQueueThreshold}.
     *
     * Set to 0 to disable update requests based on time.
     */
    protected int sendUpdateEachSeconds = 60;

    /**
     * Maximum number of events to hold until request is to be sent to the server
     *
     * Events are also sent along with session update each {@link #sendUpdateEachSeconds}.
     *
     * Set to 0 to disable buffering.
     */
    protected int eventQueueThreshold = 10;

    /**
     * {@link CrashProcessor}-implementing class which is instantiated when application
     * crashes or crash is reported programmatically using {@link Session#addCrashReport(Throwable, boolean, String, Map, String...)}.
     * Crash processor helps you to add custom data in event of a crash: custom crash segments & crash logs.
     */
    protected String crashProcessorClass = null;

    /**
     * Feature-Class map which sets ModuleBase overrides.
     */
    protected Map<Integer, Class<? extends ModuleBase>> moduleOverrides = null;

    /**
     * Requires GDPR-compliance calls.
     * If {@code true}, SDK waits for corresponding consent calls before recording any data.
     */
    protected boolean requiresConsent = false;

    //endregion

    //Begin Remote Config Module Fields

    /**
     * If remote config automatic fetching should be enabled
     */
    protected boolean enableRemoteConfigAutomaticDownloadTriggers = false;

    /**
     * If remote config value caching should be enabled
     */
    protected boolean enableRemoteConfigValueCaching = false;

    /**
     * If automatic remote config enrollment should be enabled
     */
    protected boolean enableAutoEnrollFlag = false;

    protected List<RCDownloadCallback> remoteConfigGlobalCallbacks = new ArrayList<>();

    //End Remote Config Module Fields

    /**
     * Maximum in memory request queue size.
     */
    protected int requestQueueMaxSize = 1000;

    /**
     * Storage path for storing requests and events queues
     */
    protected File sdkStorageRootDirectory = null;

    /**
     * If sdk used across multiple platforms
     */
    protected String sdkPlatform = System.getProperty("os.name");

    protected String location = null;
    protected String ip = null;
    protected String city = null;
    protected String country = null;
    protected boolean locationEnabled = true;

    //    /**
    //    * Maximum size of all string keys
    //    */
    //    protected int maxKeyLength = 128;
    //
    //    /**
    //    * Maximum size of all values in our key-value pairs
    //    */
    //    protected int maxValueSize = 256;
    //
    //    /**
    //    * Max amount of custom (dev provided) segmentation in one event
    //    */
    //    protected int maxSegmentationValues = 30;
    //
    //    /**
    //    * Limits how many stack trace lines would be recorded per thread
    //    */
    //    protected int maxStackTraceLinesPerThread = 30;
    //
    //    /**
    //    * Limits how many characters are allowed per stack trace line
    //    */
    //    protected int maxStackTraceLineLength = 200;
    //
    //    /**
    //    * Set the maximum amount of breadcrumbs.
    //    */
    //    protected int totalBreadcrumbsAllowed = 100;
    //
    //    //endregion
    //
    //    public int getMaxKeyLength() {
    //        return maxKeyLength;
    //    }
    //
    //    public Config setMaxKeyLength(int maxKeyLength) {
    //        this.maxKeyLength = maxKeyLength;
    //        return this;
    //    }
    //
    //    public int getMaxValueSize() {
    //        return maxValueSize;
    //    }
    //
    //    public Config setMaxValueSize(int maxValueSize) {
    //        this.maxValueSize = maxValueSize;
    //        return this;
    //    }
    //
    //    public int getMaxSegmentationValues() {
    //        return maxSegmentationValues;
    //    }
    //
    //    public Config setMaxSegmentationValues(int maxSegmentationValues) {
    //        this.maxSegmentationValues = maxSegmentationValues;
    //        return this;
    //    }
    //
    //    public int getMaxStackTraceLinesPerThread() {
    //        return maxStackTraceLinesPerThread;
    //    }
    //
    //    public Config setMaxStackTraceLinesPerThread(int maxStackTraceLinesPerThread) {
    //        this.maxStackTraceLinesPerThread = maxStackTraceLinesPerThread;
    //        return this;
    //
    //    }
    //
    //    public int getMaxStackTraceLineLength() {
    //        return maxStackTraceLineLength;
    //    }
    //
    //    public Config setMaxStackTraceLineLength(int maxStackTraceLineLength) {
    //        this.maxStackTraceLineLength = maxStackTraceLineLength;
    //        return this;
    //    }
    //
    //    public int getTotalBreadcrumbsAllowed() {
    //        return totalBreadcrumbsAllowed;
    //    }
    //
    //    public Config setTotalBreadcrumbsAllowed(int totalBreadcrumbsAllowed) {
    //        this.totalBreadcrumbsAllowed = totalBreadcrumbsAllowed;
    //        return this;
    //
    //    }

    // TODO: storage limits & configuration
    //    protected int maxRequestsStored = 0;
    //    protected int storageDirectory = "";
    //    protected int storagePrefix = "[CLY]_";

    /**
     * The only Config constructor.
     *
     * @param serverURL valid {@link URL} of Countly server
     * @param serverAppKey App Key from Management -> Applications section of your Countly Dashboard
     * @deprecated use {@link #Config(String, String, File)} instead
     */
    public Config(String serverURL, String serverAppKey) {
        this(serverURL, serverAppKey, null);
    }

    /**
     * The only Config constructor.
     *
     * @param serverURL valid {@link URL} of Countly server
     * @param serverAppKey App Key from Management -> Applications section of your Countly Dashboard
     * @param sdkStorageRootDirectory root directory for SDK files
     */
    public Config(String serverURL, String serverAppKey, File sdkStorageRootDirectory) {
        //the last '/' should be deleted
        if (serverURL != null && serverURL.length() > 0 && serverURL.charAt(serverURL.length() - 1) == '/') {
            serverURL = serverURL.substring(0, serverURL.length() - 1);
        }

        try {
            this.serverURL = new URL(serverURL);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(e);
        }
        this.serverAppKey = serverAppKey;
        this.sdkStorageRootDirectory = sdkStorageRootDirectory;
    }

    /**
     * Whether to allow fallback from unavailable device id strategy to Countly OpenUDID derivative.
     *
     * @param deviceIdFallbackAllowed true if fallback is allowed
     * @return {@code this} instance for method chaining
     * @deprecated this will do nothing
     */
    public Config setDeviceIdFallbackAllowed(boolean deviceIdFallbackAllowed) {
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
            if (configLog != null) {
                configLog.e("[Config] Features array cannot be null");
            }
        } else {
            for (Config.Feature f : features) {
                if (f == null) {
                    if (configLog != null) {
                        configLog.e("[Config] Feature cannot be null");
                    }
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
            if (configLog != null) {
                configLog.e("[Config] Features array cannot be null");
            }
        } else {
            for (Config.Feature f : features) {
                if (f == null) {
                    if (configLog != null) {
                        configLog.e("[Config] Feature cannot be null");
                    }
                } else {
                    this.features = this.features & ~f.getIndex();
                }
            }
        }
        return this;
    }

    /**
     * Add a log callback that will duplicate all logs done by the SDK.
     * For each message you will receive the message string and it's targeted log level.
     *
     * @param logCallback
     * @return Returns the same config object for convenient linking
     */
    public Config setLogListener(LogCallback logCallback) {
        this.logListener = logCallback;
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
                    if (configLog != null) {
                        configLog.e("[Config] " + i + "-th feature is null in setFeatures");
                    }
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
     * @param strategy strategy to use instead of default OpenUDID
     * @param customDeviceId device id for use with {@link DeviceIdStrategy#CUSTOM_ID}
     * @return {@code this} instance for method chaining
     */
    public Config setDeviceIdStrategy(DeviceIdStrategy strategy, String customDeviceId) {
        if (strategy == null) {
            if (configLog != null) {
                configLog.e("[Config] DeviceIdStrategy cannot be null");
            }
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
        if (Utils.isEmptyOrNull(customDeviceId)) {
            if (configLog != null) {
                configLog.e("[Config] DeviceIdStrategy.CUSTOM_ID strategy cannot be used without device id specified");
            }

            this.customDeviceId = null;
            this.deviceIdStrategy = 0;
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
     * @deprecated use {@link #enableForcedHTTPPost()} instead
     */
    public Config enableUsePOST() {
        return enableForcedHTTPPost();
    }

    /**
     * Force usage of POST method for all requests
     *
     * @return {@code this} instance for method chaining
     */
    public Config enableForcedHTTPPost() {
        this.forceHTTPPost = true;
        return this;
    }

    /**
     * Force usage of POST method for all requests.
     *
     * @param forcePost whether to force using POST method for all requests or not
     * @return {@code this} instance for method chaining
     * @deprecated please use {@link #enableForcedHTTPPost()} instead
     */
    public Config setUsePOST(boolean forcePost) {
        this.forceHTTPPost = forcePost;
        return this;
    }

    /**
     * Enable SDK's backend mode.
     *
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
     *
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
        if (Utils.isEmptyOrNull(salt)) {
            if (configLog != null) {
                configLog.e("[Config] Salt cannot be empty in enableParameterTamperingProtection");
            }
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
     * @deprecated Calling this function will do nothing
     */
    public Config setLoggingTag(String loggingTag) {
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
            if (configLog != null) {
                configLog.e("[Config] Logging level cannot be null");
            }
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
     * @deprecated Calling this function will do nothing
     */
    public Config enableTestMode() {
        return this;
    }

    /**
     * Disable test mode, so SDK will silently avoid raising exceptions whenever possible.
     * Test mode is disabled by default.
     *
     * @return {@code this} instance for method chaining
     * @deprecated Calling this function will do nothing
     */
    public Config disableTestMode() {
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
     * @deprecated this will be removed, please use {@link #setUpdateSessionTimerDelay(int)}
     */
    public Config setSendUpdateEachSeconds(int sendUpdateEachSeconds) {
        return setUpdateSessionTimerDelay(sendUpdateEachSeconds);
    }

    /**
     * Changes the maximum amount of time in seconds between two update requests to the server reporting
     * session duration and other parameters, if any, added between update requests.
     *
     * An update request is also sent when the number of unsent events reaches {@link #setEventQueueSizeToSend(int)}.
     *
     * @param delay max time interval between two update requests, set to 0 to disable update requests based on time.
     * @return {@code this} instance for method chaining
     */
    public Config setUpdateSessionTimerDelay(int delay) {
        if (delay < 0) {
            if (configLog != null) {
                configLog.e("[Config] delay cannot be negative");
            }
        } else {
            this.sendUpdateEachSeconds = delay;
        }
        return this;
    }

    /**
     * Sets maximum number of events to hold until forcing update request to be sent to the server
     *
     * Update request is also sent when last update request was sent more than {@link #setSendUpdateEachSeconds(int)} seconds ago.
     *
     * @param eventQueueThreshold max number of events between two update requests, set to 0 to disable update requests based on events.
     * @return {@code this} instance for method chaining
     * @deprecated this will be removed, please use {@link #setEventQueueSizeToSend(int)}
     */
    public Config setEventsBufferSize(int eventQueueThreshold) {
        return setEventQueueSizeToSend(eventQueueThreshold);
    }

    /**
     * Changes the maximum number of events to hold until an update request is sent to the server
     *
     * An update request is also sent when the last update request was sent more than {@link #setUpdateSessionTimerDelay(int)} seconds ago.
     *
     * @param eventsQueueSize max number of events between two update requests, set to 0 to disable update requests based on events.
     * @return {@code this} instance for method chaining
     */
    public Config setEventQueueSizeToSend(int eventsQueueSize) {
        if (eventsQueueSize < 0) {
            if (configLog != null) {
                configLog.e("[Config] eventsQueueSize cannot be negative");
            }
        } else {
            this.eventQueueThreshold = eventsQueueSize;
        }
        return this;
    }

    /**
     * Disable update requests completely. Only begin & end requests will be sent + some special
     * cases if applicable like User Profile change or Push token updated.
     *
     * @return {@code this} instance for method chaining
     * @see #setUpdateSessionTimerDelay(int)
     * @see #setEventQueueSizeToSend(int)
     */
    public Config disableUpdateRequests() {
        this.sendUpdateEachSeconds = 0;
        return this;
    }

    /**
     * Change name of SDK used in HTTP requests
     *
     * @param sdkName new name of SDK
     * @return {@code this} instance for method chaining
     * @deprecated Calling this function will do nothing
     */
    public Config setSdkName(String sdkName) {
        return this;
    }

    /**
     * Change version of SDK used in HTTP requests
     *
     * @param sdkVersion new version of SDK
     * @return {@code this} instance for method chaining
     * @deprecated Calling this function will do nothing
     */
    public Config setSdkVersion(String sdkVersion) {
        return this;
    }

    /**
     * Change application name reported to Countly server
     *
     * @param name new name
     * @return {@code this} instance for method chaining
     * @deprecated this will do nothing
     */
    public Config setApplicationName(String name) {
        return this;
    }

    /**
     * Change application version reported to Countly server
     *
     * @param version new version
     * @return {@code this} instance for method chaining
     */
    public Config setApplicationVersion(String version) {
        if (Utils.isEmptyOrNull(version)) {
            if (configLog != null) {
                configLog.e("[Config] version cannot be empty");
            }
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
            if (configLog != null) {
                configLog.e("[Config] Connection timeout must be between 0 and 300");
            }
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
            if (configLog != null) {
                configLog.e("[Config] Read timeout must be between 0 and 300");
            }
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
            if (configLog != null) {
                configLog.e("[Config] Request cooldown must be between 0 and 30000");
            }
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
            if (configLog != null) {
                configLog.e("[Config] Important request cooldown must be between 0 and 30");
            }
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
        if (Utils.isEmptyOrNull(pemEncodedPublicKey)) {
            if (configLog != null) {
                configLog.e("[Config] pemEncodedPublicKey cannot be empty");
            }
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
        if (Utils.isEmptyOrNull(pemEncodedCertificate)) {
            if (configLog != null) {
                configLog.e("[Config] pemEncodedCertificate cannot be empty");
            }
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
     * Meaning *some* ANRs are to be recorded if main thread is blocked for slightly more than #crashReportingANRCheckingPeriod.
     * Statistically it should be good enough as you don't really need all ANRs on the server.
     * *More* ANRs will be recorded in case main thread is blocked for {@code 1.5 * crashReportingANRCheckingPeriod}. Almost all ANRs
     * are going to be recorded once main thread is blocked for {@code 2 * crashReportingANRCheckingPeriod} or more seconds.
     *
     * To disable ANR reporting, use {@link #disableANRCrashReporting()}.
     *
     * @param periodInSeconds how much time the SDK waits between individual ANR checks
     * @return {@code this} instance for method chaining
     * @deprecated will do nothing
     */
    public Config setCrashReportingANRCheckingPeriod(int periodInSeconds) {
        return this;
    }

    /**
     * Disable ANR detection and thus reporting to Countly server.
     *
     * @return {@code this} instance for method chaining
     * @deprecated will do nothing
     */
    public Config disableANRCrashReporting() {
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
            if (configLog != null) {
                configLog.e("[Config] crashProcessorClass cannot be null");
            }
        } else {
            this.crashProcessorClass = crashProcessorClass.getName();
        }
        return this;
    }

    /**
     * Override some {@link ModuleBase} functionality with your own class.
     *
     * @param feature feature index to override
     * @param cls {@link Class} to use instead of Countly SDK standard class
     * @return {@code this} instance for method chaining
     * @deprecated this will do nothing
     */
    protected Config overrideModule(Integer feature, Class<? extends ModuleBase> cls) {
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

    public int getFeaturesMap() {
        return features;
    }

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
     * @deprecated this will do nothing
     */
    public Class<? extends ModuleBase> getModuleOverride(Config.Feature feature) {
        return null;
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
     * Getter for {@link #serverURL}
     *
     * @return {@link #serverURL} value
     */
    public URL getServerURL() {
        return serverURL;
    }

    /**
     * Getter for {@link #serverAppKey}
     *
     * @return {@link #serverAppKey} value
     */
    public String getServerAppKey() {
        return serverAppKey;
    }

    /**
     * Getter for {@link #deviceIdStrategy}
     *
     * @return {@link #deviceIdStrategy} value
     */
    public int getDeviceIdStrategy() {
        return deviceIdStrategy;
    }

    /**
     * Whether to allow fallback from unavailable device id strategy to any other available.
     *
     * @return true if fallback is allowed
     * @deprecated this will always return "true"
     */
    public boolean isDeviceIdFallbackAllowed() {
        return true;
    }

    /**
     * Getter for {@link #customDeviceId}
     *
     * @return {@link #customDeviceId} value
     */
    public String getCustomDeviceId() {
        return customDeviceId;
    }

    /**
     * Getter for {@link #forceHTTPPost}
     *
     * @return {@link #forceHTTPPost} value
     */
    public boolean isHTTPPostForced() {
        return forceHTTPPost;
    }

    /**
     * Getter for {@link #enableBackendMode}
     *
     * @return {@link #enableBackendMode} value
     */
    public boolean isBackendModeEnabled() {
        return enableBackendMode;
    }

    /**
     * Getter for {@link #salt}
     *
     * @return {@link #salt} value
     */
    public String getParameterTamperingProtectionSalt() {
        return salt;
    }

    /**
     * Getter for {@link #sdkName}
     *
     * @return {@link #sdkName} value
     * @deprecated this will be removed
     */
    public String getSdkName() {
        return sdkName;
    }

    /**
     * Getter for {@link #sdkVersion}
     *
     * @return {@link #sdkVersion} value
     * @deprecated this will be removed
     */
    public String getSdkVersion() {
        return sdkVersion;
    }

    /**
     * Check if particular feature is enabled
     *
     * @param feature index of feature to check
     * @return {@code true} if the feature is enabled
     */
    public boolean isFeatureEnabled(int feature) {
        return (features & feature) > 0;
    }

    /**
     * Getter for applicationName
     *
     * @return applicationName value
     * @deprecated will return empty string
     */
    public String getApplicationName() {
        return "";
    }

    /**
     * Getter for {@link #applicationVersion}
     *
     * @return {@link #applicationVersion} value
     */
    public String getApplicationVersion() {
        return applicationVersion;
    }

    /**
     * @deprecated Calling this function will always return "Countly"
     */
    public String getLoggingTag() {
        return "Countly";
    }

    /**
     * Getter for {@link #loggingLevel}
     *
     * @return {@link #loggingLevel} value
     */
    public LoggingLevel getLoggingLevel() {
        return loggingLevel;
    }

    /**
     * Getter for {@link #logListener}
     *
     * @return {@link #logListener} value
     */
    public LogCallback getLogListener() {
        return logListener;
    }

    /**
     * Getter for #testMode
     *
     * @return #testMode value
     * @deprecated Calling this function will always return 'false'
     */
    public boolean isTestModeEnabled() {
        return false;
    }

    /**
     * Getter for {@link #sendUpdateEachSeconds}
     *
     * @return {@link #sendUpdateEachSeconds} value
     */
    public int getSendUpdateEachSeconds() {
        return sendUpdateEachSeconds;
    }

    /**
     * Getter for {@link #eventQueueThreshold}
     *
     * @return {@link #eventQueueThreshold} value
     * @deprecated
     */
    public int getEventsBufferSize() {
        return eventQueueThreshold;
    }

    /**
     * Getter for {@link #networkConnectionTimeout}
     *
     * @return {@link #networkConnectionTimeout} value
     */
    public int getNetworkConnectionTimeout() {
        return networkConnectionTimeout;
    }

    /**
     * Getter for {@link #networkReadTimeout}
     *
     * @return {@link #networkReadTimeout} value
     */
    public int getNetworkReadTimeout() {
        return networkReadTimeout;
    }

    /**
     * Getter for {@link #networkRequestCooldown}
     *
     * @return {@link #networkRequestCooldown} value
     */
    public int getNetworkRequestCooldown() {
        return networkRequestCooldown;
    }

    /**
     * Getter for {@link #networkImportantRequestCooldown}
     *
     * @return {@link #networkImportantRequestCooldown} value
     */
    public int getNetworkImportantRequestCooldown() {
        return networkImportantRequestCooldown;
    }

    /**
     * Getter for {@link #publicKeyPins}
     *
     * @return {@link #publicKeyPins} value
     */
    public Set<String> getPublicKeyPins() {
        return publicKeyPins;
    }

    /**
     * Getter for {@link #certificatePins}
     *
     * @return {@link #certificatePins} value
     */
    public Set<String> getCertificatePins() {
        return certificatePins;
    }

    /**
     * Getter for #crashReportingANRCheckingPeriod
     *
     * @return #crashReportingANRCheckingPeriod value
     * @deprecated will always return "5"
     */
    public int getCrashReportingANRCheckingPeriod() {
        return 5;
    }

    /**
     * Getter for {@link #crashProcessorClass}
     *
     * @return {@link #crashProcessorClass} value
     */
    public String getCrashProcessorClass() {
        return crashProcessorClass;
    }

    /**
     * Getter for {@link #moduleOverrides}
     *
     * @return {@link #moduleOverrides} value for {@code Feature} specified
     * @deprecated this always return "null"
     */
    public Class<? extends ModuleBase> getModuleOverride(int feature) {
        return null;
    }

    /**
     * Getter for {@link #requiresConsent}
     *
     * @return {@link #requiresConsent} value
     */
    public boolean requiresConsent() {
        return requiresConsent;
    }

    /**
     * Mechanism for overriding metrics that are sent together with "begin session" requests and remote config
     *
     * @param metricOverride map of values to be used for override
     * @return {@code this} instance for method chaining
     */
    public Config setMetricOverride(Map<String, String> metricOverride) {
        this.metricOverride.putAll(metricOverride);
        return this;
    }

    /**
     * Default sdk platform is os name
     * If you want to override it, you can use this method
     *
     * @param platform sdk platform
     * @return {@code this} instance for method chaining
     */
    public Config setSdkPlatform(String platform) {
        this.sdkPlatform = platform;
        return this;
    }

    /**
     * Getter for {@link #sdkPlatform}
     *
     * @return {@link #sdkPlatform} value
     */
    public String getSdkPlatform() {
        return sdkPlatform;
    }

    /**
     * Enable automatic download of remote config values
     *
     * @return {@code this} instance for method chaining
     */
    public Config enableRemoteConfigAutomaticTriggers() {
        this.enableRemoteConfigAutomaticDownloadTriggers = true;
        return this;
    }

    /**
     * Enable automatic enroll for AB
     *
     * @return {@code this} instance for method chaining
     */
    public Config enrollABOnRCDownload() {
        this.enableAutoEnrollFlag = true;
        return this;
    }

    /**
     * Enable caching of remote config values
     *
     * @return {@code this} instance for method chaining
     */
    public Config enableRemoteConfigValueCaching() {
        this.enableRemoteConfigValueCaching = true;
        return this;
    }

    /**
     * Register a callback to be called when remote config is downloaded
     *
     * @param callback to be called see {@link RCDownloadCallback}
     * @return {@code this} instance for method chaining
     */
    public Config remoteConfigRegisterGlobalCallback(RCDownloadCallback callback) {
        remoteConfigGlobalCallbacks.add(callback);
        return this;
    }

    /**
     * Set global location parameters
     *
     * @param countryCode ISO Country code
     * @param cityName City name
     * @param gpsCoordinates GPS coordinates in "lat,long" format
     * @param ipAddress IP address
     * @return {@code this} instance for method chaining
     */
    public Config setLocation(String countryCode, String cityName, String gpsCoordinates, String ipAddress) {
        country = countryCode;
        city = cityName;
        location = gpsCoordinates;
        ip = ipAddress;
        locationEnabled = true;
        return this;
    }

    /**
     * Disable location tracking
     *
     * @return {@code this} instance for method chaining
     */
    public Config disableLocation() {
        locationEnabled = false;
        return this;
    }
}
