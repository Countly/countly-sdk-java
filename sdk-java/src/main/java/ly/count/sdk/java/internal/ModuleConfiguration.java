package ly.count.sdk.java.internal;

import java.util.Iterator;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Receives, validates and applies SDK behavior settings sourced from the
 * Countly server's {@code /o/sdk?method=sc} endpoint. The settings let the
 * server toggle individual feature trackings (events, sessions, views,
 * crashes, location) and tune queue sizes / update intervals without
 * shipping a new SDK build.
 *
 * The module is the canonical {@link ConfigurationProvider} for the SDK;
 * other modules read flags through that interface.
 */
public class ModuleConfiguration extends ModuleBase implements ConfigurationProvider {

    JSONObject latestRetrievedConfigurationFull = null;
    JSONObject latestRetrievedConfiguration = null;

    private CountlyTimer serverConfigUpdateTimer;
    private long lastServerConfigFetchTimestamp = -1;
    private boolean serverConfigRequestsDisabled = false;

    // ---- response envelope keys ----
    static final String keyRTimestamp = "t";
    static final String keyRVersion = "v";
    static final String keyRConfig = "c";

    // ---- inner config keys (mirrors Android subset that maps to Java's feature surface) ----
    static final String keyRTracking = "tracking";
    static final String keyRNetworking = "networking";
    static final String keyRSessionTracking = "st";
    static final String keyRCrashReporting = "crt";
    static final String keyRViewTracking = "vt";
    static final String keyRCustomEventTracking = "cet";
    static final String keyRLocationTracking = "lt";
    static final String keyRConsentRequired = "cr";
    static final String keyRLogging = "log";
    static final String keyRServerConfigUpdateInterval = "scui";
    static final String keyRReqQueueSize = "rqs";
    static final String keyREventQueueSize = "eqs";
    static final String keyRSessionUpdateInterval = "sui";

    // ---- runtime flags (defaults: safe = "everything on") ----
    boolean currentVTracking = true;
    boolean currentVNetworking = true;
    boolean currentVSessionTracking = true;
    boolean currentVCrashReporting = true;
    boolean currentVViewTracking = true;
    boolean currentVCustomEventTracking = true;
    boolean currentVLocationTracking = true;

    // ---- timer tunable (hours between refresh fetches) ----
    int currentServerConfigUpdateInterval = 4;

    @Override
    public void init(InternalConfig config) {
        super.init(config);
        L.v("[ModuleConfiguration] init");
        config.configProvider = this;

        serverConfigRequestsDisabled = config.isSdkBehaviorSettingsRequestsDisabled();
        serverConfigUpdateTimer = new CountlyTimer(L);

        loadConfigFromStorage(config.getSdkBehaviorSettings());
        updateConfigVariables(config);
    }

    @Override
    public void initFinished(@Nonnull InternalConfig config) {
        L.d("[ModuleConfiguration] initFinished");
        if (!serverConfigRequestsDisabled) {
            fetchConfigFromServer(config);
            startServerConfigUpdateTimer(config);
        }
    }

    @Override
    public void stop(InternalConfig config, boolean clear) {
        super.stop(config, clear);
        if (serverConfigUpdateTimer != null) {
            serverConfigUpdateTimer.stopTimer();
            serverConfigUpdateTimer = null;
        }
    }

    /**
     * Loads any previously-persisted config; falls back to the init-time
     * seed JSON the developer passed via {@code Config.setSdkBehaviorSettings}.
     */
    void loadConfigFromStorage(@Nullable String sdkBehaviorSettingsSeed) {
        String sConfig = internalConfig.storageProvider.getServerConfig();

        if (Utils.isEmptyOrNull(sConfig) && !Utils.isEmptyOrNull(sdkBehaviorSettingsSeed)) {
            sConfig = sdkBehaviorSettingsSeed;
        }

        L.v("[ModuleConfiguration] loadConfigFromStorage, [" + sConfig + "]");

        if (Utils.isEmptyOrNull(sConfig)) {
            L.d("[ModuleConfiguration] loadConfigFromStorage, nothing stored, defaults retained");
            return;
        }

        try {
            JSONObject parsed = new JSONObject(sConfig);
            saveAndStoreDownloadedConfig(parsed);
        } catch (JSONException e) {
            L.w("[ModuleConfiguration] loadConfigFromStorage, failed to parse, " + e);
            latestRetrievedConfigurationFull = null;
            latestRetrievedConfiguration = null;
        }
    }

    /**
     * Pulls a single value out of {@code latestRetrievedConfiguration},
     * applying type-coercion and an optional validator. If the key is
     * missing or the value fails validation, the supplied current value
     * is returned unchanged.
     */
    private <T> T extractValue(String key, StringBuilder sb, T currentValue, Class<T> clazz, @Nullable ConfigurationValueValidator<T> validator) {
        if (latestRetrievedConfiguration == null || !latestRetrievedConfiguration.has(key)) {
            return currentValue;
        }
        try {
            Object value = latestRetrievedConfiguration.get(key);
            if (value.equals(currentValue)) {
                return currentValue;
            }
            T extractedValue = clazz.cast(value);
            if (validator != null && !validator.validate(extractedValue)) {
                L.w("[ModuleConfiguration] extractValue, value for '" + key + "' failed validation, value: [" + extractedValue + "]");
                return currentValue;
            }
            sb.append(key).append(":[").append(value).append("], ");
            return extractedValue;
        } catch (Exception e) {
            L.w("[ModuleConfiguration] extractValue, failed to load '" + key + "', " + e.getMessage());
            return currentValue;
        }
    }

    private Boolean extractBool(String key, StringBuilder sb, Boolean currentValue) {
        return extractValue(key, sb, currentValue, Boolean.class, null);
    }

    /**
     * Applies the freshly-validated config to runtime flags + writes any
     * tunables back into {@link InternalConfig} so other modules pick them
     * up. Tunable changes that drive timers (e.g. {@code sui}) only take
     * effect at the next init — the SDK does not restart its global timer
     * mid-session.
     */
    private void updateConfigVariables(@Nonnull InternalConfig clyConfig) {
        L.v("[ModuleConfiguration] updateConfigVariables");
        if (latestRetrievedConfiguration == null) {
            return;
        }

        StringBuilder sb = new StringBuilder();

        currentVNetworking = extractBool(keyRNetworking, sb, currentVNetworking);
        currentVTracking = extractBool(keyRTracking, sb, currentVTracking);
        currentVSessionTracking = extractBool(keyRSessionTracking, sb, currentVSessionTracking);
        currentVCrashReporting = extractBool(keyRCrashReporting, sb, currentVCrashReporting);
        currentVViewTracking = extractBool(keyRViewTracking, sb, currentVViewTracking);
        currentVCustomEventTracking = extractBool(keyRCustomEventTracking, sb, currentVCustomEventTracking);
        currentVLocationTracking = extractBool(keyRLocationTracking, sb, currentVLocationTracking);

        currentServerConfigUpdateInterval = extractValue(keyRServerConfigUpdateInterval, sb, currentServerConfigUpdateInterval, Integer.class, value -> value > 0);

        Integer rqs = extractValue(keyRReqQueueSize, sb, clyConfig.getRequestQueueMaxSize(), Integer.class, value -> value > 0);
        if (rqs != null && rqs != clyConfig.getRequestQueueMaxSize()) {
            clyConfig.setRequestQueueMaxSize(rqs);
        }

        Integer eqs = extractValue(keyREventQueueSize, sb, clyConfig.getEventsBufferSize(), Integer.class, value -> value > 0);
        if (eqs != null && eqs != clyConfig.getEventsBufferSize()) {
            clyConfig.setEventQueueSizeToSend(eqs);
        }

        Integer sui = extractValue(keyRSessionUpdateInterval, sb, clyConfig.getSendUpdateEachSeconds(), Integer.class, value -> value > 0);
        if (sui != null && sui != clyConfig.getSendUpdateEachSeconds()) {
            clyConfig.setUpdateSessionTimerDelay(sui);
        }

        // logging + consentRequired are recognized but applied at init only
        extractBool(keyRLogging, sb, false);
        extractBool(keyRConsentRequired, sb, clyConfig.requiresConsent());

        String updatedValues = sb.toString();
        if (!updatedValues.isEmpty()) {
            L.i("[ModuleConfiguration] updateConfigVariables, settings changed: [" + updatedValues + "]");
        }
    }

    /**
     * Validate the outer envelope. We require all three of {@code v}, {@code t},
     * {@code c} to be present, the envelope to have exactly those three keys
     * (anything else is suspect), and {@code c} to be a non-empty object.
     */
    boolean validateServerConfig(@Nonnull JSONObject config) {
        L.v("[ModuleConfiguration] validateServerConfig");
        if (!config.has(keyRVersion)) {
            L.w("[ModuleConfiguration] validateServerConfig, missing 'v'; rejected");
            return false;
        }
        if (!config.has(keyRTimestamp)) {
            L.w("[ModuleConfiguration] validateServerConfig, missing 't'; rejected");
            return false;
        }
        if (!config.has(keyRConfig)) {
            L.w("[ModuleConfiguration] validateServerConfig, missing 'c'; rejected");
            return false;
        }
        if (config.length() != 3) {
            L.w("[ModuleConfiguration] validateServerConfig, wrong number of top-level keys; rejected");
            return false;
        }
        JSONObject inner = config.optJSONObject(keyRConfig);
        if (inner == null || inner.length() == 0) {
            L.d("[ModuleConfiguration] validateServerConfig, 'c' empty or not an object; rejected");
            return false;
        }
        removeUnsupportedKeys(inner);
        return true;
    }

    /**
     * Drops keys whose values don't match the expected type, plus any key
     * we don't know how to handle. Done in place; the resulting object is
     * what we persist.
     */
    private void removeUnsupportedKeys(@Nonnull JSONObject inner) {
        Iterator<String> keys = inner.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = inner.opt(key);
            boolean isValid;
            switch (key) {
                case keyRNetworking:
                case keyRTracking:
                case keyRSessionTracking:
                case keyRCrashReporting:
                case keyRViewTracking:
                case keyRCustomEventTracking:
                case keyRLocationTracking:
                case keyRConsentRequired:
                case keyRLogging:
                    isValid = value instanceof Boolean;
                    break;
                case keyRServerConfigUpdateInterval:
                case keyRReqQueueSize:
                case keyREventQueueSize:
                case keyRSessionUpdateInterval:
                    isValid = value instanceof Integer && ((Integer) value) > 0;
                    break;
                default:
                    isValid = false;
                    L.w("[ModuleConfiguration] removeUnsupportedKeys, unknown key '" + key + "', removed");
                    break;
            }
            if (!isValid) {
                L.w("[ModuleConfiguration] removeUnsupportedKeys, invalid value for '" + key + "': [" + value + "], removed");
                keys.remove();
            }
        }
    }

    /**
     * Validate + merge a newly-received envelope into our cached state.
     * The merge preserves keys from prior responses unless the new payload
     * explicitly overwrites them; this matches Android's behavior.
     */
    void saveAndStoreDownloadedConfig(@Nonnull JSONObject config) {
        L.v("[ModuleConfiguration] saveAndStoreDownloadedConfig");
        if (!validateServerConfig(config)) {
            L.w("[ModuleConfiguration] saveAndStoreDownloadedConfig, invalid envelope; ignored");
            latestRetrievedConfigurationFull = null;
            latestRetrievedConfiguration = null;
            return;
        }

        JSONObject newInner = config.optJSONObject(keyRConfig);
        if (latestRetrievedConfigurationFull == null) {
            latestRetrievedConfigurationFull = new JSONObject();
            latestRetrievedConfiguration = new JSONObject();
            try {
                latestRetrievedConfigurationFull.put(keyRConfig, latestRetrievedConfiguration);
            } catch (JSONException ignored) {
            }
        }

        try {
            latestRetrievedConfigurationFull.put(keyRTimestamp, config.get(keyRTimestamp));
            latestRetrievedConfigurationFull.put(keyRVersion, config.get(keyRVersion));
        } catch (JSONException e) {
            L.w("[ModuleConfiguration] saveAndStoreDownloadedConfig, failed to merge version/timestamp; " + e);
        }

        Iterator<String> keys = newInner.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = newInner.opt(key);
            if (value != null && !JSONObject.NULL.equals(value)) {
                try {
                    latestRetrievedConfiguration.put(key, value);
                } catch (JSONException e) {
                    L.w("[ModuleConfiguration] saveAndStoreDownloadedConfig, failed to merge inner key '" + key + "'; " + e);
                }
            }
        }

        internalConfig.storageProvider.setServerConfig(latestRetrievedConfigurationFull.toString());
    }

    void fetchConfigFromServer(@Nonnull InternalConfig config) {
        L.v("[ModuleConfiguration] fetchConfigFromServer");
        if (serverConfigRequestsDisabled) {
            L.v("[ModuleConfiguration] fetchConfigFromServer, fetches disabled, aborting");
            return;
        }
        if (config.getDeviceId() == null) {
            L.d("[ModuleConfiguration] fetchConfigFromServer, no device id yet, aborting");
            return;
        }

        lastServerConfigFetchTimestamp = TimeUtils.timestampMs();

        String requestData = ModuleRequests.prepareRequiredParams(config).add("method", "sc").toString();
        boolean networkingIsEnabled = config.getNetworkingEnabled();

        if (config.sdk == null || config.sdk.networking == null) {
            L.w("[ModuleConfiguration] fetchConfigFromServer, networking not ready, aborting");
            return;
        }

        Transport transport = config.sdk.networking.getTransport();
        config.immediateRequestGenerator.createImmediateRequestMaker().doWork(requestData, "/o/sdk?", transport, false, networkingIsEnabled, response -> {
            if (response == null) {
                L.w("[ModuleConfiguration] fetchConfigFromServer, no response (offline or server unreachable)");
                return;
            }
            L.d("[ModuleConfiguration] fetchConfigFromServer, received: [" + response + "]");
            saveAndStoreDownloadedConfig(response);
            updateConfigVariables(config);
        }, L);
    }

    private void startServerConfigUpdateTimer(@Nonnull InternalConfig config) {
        long intervalSeconds = (long) currentServerConfigUpdateInterval * 60L * 60L;
        serverConfigUpdateTimer.startTimer(intervalSeconds, () -> fetchConfigFromServer(config));
    }

    long getLastServerConfigFetchTimestamp() {
        return lastServerConfigFetchTimestamp;
    }

    @Override
    public boolean getNetworkingEnabled() {
        return currentVNetworking;
    }

    @Override
    public boolean getTrackingEnabled() {
        return currentVTracking;
    }

    @Override
    public boolean getSessionTrackingEnabled() {
        return currentVSessionTracking;
    }

    @Override
    public boolean getViewTrackingEnabled() {
        return currentVViewTracking;
    }

    @Override
    public boolean getCustomEventTrackingEnabled() {
        return currentVCustomEventTracking;
    }

    @Override
    public boolean getCrashReportingEnabled() {
        return currentVCrashReporting;
    }

    @Override
    public boolean getLocationTrackingEnabled() {
        return currentVLocationTracking;
    }

    interface ConfigurationValueValidator<T> {
        boolean validate(T value);
    }
}
