package ly.count.sdk.java.internal;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;
import org.junit.Assert;

import static ly.count.sdk.java.internal.ModuleConfiguration.keyLGBatchSize;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyLGEnabled;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyLGId;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyLGLevels;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyRAutomaticCrashReporting;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyRAutomaticSessionTracking;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyRAutomaticViewTracking;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyRBOMAcceptedTimeout;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyRBOMDuration;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyRBOMRQPercentage;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyRBOMRequestAge;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyRBackoffMechanism;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyRConfig;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyRConnectionTest;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyRConsentRequired;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyRContentZoneInterval;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyRCrashReporting;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyRCustomEventTracking;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyRDropOldRequestTime;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyREnterContentZone;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyREventBlacklist;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyREventQueueSize;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyREventSegmentationBlacklist;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyREventSegmentationWhitelist;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyREventWhitelist;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyRJourneyTriggerEvents;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyRJourneyTriggerViews;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyRLimitBreadcrumb;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyRLimitKeyLength;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyRLimitSegValues;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyRLimitTraceLength;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyRLimitTraceLine;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyRLimitValueSize;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyRLocationTracking;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyRLogGathering;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyRLogging;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyRNetworking;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyRRefreshContentZone;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyRReqQueueSize;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyRSegmentationBlacklist;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyRSegmentationWhitelist;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyRServerConfigUpdateInterval;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyRSessionTracking;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyRSessionUpdateInterval;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyRTimestamp;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyRTracking;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyRUserPropertyBlacklist;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyRUserPropertyCacheLimit;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyRUserPropertyWhitelist;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyRVersion;
import static ly.count.sdk.java.internal.ModuleConfiguration.keyRViewTracking;

/**
 * Builds SDK behavior settings payloads, {@code {"v":..,"t":..,"c":{..}}} plus any sibling of
 * {@code c}, and checks a {@link ConfigurationProvider} against the settings it holds.
 */
class ServerConfigBuilder {
    final Map<String, Object> config = new LinkedHashMap<>();
    /**
     * Keys that sit next to {@code c}, not inside it: the {@code lg} directive, the {@code ct}
     * flag and anything a newer server may add.
     */
    final Map<String, Object> topLevelKeys = new LinkedHashMap<>();
    private Object timestamp = System.currentTimeMillis();
    private Object version = 1;

    /**
     * Sets the {@code t} value.
     *
     * @param timestamp the value
     * @return this builder
     */
    ServerConfigBuilder timestamp(Object timestamp) {
        this.timestamp = timestamp;
        return this;
    }

    /**
     * Sets the {@code v} value.
     *
     * @param version the value
     * @return this builder
     */
    ServerConfigBuilder version(Object version) {
        this.version = version;
        return this;
    }

    /**
     * Sets any setting to any value, including the ones the SDK has to reject.
     *
     * @param key the settings key
     * @param value the value
     * @return this builder
     */
    ServerConfigBuilder set(String key, Object value) {
        config.put(key, value);
        return this;
    }

    /**
     * @param enabled {@code tracking}
     * @return this builder
     */
    ServerConfigBuilder tracking(boolean enabled) {
        return set(keyRTracking, enabled);
    }

    /**
     * @param enabled {@code networking}
     * @return this builder
     */
    ServerConfigBuilder networking(boolean enabled) {
        return set(keyRNetworking, enabled);
    }

    /**
     * @param enabled {@code st}
     * @return this builder
     */
    ServerConfigBuilder sessionTracking(boolean enabled) {
        return set(keyRSessionTracking, enabled);
    }

    /**
     * @param enabled {@code vt}
     * @return this builder
     */
    ServerConfigBuilder viewTracking(boolean enabled) {
        return set(keyRViewTracking, enabled);
    }

    /**
     * @param enabled {@code cet}
     * @return this builder
     */
    ServerConfigBuilder customEventTracking(boolean enabled) {
        return set(keyRCustomEventTracking, enabled);
    }

    /**
     * @param enabled {@code ecz}
     * @return this builder
     */
    ServerConfigBuilder contentZone(boolean enabled) {
        return set(keyREnterContentZone, enabled);
    }

    /**
     * @param enabled {@code crt}
     * @return this builder
     */
    ServerConfigBuilder crashReporting(boolean enabled) {
        return set(keyRCrashReporting, enabled);
    }

    /**
     * @param enabled {@code ast}
     * @return this builder
     */
    ServerConfigBuilder automaticSessionTracking(boolean enabled) {
        return set(keyRAutomaticSessionTracking, enabled);
    }

    /**
     * @param enabled {@code avt}
     * @return this builder
     */
    ServerConfigBuilder automaticViewTracking(boolean enabled) {
        return set(keyRAutomaticViewTracking, enabled);
    }

    /**
     * @param enabled {@code acr}
     * @return this builder
     */
    ServerConfigBuilder automaticCrashReporting(boolean enabled) {
        return set(keyRAutomaticCrashReporting, enabled);
    }

    /**
     * @param enabled {@code lt}
     * @return this builder
     */
    ServerConfigBuilder locationTracking(boolean enabled) {
        return set(keyRLocationTracking, enabled);
    }

    /**
     * @param enabled {@code rcz}
     * @return this builder
     */
    ServerConfigBuilder refreshContentZone(boolean enabled) {
        return set(keyRRefreshContentZone, enabled);
    }

    /**
     * @param enabled {@code bom}
     * @return this builder
     */
    ServerConfigBuilder backoffMechanism(boolean enabled) {
        return set(keyRBackoffMechanism, enabled);
    }

    /**
     * @param enabled {@code log}
     * @return this builder
     */
    ServerConfigBuilder logging(boolean enabled) {
        return set(keyRLogging, enabled);
    }

    /**
     * @param required {@code cr}
     * @return this builder
     */
    ServerConfigBuilder consentRequired(boolean required) {
        return set(keyRConsentRequired, required);
    }

    /**
     * @param hours {@code scui}
     * @return this builder
     */
    ServerConfigBuilder serverConfigUpdateInterval(int hours) {
        return set(keyRServerConfigUpdateInterval, hours);
    }

    /**
     * @param size {@code rqs}
     * @return this builder
     */
    ServerConfigBuilder requestQueueSize(int size) {
        return set(keyRReqQueueSize, size);
    }

    /**
     * @param size {@code eqs}
     * @return this builder
     */
    ServerConfigBuilder eventQueueSize(int size) {
        return set(keyREventQueueSize, size);
    }

    /**
     * @param seconds {@code sui}
     * @return this builder
     */
    ServerConfigBuilder sessionUpdateInterval(int seconds) {
        return set(keyRSessionUpdateInterval, seconds);
    }

    /**
     * @param limit {@code lkl}
     * @return this builder
     */
    ServerConfigBuilder keyLengthLimit(int limit) {
        return set(keyRLimitKeyLength, limit);
    }

    /**
     * @param limit {@code lvs}
     * @return this builder
     */
    ServerConfigBuilder valueSizeLimit(int limit) {
        return set(keyRLimitValueSize, limit);
    }

    /**
     * @param limit {@code lsv}
     * @return this builder
     */
    ServerConfigBuilder segmentationValuesLimit(int limit) {
        return set(keyRLimitSegValues, limit);
    }

    /**
     * @param limit {@code lbc}
     * @return this builder
     */
    ServerConfigBuilder breadcrumbLimit(int limit) {
        return set(keyRLimitBreadcrumb, limit);
    }

    /**
     * @param limit {@code ltlpt}
     * @return this builder
     */
    ServerConfigBuilder traceLinesLimit(int limit) {
        return set(keyRLimitTraceLine, limit);
    }

    /**
     * @param limit {@code ltl}
     * @return this builder
     */
    ServerConfigBuilder traceLengthLimit(int limit) {
        return set(keyRLimitTraceLength, limit);
    }

    /**
     * @param limit {@code upcl}
     * @return this builder
     */
    ServerConfigBuilder userPropertyCacheLimit(int limit) {
        return set(keyRUserPropertyCacheLimit, limit);
    }

    /**
     * @param seconds {@code bom_at}
     * @return this builder
     */
    ServerConfigBuilder backoffAcceptedTimeout(int seconds) {
        return set(keyRBOMAcceptedTimeout, seconds);
    }

    /**
     * @param share {@code bom_rqp}
     * @return this builder
     */
    ServerConfigBuilder backoffRequestQueuePercentage(double share) {
        return set(keyRBOMRQPercentage, share);
    }

    /**
     * @param hours {@code bom_ra}
     * @return this builder
     */
    ServerConfigBuilder backoffRequestAge(int hours) {
        return set(keyRBOMRequestAge, hours);
    }

    /**
     * @param seconds {@code bom_d}
     * @return this builder
     */
    ServerConfigBuilder backoffDuration(int seconds) {
        return set(keyRBOMDuration, seconds);
    }

    /**
     * @param hours {@code dort}
     * @return this builder
     */
    ServerConfigBuilder dropOldRequestTime(int hours) {
        return set(keyRDropOldRequestTime, hours);
    }

    /**
     * @param seconds {@code czi}
     * @return this builder
     */
    ServerConfigBuilder contentZoneInterval(int seconds) {
        return set(keyRContentZoneInterval, seconds);
    }

    /**
     * Sets {@code eb} or {@code ew}, dropping the other one.
     *
     * @param names the event keys
     * @param isWhitelist {@code true} for {@code ew}
     * @return this builder
     */
    ServerConfigBuilder eventFilterList(Set<String> names, boolean isWhitelist) {
        return filter(keyREventBlacklist, keyREventWhitelist, names, isWhitelist);
    }

    /**
     * Sets {@code upb} or {@code upw}, dropping the other one.
     *
     * @param names the user property keys
     * @param isWhitelist {@code true} for {@code upw}
     * @return this builder
     */
    ServerConfigBuilder userPropertyFilterList(Set<String> names, boolean isWhitelist) {
        return filter(keyRUserPropertyBlacklist, keyRUserPropertyWhitelist, names, isWhitelist);
    }

    /**
     * Sets {@code sb} or {@code sw}, dropping the other one.
     *
     * @param names the segmentation keys
     * @param isWhitelist {@code true} for {@code sw}
     * @return this builder
     */
    ServerConfigBuilder segmentationFilterList(Set<String> names, boolean isWhitelist) {
        return filter(keyRSegmentationBlacklist, keyRSegmentationWhitelist, names, isWhitelist);
    }

    /**
     * Sets {@code esb} or {@code esw}, dropping the other one.
     *
     * @param namesPerEvent the segmentation keys of each event key
     * @param isWhitelist {@code true} for {@code esw}
     * @return this builder
     */
    ServerConfigBuilder eventSegmentationFilterMap(Map<String, Set<String>> namesPerEvent, boolean isWhitelist) {
        config.remove(isWhitelist ? keyREventSegmentationBlacklist : keyREventSegmentationWhitelist);
        return set(isWhitelist ? keyREventSegmentationWhitelist : keyREventSegmentationBlacklist, namesPerEvent);
    }

    /**
     * @param eventKeys {@code jte}
     * @return this builder
     */
    ServerConfigBuilder journeyTriggerEvents(Set<String> eventKeys) {
        return set(keyRJourneyTriggerEvents, eventKeys);
    }

    /**
     * @param viewNames {@code jtv}
     * @return this builder
     */
    ServerConfigBuilder journeyTriggerViews(Set<String> viewNames) {
        return set(keyRJourneyTriggerViews, viewNames);
    }

    /**
     * Sets one of a blacklist and whitelist pair, dropping the other one.
     *
     * @param blacklistKey the blacklist key
     * @param whitelistKey the whitelist key
     * @param names the names
     * @param isWhitelist whether to set the whitelist
     * @return this builder
     */
    private ServerConfigBuilder filter(String blacklistKey, String whitelistKey, Set<String> names, boolean isWhitelist) {
        config.remove(isWhitelist ? blacklistKey : whitelistKey);
        return set(isWhitelist ? whitelistKey : blacklistKey, names);
    }

    /**
     * Sets any key next to {@code c}.
     *
     * @param key the top level key
     * @param value the value
     * @return this builder
     */
    ServerConfigBuilder topLevelKey(String key, Object value) {
        topLevelKeys.put(key, value);
        return this;
    }

    /**
     * Sets the {@code ct} connection test flag.
     *
     * @param value the flag, of any type
     * @return this builder
     */
    ServerConfigBuilder connectionTest(Object value) {
        return topLevelKey(keyRConnectionTest, value);
    }

    /**
     * A log gathering directive that turns gathering off, {@code {"e":false}}.
     *
     * @return this builder
     */
    ServerConfigBuilder logGatheringOff() {
        return logGatheringDirective(false, null, null, null);
    }

    /**
     * A log gathering directive with every field filled in.
     *
     * @param gatherId {@code i}
     * @param levels {@code l}
     * @param batchSize {@code b}
     * @return this builder
     */
    ServerConfigBuilder logGatheringOn(String gatherId, String levels, int batchSize) {
        return logGatheringDirective(true, gatherId, levels, batchSize);
    }

    /**
     * A log gathering directive from raw values, so malformed ones can be built too. A {@code null}
     * value is left out rather than written as JSON null.
     *
     * @param enabled {@code e}
     * @param gatherId {@code i}
     * @param levels {@code l}
     * @param batchSize {@code b}
     * @return this builder
     */
    ServerConfigBuilder logGatheringDirective(Object enabled, Object gatherId, Object levels, Object batchSize) {
        JSONObject directive = new JSONObject();
        putIfNotNull(directive, keyLGEnabled, enabled);
        putIfNotNull(directive, keyLGId, gatherId);
        putIfNotNull(directive, keyLGLevels, levels);
        putIfNotNull(directive, keyLGBatchSize, batchSize);
        return topLevelKey(keyRLogGathering, directive);
    }

    /**
     * Puts a value unless it is {@code null}.
     *
     * @param target the object to put into
     * @param key the key
     * @param value the value
     */
    private static void putIfNotNull(JSONObject target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    /**
     * Every setting at a valid value that differs from what the SDK uses by default.
     *
     * @return this builder
     */
    ServerConfigBuilder allKeysAtNonDefaultValues() {
        tracking(false).networking(false).sessionTracking(false).viewTracking(false).customEventTracking(false)
            .contentZone(true).crashReporting(false).automaticSessionTracking(true).automaticViewTracking(true)
            .automaticCrashReporting(false).locationTracking(false).refreshContentZone(false).backoffMechanism(false)
            .logging(true).consentRequired(true);

        serverConfigUpdateInterval(8).requestQueueSize(2000).eventQueueSize(200).sessionUpdateInterval(120)
            .keyLengthLimit(89).valueSizeLimit(43).segmentationValuesLimit(25).breadcrumbLimit(90)
            .traceLinesLimit(89).traceLengthLimit(78).userPropertyCacheLimit(67)
            .backoffAcceptedTimeout(12).backoffRequestQueuePercentage(0.25).backoffRequestAge(36).backoffDuration(90)
            .dropOldRequestTime(5).contentZoneInterval(60);

        eventFilterList(names("blocked_event"), false);
        userPropertyFilterList(names("allowed_property"), true);
        segmentationFilterList(names("blocked_segment"), false);
        Map<String, Set<String>> perEvent = new LinkedHashMap<>();
        perEvent.put("purchase", names("card_number"));
        eventSegmentationFilterMap(perEvent, false);
        journeyTriggerEvents(names("journey_event"));
        return journeyTriggerViews(names("journey_view"));
    }

    /**
     * A set of names in the given order.
     *
     * @param names the names
     * @return the set
     */
    static Set<String> names(String... names) {
        return new LinkedHashSet<>(Arrays.asList(names));
    }

    /**
     * The payload as the server sends it.
     *
     * @return the JSON text
     */
    String build() {
        return buildJson().toString();
    }

    /**
     * The payload as a JSON object.
     *
     * @return the payload
     */
    JSONObject buildJson() {
        JSONObject json = new JSONObject();
        json.put(keyRVersion, version);
        json.put(keyRTimestamp, timestamp);
        json.put(keyRConfig, new JSONObject(config));
        for (Map.Entry<String, Object> entry : topLevelKeys.entrySet()) {
            json.put(entry.getKey(), entry.getValue());
        }
        return json;
    }

    /**
     * Asserts that the provider serves every setting this builder holds.
     *
     * @param provider the provider to check
     */
    @SuppressWarnings("unchecked")
    void validateAgainst(ConfigurationProvider provider) {
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            String key = entry.getKey();
            Object expected = entry.getValue();
            switch (key) {
                case keyREventBlacklist:
                case keyREventWhitelist:
                    assertFilter(key, (Set<String>) expected, keyREventWhitelist.equals(key), provider.getEventFilterList());
                    break;
                case keyRUserPropertyBlacklist:
                case keyRUserPropertyWhitelist:
                    assertFilter(key, (Set<String>) expected, keyRUserPropertyWhitelist.equals(key), provider.getUserPropertyFilterList());
                    break;
                case keyRSegmentationBlacklist:
                case keyRSegmentationWhitelist:
                    assertFilter(key, (Set<String>) expected, keyRSegmentationWhitelist.equals(key), provider.getSegmentationFilterList());
                    break;
                case keyREventSegmentationBlacklist:
                case keyREventSegmentationWhitelist:
                    ConfigurationProvider.FilterList<Map<String, Set<String>>> perEvent = provider.getEventSegmentationFilterList();
                    Assert.assertEquals("kind of '" + key + "'", keyREventSegmentationWhitelist.equals(key), perEvent.isWhitelist());
                    Assert.assertEquals("names of '" + key + "'", expected, perEvent.getFilterList());
                    break;
                default:
                    Assert.assertEquals("value of '" + key + "'", expected, valueOf(provider, key));
                    break;
            }
        }
    }

    /**
     * Asserts one names filter.
     *
     * @param key the settings key
     * @param expected the names
     * @param isWhitelist the expected kind
     * @param actual the filter the provider serves
     */
    private static void assertFilter(String key, Set<String> expected, boolean isWhitelist, ConfigurationProvider.FilterList<Set<String>> actual) {
        Assert.assertEquals("kind of '" + key + "'", isWhitelist, actual.isWhitelist());
        Assert.assertEquals("names of '" + key + "'", expected, actual.getFilterList());
    }

    /**
     * The value a provider serves for a setting that is not a listing filter.
     *
     * @param provider the provider
     * @param key the settings key
     * @return the served value, boxed
     */
    static Object valueOf(ConfigurationProvider provider, String key) {
        switch (key) {
            case keyRTracking:
                return provider.getTrackingEnabled();
            case keyRNetworking:
                return provider.getNetworkingEnabled();
            case keyRSessionTracking:
                return provider.getSessionTrackingEnabled();
            case keyRViewTracking:
                return provider.getViewTrackingEnabled();
            case keyRCustomEventTracking:
                return provider.getCustomEventTrackingEnabled();
            case keyREnterContentZone:
                return provider.getContentZoneEnabled();
            case keyRCrashReporting:
                return provider.getCrashReportingEnabled();
            case keyRAutomaticSessionTracking:
                return provider.getAutomaticSessionTrackingEnabled();
            case keyRAutomaticViewTracking:
                return provider.getAutomaticViewTrackingEnabled();
            case keyRAutomaticCrashReporting:
                return provider.getAutomaticCrashReportingEnabled();
            case keyRLocationTracking:
                return provider.getLocationTrackingEnabled();
            case keyRRefreshContentZone:
                return provider.getRefreshContentZoneEnabled();
            case keyRBackoffMechanism:
                return provider.getBOMEnabled();
            case keyRLogging:
                return provider.getLoggingEnabled();
            case keyRConsentRequired:
                return provider.getConsentRequired();
            case keyRServerConfigUpdateInterval:
                return provider.getServerConfigUpdateInterval();
            case keyRReqQueueSize:
                return provider.getRequestQueueMaxSize();
            case keyREventQueueSize:
                return provider.getEventQueueSizeThreshold();
            case keyRSessionUpdateInterval:
                return provider.getSessionUpdateInterval();
            case keyRLimitKeyLength:
                return provider.getMaxKeyLength();
            case keyRLimitValueSize:
                return provider.getMaxValueSize();
            case keyRLimitSegValues:
                return provider.getMaxSegmentationValues();
            case keyRLimitBreadcrumb:
                return provider.getMaxBreadcrumbCount();
            case keyRLimitTraceLine:
                return provider.getMaxStackTraceLinesPerThread();
            case keyRLimitTraceLength:
                return provider.getMaxStackTraceLineLength();
            case keyRUserPropertyCacheLimit:
                return provider.getUserPropertyCacheLimit();
            case keyRBOMAcceptedTimeout:
                return provider.getBOMAcceptedTimeoutSeconds();
            case keyRBOMRQPercentage:
                return provider.getBOMRQPercentage();
            case keyRBOMRequestAge:
                return provider.getBOMRequestAge();
            case keyRBOMDuration:
                return provider.getBOMDuration();
            case keyRDropOldRequestTime:
                return provider.getRequestDropAgeHours();
            case keyRContentZoneInterval:
                return provider.getContentZoneTimerInterval();
            case keyRJourneyTriggerEvents:
                return provider.getJourneyTriggerEvents();
            case keyRJourneyTriggerViews:
                return provider.getJourneyTriggerViews();
            default:
                throw new IllegalArgumentException("no getter for settings key [" + key + "]");
        }
    }
}
