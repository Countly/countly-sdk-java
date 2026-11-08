package ly.count.sdk.java.internal;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import ly.count.sdk.java.Session;
import org.json.JSONObject;

/**
 * Sends the SDK health check once per initialization, as an immediate request to {@code /i}, and
 * stores the health check counters on the update timer, at the end of a session and when the SDK
 * stops. Logs at debug level only, as every warning and error the SDK logs is counted.
 */
public class ModuleHealthCheck extends ModuleBase {
    static final String ENDPOINT = "/i?";
    static final String KEY_HEALTH_CHECK = "hc";
    static final String KEY_METRICS = "metrics";
    static final String KEY_APP_VERSION = "_app_version";

    private final AtomicBoolean healthCheckSent = new AtomicBoolean(false);
    @Nullable HealthCheckCounter counter;

    @Override
    public void init(InternalConfig config) {
        super.init(config);
        counter = config.sdk == null ? null : config.sdk.healthCheckCounter;
        L.v("[ModuleHealthCheck] init, health check enabled:[" + config.isHealthCheckEnabled() + "]");
    }

    @Override
    protected void initFinished(InternalConfig config) {
        sendHealthCheck();
    }

    @Override
    protected void onTimer() {
        HealthCheckCounter current = counter;
        if (current != null) {
            current.saveState();
        }
    }

    @Override
    public void onSessionEnded(Session session, InternalConfig config) {
        HealthCheckCounter current = counter;
        if (current != null) {
            current.saveState();
        }
    }

    /**
     * Stores the counters when the SDK stops and keeps its data. When it clears its data, the
     * stored state goes with it.
     *
     * @param config configuration of the SDK being stopped
     * @param clear whether the SDK clears its data
     */
    @Override
    public void stop(InternalConfig config, boolean clear) {
        HealthCheckCounter current = counter;
        if (current != null && !clear) {
            current.saveState();
        }
        counter = null;
    }

    /**
     * Sends the health check, unless it is disabled, offline mode is on, the SDK behavior settings
     * turned networking off, or it was sent already in this initialization. The request carries
     * the base request parameters, the app version as metrics while the sessions consent allows
     * metrics, and a snapshot of the counters. Only what the snapshot reported is taken away from
     * the counters, and only once the server accepted it: a 2xx answer with a JSON object that has
     * a "result". Otherwise the counters wait for the next initialization.
     */
    void sendHealthCheck() {
        L.v("[ModuleHealthCheck] sendHealthCheck, preparing the health check");

        if (!internalConfig.isHealthCheckEnabled()) {
            L.d("[ModuleHealthCheck] sendHealthCheck, the health check is disabled, nothing is sent");
            return;
        }

        if (internalConfig.isTemporaryIdEnabled()) {
            L.d("[ModuleHealthCheck] sendHealthCheck, offline mode is on, nothing is sent");
            return;
        }

        if (!internalConfig.getNetworkingEnabled()) {
            L.d("[ModuleHealthCheck] sendHealthCheck, networking is disabled by the SDK behavior settings, the counters wait for the next initialization");
            return;
        }

        final HealthCheckCounter current = counter;
        SDKCore core = internalConfig.sdk;
        Networking networking = core == null ? null : core.networking;
        if (current == null || networking == null || networking.getTransport() == null) {
            L.d("[ModuleHealthCheck] sendHealthCheck, the SDK has no counters or no networking, nothing is sent");
            return;
        }

        if (!healthCheckSent.compareAndSet(false, true)) {
            L.d("[ModuleHealthCheck] sendHealthCheck, the health check was already sent in this initialization");
            return;
        }

        final JSONObject snapshot = current.snapshot();
        Params params = ModuleRequests.prepareRequiredParams(internalConfig);
        String metrics = healthCheckMetrics();
        if (metrics != null) {
            params.add(KEY_METRICS, metrics);
        }
        params.add(KEY_HEALTH_CHECK, snapshot.toString());

        L.d("[ModuleHealthCheck] sendHealthCheck, sending the health check [" + snapshot + "]");
        internalConfig.immediateRequestGenerator.createImmediateRequestMaker().doWork(params.toString(), ENDPOINT, networking.getTransport(), false, true, response -> {
            if (response == null || !response.has("result")) {
                L.d("[ModuleHealthCheck] sendHealthCheck, the server did not accept the health check, the counters are kept for the next initialization, response:[" + response + "]");
                return;
            }

            if (counter != current) {
                L.d("[ModuleHealthCheck] sendHealthCheck, the SDK stopped before the answer arrived, the counters are left as they are");
                return;
            }

            L.d("[ModuleHealthCheck] sendHealthCheck, the server accepted the health check");
            current.clearSentValues(snapshot);
        }, L);
    }

    /**
     * The metrics of the health check: only the app version, the overridden value when the
     * developer set one. Sent only while the sessions consent allows metrics, as for a session
     * start, since this SDK has no consent of its own for metrics.
     *
     * @return the metrics as JSON text, {@code null} when there is no app version or no consent
     */
    @Nullable
    String healthCheckMetrics() {
        SDKCore core = internalConfig.sdk;
        if (core == null || !core.hasConsentForFeature(CoreFeature.Sessions)) {
            return null;
        }

        String appVersion = internalConfig.getApplicationVersion();
        Map<String, String> overrides = internalConfig.getMetricOverride();
        if (overrides != null && !Utils.isEmptyOrNull(overrides.get(KEY_APP_VERSION))) {
            appVersion = overrides.get(KEY_APP_VERSION);
        }

        if (Utils.isEmptyOrNull(appVersion)) {
            return null;
        }

        return new JSONObject().put(KEY_APP_VERSION, appVersion).toString();
    }

    /**
     * Whether the health check was sent in this initialization.
     *
     * @return {@code true} once it was sent
     */
    boolean isHealthCheckSent() {
        return healthCheckSent.get();
    }

    /**
     * The counters this module sends, for the tests.
     *
     * @return the counters, {@code null} once the module stopped
     */
    @Nullable
    HealthCheckCounter counter() {
        return counter;
    }

    /**
     * Creates the counters of a starting SDK, unless it runs in backend mode, where no health
     * check is sent.
     *
     * @param config configuration of the starting SDK
     * @param storageProvider where the counters are stored
     * @param core the starting SDK, which tells which feature has consent
     * @return the counters, {@code null} in backend mode
     */
    @Nullable
    static HealthCheckCounter createCounter(@Nonnull InternalConfig config, @Nonnull StorageProvider storageProvider, @Nonnull SDKCore core) {
        if (config.isBackendModeEnabled()) {
            return null;
        }
        return new HealthCheckCounter(storageProvider, config.getLogger(), core::hasConsentForUsageArea);
    }
}
