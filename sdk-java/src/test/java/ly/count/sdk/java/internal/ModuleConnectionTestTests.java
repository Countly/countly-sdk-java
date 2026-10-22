package ly.count.sdk.java.internal;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.net.ssl.HttpsURLConnection;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import ly.count.sdk.java.internal.ModuleConnectionTest.ProbeOutcome;
import ly.count.sdk.java.internal.ModuleConnectionTest.ResultRow;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static ly.count.sdk.java.internal.ModuleConnectionTest.keyResults;
import static ly.count.sdk.java.internal.ServerConfigBuilder.names;

/**
 * The connection test a live SDK behavior settings response arms with its top level {@code ct}
 * flag: one battery of parameterless GET probes against every endpoint the SDK depends on, graded
 * and queued as one {@code ct_results} report.
 * <p>
 * Settings requests go through a {@link ModuleConfigurationTests.ServerConfigResponder}, which
 * answers on the calling thread, so a response to the fetch made at init arms the test before init
 * returns. Probes go to a local HTTP server that answers every path the way a Countly server behind
 * the path prefix {@value #PREFIX} does. It refuses the SDK's own requests, which carry the app key,
 * so the report stays in the request queue, where {@link TestUtils#getCurrentRQ()} reads it. A test
 * that needs short deadlines or a fake probe transport sets them through the test hooks before it
 * arms the test; no wait relies on the 10 second probe timeout or on the 60 second SDK timer.
 */
@RunWith(JUnit4.class)
public class ModuleConnectionTestTests {

    private static final String PREFIX = "/countly";
    private static final String[] EXPECTED_ORDER = {
        "core", "core-write", "sc", "rc", "ab", "feedback", "feedback-widget", "feedback-submit", "content", "feedback-page", "feedback-assets", "content-page"
    };
    private static final List<String> EXPECTED_PATHS = Arrays.asList(
        "/o/ping", "/i", "/o/sdk?method=rc", "/o/sdk?method=ab_fetch_variants", "/o/sdk?method=feedback",
        "/o/surveys/nps/widget", "/o/surveys/survey/widget", "/o/feedback/widget", "/i/feedback/inputs", "/o/sdk/content",
        "/feedback/nps", "/feedback/survey", "/feedback/rating", "/surveys/images/ct-probe.png", "/star-rating/images/ct-probe.png",
        "/_external/content/");
    private static final Set<String> ROW_KEYS = names("f", "ok", "st", "ms", "e", "n");
    private static final String MISSING_PARAMETER = "{\"result\":\"Missing parameter \\\"app_key\\\" or \\\"device_id\\\"\"}";
    private static final String NOT_RUN = "not run: battery cap";

    private static final String BATTERY_STARTED = "[ModuleConnectionTest] startBattery, the server asked for a connection test";
    private static final String ALREADY_RUNNING = "[ModuleConnectionTest] startBattery, a battery is already running";
    private static final String NETWORKING_DISABLED = "[ModuleConnectionTest] startBattery, networking is disabled";
    private static final String REPORT_DROPPED = "[ModuleConnectionTest] runBatteryAndReport, the SDK stopped during the battery, dropping the report";

    private static final long WAIT_BUDGET_MS = 30_000;
    private static final long POLL_MS = 20;

    private ModuleConfigurationTests.ServerConfigResponder settings;
    private ProbeServer server;
    private final List<String> logs = new CopyOnWriteArrayList<>();

    /**
     * Starts every test from empty storage, without a test module, with a fresh settings responder
     * and a fresh local server.
     *
     * @throws IOException when the local server cannot start
     */
    @Before
    public void beforeTest() throws IOException {
        TestUtils.createCleanTestState();
        SDKCore.testDummyModule = null;
        settings = new ModuleConfigurationTests.ServerConfigResponder();
        server = new ProbeServer();
        logs.clear();
    }

    /**
     * Stops the SDK, clearing its data, then the local server.
     */
    @After
    public void afterTest() {
        Countly.instance().halt();
        server.stop();
    }

    // region scenarios

    /**
     * A healthy server behind a path prefix, armed by the response to the fetch made at init.
     * <p>
     * Verifies that the battery reports every row reachable, in table order, with the status a
     * Countly server answers a bare request with; that the report is one queued request of its own
     * carrying the SDK identity and the required parameters and nothing else; that each of the 16
     * probes is a bare GET resolved against the prefix, with a single slash at the join although
     * the configured URL ends in slashes, carrying only the method selector, the marker and the
     * cache buster, no body and the custom request headers; that the flag is never stored while the
     * rest of the response applies; and that the report then rides the queue to the server.
     */
    @Test
    public void armed_healthyServer_reportsEveryRowReachable_fromBareProbesResolvedAgainstThePathPrefix() throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Countly-Test", "yes");
        settings.respondWith(new ServerConfigBuilder().viewTracking(false).connectionTest(1));
        init(config(server.url(PREFIX + "//")).addCustomNetworkRequestHeaders(headers));
        awaitBatteryIdle(connectionTest());

        Map<String, String> queued = onlyQueuedReport();
        TestUtils.validateRequiredParams(queued);
        for (String param : Arrays.asList("events", "begin_session", "end_session", "session_id", "metrics", "method", "user_details")) {
            Assert.assertFalse("the report carries [" + param + "]", queued.containsKey(param));
        }

        JSONObject report = new JSONObject(queued.get(keyResults));
        Assert.assertEquals(names("ts", "sdk", "results"), report.keySet());
        Assert.assertTrue(report.getLong("ts") > 0);
        Assert.assertEquals(TestUtils.SDK_NAME, report.getJSONObject("sdk").getString("name"));
        Assert.assertEquals(TestUtils.SDK_VERSION, report.getJSONObject("sdk").getString("version"));
        assertRowOrder(report);
        Map<String, JSONObject> rows = rowsByFeature(report);
        assertRow(rows.get("core"), true, 200, null, null);
        assertRow(rows.get("core-write"), true, 400, null, null);
        assertRow(rows.get("sc"), true, 200, null, null);
        assertRow(rows.get("rc"), true, 400, null, null);
        assertRow(rows.get("ab"), true, 400, null, null);
        assertRow(rows.get("feedback"), true, 400, null, null);
        assertRow(rows.get("feedback-widget"), true, 400, null, 3);
        assertRow(rows.get("feedback-submit"), true, 400, null, null);
        assertRow(rows.get("content"), true, 400, null, null);
        assertRow(rows.get("feedback-page"), true, 200, null, 3);
        assertRow(rows.get("feedback-assets"), true, 200, null, 2);
        assertRow(rows.get("content-page"), true, 200, null, null);

        List<String> probedPaths = new ArrayList<>();
        for (Received probe : server.probes) {
            Assert.assertEquals("GET", probe.method);
            Assert.assertTrue(probe.path, probe.path.startsWith(PREFIX + "/"));
            Assert.assertFalse(probe.path, probe.path.contains("//"));
            Assert.assertTrue(probe.rawQuery, probe.rawQuery.matches("(method=[a-z_]+&)?ct=1&_=\\d+"));
            Assert.assertEquals(0, probe.bodyLength);
            Assert.assertEquals("yes", probe.headers.get("x-countly-test"));
            String method = probe.params.get("method");
            probedPaths.add(probe.path.substring(PREFIX.length()) + (method == null ? "" : "?method=" + method));
        }
        Assert.assertEquals(EXPECTED_PATHS, probedPaths);

        Assert.assertEquals(names("v", "t", "c"), storedConfig().keySet());
        Assert.assertFalse(provider().getViewTrackingEnabled());

        Assert.assertTrue("the report never reached the server", waitFor(() -> !reportsSentToTheServer().isEmpty()));
        Received sent = reportsSentToTheServer().get(0);
        Assert.assertEquals(queued.get(keyResults), sent.params.get(keyResults));
        Assert.assertEquals("yes", sent.headers.get("x-countly-test"));
    }

    /**
     * A server that fails in every way the grading tells apart.
     * <p>
     * Verifies that {@code /o/ping} answering {@code 404 DB Error} and an image route answering 404
     * are faults on the two rows that need a 2xx, that a 403, a 5xx and a redirect are faults on any
     * row, each with its own {@code e}, that a combined row keeps probing after its first failing
     * path and reports that path's status, and that the redirect is read as a 3xx and never
     * followed.
     */
    @Test
    public void armed_brokenServer_gradesEveryFailureToItsReason_andNeverFollowsARedirect() throws Exception {
        server.overrides.put(PREFIX + "/o/ping", Answer.json(404, "{\"result\":\"DB Error\"}"));
        server.overrides.put(PREFIX + "/i", Answer.page(403, "text/html", "forbidden"));
        server.overrides.put(PREFIX + "/o/sdk", Answer.json(500, "{\"result\":\"boom\"}"));
        server.overrides.put(PREFIX + "/o/surveys/survey/widget", Answer.redirect(PREFIX + "/o/ping"));
        server.overrides.put(PREFIX + "/star-rating/images/ct-probe.png", Answer.page(404, "text/html", "no such image"));
        server.overrides.put(PREFIX + "/feedback/rating", Answer.page(502, "text/html", "bad gateway"));
        settings.respondWith(new ServerConfigBuilder().connectionTest(true));
        init(config(server.url(PREFIX)));
        awaitBatteryIdle(connectionTest());

        JSONObject report = new JSONObject(onlyQueuedReport().get(keyResults));
        assertRowOrder(report);
        Map<String, JSONObject> rows = rowsByFeature(report);
        assertRow(rows.get("core"), false, 404, "HTTP 404", null);
        assertRow(rows.get("core-write"), false, 403, "HTTP 403", null);
        assertRow(rows.get("sc"), true, 200, null, null);
        assertRow(rows.get("rc"), false, 500, "HTTP 500", null);
        assertRow(rows.get("ab"), false, 500, "HTTP 500", null);
        assertRow(rows.get("feedback"), false, 500, "HTTP 500", null);
        assertRow(rows.get("feedback-widget"), false, 302, "redirected", 3);
        assertRow(rows.get("feedback-submit"), true, 400, null, null);
        assertRow(rows.get("content"), true, 400, null, null);
        assertRow(rows.get("feedback-page"), false, 502, "HTTP 502", 3);
        assertRow(rows.get("feedback-assets"), false, 404, "HTTP 404", 2);
        assertRow(rows.get("content-page"), true, 200, null, null);

        Assert.assertEquals(16, server.probes.size());
        Assert.assertEquals(1, probesTo(PREFIX + "/o/ping"));
        Assert.assertEquals(1, probesTo(PREFIX + "/o/feedback/widget"));
    }

    /**
     * A response without the flag, or with a falsy one.
     * <p>
     * Verifies, for an absent flag and for {@code 0}, {@code false}, {@code "0"}, {@code "false"},
     * {@code ""} and {@code null}, each delivered by the fetch made at init, that no battery starts,
     * no probe reaches the server and no report is queued, while the settings of the same response
     * apply and the flag is never stored.
     */
    @Test
    public void notArmed_anAbsentOrFalsyFlag_runsNothing_whileTheSettingsStillApply() throws Exception {
        Object[] variants = { null, 0, false, "0", "false", "", JSONObject.NULL };
        for (Object variant : variants) {
            String label = "ct = " + (variant == null ? "absent" : JSONObject.valueToString(variant));
            TestUtils.createCleanTestState();
            ServerConfigBuilder response = new ServerConfigBuilder().viewTracking(false);
            if (variant != null) {
                response.connectionTest(variant);
            }
            settings.respondWith(response);
            init(config(server.url(PREFIX)));

            Assert.assertFalse(label, provider().getViewTrackingEnabled());
            Assert.assertFalse(label, connectionTest().batteryRunning.get());
            Assert.assertEquals(label, 0, logsContaining(BATTERY_STARTED));
            Assert.assertEquals(label, names("v", "t", "c"), storedConfig().keySet());
            Assert.assertTrue(label, queuedReports().isEmpty());
        }

        Assert.assertEquals(variants.length, settings.requests.size());
        Assert.assertTrue(server.probes.isEmpty());
    }

    /**
     * An armed response that also switches networking off.
     * <p>
     * Verifies that the battery is skipped, so no probe and no report go out, and that nothing about
     * the skipped test is kept: the next armed response, which allows networking again, runs the
     * battery and queues its report.
     */
    @Test
    public void armed_whileNetworkingIsDisabled_skipsTheBattery_andTheNextArmedResponseRunsIt() throws Exception {
        settings.respondWith(new ServerConfigBuilder().networking(false).connectionTest(1));
        init(config(server.url(PREFIX)));
        ModuleConnectionTest module = connectionTest();

        Assert.assertFalse(provider().getNetworkingEnabled());
        Assert.assertFalse(module.batteryRunning.get());
        Assert.assertEquals(1, logsContaining(NETWORKING_DISABLED));
        Assert.assertEquals(0, logsContaining(BATTERY_STARTED));
        Assert.assertTrue(queuedReports().isEmpty());
        Assert.assertTrue(server.probes.isEmpty());

        push(new ServerConfigBuilder().networking(true).connectionTest(1));
        awaitBatteryIdle(module);

        Assert.assertEquals(16, server.probes.size());
        assertRowOrder(new JSONObject(onlyQueuedReport().get(keyResults)));
    }

    /**
     * An armed SDK whose server refuses every connection.
     * <p>
     * Verifies that every probed row is a transport error with status 0, keeping its path count,
     * that the {@code sc} row still carries the measured settings request, and that the report is
     * queued with the SDK identity and stays queued after the queue failed to send it.
     */
    @Test
    public void armed_serverDown_reportsATransportErrorOnEveryProbedRow_andTheReportStaysQueued() throws Exception {
        settings.respondWith(new ServerConfigBuilder().connectionTest("1"));
        init(config(deadUrl()));
        awaitBatteryIdle(connectionTest());

        Assert.assertTrue("the queue never tried to send the report, logs: " + logs,
            waitFor(() -> logs.stream().anyMatch(line -> line.contains("Error while sending request") && line.contains(keyResults))));
        Map<String, String> queued = onlyQueuedReport();
        Assert.assertEquals(TestUtils.SERVER_APP_KEY, queued.get("app_key"));
        Assert.assertEquals(TestUtils.DEVICE_ID, queued.get("device_id"));

        JSONObject report = new JSONObject(queued.get(keyResults));
        assertRowOrder(report);
        Map<String, JSONObject> rows = rowsByFeature(report);
        Map<String, Integer> pathCounts = new HashMap<>();
        pathCounts.put("feedback-widget", 3);
        pathCounts.put("feedback-page", 3);
        pathCounts.put("feedback-assets", 2);
        for (String feature : EXPECTED_ORDER) {
            if ("sc".equals(feature)) {
                assertRow(rows.get(feature), true, 200, null, null);
            } else {
                assertRow(rows.get(feature), false, 0, "transport error", pathCounts.get(feature));
            }
        }
    }

    /**
     * An endpoint that accepts the connection but never answers.
     * <p>
     * Verifies with a one second per request timeout that the silent endpoint is reported as a
     * timeout with status 0 after at least the timeout, while the rows around it are unaffected.
     */
    @Test
    public void armed_silentEndpoint_reportsATimeout_andTheOtherRowsAreUnaffected() throws Exception {
        server.overrides.put(PREFIX + "/o/sdk/content", Answer.SILENT);
        settings.pending();
        init(config(server.url(PREFIX)));
        ModuleConnectionTest module = connectionTest();
        module.perRequestTimeoutMs = 1_000;

        push(new ServerConfigBuilder().connectionTest(1));
        awaitBatteryIdle(module);

        Map<String, JSONObject> rows = rowsByFeature(new JSONObject(onlyQueuedReport().get(keyResults)));
        JSONObject content = rows.get("content");
        assertRow(content, false, 0, "timeout", null);
        Assert.assertTrue("ms " + content.getLong("ms"), content.getLong("ms") >= 1_000);
        assertRow(rows.get("core"), true, 200, null, null);
        assertRow(rows.get("feedback-submit"), true, 400, null, null);
        assertRow(rows.get("feedback-page"), true, 200, null, 3);
        assertRow(rows.get("content-page"), true, 200, null, null);
    }

    /**
     * Deliveries that overlap a running battery, and one after it.
     * <p>
     * Verifies that a delivery while a battery runs is ignored, that the battery runs on a daemon
     * thread named {@value ModuleConnectionTest#THREAD_NAME}, never on the thread that delivered the
     * response, and hands every probe the transport of the queued requests, and that a delivery after
     * the battery finished runs it again with the latency of its own settings request.
     */
    @Test
    public void aDeliveryWhileABatteryRuns_isIgnored_andOneAfterItRunsAgain_onADaemonThreadOfItsOwn() throws Exception {
        settings.pending();
        init(config(server.url(PREFIX)));
        ModuleConnectionTest module = connectionTest();
        FakeTransport first = FakeTransport.gated();
        module.transport = first;

        push(new ServerConfigBuilder().connectionTest(1));
        Assert.assertTrue(first.firstProbeStarted.await(WAIT_BUDGET_MS, TimeUnit.MILLISECONDS));
        Assert.assertTrue(module.batteryRunning.get());

        push(new ServerConfigBuilder().connectionTest(true));
        push(new ServerConfigBuilder().connectionTest("yes"));
        Assert.assertEquals(2, logsContaining(ALREADY_RUNNING));
        Assert.assertEquals(1, first.urls.size());

        first.gate.countDown();
        awaitBatteryIdle(module);
        Assert.assertEquals(16, first.urls.size());
        Assert.assertEquals(Collections.singleton(ModuleConnectionTest.THREAD_NAME), first.threadNames);
        Assert.assertFalse(first.daemonFlags.contains(false));
        Transport queueTransport = SDKCore.instance.networking.getTransport();
        Assert.assertNotNull(queueTransport);
        for (Transport used : first.transports) {
            Assert.assertSame(queueTransport, used);
        }
        Assert.assertEquals(1, queuedReports().size());

        FakeTransport second = FakeTransport.instant(1);
        module.transport = second;
        settings.delayedBy(80);
        push(new ServerConfigBuilder().connectionTest(1));
        awaitBatteryIdle(module);

        Assert.assertEquals(16, second.urls.size());
        Assert.assertEquals(2, logsContaining(BATTERY_STARTED));
        List<Map<String, String>> reports = queuedReports();
        Assert.assertEquals(2, reports.size());
        long latencyMs = rowsByFeature(new JSONObject(reports.get(1).get(keyResults))).get("sc").getLong("ms");
        Assert.assertTrue("sc ms " + latencyMs, latencyMs >= 70);
    }

    /**
     * The SDK stops while a battery runs.
     * <p>
     * Verifies that the module listens to the configuration module of its SDK from init and stops
     * listening on stop, that the stopped battery ends before its next row and queues no report,
     * not even into the queue of an SDK started again meanwhile, and that the stopped module refuses
     * a later delivery.
     */
    @Test
    public void stop_duringABattery_endsItBeforeTheNextRow_queuesNoReport_andStopsListening() throws Exception {
        settings.pending();
        init(config(server.url(PREFIX)));
        ModuleConnectionTest module = connectionTest();
        ModuleConfiguration configuration = SDKCore.instance.module(ModuleConfiguration.class);
        Assert.assertSame(module, SDKCore.instance.modules.get(0));
        Assert.assertSame(module, listenerOf(configuration));
        FakeTransport gated = FakeTransport.gated();
        module.transport = gated;

        push(new ServerConfigBuilder().connectionTest(1));
        Assert.assertTrue(gated.firstProbeStarted.await(WAIT_BUDGET_MS, TimeUnit.MILLISECONDS));
        Countly.instance().stop();

        Assert.assertTrue(module.halted);
        Assert.assertNull(listenerOf(configuration));

        //started before the stopped battery moves on, so a report it queued would land in this queue
        settings.pending();
        init(config(server.url(PREFIX)));
        gated.gate.countDown();
        awaitBatteryIdle(module);

        Assert.assertEquals(1, gated.urls.size());
        Assert.assertEquals(1, logsContaining(REPORT_DROPPED));
        Assert.assertTrue(queuedReports().isEmpty());

        module.onConnectionTestRequested(5);
        Assert.assertFalse(module.batteryRunning.get());
        Assert.assertEquals(1, gated.urls.size());
        Assert.assertNotSame(module, connectionTest());
        Assert.assertSame(connectionTest(), listenerOf(SDKCore.instance.module(ModuleConfiguration.class)));
    }

    /**
     * The battery cap, attempted requests times the per request timeout plus the extra time.
     * <p>
     * Verifies with a 160 ms cap and probes that take 40 ms each that the first row is always
     * probed, that the {@code sc} row is never capped, that once the cap is reached every later
     * probed row is reported as not run, with status 0, 0 ms and no path count, that no probe is
     * issued for those rows, and that the report still goes out.
     */
    @Test
    public void batteryCap_reportsEveryRowItIsReachedBeforeAsNotRun_andTheReportStillGoesOut() throws Exception {
        settings.pending();
        init(config(server.url(PREFIX)));
        ModuleConnectionTest module = connectionTest();
        module.perRequestTimeoutMs = 10;
        module.batteryCapExtraMs = 0;
        FakeTransport slow = FakeTransport.slow(40);
        module.transport = slow;

        push(new ServerConfigBuilder().connectionTest(1));
        awaitBatteryIdle(module);

        JSONObject report = new JSONObject(onlyQueuedReport().get(keyResults));
        assertRowOrder(report);
        JSONArray results = report.getJSONArray("results");
        assertRow(results.getJSONObject(0), true, 200, null, null);
        assertRow(results.getJSONObject(2), true, 200, null, null);

        boolean capReached = false;
        int probedPaths = 0;
        for (int i = 0; i < results.length(); i++) {
            JSONObject row = results.getJSONObject(i);
            String feature = row.getString("f");
            if ("sc".equals(feature)) {
                continue;
            }
            if (NOT_RUN.equals(row.optString("e"))) {
                capReached = true;
                assertRow(row, false, 0, NOT_RUN, null);
                Assert.assertEquals(feature, 0, row.getLong("ms"));
            } else {
                Assert.assertFalse(feature + " was probed after the cap was reached", capReached);
                Assert.assertTrue(feature, row.getBoolean("ok"));
                probedPaths += row.optInt("n", 1);
            }
        }
        Assert.assertTrue(capReached);
        Assert.assertEquals(NOT_RUN, results.getJSONObject(results.length() - 1).getString("e"));
        Assert.assertEquals(probedPaths, slow.urls.size());
        Assert.assertTrue(slow.urls.size() < 16);
    }

    /**
     * The grading table.
     * <p>
     * Verifies that a 2xx is reached, that a 4xx other than 403 is reached except on a row that
     * needs a 2xx, that a redirect, a 403, a 5xx and an informational status are faults with their
     * {@code e}, and that no status reports its failure, a transport error when none is known.
     */
    @Test
    public void grade_followsTheGradingTable() {
        Object[][] cases = {
            // status, failure, requires a 2xx, expected e (null when reached)
            { 200, null, false, null }, { 204, null, false, null }, { 400, null, false, null }, { 404, null, false, null },
            { 499, null, false, null }, { 403, null, false, "HTTP 403" }, { 500, null, false, "HTTP 500" }, { 502, null, false, "HTTP 502" },
            { 503, null, false, "HTTP 503" }, { 301, null, false, "redirected" }, { 302, null, false, "redirected" },
            { 307, null, false, "redirected" }, { 308, null, false, "redirected" }, { 100, null, false, "HTTP 100" },
            { 0, "timeout", false, "timeout" }, { 0, "transport error", false, "transport error" }, { 0, null, false, "transport error" },
            { -1, null, false, "transport error" },
            { 200, null, true, null }, { 299, null, true, null }, { 400, null, true, "HTTP 400" }, { 404, null, true, "HTTP 404" },
            { 403, null, true, "HTTP 403" }, { 302, null, true, "redirected" }, { 500, null, true, "HTTP 500" }, { 0, "timeout", true, "timeout" },
        };
        for (Object[] c : cases) {
            ProbeOutcome outcome = new ProbeOutcome();
            outcome.status = (int) c[0];
            outcome.failure = (String) c[1];
            Assert.assertEquals("status " + c[0] + ", requires a 2xx " + c[2], c[3], ModuleConnectionTest.grade(outcome, (boolean) c[2]));
        }
    }

    /**
     * Probe URLs.
     * <p>
     * Verifies that a path resolves against the configured URL with its path prefix kept, trailing
     * slashes stripped and exactly one slash at the join, that the marker and the cache buster follow
     * a {@code ?}, or an {@code &} after the method selector of a path that has one, and that the
     * cache buster is the current time.
     */
    @Test
    public void buildProbeUrl_keepsThePathPrefix_stripsTrailingSlashes_andAppendsTheMarkerAndTheCacheBuster() {
        long before = System.currentTimeMillis();
        String plain = ModuleConnectionTest.buildProbeUrl("https://x.com", "/o/ping");
        long after = System.currentTimeMillis();
        Assert.assertTrue(plain, plain.startsWith("https://x.com/o/ping?ct=1&_="));
        long cacheBuster = Long.parseLong(plain.substring(plain.indexOf("_=") + 2));
        Assert.assertTrue(cacheBuster >= before && cacheBuster <= after);

        Assert.assertTrue(ModuleConnectionTest.buildProbeUrl("https://x.com/countly", "/o/ping").startsWith("https://x.com/countly/o/ping?ct=1&_="));
        Assert.assertTrue(ModuleConnectionTest.buildProbeUrl("https://x.com/countly/", "/o/ping").startsWith("https://x.com/countly/o/ping?ct=1&_="));
        Assert.assertTrue(ModuleConnectionTest.buildProbeUrl("https://x.com/countly///", "o/ping").startsWith("https://x.com/countly/o/ping?ct=1&_="));
        Assert.assertTrue(ModuleConnectionTest.buildProbeUrl("http://127.0.0.1:8080/a/b/", "/_external/content/").startsWith("http://127.0.0.1:8080/a/b/_external/content/?ct=1&_="));

        String withSelector = ModuleConnectionTest.buildProbeUrl("https://x.com/countly/", "/o/sdk?method=rc");
        Assert.assertTrue(withSelector, withSelector.matches("https://x\\.com/countly/o/sdk\\?method=rc&ct=1&_=\\d+"));
    }

    /**
     * The caps of the report.
     * <p>
     * Verifies that a report keeps at most 32 rows, that one over 8 KB loses every {@code e} except
     * {@code opaque} before any row, that a small one keeps its {@code e} cut at 256 characters, and
     * that rows too large even without their {@code e} are dropped from the end, the size counted in
     * UTF-8 bytes rather than characters.
     */
    @Test
    public void buildReport_keepsAtMost32Rows_within8KB_dropsErrorsBeforeRows_andOpaqueSurvives() {
        init(TestUtils.getBaseConfig());
        ModuleConnectionTest module = connectionTest();
        String longError = repeat('x', 300);

        List<ResultRow> rows = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            rows.add(resultRow("row" + i, false, 403, i, longError, 1));
        }
        rows.set(5, resultRow("opaque-row", true, 0, 3, "opaque", 1));

        JSONObject report = new JSONObject(module.buildReport(rows));
        JSONArray results = report.getJSONArray("results");
        Assert.assertEquals(32, results.length());
        Assert.assertTrue(ModuleConnectionTest.utf8Length(report.toString()) <= 8 * 1024);
        for (int i = 0; i < results.length(); i++) {
            JSONObject row = results.getJSONObject(i);
            if (i == 5) {
                Assert.assertEquals("opaque-row", row.getString("f"));
                Assert.assertEquals("opaque", row.getString("e"));
            } else {
                Assert.assertEquals("row" + i, row.getString("f"));
                Assert.assertFalse(row.has("e"));
                Assert.assertEquals(403, row.getInt("st"));
                Assert.assertEquals(i, row.getLong("ms"));
            }
        }

        JSONObject small = new JSONObject(module.buildReport(Collections.singletonList(resultRow("core", false, 500, 10, longError, 1))));
        JSONObject row = small.getJSONArray("results").getJSONObject(0);
        Assert.assertEquals(longError.substring(0, 256), row.getString("e"));
        Assert.assertEquals(500, row.getInt("st"));
        Assert.assertFalse(row.has("n"));
        Assert.assertTrue(small.getLong("ts") > 0);
        Assert.assertEquals(TestUtils.SDK_NAME, small.getJSONObject("sdk").getString("name"));

        List<ResultRow> wide = new ArrayList<>();
        JSONArray untrimmed = new JSONArray();
        for (int i = 0; i < 32; i++) {
            ResultRow wideRow = resultRow(i + "-" + repeat('ü', 150), true, 200, 0, "opaque", 1);
            wide.add(wideRow);
            untrimmed.put(ModuleConnectionTest.rowToJson(wideRow));
        }
        Assert.assertTrue("32 rows fit by characters", untrimmed.toString().length() < 8 * 1024);
        JSONObject trimmed = new JSONObject(module.buildReport(wide));
        JSONArray kept = trimmed.getJSONArray("results");
        Assert.assertTrue("kept " + kept.length(), kept.length() > 0 && kept.length() < 32);
        Assert.assertTrue(ModuleConnectionTest.utf8Length(trimmed.toString()) <= 8 * 1024);
        for (int i = 0; i < kept.length(); i++) {
            Assert.assertTrue(kept.getJSONObject(i).getString("f").startsWith(i + "-"));
            Assert.assertEquals("opaque", kept.getJSONObject(i).getString("e"));
        }
    }

    /**
     * A battery without a measured settings request.
     * <p>
     * Verifies that the {@code sc} row is left out rather than invented, that the other rows keep
     * their order, and that a combined row sums the latencies of its paths and reports how many it
     * probed, which a single path row leaves out of its JSON.
     */
    @Test
    public void runBattery_withoutAScLatency_leavesTheScRowOut_andCombinesEveryMultiPathRow() {
        init(TestUtils.getBaseConfig());
        ModuleConnectionTest module = connectionTest();
        module.transport = FakeTransport.instant(2);

        List<ResultRow> rows = module.runBattery(-1);

        List<String> expected = new ArrayList<>(Arrays.asList(EXPECTED_ORDER));
        expected.remove("sc");
        List<String> features = new ArrayList<>();
        Map<String, ResultRow> byFeature = new HashMap<>();
        for (ResultRow row : rows) {
            features.add(row.feature);
            byFeature.put(row.feature, row);
            Assert.assertTrue(row.feature, row.ok);
            Assert.assertNull(row.feature, row.error);
        }
        Assert.assertEquals(expected, features);
        assertCombined(byFeature.get("feedback-widget"), 400, 6, 3);
        assertCombined(byFeature.get("feedback-page"), 400, 6, 3);
        assertCombined(byFeature.get("feedback-assets"), 200, 4, 2);
        assertCombined(byFeature.get("core"), 200, 2, 1);
        Assert.assertFalse(ModuleConnectionTest.rowToJson(byFeature.get("core")).has("n"));
        Assert.assertEquals(3, ModuleConnectionTest.rowToJson(byFeature.get("feedback-widget")).getInt("n"));
    }

    /**
     * The connection a probe is sent on.
     * <p>
     * Verifies that it is a bare GET that follows no redirect, without touching the default of
     * every connection, uses no cache and has the given timeouts, that it carries the custom request
     * headers with a usable name and value, that an HTTPS probe verifies the server against the pins
     * only when pins are configured, and that the plain connection used without a transport is the
     * same bare GET without any header.
     */
    @Test
    public void probeConnection_isABareGet_withTheCustomHeaders_andThePinsOnlyWhenPinned() throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Countly-Test", "yes");
        headers.put("", "a header without a name");
        headers.put("X-Null-Value", null);
        Transport unpinned = initializedTransport(TestUtils.getBaseConfig().addCustomNetworkRequestHeaders(headers));

        HttpURLConnection probe = unpinned.openProbeConnection("http://127.0.0.1:9" + PREFIX + "/o/ping?ct=1&_=5", 1_234);
        assertBareGet(probe, 1_234);
        Assert.assertTrue(HttpURLConnection.getFollowRedirects());
        Assert.assertEquals("yes", probe.getRequestProperty("X-Countly-Test"));
        Assert.assertEquals(1, probe.getRequestProperties().size());

        HttpsURLConnection unpinnedHttps = (HttpsURLConnection) unpinned.openProbeConnection("https://127.0.0.1:9/o/ping", 100);
        Assert.assertSame(HttpsURLConnection.getDefaultSSLSocketFactory(), unpinnedHttps.getSSLSocketFactory());

        Transport pinned = initializedTransport(TestUtils.getBaseConfig().addPublicKeyPin(generatedPublicKeyPin()));
        HttpsURLConnection pinnedHttps = (HttpsURLConnection) pinned.openProbeConnection("https://127.0.0.1:9/o/ping", 100);
        assertBareGet(pinnedHttps, 100);
        Assert.assertNotSame(HttpsURLConnection.getDefaultSSLSocketFactory(), pinnedHttps.getSSLSocketFactory());
        Assert.assertTrue(pinnedHttps.getRequestProperties().isEmpty());

        HttpURLConnection bare = Transport.openBareGetConnection("http://127.0.0.1:9/o/ping", 77);
        assertBareGet(bare, 77);
        Assert.assertTrue(bare.getRequestProperties().isEmpty());
    }

    /**
     * The default probe transport, used without a transport of the SDK.
     * <p>
     * Verifies against the local server that it reads the real status of a 2xx, a 4xx and a 3xx,
     * which it never follows, that it reads a body larger than it drains, that a silent endpoint
     * becomes a timeout with status 0 after at least the timeout, and that a refused connection
     * becomes a transport error with status 0.
     */
    @Test
    public void httpProbe_readsTheRealStatus_neverFollowsARedirect_andMapsEveryFailureToItsReason() throws Exception {
        server.overrides.put(PREFIX + "/moved", Answer.redirect(PREFIX + "/o/ping"));
        server.overrides.put(PREFIX + "/large", Answer.page(200, "text/html", repeat('x', 200 * 1024)));
        server.overrides.put(PREFIX + "/silent", Answer.SILENT);
        ModuleConnectionTest.HttpProbeTransport http = new ModuleConnectionTest.HttpProbeTransport();

        assertOutcome(http.probe(server.url(PREFIX + "/o/ping?ct=1"), 2_000, null), 200, null);
        assertOutcome(http.probe(server.url(PREFIX + "/i?ct=1"), 2_000, null), 400, null);
        assertOutcome(http.probe(server.url(PREFIX + "/o/feedback/widget?ct=1"), 2_000, null), 404, null);
        assertOutcome(http.probe(server.url(PREFIX + "/moved?ct=1"), 2_000, null), 302, null);
        assertOutcome(http.probe(server.url(PREFIX + "/large?ct=1"), 2_000, null), 200, null);
        Assert.assertEquals(1, probesTo(PREFIX + "/o/ping"));

        ProbeOutcome silent = http.probe(server.url(PREFIX + "/silent?ct=1"), 300, null);
        assertOutcome(silent, 0, "timeout");
        Assert.assertTrue("ms " + silent.ms, silent.ms >= 300);

        assertOutcome(http.probe(deadUrl() + "/o/ping?ct=1", 2_000, null), 0, "transport error");
    }

    /**
     * The rules of the request queue that the report is subject to.
     * <p>
     * Verifies that the module is built and the report queued while consent is required and none is
     * given, as the probes carry no identity, and that while the settings switch tracking off the
     * battery still runs but the queue drops its report.
     */
    @Test
    public void report_isQueuedWithoutAnyConsent_butDroppedWhileTrackingIsOff() throws Exception {
        settings.respondWith(new ServerConfigBuilder().connectionTest(1));
        init(config(server.url(PREFIX)).setRequiresConsent(true).enableFeatures(Config.Feature.Events));
        ModuleConnectionTest module = connectionTest();
        awaitBatteryIdle(module);

        Assert.assertTrue(SDKCore.instance.config.requiresConsent());
        Assert.assertFalse(Countly.isTracking(Config.Feature.Events));
        Assert.assertSame(module, SDKCore.instance.modules.get(0));
        Assert.assertEquals(16, server.probes.size());
        Assert.assertEquals(1, queuedReports().size());

        push(new ServerConfigBuilder().tracking(false).connectionTest(1));
        awaitBatteryIdle(module);

        Assert.assertEquals(32, server.probes.size());
        Assert.assertEquals(1, queuedReports().size());
        Assert.assertEquals(1, logsContaining("[ModuleRequests] pushAsync, tracking disabled by SDK behavior settings; dropping request"));
    }

    // endregion
    // region helpers

    /**
     * One request as the local server saw it.
     */
    private static final class Received {
        final String method;
        final String path;
        final String rawQuery;
        final int bodyLength;
        final Map<String, String> headers = new HashMap<>();
        final Map<String, String> params = new LinkedHashMap<>();

        /**
         * Records a request as it arrives: its method, path, query, body, headers by lower case
         * name, and its parameters, decoded from the query and the body.
         *
         * @param exchange the request
         * @throws IOException when the body cannot be read
         */
        Received(HttpExchange exchange) throws IOException {
            method = exchange.getRequestMethod();
            path = exchange.getRequestURI().getPath();
            rawQuery = exchange.getRequestURI().getRawQuery();
            byte[] body = readAll(exchange.getRequestBody());
            bodyLength = body.length;
            for (Map.Entry<String, List<String>> header : exchange.getRequestHeaders().entrySet()) {
                if (!header.getValue().isEmpty()) {
                    headers.put(header.getKey().toLowerCase(Locale.ROOT), header.getValue().get(0));
                }
            }
            addParams(rawQuery);
            addParams(new String(body, StandardCharsets.UTF_8));
        }

        /**
         * Decodes url encoded parameters into {@link #params}.
         *
         * @param encoded the parameters, {@code null} for none
         */
        private void addParams(String encoded) {
            if (encoded == null || encoded.isEmpty()) {
                return;
            }
            for (String pair : encoded.split("&")) {
                String[] keyValue = pair.split("=", 2);
                params.put(Utils.urldecode(keyValue[0]), keyValue.length > 1 ? Utils.urldecode(keyValue[1]) : "");
            }
        }
    }

    /**
     * How the local server answers a request.
     */
    private static final class Answer {
        static final Answer SILENT = new Answer(0, null, "", null, true);

        final int status;
        final String contentType;
        final String body;
        final String location;
        final boolean silent;

        /**
         * Describes an answer.
         *
         * @param status the status
         * @param contentType the content type
         * @param body the body, empty for none
         * @param location the redirect target, {@code null} for none
         * @param silent whether the server holds the request without ever answering it
         */
        private Answer(int status, String contentType, String body, String location, boolean silent) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
            this.location = location;
            this.silent = silent;
        }

        /**
         * A JSON answer.
         *
         * @param status the status
         * @param body the JSON text
         * @return the answer
         */
        static Answer json(int status, String body) {
            return new Answer(status, "application/json", body, null, false);
        }

        /**
         * A page, an image or any other body.
         *
         * @param status the status
         * @param contentType the content type
         * @param body the body
         * @return the answer
         */
        static Answer page(int status, String contentType, String body) {
            return new Answer(status, contentType, body, null, false);
        }

        /**
         * A 302 to the given location, without a body.
         *
         * @param location the redirect target
         * @return the answer
         */
        static Answer redirect(String location) {
            return new Answer(302, "text/html", "", location, false);
        }
    }

    /**
     * A local stand in for a Countly server behind {@link #PREFIX}. A probe gets the answer a
     * healthy server gives a request without app key and device ID, unless a test overrides its
     * path: {@code /o/ping} 200, {@code /o/feedback/widget} 404, the frontend pages 200 or 400, the
     * images 200 and every other API path 400. The SDK's own requests, which carry the app key, are
     * recorded and refused, so they stay queued. Handlers run on a thread pool, so a silent answer
     * never holds up the next probe.
     */
    private static final class ProbeServer {
        final List<Received> probes = new CopyOnWriteArrayList<>();
        final List<Received> sdkRequests = new CopyOnWriteArrayList<>();
        final Map<String, Answer> overrides = new ConcurrentHashMap<>();
        private final CountDownLatch silenceEnds = new CountDownLatch(1);
        private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "ct-probe-server");
            thread.setDaemon(true);
            return thread;
        });
        private final HttpServer http;

        /**
         * Starts the server on a free loopback port.
         *
         * @throws IOException when the server cannot start
         */
        ProbeServer() throws IOException {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.createContext("/", this::handle);
            http.setExecutor(executor);
            http.start();
        }

        /**
         * The URL of a path on this server.
         *
         * @param path the path, with any query
         * @return the URL
         */
        String url(String path) {
            return "http://127.0.0.1:" + http.getAddress().getPort() + path;
        }

        /**
         * Records a request and answers it.
         *
         * @param exchange the request and its response
         * @throws IOException when the answer cannot be written
         */
        private void handle(HttpExchange exchange) throws IOException {
            Received received = new Received(exchange);
            if (received.params.containsKey("app_key")) {
                sdkRequests.add(received);
                send(exchange, Answer.json(500, "{\"result\":\"refused by the test server\"}"));
                return;
            }

            probes.add(received);
            Answer override = overrides.get(received.path);
            send(exchange, override != null ? override : healthyAnswer(received.path));
        }

        /**
         * What a healthy server answers a bare request to a path.
         *
         * @param path the request path
         * @return the answer
         */
        private static Answer healthyAnswer(String path) {
            if (!path.startsWith(PREFIX + "/")) {
                return Answer.page(404, "text/html", "no such location");
            }

            switch (path.substring(PREFIX.length())) {
                case "/o/ping":
                    return Answer.json(200, "{\"result\":\"Success\"}");
                case "/o/feedback/widget":
                    return Answer.json(404, "{\"result\":\"Widget not found\"}");
                case "/feedback/nps":
                case "/feedback/survey":
                case "/_external/content/":
                    return Answer.page(200, "text/html", "<html></html>");
                case "/feedback/rating":
                    return Answer.page(400, "text/html", "missing widget id");
                case "/surveys/images/ct-probe.png":
                case "/star-rating/images/ct-probe.png":
                    return Answer.page(200, "image/png", "png");
                default:
                    return Answer.json(400, MISSING_PARAMETER);
            }
        }

        /**
         * Writes an answer, or holds the request until the server stops for a silent one.
         *
         * @param exchange the request and its response
         * @param answer the answer
         * @throws IOException when the answer cannot be written
         */
        private void send(HttpExchange exchange, Answer answer) throws IOException {
            if (answer.silent) {
                try {
                    silenceEnds.await(WAIT_BUDGET_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                exchange.close();
                return;
            }

            byte[] body = answer.body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", answer.contentType);
            if (answer.location != null) {
                exchange.getResponseHeaders().set("Location", answer.location);
            }
            exchange.sendResponseHeaders(answer.status, body.length == 0 ? -1 : body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }

        /**
         * Lets every silent request go, then stops the server and its handler threads.
         */
        void stop() {
            silenceEnds.countDown();
            http.stop(0);
            executor.shutdownNow();
        }
    }

    /**
     * A probe transport without the network that answers like a healthy server, 200 for
     * {@code /o/ping} and the images and 400 for everything else. It records every probe with the
     * thread it ran on and the transport it was handed, and can hold every probe at a gate or take
     * a given time.
     */
    private static final class FakeTransport implements ModuleConnectionTest.ProbeTransport {
        final List<String> urls = new CopyOnWriteArrayList<>();
        final List<Transport> transports = new CopyOnWriteArrayList<>();
        final Set<String> threadNames = ConcurrentHashMap.newKeySet();
        final List<Boolean> daemonFlags = new CopyOnWriteArrayList<>();
        final CountDownLatch firstProbeStarted = new CountDownLatch(1);
        final CountDownLatch gate;
        private final long takesMs;
        private final long reportedMs;

        /**
         * Describes the fake.
         *
         * @param gated whether every probe waits at {@link #gate} until a test opens it
         * @param takesMs how long every probe takes, in milliseconds
         * @param reportedMs the latency every probe reports, in milliseconds
         */
        private FakeTransport(boolean gated, long takesMs, long reportedMs) {
            this.gate = new CountDownLatch(gated ? 1 : 0);
            this.takesMs = takesMs;
            this.reportedMs = reportedMs;
        }

        /**
         * A fake whose probes answer at once.
         *
         * @param reportedMs the latency every probe reports, in milliseconds
         * @return the fake
         */
        static FakeTransport instant(long reportedMs) {
            return new FakeTransport(false, 0, reportedMs);
        }

        /**
         * A fake whose probes wait at the gate.
         *
         * @return the fake
         */
        static FakeTransport gated() {
            return new FakeTransport(true, 0, 1);
        }

        /**
         * A fake whose probes take the given time.
         *
         * @param takesMs how long every probe takes, in milliseconds
         * @return the fake
         */
        static FakeTransport slow(long takesMs) {
            return new FakeTransport(false, takesMs, takesMs);
        }

        /**
         * Records the probe, waits at the gate and for the time a probe takes, then answers.
         *
         * @param url the probe URL
         * @param timeoutMs the per request timeout
         * @param transport the transport of the queued requests
         * @return the answer of a healthy server
         */
        @Nonnull
        @Override
        public ProbeOutcome probe(@Nonnull String url, int timeoutMs, @Nullable Transport transport) {
            urls.add(url);
            transports.add(transport);
            threadNames.add(Thread.currentThread().getName());
            daemonFlags.add(Thread.currentThread().isDaemon());
            firstProbeStarted.countDown();
            try {
                gate.await(WAIT_BUDGET_MS, TimeUnit.MILLISECONDS);
                if (takesMs > 0) {
                    Thread.sleep(takesMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            ProbeOutcome outcome = new ProbeOutcome();
            outcome.status = url.contains("/o/ping?") || url.contains(".png?") ? 200 : 400;
            outcome.ms = reportedMs;
            return outcome;
        }
    }

    /**
     * A configuration pointing at the given server URL, with no request cooldowns and every log line
     * kept in {@link #logs}.
     *
     * @param serverUrl the server URL
     * @return the configuration
     */
    private Config config(String serverUrl) {
        File directory = TestUtils.getTestSDirectory();
        TestUtils.checkSdkStorageRootDirectoryExist(directory);
        return new Config(serverUrl, TestUtils.SERVER_APP_KEY, directory)
            .setApplicationVersion(TestUtils.APPLICATION_VERSION)
            .setCustomDeviceId(TestUtils.DEVICE_ID)
            .setNetworkRequestCooldown(0)
            .setNetworkImportantRequestCooldown(0)
            .setLogListener((message, level) -> logs.add(message));
    }

    /**
     * Initializes the SDK with {@link #settings} answering its settings requests.
     *
     * @param config the configuration
     */
    private void init(Config config) {
        InternalConfig internalConfig = new InternalConfig(config);
        internalConfig.immediateRequestGenerator = settings;
        Countly.instance().init(internalConfig);
    }

    /**
     * Answers the next settings request with the given payload and makes the SDK fetch, as the
     * refresh timer does. The response is applied, and the test armed, before this returns.
     *
     * @param builder the payload
     */
    private void push(ServerConfigBuilder builder) {
        settings.respondWith(builder);
        SDKCore.instance.module(ModuleConfiguration.class).fetchConfigFromServer(SDKCore.instance.config);
    }

    /**
     * The connection test module of the running SDK.
     *
     * @return the module
     */
    private static ModuleConnectionTest connectionTest() {
        return SDKCore.instance.module(ModuleConnectionTest.class);
    }

    /**
     * The settings in effect in the running SDK.
     *
     * @return the provider
     */
    private static ConfigurationProvider provider() {
        return SDKCore.instance.config.getConfigurationProvider();
    }

    /**
     * The settings as stored on disk.
     *
     * @return the stored settings, {@code null} when nothing is stored
     */
    private static JSONObject storedConfig() {
        String stored = TestUtils.readJsonFile(SDKStorage.JSON_FILE_NAME).optString("sc", null);
        return stored == null ? null : new JSONObject(stored);
    }

    /**
     * The listener a configuration module hands connection test requests to.
     *
     * @param configuration the configuration module
     * @return the listener, {@code null} for none
     * @throws ReflectiveOperationException when the field cannot be read
     */
    private static Object listenerOf(ModuleConfiguration configuration) throws ReflectiveOperationException {
        Field field = ModuleConfiguration.class.getDeclaredField("connectionTestListener");
        field.setAccessible(true);
        return field.get(configuration);
    }

    /**
     * Waits until no battery runs.
     *
     * @param module the connection test module
     * @throws InterruptedException when the wait is interrupted
     */
    private static void awaitBatteryIdle(ModuleConnectionTest module) throws InterruptedException {
        Assert.assertTrue("the battery did not finish in time", waitFor(() -> !module.batteryRunning.get()));
    }

    /**
     * Every queued request that carries a report.
     *
     * @return the parameters of each, in queue order
     */
    private static List<Map<String, String>> queuedReports() {
        List<Map<String, String>> reports = new ArrayList<>();
        for (Map<String, String> request : TestUtils.getCurrentRQ()) {
            if (request.containsKey(keyResults)) {
                reports.add(request);
            }
        }
        return reports;
    }

    /**
     * The one queued request that carries a report.
     *
     * @return its parameters
     */
    private static Map<String, String> onlyQueuedReport() {
        List<Map<String, String>> reports = queuedReports();
        Assert.assertEquals("queued reports", 1, reports.size());
        return reports.get(0);
    }

    /**
     * The requests carrying a report that the queue sent to the local server.
     *
     * @return the requests, in arrival order
     */
    private List<Received> reportsSentToTheServer() {
        List<Received> sent = new ArrayList<>();
        for (Received request : server.sdkRequests) {
            if (request.params.containsKey(keyResults)) {
                sent.add(request);
            }
        }
        return sent;
    }

    /**
     * How many probes reached a path.
     *
     * @param path the path
     * @return the number of probes
     */
    private int probesTo(String path) {
        int count = 0;
        for (Received probe : server.probes) {
            if (path.equals(probe.path)) {
                count++;
            }
        }
        return count;
    }

    /**
     * How many log lines contain a text.
     *
     * @param text the text
     * @return the number of lines
     */
    private int logsContaining(String text) {
        int count = 0;
        for (String line : logs) {
            if (line.contains(text)) {
                count++;
            }
        }
        return count;
    }

    /**
     * The rows of a report by feature key.
     *
     * @param report the report
     * @return the rows
     */
    private static Map<String, JSONObject> rowsByFeature(JSONObject report) {
        JSONArray results = report.getJSONArray("results");
        Map<String, JSONObject> rows = new HashMap<>();
        for (int i = 0; i < results.length(); i++) {
            JSONObject row = results.getJSONObject(i);
            rows.put(row.getString("f"), row);
        }
        return rows;
    }

    /**
     * Asserts that a report holds the twelve rows in table order.
     *
     * @param report the report
     */
    private static void assertRowOrder(JSONObject report) {
        JSONArray results = report.getJSONArray("results");
        Assert.assertEquals(EXPECTED_ORDER.length, results.length());
        for (int i = 0; i < EXPECTED_ORDER.length; i++) {
            Assert.assertEquals(EXPECTED_ORDER[i], results.getJSONObject(i).getString("f"));
        }
    }

    /**
     * Asserts one report row, which carries only the report keys and never {@code sk}.
     *
     * @param row the row
     * @param ok the expected verdict
     * @param st the expected status
     * @param e the expected reason, {@code null} when the row has none
     * @param n the expected path count, {@code null} when the row has none
     */
    private static void assertRow(JSONObject row, boolean ok, int st, String e, Integer n) {
        Assert.assertNotNull("missing row", row);
        String label = row.getString("f");
        Assert.assertTrue(label + " keys " + row.keySet(), ROW_KEYS.containsAll(row.keySet()));
        Assert.assertEquals(label + " ok", ok, row.getBoolean("ok"));
        Assert.assertEquals(label + " st", st, row.getInt("st"));
        Assert.assertTrue(label + " ms", row.getLong("ms") >= 0);
        if (e == null) {
            Assert.assertFalse(label + " has e", row.has("e"));
        } else {
            Assert.assertEquals(label + " e", e, row.getString("e"));
        }
        if (n == null) {
            Assert.assertFalse(label + " has n", row.has("n"));
        } else {
            Assert.assertEquals(label + " n", (int) n, row.getInt("n"));
        }
    }

    /**
     * Asserts a graded row that reached every path.
     *
     * @param row the row
     * @param status the expected status
     * @param ms the expected summed latency
     * @param pathCount the expected number of paths
     */
    private static void assertCombined(ResultRow row, int status, long ms, int pathCount) {
        Assert.assertTrue(row.feature, row.ok);
        Assert.assertEquals(row.feature, status, row.status);
        Assert.assertEquals(row.feature, ms, row.ms);
        Assert.assertEquals(row.feature, pathCount, row.pathCount);
    }

    /**
     * Asserts what a probe observed.
     *
     * @param outcome the outcome
     * @param status the expected status
     * @param failure the expected failure, {@code null} for none
     */
    private static void assertOutcome(ProbeOutcome outcome, int status, String failure) {
        Assert.assertEquals(status, outcome.status);
        Assert.assertEquals(failure, outcome.failure);
        Assert.assertTrue(outcome.ms >= 0);
    }

    /**
     * Asserts that a connection is a bare GET with the given timeouts.
     *
     * @param connection the connection, not connected yet
     * @param timeoutMs the expected connect and read timeout
     */
    private static void assertBareGet(HttpURLConnection connection, int timeoutMs) {
        Assert.assertEquals("GET", connection.getRequestMethod());
        Assert.assertFalse(connection.getInstanceFollowRedirects());
        Assert.assertFalse(connection.getUseCaches());
        Assert.assertTrue(connection.getDoInput());
        Assert.assertFalse(connection.getDoOutput());
        Assert.assertEquals(timeoutMs, connection.getConnectTimeout());
        Assert.assertEquals(timeoutMs, connection.getReadTimeout());
    }

    /**
     * A graded row.
     *
     * @param feature the feature key
     * @param ok the verdict
     * @param status the status
     * @param ms the latency
     * @param error the reason, {@code null} for none
     * @param pathCount the number of paths
     * @return the row
     */
    private static ResultRow resultRow(String feature, boolean ok, int status, long ms, String error, int pathCount) {
        ResultRow row = new ResultRow(feature);
        row.ok = ok;
        row.status = status;
        row.ms = ms;
        row.error = error;
        row.pathCount = pathCount;
        return row;
    }

    /**
     * A transport initialized with the given configuration.
     *
     * @param config the configuration
     * @return the transport
     */
    private static Transport initializedTransport(Config config) {
        Transport transport = new Transport();
        transport.init(TestUtils.getInternalConfigWithLogger(config));
        return transport;
    }

    /**
     * A public key pin for a key generated for the test, base64 encoded as a pin may be given.
     *
     * @return the pin
     * @throws Exception when no key can be generated
     */
    private static String generatedPublicKeyPin() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded());
    }

    /**
     * The URL of a loopback port nothing listens on.
     *
     * @return the URL, with {@link #PREFIX}
     * @throws IOException when no free port can be found
     */
    private static String deadUrl() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return "http://127.0.0.1:" + socket.getLocalPort() + PREFIX;
        }
    }

    /**
     * A text of one character repeated.
     *
     * @param character the character
     * @param count how many times
     * @return the text
     */
    private static String repeat(char character, int count) {
        char[] characters = new char[count];
        Arrays.fill(characters, character);
        return new String(characters);
    }

    /**
     * Reads a stream to its end.
     *
     * @param stream the stream
     * @return the bytes
     * @throws IOException when the stream cannot be read
     */
    private static byte[] readAll(InputStream stream) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            bytes.write(buffer, 0, read);
        }
        return bytes.toByteArray();
    }

    /**
     * Polls a condition until it holds or the wait budget runs out.
     *
     * @param condition the condition
     * @return whether it held
     * @throws InterruptedException when the wait is interrupted
     */
    private static boolean waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + WAIT_BUDGET_MS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(POLL_MS);
        }
        return condition.getAsBoolean();
    }

    // endregion
}
