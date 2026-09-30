package ly.count.sdk.java.internal;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Runs the connection test a live SDK behavior settings response asks for with its top level
 * {@code ct} flag: one battery of parameterless GET probes against every endpoint the SDK depends
 * on, graded row by row and queued as one {@code ct_results} report. The feature keys, their order
 * and the {@code e} strings are the ones every Countly SDK reports, so the dashboard can group the
 * results.
 * <p>
 * The module is built for every SDK, whatever its features and consent, and listens to
 * {@link ModuleConfiguration}, which reads the flag. One battery runs at a time, on a daemon thread
 * of its own, and none while the settings switch networking off. Probes go straight to the server,
 * past the request queue, so neither the backoff mechanism nor the request drop age can hold them
 * back, while the report is an ordinary queued request. Nothing about the test is ever stored, so a
 * response that asks again runs the battery again.
 */
class ModuleConnectionTest extends ModuleBase implements ModuleConfiguration.ConnectionTestListener {
    static final String keyResults = "ct_results";
    static final String THREAD_NAME = "Countly-ConnectionTest";

    static final int DEFAULT_PER_REQUEST_TIMEOUT_MS = 10_000;
    static final long BATTERY_CAP_EXTRA_MS = 30_000;
    static final int MAX_ROWS = 32;
    static final int MAX_REPORT_BYTES = 8 * 1024;
    static final int MAX_ERROR_LENGTH = 256;

    static final String errorTimeout = "timeout";
    static final String errorTransport = "transport error";
    static final String errorRedirected = "redirected";
    static final String errorNotRun = "not run: battery cap";
    //the qualifier of a success graded from an opaque browser probe: never produced here, but it always survives the size cap
    static final String errorOpaque = "opaque";

    /**
     * The feature rows in report order. The row without paths is {@code sc}, measured from the
     * settings request that asked for the test.
     */
    static final ProbeRow[] ROWS = {
        new ProbeRow("core", true, "/o/ping"),
        new ProbeRow("core-write", false, "/i"),
        new ProbeRow("sc", false),
        new ProbeRow("rc", false, "/o/sdk?method=rc"),
        new ProbeRow("ab", false, "/o/sdk?method=ab_fetch_variants"),
        new ProbeRow("feedback", false, "/o/sdk?method=feedback"),
        new ProbeRow("feedback-widget", false, "/o/surveys/nps/widget", "/o/surveys/survey/widget", "/o/feedback/widget"),
        new ProbeRow("feedback-submit", false, "/i/feedback/inputs"),
        new ProbeRow("content", false, "/o/sdk/content"),
        new ProbeRow("feedback-page", false, "/feedback/nps", "/feedback/survey", "/feedback/rating"),
        new ProbeRow("feedback-assets", true, "/surveys/images/ct-probe.png", "/star-rating/images/ct-probe.png"),
        new ProbeRow("content-page", false, "/_external/content/"),
    };

    /**
     * One feature row of the battery.
     */
    static final class ProbeRow {
        final String feature;
        /**
         * Whether only a 2xx counts as reached: {@code core} and {@code feedback-assets} have no
         * legitimate 4xx answer, so anything else is a fault there.
         */
        final boolean requiresSuccessStatus;
        final String[] paths;

        /**
         * Describes one row.
         *
         * @param feature the feature key, reported as {@code f}
         * @param requiresSuccessStatus whether only a 2xx counts as reached
         * @param paths the paths to probe, relative to the server URL; none for the {@code sc} row
         */
        ProbeRow(@Nonnull String feature, boolean requiresSuccessStatus, @Nonnull String... paths) {
            this.feature = feature;
            this.requiresSuccessStatus = requiresSuccessStatus;
            this.paths = paths;
        }
    }

    /**
     * What one probe observed, before it is graded.
     */
    static final class ProbeOutcome {
        /**
         * The HTTP status, {@code 0} when none could be read.
         */
        int status;
        /**
         * From issuing the request to reading the whole response, or to the failure.
         */
        long ms;
        /**
         * {@link #errorTimeout} or {@link #errorTransport} while the status is {@code 0}, otherwise {@code null}.
         */
        String failure;
    }

    /**
     * One graded row of the report.
     */
    static final class ResultRow {
        final String feature;
        boolean ok;
        int status;
        long ms;
        String error;
        int pathCount;

        /**
         * Starts a row that reached nothing yet.
         *
         * @param feature the feature key
         */
        ResultRow(@Nonnull String feature) {
            this.feature = feature;
        }
    }

    /**
     * Issues one probe. Tests replace it to take the network out.
     */
    interface ProbeTransport {
        /**
         * Sends one bare GET and reads its status.
         *
         * @param url the probe URL
         * @param timeoutMs the connect and the read timeout, in milliseconds
         * @param transport the transport of the queued requests, whose pinning and custom request
         *     headers the probe reuses, {@code null} when the SDK runs without one
         * @return what the probe observed
         */
        @Nonnull
        ProbeOutcome probe(@Nonnull String url, int timeoutMs, @Nullable Transport transport);
    }

    final AtomicBoolean batteryRunning = new AtomicBoolean(false);
    volatile boolean halted = false;
    //taken by stop() to set halted and by the battery to check it and queue the report, so a stopped SDK never gets one
    private final Object reportLock = new Object();
    private volatile ModuleConfiguration listenedConfiguration = null;

    //package-private so tests can shrink the deadlines and take the network out
    volatile int perRequestTimeoutMs = DEFAULT_PER_REQUEST_TIMEOUT_MS;
    volatile long batteryCapExtraMs = BATTERY_CAP_EXTRA_MS;
    volatile ProbeTransport transport = new HttpProbeTransport();

    /**
     * Starts listening to the configuration module of the SDK for connection test requests.
     *
     * @param config configuration of the SDK being initialized
     */
    @Override
    public void init(InternalConfig config) {
        super.init(config);
        ModuleConfiguration configuration = config.sdk == null ? null : config.sdk.module(ModuleConfiguration.class);
        if (configuration == null) {
            L.d("[ModuleConnectionTest] init, no configuration module runs, a connection test can never be requested");
            return;
        }

        configuration.setConnectionTestListener(this);
        listenedConfiguration = configuration;
        L.v("[ModuleConnectionTest] init, listening for connection test requests");
    }

    /**
     * Stops listening for connection test requests. A running battery ends at its next row and
     * never queues its report.
     *
     * @param config configuration of the SDK being stopped
     * @param clear whether the SDK clears its data
     */
    @Override
    public void stop(InternalConfig config, boolean clear) {
        super.stop(config, clear);
        synchronized (reportLock) {
            halted = true;
        }

        ModuleConfiguration configuration = listenedConfiguration;
        listenedConfiguration = null;
        if (configuration != null) {
            configuration.setConnectionTestListener(null);
        }

        if (batteryRunning.get()) {
            L.d("[ModuleConnectionTest] stop, the running battery ends at its next row and queues no report");
        }
    }

    /**
     * Runs the battery a live settings response asked for.
     *
     * @param fetchLatencyMs how long the settings request took, in milliseconds
     */
    @Override
    public void onConnectionTestRequested(long fetchLatencyMs) {
        startBattery(fetchLatencyMs);
    }

    /**
     * Starts one battery on a daemon thread of its own, unless the SDK stopped, the settings switch
     * networking off or a battery is already running.
     *
     * @param scLatencyMs latency of the settings request that asked for the test, in milliseconds,
     *     negative when unknown
     */
    void startBattery(final long scLatencyMs) {
        if (halted) {
            L.d("[ModuleConnectionTest] startBattery, the SDK is stopped, ignoring the request");
            return;
        }

        if (!internalConfig.getConfigurationProvider().getNetworkingEnabled()) {
            L.d("[ModuleConnectionTest] startBattery, networking is disabled by the SDK behavior settings, ignoring the request");
            return;
        }

        if (!batteryRunning.compareAndSet(false, true)) {
            L.d("[ModuleConnectionTest] startBattery, a battery is already running, ignoring the request");
            return;
        }

        L.i("[ModuleConnectionTest] startBattery, the server asked for a connection test, running the probe battery");
        Thread worker = new Thread(() -> {
            try {
                runBatteryAndReport(scLatencyMs);
            } catch (Throwable t) {
                L.e("[ModuleConnectionTest] startBattery, the battery failed, [" + t + "]");
            } finally {
                batteryRunning.set(false);
            }
        }, THREAD_NAME);
        worker.setDaemon(true);

        try {
            worker.start();
        } catch (Throwable t) {
            batteryRunning.set(false);
            L.e("[ModuleConnectionTest] startBattery, failed to start the battery thread, [" + t + "]");
        }
    }

    /**
     * Runs the battery and queues its report as a request of its own, unless the SDK stopped
     * meanwhile.
     *
     * @param scLatencyMs latency of the settings request that asked for the test, in milliseconds,
     *     negative when unknown
     */
    void runBatteryAndReport(long scLatencyMs) {
        List<ResultRow> rows = runBattery(scLatencyMs);
        String report = buildReport(rows);

        synchronized (reportLock) {
            if (halted) {
                L.d("[ModuleConnectionTest] runBatteryAndReport, the SDK stopped during the battery, dropping the report");
                return;
            }

            L.d("[ModuleConnectionTest] runBatteryAndReport, queueing the report [" + report + "]");
            Request request = ModuleRequests.nonSessionRequest(internalConfig);
            request.params.add(keyResults, report);
            ModuleRequests.pushAsync(internalConfig, request);
        }
    }

    /**
     * Probes the rows in report order, one request at a time. A row the battery cap is reached
     * before is reported as not run, and a stop ends the battery before the next row.
     *
     * @param scLatencyMs latency of the settings request that asked for the test, in milliseconds,
     *     negative when unknown, which leaves the {@code sc} row out
     * @return the graded rows in report order
     */
    @Nonnull
    List<ResultRow> runBattery(long scLatencyMs) {
        String serverUrl = internalConfig.getServerURL().toString();
        Transport sdkTransport = sdkTransport();
        ProbeTransport probeTransport = transport;
        int timeoutMs = perRequestTimeoutMs;

        int requestCount = 0;
        for (ProbeRow row : ROWS) {
            requestCount += row.paths.length;
        }
        long capMs = (long) requestCount * timeoutMs + batteryCapExtraMs;
        long batteryStartNs = System.nanoTime();

        List<ResultRow> results = new ArrayList<>(ROWS.length);
        for (ProbeRow row : ROWS) {
            if (halted) {
                L.d("[ModuleConnectionTest] runBattery, the SDK stopped, ending the battery before [" + row.feature + "]");
                break;
            }

            if (row.paths.length == 0) {
                if (scLatencyMs >= 0) {
                    //the settings request was answered, or no test would have been asked for
                    ResultRow sc = new ResultRow(row.feature);
                    sc.ok = true;
                    sc.status = HttpURLConnection.HTTP_OK;
                    sc.ms = scLatencyMs;
                    results.add(sc);
                }
                continue;
            }

            long elapsedMs = (System.nanoTime() - batteryStartNs) / 1_000_000L;
            if (elapsedMs > capMs) {
                ResultRow notRun = new ResultRow(row.feature);
                notRun.error = errorNotRun;
                results.add(notRun);
                continue;
            }

            results.add(probeRow(row, serverUrl, probeTransport, sdkTransport, timeoutMs));
        }

        L.d("[ModuleConnectionTest] runBattery, [" + results.size() + "] rows graded against [" + serverUrl + "]");
        return results;
    }

    /**
     * Probes every path of a row and combines them into one row: reached only when every path was,
     * the status and the reason of the first failing path, or the first status when none failed, the
     * latencies summed and the number of paths.
     *
     * @param row the row
     * @param serverUrl the configured server URL
     * @param probeTransport what issues the probes
     * @param sdkTransport the transport of the queued requests, {@code null} when there is none
     * @param timeoutMs the per request timeout, in milliseconds
     * @return the graded row
     */
    @Nonnull
    ResultRow probeRow(@Nonnull ProbeRow row, @Nonnull String serverUrl, @Nonnull ProbeTransport probeTransport, @Nullable Transport sdkTransport, int timeoutMs) {
        ResultRow result = new ResultRow(row.feature);
        result.ok = true;
        result.pathCount = row.paths.length;

        for (int i = 0; i < row.paths.length; i++) {
            String url = buildProbeUrl(serverUrl, row.paths[i]);
            ProbeOutcome outcome = probeTransport.probe(url, timeoutMs, sdkTransport);
            result.ms += outcome.ms;

            String failure = grade(outcome, row.requiresSuccessStatus);
            L.v("[ModuleConnectionTest] probeRow, [" + row.feature + "] " + url + " -> status:[" + outcome.status + "] ms:[" + outcome.ms + "] failure:[" + failure + "]");

            if (i == 0) {
                result.status = outcome.status;
            }

            if (failure != null && result.ok) {
                result.ok = false;
                result.status = outcome.status;
                result.error = failure;
            }
        }

        return result;
    }

    /**
     * Grades one probe by whether the Countly application answered it: a 2xx did, and so did a
     * 4xx other than 403 on a row that has a legitimate 4xx answer. No status, a redirect, a 403, a
     * 5xx and anything else did not.
     *
     * @param outcome what the probe observed
     * @param requiresSuccessStatus whether only a 2xx counts as reached on this row
     * @return {@code null} when the application answered, otherwise the {@code e} reason
     */
    @Nullable
    static String grade(@Nonnull ProbeOutcome outcome, boolean requiresSuccessStatus) {
        int status = outcome.status;
        if (status <= 0) {
            return outcome.failure != null ? outcome.failure : errorTransport;
        }
        if (status >= 200 && status < 300) {
            return null;
        }
        if (status >= 300 && status < 400) {
            return errorRedirected;
        }
        if (!requiresSuccessStatus && status >= 400 && status < 500 && status != HttpURLConnection.HTTP_FORBIDDEN) {
            return null;
        }
        return "HTTP " + status;
    }

    /**
     * Resolves a path against the configured server URL, keeping any path prefix, with exactly one
     * {@code /} at the join, then appends the probe marker and a cache buster.
     *
     * @param serverUrl the configured server URL
     * @param path the path, which may carry a query
     * @return the probe URL
     */
    @Nonnull
    static String buildProbeUrl(@Nonnull String serverUrl, @Nonnull String path) {
        String base = serverUrl;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String joined = path.startsWith("/") ? base + path : base + "/" + path;
        String separator = joined.contains("?") ? "&" : "?";
        return joined + separator + "ct=1&_=" + System.currentTimeMillis();
    }

    /**
     * Builds the report: the device time, the SDK identity and the rows, within the caps of
     * {@value #MAX_ROWS} rows and {@value #MAX_REPORT_BYTES} bytes of UTF-8. Over the size cap every
     * {@code e} except {@link #errorOpaque} is dropped first, then rows from the end.
     *
     * @param rows the graded rows in report order
     * @return the report as JSON text
     */
    @Nonnull
    String buildReport(@Nonnull List<ResultRow> rows) {
        JSONObject report = new JSONObject();
        JSONArray results = new JSONArray();
        try {
            report.put("ts", System.currentTimeMillis());
            JSONObject sdk = new JSONObject();
            sdk.put("name", internalConfig.getSdkName());
            sdk.put("version", internalConfig.getSdkVersion());
            report.put("sdk", sdk);
            report.put("results", results);

            int rowLimit = Math.min(rows.size(), MAX_ROWS);
            for (int i = 0; i < rowLimit; i++) {
                results.put(rowToJson(rows.get(i)));
            }

            if (utf8Length(report.toString()) > MAX_REPORT_BYTES) {
                L.w("[ModuleConnectionTest] buildReport, the report is over the size cap, dropping the error details");
                for (int i = 0; i < results.length(); i++) {
                    JSONObject row = results.getJSONObject(i);
                    if (!errorOpaque.equals(row.optString("e"))) {
                        row.remove("e");
                    }
                }
            }

            while (utf8Length(report.toString()) > MAX_REPORT_BYTES && results.length() > 0) {
                L.w("[ModuleConnectionTest] buildReport, the report is still over the size cap, dropping its last row");
                results.remove(results.length() - 1);
            }
        } catch (JSONException e) {
            L.w("[ModuleConnectionTest] buildReport, failed to build the report, [" + e + "]");
        }

        return report.toString();
    }

    /**
     * Serializes one row, its {@code e} cut at {@value #MAX_ERROR_LENGTH} characters and its path
     * count only when it probed more than one path.
     *
     * @param row the graded row
     * @return the row as the report carries it
     */
    @Nonnull
    static JSONObject rowToJson(@Nonnull ResultRow row) {
        JSONObject json = new JSONObject();
        json.put("f", row.feature);
        json.put("ok", row.ok);
        json.put("st", row.status);
        json.put("ms", row.ms);
        if (row.error != null) {
            String error = row.error;
            if (error.length() > MAX_ERROR_LENGTH) {
                error = error.substring(0, MAX_ERROR_LENGTH);
            }
            json.put("e", error);
        }
        if (row.pathCount > 1) {
            json.put("n", row.pathCount);
        }
        return json;
    }

    /**
     * The size of a text in UTF-8.
     *
     * @param value the text
     * @return its length in bytes
     */
    static int utf8Length(@Nonnull String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * The transport the queued requests go through, whose pinning and custom request headers the
     * probes reuse.
     *
     * @return the transport, {@code null} when the SDK runs without its default networking
     */
    @Nullable
    private Transport sdkTransport() {
        SDKCore sdk = internalConfig.sdk;
        Networking networking = sdk == null ? null : sdk.networking;
        return networking == null ? null : networking.getTransport();
    }

    /**
     * Probes with a bare GET opened by {@link Transport#openProbeConnection(String, int)}.
     */
    static final class HttpProbeTransport implements ProbeTransport {
        //only the status matters, but the body is read so 'ms' covers the whole response, up to this much of it
        static final int MAX_DRAIN_BYTES = 64 * 1024;

        /**
         * Sends the probe and reads its status and its body, mapping a timeout to
         * {@link #errorTimeout} and any other failure to {@link #errorTransport}.
         *
         * @param url the probe URL
         * @param timeoutMs the connect and the read timeout, in milliseconds
         * @param transport the transport whose pinning and custom request headers the probe reuses,
         *     {@code null} for a plain connection
         * @return what the probe observed
         */
        @Nonnull
        @Override
        public ProbeOutcome probe(@Nonnull String url, int timeoutMs, @Nullable Transport transport) {
            ProbeOutcome outcome = new ProbeOutcome();
            long startNs = System.nanoTime();
            HttpURLConnection connection = null;
            try {
                connection = transport == null ? Transport.openBareGetConnection(url, timeoutMs) : transport.openProbeConnection(url, timeoutMs);
                int status = connection.getResponseCode();
                if (status > 0) {
                    outcome.status = status;
                    drain(connection);
                } else {
                    //not an HTTP answer
                    outcome.failure = errorTransport;
                }
            } catch (SocketTimeoutException e) {
                outcome.status = 0;
                outcome.failure = errorTimeout;
            } catch (Exception e) {
                outcome.status = 0;
                outcome.failure = errorTransport;
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
                outcome.ms = (System.nanoTime() - startNs) / 1_000_000L;
            }
            return outcome;
        }

        /**
         * Reads the body, up to {@link #MAX_DRAIN_BYTES}, and discards it.
         *
         * @param connection the answered connection
         */
        private static void drain(@Nonnull HttpURLConnection connection) {
            try (InputStream stream = bodyStream(connection)) {
                if (stream == null) {
                    return;
                }
                byte[] buffer = new byte[4096];
                int total = 0;
                int read;
                while (total < MAX_DRAIN_BYTES && (read = stream.read(buffer)) != -1) {
                    total += read;
                }
            } catch (IOException ignored) {
                //the status is already read, a failed body read changes nothing
            }
        }

        /**
         * The body of the answer, which is the error stream for a failing status.
         *
         * @param connection the answered connection
         * @return the body, {@code null} when there is none
         */
        @Nullable
        private static InputStream bodyStream(@Nonnull HttpURLConnection connection) {
            try {
                return connection.getInputStream();
            } catch (IOException e) {
                return connection.getErrorStream();
            }
        }
    }
}
