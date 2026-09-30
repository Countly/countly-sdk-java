package ly.count.sdk.java.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import ly.count.sdk.java.Config;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Resolves, stores and serves the SDK behavior settings, the server side configuration served by
 * {@code /o/sdk?method=sc}.
 * <p>
 * Every setting starts from the developer configuration and is overridden by the settings provided
 * at init (only while nothing is stored), by the stored settings and by every later server response.
 * {@link SDKCore} runs {@link #init(InternalConfig)}, which does the resolve, before it starts the
 * global timer and builds the other modules; {@link #initFinished(InternalConfig)} fetches the
 * settings and starts the refresh timer. In backend mode the module stays inert and serves the
 * developer configuration.
 * <p>
 * Resolved values are published through volatile fields and immutable snapshots, so a reader on any
 * thread sees consistent values while a response is applied on the thread that delivered it.
 */
public class ModuleConfiguration extends ModuleBase implements ConfigurationProvider {

    static final String keyRTimestamp = "t";
    static final String keyRVersion = "v";
    static final String keyRConfig = "c";
    static final String keyRLogGathering = "lg";
    static final String keyRConnectionTest = "ct";

    static final String keyRTracking = "tracking";
    static final String keyRNetworking = "networking";
    static final String keyRReqQueueSize = "rqs";
    static final String keyREventQueueSize = "eqs";
    static final String keyRLogging = "log";
    static final String keyRSessionUpdateInterval = "sui";
    static final String keyRSessionTracking = "st";
    static final String keyRViewTracking = "vt";
    static final String keyRLocationTracking = "lt";
    static final String keyRRefreshContentZone = "rcz";
    static final String keyRLimitKeyLength = "lkl";
    static final String keyRLimitValueSize = "lvs";
    static final String keyRLimitSegValues = "lsv";
    static final String keyRLimitBreadcrumb = "lbc";
    static final String keyRLimitTraceLine = "ltlpt";
    static final String keyRLimitTraceLength = "ltl";
    static final String keyRCustomEventTracking = "cet";
    static final String keyREnterContentZone = "ecz";
    static final String keyRContentZoneInterval = "czi";
    static final String keyRConsentRequired = "cr";
    static final String keyRDropOldRequestTime = "dort";
    static final String keyRCrashReporting = "crt";
    static final String keyRAutomaticSessionTracking = "ast";
    static final String keyRAutomaticViewTracking = "avt";
    static final String keyRAutomaticCrashReporting = "acr";
    static final String keyRServerConfigUpdateInterval = "scui";
    static final String keyRBackoffMechanism = "bom";
    static final String keyRBOMAcceptedTimeout = "bom_at";
    static final String keyRBOMRQPercentage = "bom_rqp";
    static final String keyRBOMRequestAge = "bom_ra";
    static final String keyRBOMDuration = "bom_d";
    static final String keyRUserPropertyCacheLimit = "upcl";
    static final String keyREventBlacklist = "eb";
    static final String keyRUserPropertyBlacklist = "upb";
    static final String keyRSegmentationBlacklist = "sb";
    static final String keyREventSegmentationBlacklist = "esb";
    static final String keyREventWhitelist = "ew";
    static final String keyRUserPropertyWhitelist = "upw";
    static final String keyRSegmentationWhitelist = "sw";
    static final String keyREventSegmentationWhitelist = "esw";
    static final String keyRJourneyTriggerEvents = "jte";
    static final String keyRJourneyTriggerViews = "jtv";

    static final String keyLGEnabled = "e";
    static final String keyLGId = "i";
    static final String keyLGLevels = "l";
    static final String keyLGBatchSize = "b";

    static final String logGatheringAllLevels = "ewidv";
    static final int logGatheringDefaultBatchSize = 100;
    static final int logGatheringMinBatchSize = 10;
    //the log buffer ceiling, so also the largest batch the server may ask for: a bigger batch would never fill
    static final int logGatheringMaxBufferedLines = 500;

    static final int DEFAULT_SERVER_CONFIG_UPDATE_INTERVAL_HOURS = 4;
    static final int DEFAULT_BOM_ACCEPTED_TIMEOUT_SECONDS = 10;
    static final double DEFAULT_BOM_RQ_PERCENTAGE = 0.5;
    static final int DEFAULT_BOM_REQUEST_AGE_HOURS = 24;
    static final int DEFAULT_BOM_DURATION_SECONDS = 60;
    static final int MIN_CONTENT_ZONE_INTERVAL = 16;
    static final int NO_LIMIT = Integer.MAX_VALUE;

    //the settings merged from every accepted configuration, the stored form; guarded by this
    JSONObject latestRetrievedConfigurationFull = null;
    JSONObject latestRetrievedConfiguration = null;

    //guarded by this
    CountlyTimer serverConfigUpdateTimer = null;

    private boolean serverConfigRequestsDisabled = false;
    private volatile boolean running = false;
    private volatile ConnectionTestListener connectionTestListener = null;

    //resolved values; the constant seeds are here, the ones read from the developer configuration are in seedFromDeveloperConfig
    volatile boolean currentVTracking = true;
    volatile boolean currentVNetworking = true;
    volatile boolean currentVSessionTracking = true;
    volatile boolean currentVViewTracking = true;
    volatile boolean currentVCustomEventTracking = true;
    volatile boolean currentVContentZone = false;
    volatile boolean currentVCrashReporting = true;
    volatile boolean currentVAutomaticSessionTracking = false;
    volatile boolean currentVAutomaticViewTracking = false;
    volatile boolean currentVAutomaticCrashReporting = true;
    volatile boolean currentVLocationTracking = true;
    volatile boolean currentVRefreshContentZone = true;
    volatile boolean currentVBackoffMechanism = true;
    volatile boolean currentVLoggingEnabled = false;
    volatile boolean currentVRequiresConsent = false;

    volatile int currentVServerConfigUpdateInterval = DEFAULT_SERVER_CONFIG_UPDATE_INTERVAL_HOURS;
    volatile int currentVRequestQueueMaxSize = 1000;
    volatile boolean currentVRequestQueueMaxSizeFromBehaviorSettings = false;
    volatile int currentVEventQueueSizeThreshold = 10;
    volatile int currentVSessionUpdateInterval = 60;
    volatile int currentVMaxKeyLength = NO_LIMIT;
    volatile int currentVMaxValueSize = NO_LIMIT;
    volatile int currentVMaxSegmentationValues = NO_LIMIT;
    volatile int currentVMaxBreadcrumbCount = 100;
    volatile int currentVMaxStackTraceLinesPerThread = NO_LIMIT;
    volatile int currentVMaxStackTraceLineLength = NO_LIMIT;
    volatile int currentVUserPropertyCacheLimit = NO_LIMIT;
    volatile int currentVBOMAcceptedTimeoutSeconds = DEFAULT_BOM_ACCEPTED_TIMEOUT_SECONDS;
    volatile double currentVBOMRQPercentage = DEFAULT_BOM_RQ_PERCENTAGE;
    volatile int currentVBOMRequestAge = DEFAULT_BOM_REQUEST_AGE_HOURS;
    volatile int currentVBOMDuration = DEFAULT_BOM_DURATION_SECONDS;
    volatile int currentVDropAgeHours = 0;
    volatile int currentVZoneTimerInterval = ConfigContent.DEFAULT_ZONE_TIMER_INTERVAL;

    volatile FilterList<Set<String>> currentVEventFilterList = FilterList.NO_NAMES;
    volatile FilterList<Set<String>> currentVUserPropertyFilterList = FilterList.NO_NAMES;
    volatile FilterList<Set<String>> currentVSegmentationFilterList = FilterList.NO_NAMES;
    volatile FilterList<Map<String, Set<String>>> currentVEventSegmentationFilterList = FilterList.NO_NAMES_PER_EVENT;
    volatile Set<String> currentVJourneyTriggerEvents = Collections.emptySet();
    volatile Set<String> currentVJourneyTriggerViews = Collections.emptySet();
    volatile LogGatheringDirective currentVLogGathering = LogGatheringDirective.UNDECIDED;

    /**
     * Runs the connection test that a live settings response asks for with its {@code ct} flag.
     */
    interface ConnectionTestListener {
        /**
         * Called on the thread that delivered the response, after the settings in it were applied.
         *
         * @param fetchLatencyMs how long the settings request took, from sending it to its response, in milliseconds
         */
        void onConnectionTestRequested(long fetchLatencyMs);
    }

    /**
     * One decision of the log gathering directive, published as a whole so a reader never sees the
     * state of one directive next to the gather id of another.
     */
    static final class LogGatheringDirective {
        static final LogGatheringDirective UNDECIDED = new LogGatheringDirective(LogGatheringState.UNDECIDED, null, logGatheringAllLevels, logGatheringDefaultBatchSize);
        static final LogGatheringDirective NOT_GATHERING = new LogGatheringDirective(LogGatheringState.NOT_GATHERING, null, logGatheringAllLevels, logGatheringDefaultBatchSize);

        final LogGatheringState state;
        final String gatherId;
        final String levels;
        final int batchSize;

        /**
         * Holds one decision.
         *
         * @param state what was decided
         * @param gatherId the gather id, {@code null} unless gathering
         * @param levels the level characters to gather
         * @param batchSize how many lines make a batch
         */
        LogGatheringDirective(@Nonnull LogGatheringState state, @Nullable String gatherId, @Nonnull String levels, int batchSize) {
            this.state = state;
            this.gatherId = gatherId;
            this.levels = levels;
            this.batchSize = batchSize;
        }
    }

    /**
     * Builds a provider that serves the developer configuration alone, for code that reads the
     * settings while no configuration module is registered on that configuration.
     *
     * @param config the developer configuration to serve
     * @return a provider that never changes
     */
    @Nonnull
    static ConfigurationProvider developerDefaults(@Nonnull InternalConfig config) {
        ModuleConfiguration defaults = new ModuleConfiguration();
        defaults.seedFromDeveloperConfig(config);
        return defaults;
    }

    /**
     * Seeds every setting from the developer configuration and resolves the provided and the stored
     * settings over it, then registers this module as the provider of the configuration. In backend
     * mode nothing is loaded and the developer configuration stays in effect.
     *
     * @param config configuration of the SDK being initialized
     */
    @Override
    public void init(InternalConfig config) {
        super.init(config);
        boolean backendMode = config.isBackendModeEnabled();
        serverConfigRequestsDisabled = backendMode || config.isSdkBehaviorSettingsRequestsDisabled();
        L.d("[ModuleConfiguration] init, backend mode:[" + backendMode + "], server config requests disabled:[" + serverConfigRequestsDisabled + "]");

        seedFromDeveloperConfig(config);

        if (backendMode) {
            L.d("[ModuleConfiguration] init, backend mode is enabled, SDK behavior settings are not applied");
        } else {
            loadConfigFromStorage(config.getSdkBehaviorSettings());
        }

        updateConfigVariables(config, null);
        running = true;
        config.configProvider = this;
    }

    /**
     * Fetches the settings from the server and starts the refresh timer, unless settings requests
     * are disabled or backend mode is on.
     *
     * @param config configuration of the running SDK
     */
    @Override
    public void initFinished(@Nonnull InternalConfig config) {
        L.d("[ModuleConfiguration] initFinished");
        if (serverConfigRequestsDisabled) {
            return;
        }

        fetchConfigFromServer(config);
        startServerConfigUpdateTimer();
    }

    /**
     * Stops the refresh timer; a response that arrives afterwards is ignored.
     *
     * @param config configuration of the SDK being stopped
     * @param clear whether the SDK clears its data, which removes the stored settings with it
     */
    @Override
    public void stop(InternalConfig config, boolean clear) {
        super.stop(config, clear);
        running = false;
        stopServerConfigUpdateTimer();
    }

    /**
     * Sets the resolved values that come from the developer configuration.
     *
     * @param config the developer configuration
     */
    private void seedFromDeveloperConfig(@Nonnull InternalConfig config) {
        currentVLoggingEnabled = config.getLoggingLevel() != null && config.getLoggingLevel() != Config.LoggingLevel.OFF;
        currentVRequiresConsent = config.requiresConsent();
        currentVAutomaticCrashReporting = config.isUnhandledCrashReportingEnabled();
        currentVBackoffMechanism = config.isBackoffMechanismEnabled();
        currentVRequestQueueMaxSize = config.getRequestQueueMaxSize();
        currentVEventQueueSizeThreshold = config.getEventsBufferSize();
        currentVSessionUpdateInterval = config.getSendUpdateEachSeconds();
        currentVMaxBreadcrumbCount = config.getMaxBreadcrumbCount();
        currentVZoneTimerInterval = config.content.zoneTimerInterval;
    }

    /**
     * Loads the stored settings, or the provided ones when nothing is stored yet, which stores them.
     *
     * @param sdkBehaviorSettings the settings the developer provided, {@code null} for none
     */
    void loadConfigFromStorage(@Nullable String sdkBehaviorSettings) {
        String sConfig = internalConfig.storageProvider.getServerConfig();

        if (Utils.isEmptyOrNull(sConfig) && !Utils.isEmptyOrNull(sdkBehaviorSettings)) {
            L.d("[ModuleConfiguration] loadConfigFromStorage, nothing is stored, using the provided settings");
            sConfig = sdkBehaviorSettings;
        }

        L.v("[ModuleConfiguration] loadConfigFromStorage, [" + sConfig + "]");

        if (Utils.isEmptyOrNull(sConfig)) {
            L.d("[ModuleConfiguration] loadConfigFromStorage, no configuration is stored or provided");
            return;
        }

        try {
            saveAndStoreDownloadedConfig(new JSONObject(sConfig));
        } catch (JSONException e) {
            L.w("[ModuleConfiguration] loadConfigFromStorage, failed to parse, " + e);
        }
    }

    /**
     * Requires {@code v}, {@code t} and an object {@code c}, then removes the settings this SDK does
     * not support. Other top level keys are allowed; they are never stored.
     *
     * @param config a configuration as the server sends it, its settings object is changed in place
     * @return whether the configuration can be merged
     */
    boolean validateServerConfig(@Nonnull JSONObject config) {
        L.v("[ModuleConfiguration] validateServerConfig");
        if (!config.has(keyRVersion)) {
            L.w("[ModuleConfiguration] validateServerConfig, retrieved configuration has no 'v' field, it is ignored");
            return false;
        }
        if (!config.has(keyRTimestamp)) {
            L.w("[ModuleConfiguration] validateServerConfig, retrieved configuration has no 't' field, it is ignored");
            return false;
        }
        if (!config.has(keyRConfig)) {
            L.w("[ModuleConfiguration] validateServerConfig, retrieved configuration has no 'c' field, it is ignored");
            return false;
        }

        JSONObject newInner = config.optJSONObject(keyRConfig);
        if (newInner == null) {
            L.w("[ModuleConfiguration] validateServerConfig, retrieved configuration has a 'c' that is not an object, it is ignored");
            return false;
        }

        removeUnsupportedKeys(newInner);
        return true;
    }

    /**
     * Removes every setting whose key is unknown or whose value has the wrong type or range.
     *
     * @param newInner the settings object, changed in place
     */
    private void removeUnsupportedKeys(@Nonnull JSONObject newInner) {
        Iterator<String> keys = newInner.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = newInner.opt(key);
            boolean isValid;

            switch (key) {
                case keyRNetworking:
                case keyRTracking:
                case keyRSessionTracking:
                case keyRCrashReporting:
                case keyRAutomaticSessionTracking:
                case keyRAutomaticViewTracking:
                case keyRAutomaticCrashReporting:
                case keyRViewTracking:
                case keyRCustomEventTracking:
                case keyRLocationTracking:
                case keyREnterContentZone:
                case keyRRefreshContentZone:
                case keyRBackoffMechanism:
                case keyRLogging:
                case keyRConsentRequired:
                    isValid = value instanceof Boolean;
                    break;
                case keyRServerConfigUpdateInterval:
                case keyRBOMAcceptedTimeout:
                case keyRBOMRequestAge:
                case keyRBOMDuration:
                case keyRReqQueueSize:
                case keyREventQueueSize:
                case keyRSessionUpdateInterval:
                case keyRLimitKeyLength:
                case keyRLimitValueSize:
                case keyRLimitSegValues:
                case keyRLimitBreadcrumb:
                case keyRLimitTraceLine:
                case keyRLimitTraceLength:
                case keyRUserPropertyCacheLimit:
                    isValid = isIntAtLeast(value, 1);
                    break;
                case keyRDropOldRequestTime:
                    isValid = isIntAtLeast(value, 0);
                    break;
                case keyRContentZoneInterval:
                    isValid = isIntAtLeast(value, MIN_CONTENT_ZONE_INTERVAL);
                    break;
                case keyRBOMRQPercentage:
                    isValid = isFraction(value);
                    break;
                case keyREventBlacklist:
                case keyRSegmentationBlacklist:
                case keyRUserPropertyBlacklist:
                case keyREventWhitelist:
                case keyRSegmentationWhitelist:
                case keyRUserPropertyWhitelist:
                case keyRJourneyTriggerEvents:
                case keyRJourneyTriggerViews:
                    isValid = value instanceof JSONArray;
                    break;
                case keyREventSegmentationBlacklist:
                case keyREventSegmentationWhitelist:
                    isValid = value instanceof JSONObject;
                    break;
                default:
                    L.w("[ModuleConfiguration] removeUnsupportedKeys, unknown key [" + key + "], removing it, value: [" + value + "]");
                    keys.remove();
                    continue;
            }

            if (!isValid) {
                L.w("[ModuleConfiguration] removeUnsupportedKeys, invalid value for key [" + key + "], removing it, value: [" + value + "]");
                keys.remove();
            }
        }
    }

    /**
     * Whether a setting value is an integer of at least the given value.
     *
     * @param value the value as parsed from JSON
     * @param minimum the smallest valid value
     * @return {@code true} for a valid value
     */
    private static boolean isIntAtLeast(@Nullable Object value, int minimum) {
        return value instanceof Integer && (Integer) value >= minimum;
    }

    /**
     * Whether a setting value is a number strictly between 0 and 1. Decimals are parsed as
     * {@link java.math.BigDecimal}, so any {@link Number} is accepted.
     *
     * @param value the value as parsed from JSON
     * @return {@code true} for a valid value
     */
    private static boolean isFraction(@Nullable Object value) {
        if (!(value instanceof Number)) {
            return false;
        }
        double fraction = ((Number) value).doubleValue();
        return fraction > 0.0 && fraction < 1.0;
    }

    /**
     * Validates a configuration, merges its settings into the stored ones and stores the result.
     * Only {@code v}, {@code t} and {@code c} are stored, so the log gathering directive and the
     * connection test flag never are. An invalid configuration leaves the stored settings as they are.
     *
     * @param config a configuration as the server sends it
     */
    synchronized void saveAndStoreDownloadedConfig(@Nonnull JSONObject config) {
        L.v("[ModuleConfiguration] saveAndStoreDownloadedConfig");
        if (!validateServerConfig(config)) {
            L.w("[ModuleConfiguration] saveAndStoreDownloadedConfig, retrieved configuration is not valid, ignoring it");
            return;
        }

        JSONObject newInner = config.optJSONObject(keyRConfig);
        if (latestRetrievedConfigurationFull == null) {
            latestRetrievedConfiguration = new JSONObject();
            latestRetrievedConfigurationFull = new JSONObject();
            latestRetrievedConfigurationFull.put(keyRConfig, latestRetrievedConfiguration);
        }

        latestRetrievedConfigurationFull.put(keyRVersion, config.get(keyRVersion));
        latestRetrievedConfigurationFull.put(keyRTimestamp, config.get(keyRTimestamp));

        List<String> notStoredTopLevelKeys = new ArrayList<>();
        for (String key : config.keySet()) {
            if (!keyRVersion.equals(key) && !keyRTimestamp.equals(key) && !keyRConfig.equals(key)) {
                notStoredTopLevelKeys.add(key);
            }
        }
        if (!notStoredTopLevelKeys.isEmpty()) {
            L.d("[ModuleConfiguration] saveAndStoreDownloadedConfig, top level keys that are not stored: " + notStoredTopLevelKeys);
        }

        removeListingFilterKeysFromConfig(newInner);

        for (String key : newInner.keySet()) {
            Object value = newInner.opt(key);
            if (value != null && !JSONObject.NULL.equals(value)) {
                latestRetrievedConfiguration.put(key, value);
            }
        }

        internalConfig.storageProvider.setServerConfig(latestRetrievedConfigurationFull.toString());
    }

    /**
     * Drops the stored listing filters of the opposite kind: a configuration with any whitelist
     * removes every stored blacklist and the reverse. One with neither keeps the stored filters.
     *
     * @param newConfig the settings object being merged
     */
    private void removeListingFilterKeysFromConfig(@Nonnull JSONObject newConfig) {
        boolean hasAnyWhitelist = newConfig.has(keyREventWhitelist)
            || newConfig.has(keyRUserPropertyWhitelist)
            || newConfig.has(keyRSegmentationWhitelist)
            || newConfig.has(keyREventSegmentationWhitelist);

        boolean hasAnyBlacklist = newConfig.has(keyREventBlacklist)
            || newConfig.has(keyRUserPropertyBlacklist)
            || newConfig.has(keyRSegmentationBlacklist)
            || newConfig.has(keyREventSegmentationBlacklist);

        if (hasAnyWhitelist) {
            latestRetrievedConfiguration.remove(keyREventBlacklist);
            latestRetrievedConfiguration.remove(keyRUserPropertyBlacklist);
            latestRetrievedConfiguration.remove(keyRSegmentationBlacklist);
            latestRetrievedConfiguration.remove(keyREventSegmentationBlacklist);
        }

        if (hasAnyBlacklist) {
            latestRetrievedConfiguration.remove(keyREventWhitelist);
            latestRetrievedConfiguration.remove(keyRUserPropertyWhitelist);
            latestRetrievedConfiguration.remove(keyRSegmentationWhitelist);
            latestRetrievedConfiguration.remove(keyREventSegmentationWhitelist);
        }
    }

    /**
     * Applies the merged settings to the resolved values, decides the log gathering directive of a
     * live response and restarts the refresh timer when its interval changed.
     *
     * @param config configuration of the running SDK
     * @param serverResponse the live response being applied, {@code null} at init, where only stored or provided settings exist
     * @return whether a resolved value changed
     */
    synchronized boolean updateConfigVariables(@Nonnull InternalConfig config, @Nullable JSONObject serverResponse) {
        L.v("[ModuleConfiguration] updateConfigVariables, from server response:[" + (serverResponse != null) + "]");

        //read off the live response itself, so it applies even when its settings object was rejected
        readLogGatheringDirective(serverResponse);

        if (latestRetrievedConfiguration == null) {
            return false;
        }

        StringBuilder sb = new StringBuilder();
        boolean previousRequiresConsent = currentVRequiresConsent;

        currentVNetworking = extractBoolean(keyRNetworking, sb, currentVNetworking);
        currentVTracking = extractBoolean(keyRTracking, sb, currentVTracking);
        currentVSessionTracking = extractBoolean(keyRSessionTracking, sb, currentVSessionTracking);
        currentVCrashReporting = extractBoolean(keyRCrashReporting, sb, currentVCrashReporting);
        currentVAutomaticSessionTracking = extractBoolean(keyRAutomaticSessionTracking, sb, currentVAutomaticSessionTracking);
        currentVAutomaticViewTracking = extractBoolean(keyRAutomaticViewTracking, sb, currentVAutomaticViewTracking);
        currentVAutomaticCrashReporting = extractBoolean(keyRAutomaticCrashReporting, sb, currentVAutomaticCrashReporting);
        currentVViewTracking = extractBoolean(keyRViewTracking, sb, currentVViewTracking);
        currentVCustomEventTracking = extractBoolean(keyRCustomEventTracking, sb, currentVCustomEventTracking);
        currentVLocationTracking = extractBoolean(keyRLocationTracking, sb, currentVLocationTracking);
        currentVContentZone = extractBoolean(keyREnterContentZone, sb, currentVContentZone);
        currentVRefreshContentZone = extractBoolean(keyRRefreshContentZone, sb, currentVRefreshContentZone);
        currentVBackoffMechanism = extractBoolean(keyRBackoffMechanism, sb, currentVBackoffMechanism);
        currentVLoggingEnabled = extractBoolean(keyRLogging, sb, currentVLoggingEnabled);
        currentVRequiresConsent = extractBoolean(keyRConsentRequired, sb, currentVRequiresConsent);

        currentVServerConfigUpdateInterval = extractInt(keyRServerConfigUpdateInterval, sb, currentVServerConfigUpdateInterval, 1);
        currentVRequestQueueMaxSize = extractInt(keyRReqQueueSize, sb, currentVRequestQueueMaxSize, 1);
        currentVEventQueueSizeThreshold = extractInt(keyREventQueueSize, sb, currentVEventQueueSizeThreshold, 1);
        currentVSessionUpdateInterval = extractInt(keyRSessionUpdateInterval, sb, currentVSessionUpdateInterval, 1);
        currentVMaxKeyLength = extractInt(keyRLimitKeyLength, sb, currentVMaxKeyLength, 1);
        currentVMaxValueSize = extractInt(keyRLimitValueSize, sb, currentVMaxValueSize, 1);
        currentVMaxSegmentationValues = extractInt(keyRLimitSegValues, sb, currentVMaxSegmentationValues, 1);
        currentVMaxBreadcrumbCount = extractInt(keyRLimitBreadcrumb, sb, currentVMaxBreadcrumbCount, 1);
        currentVMaxStackTraceLinesPerThread = extractInt(keyRLimitTraceLine, sb, currentVMaxStackTraceLinesPerThread, 1);
        currentVMaxStackTraceLineLength = extractInt(keyRLimitTraceLength, sb, currentVMaxStackTraceLineLength, 1);
        currentVUserPropertyCacheLimit = extractInt(keyRUserPropertyCacheLimit, sb, currentVUserPropertyCacheLimit, 1);
        currentVBOMAcceptedTimeoutSeconds = extractInt(keyRBOMAcceptedTimeout, sb, currentVBOMAcceptedTimeoutSeconds, 1);
        currentVBOMRQPercentage = extractFraction(keyRBOMRQPercentage, sb, currentVBOMRQPercentage);
        currentVBOMRequestAge = extractInt(keyRBOMRequestAge, sb, currentVBOMRequestAge, 1);
        currentVBOMDuration = extractInt(keyRBOMDuration, sb, currentVBOMDuration, 1);
        currentVDropAgeHours = extractInt(keyRDropOldRequestTime, sb, currentVDropAgeHours, 0);
        currentVZoneTimerInterval = extractInt(keyRContentZoneInterval, sb, currentVZoneTimerInterval, MIN_CONTENT_ZONE_INTERVAL);
        currentVRequestQueueMaxSizeFromBehaviorSettings = latestRetrievedConfiguration.has(keyRReqQueueSize);

        updateListingFilters();

        if (serverResponse != null && previousRequiresConsent != currentVRequiresConsent) {
            L.d("[ModuleConfiguration] updateConfigVariables, consent requirement changed to [" + currentVRequiresConsent + "], it applies on the next init, the running SDK keeps [" + config.requiresConsent() + "]");
        }

        if (serverConfigUpdateTimer != null && serverConfigUpdateTimer.getTimerDelaySeconds() != serverConfigUpdateIntervalSeconds()) {
            startServerConfigUpdateTimer();
        }

        String updatedValues = sb.toString();
        if (updatedValues.isEmpty()) {
            return false;
        }

        L.i("[ModuleConfiguration] updateConfigVariables, SDK configuration has changed, new values: [" + updatedValues + "]");
        return true;
    }

    /**
     * Reads a switch from the merged settings.
     *
     * @param key the settings key
     * @param changedValues where a change is described
     * @param currentValue the value in effect
     * @return the stored value, or the value in effect when none is stored
     */
    private boolean extractBoolean(@Nonnull String key, @Nonnull StringBuilder changedValues, boolean currentValue) {
        Object value = latestRetrievedConfiguration.opt(key);
        if (!(value instanceof Boolean)) {
            return currentValue;
        }

        boolean extracted = (Boolean) value;
        if (extracted != currentValue) {
            changedValues.append(key).append(":[").append(extracted).append("], ");
        }
        return extracted;
    }

    /**
     * Reads an integer from the merged settings.
     *
     * @param key the settings key
     * @param changedValues where a change is described
     * @param currentValue the value in effect
     * @param minimum the smallest valid value
     * @return the stored value, or the value in effect when none, or none valid, is stored
     */
    private int extractInt(@Nonnull String key, @Nonnull StringBuilder changedValues, int currentValue, int minimum) {
        Object value = latestRetrievedConfiguration.opt(key);
        if (!(value instanceof Integer)) {
            return currentValue;
        }

        int extracted = (Integer) value;
        if (extracted < minimum) {
            L.w("[ModuleConfiguration] updateConfigVariables, value for '" + key + "' is not valid, value: [" + extracted + "]");
            return currentValue;
        }

        if (extracted != currentValue) {
            changedValues.append(key).append(":[").append(extracted).append("], ");
        }
        return extracted;
    }

    /**
     * Reads a fraction strictly between 0 and 1 from the merged settings.
     *
     * @param key the settings key
     * @param changedValues where a change is described
     * @param currentValue the value in effect
     * @return the stored value, or the value in effect when none, or none valid, is stored
     */
    private double extractFraction(@Nonnull String key, @Nonnull StringBuilder changedValues, double currentValue) {
        Object value = latestRetrievedConfiguration.opt(key);
        if (!(value instanceof Number)) {
            return currentValue;
        }

        double extracted = ((Number) value).doubleValue();
        if (!isFraction(value)) {
            L.w("[ModuleConfiguration] updateConfigVariables, value for '" + key + "' is not valid, value: [" + extracted + "]");
            return currentValue;
        }

        if (Double.compare(extracted, currentValue) != 0) {
            changedValues.append(key).append(":[").append(extracted).append("], ");
        }
        return extracted;
    }

    /**
     * Rebuilds the listing filters and the journey trigger sets from the merged settings and
     * publishes each as a new immutable snapshot. A filter the merged settings do not hold is an
     * empty blacklist, which allows everything.
     */
    private void updateListingFilters() {
        currentVEventFilterList = namesFilterOf(keyREventBlacklist, keyREventWhitelist);
        currentVUserPropertyFilterList = namesFilterOf(keyRUserPropertyBlacklist, keyRUserPropertyWhitelist);
        currentVSegmentationFilterList = namesFilterOf(keyRSegmentationBlacklist, keyRSegmentationWhitelist);
        currentVEventSegmentationFilterList = namesPerEventFilterOf(keyREventSegmentationBlacklist, keyREventSegmentationWhitelist);
        currentVJourneyTriggerEvents = Collections.unmodifiableSet(namesOf(latestRetrievedConfiguration.optJSONArray(keyRJourneyTriggerEvents)));
        currentVJourneyTriggerViews = Collections.unmodifiableSet(namesOf(latestRetrievedConfiguration.optJSONArray(keyRJourneyTriggerViews)));

        L.d("[ModuleConfiguration] updateListingFilters, event filter:[" + currentVEventFilterList
            + "], user property filter:[" + currentVUserPropertyFilterList
            + "], segmentation filter:[" + currentVSegmentationFilterList
            + "], event segmentation filter:[" + currentVEventSegmentationFilterList
            + "], journey trigger events:" + currentVJourneyTriggerEvents
            + ", journey trigger views:" + currentVJourneyTriggerViews);
    }

    /**
     * Builds a names filter from the merged settings, the blacklist winning when both are stored.
     *
     * @param blacklistKey the settings key of the blacklist
     * @param whitelistKey the settings key of the whitelist
     * @return the filter
     */
    @Nonnull
    private FilterList<Set<String>> namesFilterOf(@Nonnull String blacklistKey, @Nonnull String whitelistKey) {
        JSONArray blacklist = latestRetrievedConfiguration.optJSONArray(blacklistKey);
        if (blacklist != null) {
            return FilterList.ofNames(namesOf(blacklist), false);
        }

        JSONArray whitelist = latestRetrievedConfiguration.optJSONArray(whitelistKey);
        if (whitelist != null) {
            return FilterList.ofNames(namesOf(whitelist), true);
        }

        return FilterList.NO_NAMES;
    }

    /**
     * Builds a names per event filter from the merged settings, the blacklist winning when both are stored.
     *
     * @param blacklistKey the settings key of the blacklist
     * @param whitelistKey the settings key of the whitelist
     * @return the filter
     */
    @Nonnull
    private FilterList<Map<String, Set<String>>> namesPerEventFilterOf(@Nonnull String blacklistKey, @Nonnull String whitelistKey) {
        JSONObject blacklist = latestRetrievedConfiguration.optJSONObject(blacklistKey);
        if (blacklist != null) {
            return FilterList.ofNamesPerEvent(namesPerEventOf(blacklist), false);
        }

        JSONObject whitelist = latestRetrievedConfiguration.optJSONObject(whitelistKey);
        if (whitelist != null) {
            return FilterList.ofNamesPerEvent(namesPerEventOf(whitelist), true);
        }

        return FilterList.NO_NAMES_PER_EVENT;
    }

    /**
     * Reads the names of a JSON array, skipping nulls and turning other values into strings.
     *
     * @param jsonArray the array, {@code null} for none
     * @return the names in array order
     */
    @Nonnull
    private static Set<String> namesOf(@Nullable JSONArray jsonArray) {
        Set<String> names = new LinkedHashSet<>();
        if (jsonArray == null) {
            return names;
        }

        for (int i = 0; i < jsonArray.length(); i++) {
            String item = jsonArray.optString(i, null);
            if (item != null) {
                names.add(item);
            }
        }
        return names;
    }

    /**
     * Reads the names of each event key of a JSON object, skipping keys whose value is not an array.
     *
     * @param jsonObject the object of arrays
     * @return the names of each event key
     */
    @Nonnull
    private static Map<String, Set<String>> namesPerEventOf(@Nonnull JSONObject jsonObject) {
        Map<String, Set<String>> namesPerEvent = new LinkedHashMap<>();
        for (String key : jsonObject.keySet()) {
            JSONArray jsonArray = jsonObject.optJSONArray(key);
            if (jsonArray != null) {
                namesPerEvent.put(key, namesOf(jsonArray));
            }
        }
        return namesPerEvent;
    }

    /**
     * Decides the log gathering state from the top level {@code lg} directive, shaped
     * {@code {"e":false}} or {@code {"e":true,"i":id,"l":"ewidv","b":100}}. Only a live response
     * decides: without one the state stays as it is, while disabled settings requests, a response
     * without a usable directive and a directive with {@code e} not {@code true} decide off.
     *
     * @param serverResponse the live response, {@code null} at init
     */
    private synchronized void readLogGatheringDirective(@Nullable JSONObject serverResponse) {
        if (serverConfigRequestsDisabled) {
            //without settings requests no directive can ever arrive, so nothing can ever be gathered
            L.d("[ModuleConfiguration] readLogGatheringDirective, SDK behavior settings requests are disabled, log gathering can never be armed");
            setLogGatheringOff();
            return;
        }

        if (serverResponse == null) {
            L.d("[ModuleConfiguration] readLogGatheringDirective, not a server response, log gathering stays undecided");
            return;
        }

        JSONObject directive = serverResponse.optJSONObject(keyRLogGathering);
        if (directive == null) {
            L.d("[ModuleConfiguration] readLogGatheringDirective, server response had no usable '" + keyRLogGathering + "', log gathering is off");
            setLogGatheringOff();
            return;
        }

        if (!Boolean.TRUE.equals(directive.opt(keyLGEnabled))) {
            L.d("[ModuleConfiguration] readLogGatheringDirective, directive says log gathering is off");
            setLogGatheringOff();
            return;
        }

        Object gatherIdRaw = directive.opt(keyLGId);
        String gatherId = gatherIdRaw instanceof String ? ((String) gatherIdRaw).trim() : "";
        if (gatherId.isEmpty()) {
            //without the id the server rejects every uploaded batch
            L.d("[ModuleConfiguration] readLogGatheringDirective, directive enables log gathering but carries no usable '" + keyLGId + "', log gathering is off");
            setLogGatheringOff();
            return;
        }

        LogGatheringDirective gathering = new LogGatheringDirective(LogGatheringState.GATHERING, gatherId,
            sanitizeLogGatheringLevels(directive.opt(keyLGLevels)), sanitizeLogGatheringBatchSize(directive.opt(keyLGBatchSize)));
        currentVLogGathering = gathering;
        L.d("[ModuleConfiguration] readLogGatheringDirective, log gathering is on, id:[" + gathering.gatherId + "], levels:[" + gathering.levels + "], batch size:[" + gathering.batchSize + "]");
    }

    /**
     * Decides against log gathering when nothing decided yet, for a run where no response can arrive.
     *
     * @param reason why no response can arrive
     */
    private synchronized void decideLogGatheringOffIfUndecided(@Nonnull String reason) {
        if (currentVLogGathering.state == LogGatheringState.UNDECIDED) {
            L.d("[ModuleConfiguration] decideLogGatheringOffIfUndecided, " + reason + ", log gathering is off");
            setLogGatheringOff();
        }
    }

    /**
     * Publishes the decision against log gathering, which also drops any previous gather id.
     */
    private void setLogGatheringOff() {
        currentVLogGathering = LogGatheringDirective.NOT_GATHERING;
    }

    /**
     * Keeps only the level characters this SDK knows, lower cased, in the order the server sent them
     * and without duplicates. Falls back to every level when nothing usable is left.
     *
     * @param levelsRaw the {@code l} value of the directive
     * @return the level characters to gather
     */
    @Nonnull
    private String sanitizeLogGatheringLevels(@Nullable Object levelsRaw) {
        if (!(levelsRaw instanceof String)) {
            return logGatheringAllLevels;
        }

        String levels = (String) levelsRaw;
        StringBuilder filtered = new StringBuilder();
        for (int i = 0; i < levels.length(); i++) {
            char level = Character.toLowerCase(levels.charAt(i));
            if (logGatheringAllLevels.indexOf(level) > -1 && filtered.indexOf(String.valueOf(level)) < 0) {
                filtered.append(level);
            }
        }

        if (filtered.length() == 0) {
            L.d("[ModuleConfiguration] sanitizeLogGatheringLevels, no usable level in [" + levels + "], falling back to [" + logGatheringAllLevels + "]");
            return logGatheringAllLevels;
        }

        return filtered.toString();
    }

    /**
     * Clamps the batch size into [{@value #logGatheringMinBatchSize}, {@value #logGatheringMaxBufferedLines}],
     * falling back to {@value #logGatheringDefaultBatchSize} when it is missing or not a number.
     *
     * @param batchSizeRaw the {@code b} value of the directive
     * @return the batch size
     */
    private static int sanitizeLogGatheringBatchSize(@Nullable Object batchSizeRaw) {
        if (!(batchSizeRaw instanceof Number)) {
            return logGatheringDefaultBatchSize;
        }

        int batchSize = ((Number) batchSizeRaw).intValue();
        return Math.min(logGatheringMaxBufferedLines, Math.max(logGatheringMinBatchSize, batchSize));
    }

    /**
     * Requests the settings from the server and applies the response. Skipped when settings
     * requests are disabled, in backend mode and while there is no device ID. The request is sent
     * even while the settings forbid networking, as the next response is the only way to allow it again.
     *
     * @param config configuration of the running SDK
     */
    void fetchConfigFromServer(@Nonnull InternalConfig config) {
        L.v("[ModuleConfiguration] fetchConfigFromServer");
        if (!running || serverConfigRequestsDisabled) {
            L.v("[ModuleConfiguration] fetchConfigFromServer, fetching the configuration is aborted, server config requests are disabled or the SDK is stopped");
            return;
        }

        if (config.getDeviceId() == null) {
            L.d("[ModuleConfiguration] fetchConfigFromServer, fetching the configuration is aborted, there is no device ID yet");
            decideLogGatheringOffIfUndecided("no device ID, no server response this run");
            return;
        }

        if (config.sdk == null || config.sdk.networking == null) {
            L.w("[ModuleConfiguration] fetchConfigFromServer, fetching the configuration is aborted, networking is not available");
            decideLogGatheringOffIfUndecided("networking is not available, no server response this run");
            return;
        }

        try {
            String requestData = ModuleRequests.prepareRequiredParams(config).add("method", "sc").toString();
            Transport transport = config.sdk.networking.getTransport();
            final long fetchStartNs = System.nanoTime();

            config.immediateRequestGenerator.createImmediateRequestMaker().doWork(requestData, "/o/sdk?", transport, false, true,
                response -> onServerConfigResponse(config, response, fetchStartNs), L);
        } catch (Exception e) {
            L.e("[ModuleConfiguration] fetchConfigFromServer, failed to request the configuration, [" + e + "]");
            decideLogGatheringOffIfUndecided("the server config request failed");
        }
    }

    /**
     * Handles the response of a settings request: strips the connection test flag, merges, stores
     * and applies the settings, and tells the SDK when a resolved value changed.
     *
     * @param config configuration of the running SDK
     * @param response the response, {@code null} when the request failed
     * @param fetchStartNs {@link System#nanoTime()} just before the request was made
     */
    private void onServerConfigResponse(@Nonnull InternalConfig config, @Nullable JSONObject response, long fetchStartNs) {
        if (!running) {
            L.d("[ModuleConfiguration] onServerConfigResponse, the SDK was stopped before the response arrived, ignoring it");
            return;
        }

        if (response == null) {
            L.w("[ModuleConfiguration] onServerConfigResponse, not possible to retrieve configuration data, probably due to lack of connection to the server");
            decideLogGatheringOffIfUndecided("server config fetch failed");
            return;
        }

        L.d("[ModuleConfiguration] onServerConfigResponse, retrieved configuration response: [" + response + "]");
        long fetchLatencyMs = (System.nanoTime() - fetchStartNs) / 1_000_000L;
        boolean connectionTestRequested = extractConnectionTestFlag(response);

        boolean changed;
        synchronized (this) {
            //checked again under the lock stop() takes, so a response racing stop() never writes the store
            if (!running) {
                L.d("[ModuleConfiguration] onServerConfigResponse, the SDK was stopped while the response was read, ignoring it");
                return;
            }
            saveAndStoreDownloadedConfig(response);
            changed = updateConfigVariables(config, response);
        }

        if (changed && config.sdk != null) {
            config.sdk.onSdkConfigurationChanged(config);
        }

        if (connectionTestRequested) {
            notifyConnectionTestRequested(fetchLatencyMs);
        }
    }

    /**
     * Reads and removes the {@code ct} flag of a live response, so it is never stored and can never
     * come back from storage. Booleans and numbers count by value; a string counts unless it is
     * empty, {@code "0"} or {@code "false"}; any other value that is not null counts.
     *
     * @param serverConfigResponse the live response, changed in place
     * @return whether the response asks for a connection test
     */
    static boolean extractConnectionTestFlag(@Nullable JSONObject serverConfigResponse) {
        if (serverConfigResponse == null || !serverConfigResponse.has(keyRConnectionTest)) {
            return false;
        }

        Object value = serverConfigResponse.remove(keyRConnectionTest);
        if (value == null || JSONObject.NULL.equals(value)) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue() != 0;
        }
        if (value instanceof String) {
            String s = ((String) value).trim();
            return !s.isEmpty() && !s.equals("0") && !s.equalsIgnoreCase("false");
        }
        return true;
    }

    /**
     * Hands a requested connection test to the registered listener.
     *
     * @param fetchLatencyMs how long the settings request took, in milliseconds
     */
    private void notifyConnectionTestRequested(long fetchLatencyMs) {
        ConnectionTestListener listener = connectionTestListener;
        if (listener == null) {
            L.d("[ModuleConfiguration] notifyConnectionTestRequested, the server asked for a connection test, nothing is registered to run it");
            return;
        }

        try {
            listener.onConnectionTestRequested(fetchLatencyMs);
        } catch (Exception e) {
            L.e("[ModuleConfiguration] notifyConnectionTestRequested, the connection test listener failed, [" + e + "]");
        }
    }

    /**
     * Registers what runs the connection test a live settings response can ask for.
     *
     * @param listener called with the latency of the settings request, {@code null} to unregister
     */
    void setConnectionTestListener(@Nullable ConnectionTestListener listener) {
        connectionTestListener = listener;
    }

    /**
     * The refresh interval in the unit of the timer.
     *
     * @return seconds between two settings fetches
     */
    private long serverConfigUpdateIntervalSeconds() {
        return (long) currentVServerConfigUpdateInterval * 60L * 60L;
    }

    /**
     * Starts the timer that fetches the settings every {@code scui} hours, replacing a running one.
     * Never waits for the replaced timer, as this also runs from that timer's own task.
     */
    private synchronized void startServerConfigUpdateTimer() {
        if (serverConfigUpdateTimer != null) {
            serverConfigUpdateTimer.stopTimer(false);
        }

        L.d("[ModuleConfiguration] startServerConfigUpdateTimer, fetching the configuration every [" + currentVServerConfigUpdateInterval + "] hours");
        final InternalConfig config = internalConfig;
        serverConfigUpdateTimer = new CountlyTimer(L);
        serverConfigUpdateTimer.startTimer(serverConfigUpdateIntervalSeconds(), () -> fetchConfigFromServer(config));
    }

    /**
     * Stops the refresh timer, if it runs.
     */
    private synchronized void stopServerConfigUpdateTimer() {
        if (serverConfigUpdateTimer != null) {
            serverConfigUpdateTimer.stopTimer(false);
            serverConfigUpdateTimer = null;
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean getNetworkingEnabled() {
        return currentVNetworking;
    }

    /** {@inheritDoc} */
    @Override
    public boolean getTrackingEnabled() {
        return currentVTracking;
    }

    /** {@inheritDoc} */
    @Override
    public boolean getSessionTrackingEnabled() {
        return currentVSessionTracking;
    }

    /** {@inheritDoc} */
    @Override
    public boolean getViewTrackingEnabled() {
        return currentVViewTracking;
    }

    /** {@inheritDoc} */
    @Override
    public boolean getCustomEventTrackingEnabled() {
        return currentVCustomEventTracking;
    }

    /** {@inheritDoc} */
    @Override
    public boolean getContentZoneEnabled() {
        return currentVContentZone;
    }

    /** {@inheritDoc} */
    @Override
    public boolean getCrashReportingEnabled() {
        return currentVCrashReporting;
    }

    /** {@inheritDoc} */
    @Override
    public boolean getAutomaticSessionTrackingEnabled() {
        return currentVAutomaticSessionTracking;
    }

    /** {@inheritDoc} */
    @Override
    public boolean getAutomaticViewTrackingEnabled() {
        return currentVAutomaticViewTracking;
    }

    /** {@inheritDoc} */
    @Override
    public boolean getAutomaticCrashReportingEnabled() {
        return currentVAutomaticCrashReporting;
    }

    /** {@inheritDoc} */
    @Override
    public boolean getLocationTrackingEnabled() {
        return currentVLocationTracking;
    }

    /** {@inheritDoc} */
    @Override
    public boolean getRefreshContentZoneEnabled() {
        return currentVRefreshContentZone;
    }

    /** {@inheritDoc} */
    @Override
    public boolean getBOMEnabled() {
        return currentVBackoffMechanism;
    }

    /** {@inheritDoc} */
    @Override
    public boolean getLoggingEnabled() {
        return currentVLoggingEnabled;
    }

    /** {@inheritDoc} */
    @Override
    public boolean getConsentRequired() {
        return currentVRequiresConsent;
    }

    /** {@inheritDoc} */
    @Override
    public int getServerConfigUpdateInterval() {
        return currentVServerConfigUpdateInterval;
    }

    /** {@inheritDoc} */
    @Override
    public int getRequestQueueMaxSize() {
        return currentVRequestQueueMaxSize;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isRequestQueueMaxSizeFromBehaviorSettings() {
        return currentVRequestQueueMaxSizeFromBehaviorSettings;
    }

    /** {@inheritDoc} */
    @Override
    public int getEventQueueSizeThreshold() {
        return currentVEventQueueSizeThreshold;
    }

    /** {@inheritDoc} */
    @Override
    public int getSessionUpdateInterval() {
        return currentVSessionUpdateInterval;
    }

    /** {@inheritDoc} */
    @Override
    public int getMaxKeyLength() {
        return currentVMaxKeyLength;
    }

    /** {@inheritDoc} */
    @Override
    public int getMaxValueSize() {
        return currentVMaxValueSize;
    }

    /** {@inheritDoc} */
    @Override
    public int getMaxSegmentationValues() {
        return currentVMaxSegmentationValues;
    }

    /** {@inheritDoc} */
    @Override
    public int getMaxBreadcrumbCount() {
        return currentVMaxBreadcrumbCount;
    }

    /** {@inheritDoc} */
    @Override
    public int getMaxStackTraceLinesPerThread() {
        return currentVMaxStackTraceLinesPerThread;
    }

    /** {@inheritDoc} */
    @Override
    public int getMaxStackTraceLineLength() {
        return currentVMaxStackTraceLineLength;
    }

    /** {@inheritDoc} */
    @Override
    public int getUserPropertyCacheLimit() {
        return currentVUserPropertyCacheLimit;
    }

    /** {@inheritDoc} */
    @Override
    public int getBOMAcceptedTimeoutSeconds() {
        return currentVBOMAcceptedTimeoutSeconds;
    }

    /** {@inheritDoc} */
    @Override
    public double getBOMRQPercentage() {
        return currentVBOMRQPercentage;
    }

    /** {@inheritDoc} */
    @Override
    public int getBOMRequestAge() {
        return currentVBOMRequestAge;
    }

    /** {@inheritDoc} */
    @Override
    public int getBOMDuration() {
        return currentVBOMDuration;
    }

    /** {@inheritDoc} */
    @Override
    public int getRequestDropAgeHours() {
        return currentVDropAgeHours;
    }

    /** {@inheritDoc} */
    @Override
    public int getContentZoneTimerInterval() {
        return currentVZoneTimerInterval;
    }

    /** {@inheritDoc} */
    @Nonnull
    @Override
    public FilterList<Set<String>> getEventFilterList() {
        return currentVEventFilterList;
    }

    /** {@inheritDoc} */
    @Nonnull
    @Override
    public FilterList<Set<String>> getUserPropertyFilterList() {
        return currentVUserPropertyFilterList;
    }

    /** {@inheritDoc} */
    @Nonnull
    @Override
    public FilterList<Set<String>> getSegmentationFilterList() {
        return currentVSegmentationFilterList;
    }

    /** {@inheritDoc} */
    @Nonnull
    @Override
    public FilterList<Map<String, Set<String>>> getEventSegmentationFilterList() {
        return currentVEventSegmentationFilterList;
    }

    /** {@inheritDoc} */
    @Nonnull
    @Override
    public Set<String> getJourneyTriggerEvents() {
        return currentVJourneyTriggerEvents;
    }

    /** {@inheritDoc} */
    @Nonnull
    @Override
    public Set<String> getJourneyTriggerViews() {
        return currentVJourneyTriggerViews;
    }

    /** {@inheritDoc} */
    @Nonnull
    @Override
    public LogGatheringState getLogGatheringState() {
        return currentVLogGathering.state;
    }

    /** {@inheritDoc} */
    @Nullable
    @Override
    public String getLogGatheringId() {
        return currentVLogGathering.gatherId;
    }

    /** {@inheritDoc} */
    @Nonnull
    @Override
    public String getLogGatheringLevels() {
        return currentVLogGathering.levels;
    }

    /** {@inheritDoc} */
    @Override
    public int getLogGatheringBatchSize() {
        return currentVLogGathering.batchSize;
    }
}
