package ly.count.sdk.java.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.mockito.Mockito.mock;

@RunWith(JUnit4.class)
public class ModuleHealthCheckTests {

    /**
     * One immediate request the SDK made.
     */
    static class SentRequest {
        final String data;
        final String endpoint;

        SentRequest(String data, String endpoint) {
            this.data = data;
            this.endpoint = endpoint;
        }

        Map<String, String> params() {
            Map<String, String> decoded = new HashMap<>();
            for (Map.Entry<String, String> param : TestUtils.parseQueryParams(data).entrySet()) {
                decoded.put(param.getKey(), Utils.urldecode(param.getValue()));
            }
            return decoded;
        }

        JSONObject healthCheck() {
            return new JSONObject(params().get("hc"));
        }
    }

    /**
     * An in-memory storage for the counter tests.
     */
    static class MemoryStorage implements StorageProvider {
        String healthCheckState = null;
        int automaticCrashes = 0;

        @Override public String getDeviceID() {
            return null;
        }

        @Override public void setDeviceID(String deviceID) {
        }

        @Override public String getDeviceIdType() {
            return null;
        }

        @Override public void setDeviceIdType(String deviceIdTypeString) {
        }

        @Override public void setRemoteConfigValues(JSONObject remoteConfigValues) {
        }

        @Override public JSONObject getRemoteConfigValues() {
            return new JSONObject();
        }

        @Override public Integer getMigrationVersion() {
            return 0;
        }

        @Override public void setMigrationVersion(Integer migrationVersion) {
        }

        @Override public boolean isCountlyStorageEmpty() {
            return true;
        }

        @Override public void setServerConfig(String config) {
        }

        @Override public String getServerConfig() {
            return null;
        }

        @Override public String getHealthCheckState() {
            return healthCheckState;
        }

        @Override public void setHealthCheckState(String state) {
            healthCheckState = state;
        }

        @Override public void incrementAutomaticCrashCount() {
            automaticCrashes++;
        }

        @Override public int takeAutomaticCrashCount() {
            int count = automaticCrashes;
            automaticCrashes = 0;
            return count;
        }
    }

    private final List<SentRequest> sentRequests = new ArrayList<>();
    private JSONObject serverAnswer = new JSONObject().put("result", "Success");

    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
        //a start takes the log codes other tests recorded while no SDK ran, so each test begins without them
        Countly.instance().init(TestUtils.getBaseConfig());
        TestUtils.createCleanTestState();
        sentRequests.clear();
        serverAnswer = new JSONObject().put("result", "Success");
    }

    @After
    public void stop() {
        Countly.instance().halt();
    }

    private HealthCheckCounter counter(MemoryStorage storage, Predicate<CoreFeature> consent) {
        return new HealthCheckCounter(storage, mock(Log.class), consent);
    }

    private HealthCheckCounter counter(MemoryStorage storage) {
        return counter(storage, feature -> true);
    }

    /**
     * A configuration whose health check is on, as the test base configuration turns it off, with
     * an immediate request generator that keeps every request and answers with {@link #serverAnswer}.
     */
    private InternalConfig healthCheckConfig() {
        Config config = new Config(TestUtils.SERVER_URL, TestUtils.SERVER_APP_KEY, TestUtils.getTestSDirectory())
            .setApplicationVersion(TestUtils.APPLICATION_VERSION)
            .setCustomDeviceId(TestUtils.DEVICE_ID)
            .disableSdkBehaviorSettingsUpdates()
            .enableFeatures(Config.Feature.Events, Config.Feature.Sessions, Config.Feature.Views, Config.Feature.CrashReporting);
        return withRecordingServer(new InternalConfig(config));
    }

    private InternalConfig withRecordingServer(InternalConfig config) {
        config.immediateRequestGenerator = () -> (requestData, customEndpoint, cp, requestShouldBeDelayed, networkingIsEnabled, callback, log) -> {
            sentRequests.add(new SentRequest(requestData, customEndpoint));
            callback.callback(serverAnswer);
        };
        return config;
    }

    private List<SentRequest> healthChecks() {
        List<SentRequest> found = new ArrayList<>();
        for (SentRequest request : sentRequests) {
            if (request.params().containsKey("hc")) {
                found.add(request);
            }
        }
        return found;
    }

    private HealthCheckCounter runningCounter() {
        return SDKCore.instance.module(ModuleHealthCheck.class).counter();
    }

    private static int usage(JSONObject snapshot, String area, String method) {
        JSONObject methods = snapshot.getJSONObject("fu").optJSONObject(area);
        return methods == null ? 0 : methods.optInt(method, 0);
    }

    private static int code(JSONObject snapshot, String code) {
        return snapshot.getJSONObject("lc").optInt(code, 0);
    }

    /**
     * A new counter reports the defaults, with "fu" and "lc" present as empty objects.
     */
    @Test
    public void counter_defaults() {
        JSONObject snapshot = counter(new MemoryStorage()).snapshot();
        Assert.assertEquals(0, snapshot.getLong("el"));
        Assert.assertEquals(0, snapshot.getLong("wl"));
        Assert.assertEquals(-1, snapshot.getInt("sc"));
        Assert.assertEquals("", snapshot.getString("em"));
        Assert.assertEquals(0, snapshot.getLong("bom"));
        Assert.assertEquals(0, snapshot.getInt("cbom"));
        Assert.assertEquals(0, snapshot.getJSONObject("fu").length());
        Assert.assertEquals(0, snapshot.getJSONObject("lc").length());
    }

    /**
     * A stored state is restored, entries of the wrong type are skipped, and the saved state uses
     * the storage keys of the guide.
     */
    @Test
    public void counter_restoresAndSavesState() {
        MemoryStorage storage = new MemoryStorage();
        storage.healthCheckState = "{\"LErr\":2,\"LWar\":3,\"RStatC\":503,\"REMsg\":\"Service Unavailable\",\"BReq\":4,\"CBReq\":2,"
            + "\"MU\":{\"events\":{\"record\":5,\"start\":\"x\"},\"views\":7},\"LC\":{\"w740\":1,\"w720\":\"y\"}}";

        HealthCheckCounter counter = counter(storage);
        JSONObject snapshot = counter.snapshot();
        Assert.assertEquals(2, snapshot.getLong("el"));
        Assert.assertEquals(3, snapshot.getLong("wl"));
        Assert.assertEquals(503, snapshot.getInt("sc"));
        Assert.assertEquals("Service Unavailable", snapshot.getString("em"));
        Assert.assertEquals(4, snapshot.getLong("bom"));
        Assert.assertEquals(2, snapshot.getInt("cbom"));
        Assert.assertEquals(5, usage(snapshot, "events", "record"));
        Assert.assertEquals(0, usage(snapshot, "events", "start"));
        Assert.assertNull(snapshot.getJSONObject("fu").optJSONObject("views"));
        Assert.assertEquals(1, code(snapshot, "w740"));
        Assert.assertFalse(snapshot.getJSONObject("lc").has("w720"));

        counter.logError();
        counter.saveState();
        JSONObject stored = new JSONObject(storage.healthCheckState);
        Assert.assertEquals(3, stored.getLong("LErr"));
        Assert.assertEquals(3, stored.getLong("LWar"));
        Assert.assertEquals(503, stored.getInt("RStatC"));
        Assert.assertEquals("Service Unavailable", stored.getString("REMsg"));
        Assert.assertEquals(4, stored.getLong("BReq"));
        Assert.assertEquals(2, stored.getInt("CBReq"));
        Assert.assertEquals(5, stored.getJSONObject("MU").getJSONObject("events").getInt("record"));
        Assert.assertEquals(1, stored.getJSONObject("LC").getInt("w740"));
    }

    /**
     * A stored state that is not valid JSON is cleared, the counters start from zero and the
     * condition is reported as log code e503.
     */
    @Test
    public void counter_malformedStateIsResetWithE503() {
        MemoryStorage storage = new MemoryStorage();
        storage.healthCheckState = "{not json";

        JSONObject snapshot = counter(storage).snapshot();
        Assert.assertEquals("", storage.healthCheckState);
        Assert.assertEquals(0, snapshot.getLong("el"));
        Assert.assertEquals(1, code(snapshot, "e503"));
    }

    /**
     * The automatically detected crashes the previous run counted are reported as "crashes" /
     * "record:a" and their stored count goes back to 0.
     */
    @Test
    public void counter_addsAutomaticCrashesOfThePreviousRun() {
        MemoryStorage storage = new MemoryStorage();
        storage.automaticCrashes = 2;

        JSONObject snapshot = counter(storage).snapshot();
        Assert.assertEquals(2, usage(snapshot, "crashes", "record:a"));
        Assert.assertEquals(0, storage.automaticCrashes);
        Assert.assertEquals(2, new JSONObject(storage.healthCheckState).getJSONObject("MU").getJSONObject("crashes").getInt("record:a"));
    }

    /**
     * Only the last refused request is kept, status codes outside 1..999 are ignored and the
     * message is cut to 1000 characters.
     */
    @Test
    public void counter_failedRequests() {
        HealthCheckCounter counter = counter(new MemoryStorage());
        counter.logFailedNetworkRequest(500, "first");
        counter.logFailedNetworkRequest(0, "ignored");
        counter.logFailedNetworkRequest(1000, "ignored");
        StringBuilder longBody = new StringBuilder();
        for (int a = 0; a < 1200; a++) {
            longBody.append('x');
        }
        counter.logFailedNetworkRequest(400, longBody.toString());

        JSONObject snapshot = counter.snapshot();
        Assert.assertEquals(400, snapshot.getInt("sc"));
        Assert.assertEquals(1000, snapshot.getString("em").length());
    }

    /**
     * "bom" counts every backoff, "cbom" keeps the longest run of backoffs in a row, w320 is
     * recorded for every backoff and w321 for every backoff that continues a run.
     */
    @Test
    public void counter_backoffRuns() {
        HealthCheckCounter counter = counter(new MemoryStorage());
        counter.logBackoffRequest();
        counter.logBackoffRequest();
        counter.logBackoffRequest();
        counter.saveState();
        counter.logConsecutiveBackoffRequest();
        counter.logBackoffRequest();
        counter.logBackoffRequest();

        JSONObject snapshot = counter.snapshot();
        Assert.assertEquals(5, snapshot.getLong("bom"));
        Assert.assertEquals(3, snapshot.getInt("cbom"));
        Assert.assertEquals(5, code(snapshot, "w320"));
        Assert.assertEquals(3, code(snapshot, "w321"));
    }

    /**
     * Method usage and the log codes of a feature area count only while that feature has consent,
     * an unknown area never counts, and every count stops at 65535.
     */
    @Test
    public void counter_consentAndCap() {
        HealthCheckCounter counter = counter(new MemoryStorage(), feature -> feature != CoreFeature.Views);
        counter.recordUsage("views", "start");
        counter.recordUsage("events", "record");
        counter.recordUsage("unknown", "call");
        counter.recordUsage("events", "");
        counter.recordLogCode("w730", "views");
        counter.recordLogCode("w720", "events");
        counter.recordLogCode("w101", 70000);
        counter.recordLogCode("w101", 1);

        JSONObject snapshot = counter.snapshot();
        Assert.assertEquals(0, usage(snapshot, "views", "start"));
        Assert.assertEquals(1, usage(snapshot, "events", "record"));
        Assert.assertNull(snapshot.getJSONObject("fu").optJSONObject("unknown"));
        Assert.assertEquals(0, code(snapshot, "w730"));
        Assert.assertEquals(1, code(snapshot, "w720"));
        Assert.assertEquals(HealthCheckCounter.COUNT_CAP, code(snapshot, "w101"));
    }

    /**
     * While an automatic crash is reported, method usage and log codes are ignored; warnings and
     * errors are still counted. Counting resumes afterwards.
     */
    @Test
    public void counter_crashStop() {
        HealthCheckCounter counter = counter(new MemoryStorage());
        counter.stopRecordingForCrash();
        counter.recordUsage("events", "record");
        counter.recordLogCode("w202", 1);
        counter.logError();
        counter.resumeRecordingAfterCrash();
        counter.recordUsage("events", "record");

        JSONObject snapshot = counter.snapshot();
        Assert.assertEquals(1, usage(snapshot, "events", "record"));
        Assert.assertEquals(0, code(snapshot, "w202"));
        Assert.assertEquals(1, snapshot.getLong("el"));
    }

    /**
     * An accepted health check takes away only what it reported: what was counted while it was on
     * its way stays, and a failed request or a backoff run that changed meanwhile is kept.
     */
    @Test
    public void counter_clearSentValuesSubtractsWhatWasReported() {
        MemoryStorage storage = new MemoryStorage();
        HealthCheckCounter counter = counter(storage);
        counter.logWarning();
        counter.logWarning();
        counter.logFailedNetworkRequest(500, "down");
        counter.logBackoffRequest();
        counter.recordUsage("events", "record");
        counter.recordLogCode("w720", 1);

        JSONObject snapshot = counter.snapshot();

        counter.logWarning();
        counter.logFailedNetworkRequest(400, "bad");
        counter.logBackoffRequest();
        counter.recordUsage("events", "record");
        counter.recordUsage("views", "start");

        counter.clearSentValues(snapshot);
        JSONObject left = counter.snapshot();
        Assert.assertEquals(1, left.getLong("wl"));
        Assert.assertEquals(400, left.getInt("sc"));
        Assert.assertEquals("bad", left.getString("em"));
        Assert.assertEquals(1, left.getLong("bom"));
        Assert.assertEquals(2, left.getInt("cbom"));
        Assert.assertEquals(1, usage(left, "events", "record"));
        Assert.assertEquals(1, usage(left, "views", "start"));
        Assert.assertFalse(left.getJSONObject("lc").has("w720"));
        Assert.assertEquals(1, new JSONObject(storage.healthCheckState).getLong("LWar"));

        counter.clearSentValues(counter.snapshot());
        JSONObject empty = counter.snapshot();
        Assert.assertEquals(-1, empty.getInt("sc"));
        Assert.assertEquals("", empty.getString("em"));
        Assert.assertEquals(0, empty.getInt("cbom"));
        Assert.assertEquals(0, empty.getJSONObject("fu").length());
    }

    /**
     * The health check is sent once, right after init, as an immediate request to /i with the base
     * request parameters, the app version as metrics and the counters. The warnings logged before
     * the counter existed, such as an invalid init value, are in it.
     */
    @Test
    public void healthCheck_sentOnceAfterInit() {
        InternalConfig config = healthCheckConfig();
        config.setRequestQueueMaxSize(0);
        config.setLoggingLevel(Config.LoggingLevel.OFF);
        Countly.instance().init(config);

        List<SentRequest> healthChecks = healthChecks();
        Assert.assertEquals(1, healthChecks.size());
        SentRequest request = healthChecks.get(0);
        Assert.assertEquals("/i?", request.endpoint);

        Map<String, String> params = request.params();
        TestUtils.validateRequiredParams(params);
        Assert.assertEquals("{\"_app_version\":\"" + TestUtils.APPLICATION_VERSION + "\"}", params.get("metrics"));

        JSONObject hc = request.healthCheck();
        for (String key : new String[] { "el", "wl", "sc", "em", "bom", "cbom", "fu", "lc" }) {
            Assert.assertTrue("missing " + key, hc.has(key));
        }
        Assert.assertTrue(hc.getLong("wl") >= 1);
        Assert.assertEquals(1, code(hc, "w121"));

        SDKCore.instance.module(ModuleHealthCheck.class).sendHealthCheck();
        Assert.assertEquals(1, healthChecks().size());
    }

    /**
     * An accepted health check takes the reported counts away and stores what is left; a refused
     * one leaves the counters for the next initialization.
     */
    @Test
    public void healthCheck_acceptedClearsRefusedKeeps() {
        serverAnswer = new JSONObject().put("result", "Success");
        InternalConfig first = healthCheckConfig();
        first.setRequestQueueMaxSize(0);
        Countly.instance().init(first);
        Assert.assertEquals(1, code(healthChecks().get(0).healthCheck(), "w121"));
        Countly.instance().events().endEvent("never started");
        JSONObject afterAccepted = runningCounter().snapshot();
        Assert.assertEquals(1, code(afterAccepted, "w720"));
        Assert.assertEquals(0, code(afterAccepted, "w121"));
        Countly.instance().stop();

        serverAnswer = new JSONObject().put("error", "App does not exist");
        Countly.instance().init(healthCheckConfig());
        Assert.assertEquals(1, code(healthChecks().get(1).healthCheck(), "w720"));
        Assert.assertEquals(1, code(runningCounter().snapshot(), "w720"));

        serverAnswer = null;
        Countly.instance().stop();
        Countly.instance().init(healthCheckConfig());
        Assert.assertEquals(1, code(runningCounter().snapshot(), "w720"));
    }

    /**
     * With the health check disabled nothing is sent, but warnings are still counted.
     */
    @Test
    public void healthCheck_disabled() {
        InternalConfig config = healthCheckConfig();
        config.disableHealthCheck();
        Countly.instance().init(config);

        Assert.assertTrue(healthChecks().isEmpty());
        long before = runningCounter().snapshot().getLong("wl");
        Countly.instance().events().cancelEvent("never started");
        Assert.assertEquals(before + 1, runningCounter().snapshot().getLong("wl"));
    }

    /**
     * While the SDK behavior settings turn networking off, nothing is sent and the counters wait
     * for the next initialization.
     */
    @Test
    public void healthCheck_networkingOff() {
        InternalConfig config = healthCheckConfig();
        config.setSdkBehaviorSettings("{\"v\":1,\"t\":1700000000000,\"c\":{\"networking\":false}}");
        Countly.instance().init(config);

        Assert.assertTrue(healthChecks().isEmpty());
        Assert.assertFalse(SDKCore.instance.module(ModuleHealthCheck.class).isHealthCheckSent());
    }

    /**
     * Backend mode sends no health check and runs no health check module.
     */
    @Test
    public void healthCheck_backendMode() {
        InternalConfig config = healthCheckConfig();
        config.enableBackendMode();
        Countly.instance().init(config);

        Assert.assertTrue(healthChecks().isEmpty());
        Assert.assertNull(SDKCore.instance.module(ModuleHealthCheck.class));
    }

    /**
     * Without the sessions consent, which carries the metrics in this SDK, the health check is sent
     * without metrics; the health check itself needs no consent.
     */
    @Test
    public void healthCheck_withoutConsent() {
        InternalConfig config = healthCheckConfig();
        config.setRequiresConsent(true);
        Countly.instance().init(config);

        Assert.assertEquals(1, healthChecks().size());
        Assert.assertNull(healthChecks().get(0).params().get("metrics"));
    }

    /**
     * The overridden app version is the one the health check metrics carry.
     */
    @Test
    public void healthCheck_appVersionOverride() {
        InternalConfig config = healthCheckConfig();
        Map<String, String> overrides = new HashMap<>();
        overrides.put("_app_version", "9.9.9");
        config.setMetricOverride(overrides);
        Countly.instance().init(config);

        Assert.assertEquals("{\"_app_version\":\"9.9.9\"}", healthChecks().get(0).params().get("metrics"));
    }

    /**
     * Every listed method of the session, event, view and crash features is counted once per call,
     * under the names of the guide.
     */
    @Test
    public void methodUsage_counted() {
        Countly.instance().init(healthCheckConfig());

        Countly.session().begin();
        Countly.session().update();
        Countly.session().end();

        Countly.instance().events().recordEvent("a");
        Countly.instance().events().recordEvent("b", 2);
        Countly.instance().events().startEvent("timed");
        Countly.instance().events().endEvent("timed");
        Countly.instance().events().startEvent("cancelled");
        Countly.instance().events().cancelEvent("cancelled");

        String first = Countly.instance().views().startView("first");
        Countly.instance().views().startAutoStoppedView("auto");
        Countly.instance().views().pauseViewWithID(first);
        Countly.instance().views().resumeViewWithID(first);
        Countly.instance().views().stopViewWithID(first);
        Countly.instance().views().startView("named");
        Countly.instance().views().stopViewWithName("named");
        Countly.instance().views().stopAllViews(null);

        Countly.instance().crashes().addCrashBreadcrumb("crumb");
        Countly.instance().crashes().recordHandledException(new RuntimeException("handled"));

        JSONObject snapshot = runningCounter().snapshot();
        Assert.assertEquals(1, usage(snapshot, "sessions", "begin"));
        Assert.assertEquals(1, usage(snapshot, "sessions", "update"));
        Assert.assertEquals(1, usage(snapshot, "sessions", "end"));
        Assert.assertEquals(2, usage(snapshot, "events", "record"));
        Assert.assertEquals(2, usage(snapshot, "events", "start"));
        Assert.assertEquals(1, usage(snapshot, "events", "end"));
        Assert.assertEquals(1, usage(snapshot, "events", "cancel"));
        Assert.assertEquals(2, usage(snapshot, "views", "start"));
        Assert.assertEquals(1, usage(snapshot, "views", "startAuto"));
        Assert.assertEquals(1, usage(snapshot, "views", "pause"));
        Assert.assertEquals(1, usage(snapshot, "views", "resume"));
        Assert.assertEquals(2, usage(snapshot, "views", "stop"));
        Assert.assertEquals(1, usage(snapshot, "views", "stopAll"));
        Assert.assertEquals(1, usage(snapshot, "crashes", "log"));
        Assert.assertEquals(1, usage(snapshot, "crashes", "record"));
    }

    /**
     * Calls the SDK makes by itself carry ":a", and deprecated calls carry ":d" with log code w120.
     */
    @Test
    public void methodUsage_automaticAndDeprecated() {
        Countly.instance().init(healthCheckConfig());

        Countly.api().event("legacy").record();
        Countly.api().addCrashReport(new RuntimeException("legacy"), false);
        Countly.api().view("legacy view");

        JSONObject snapshot = runningCounter().snapshot();
        Assert.assertEquals(1, usage(snapshot, "events", "record:d"));
        Assert.assertEquals(1, usage(snapshot, "sessions", "begin:a"));
        Assert.assertEquals(1, usage(snapshot, "crashes", "addCrashReport:d"));
        Assert.assertEquals(1, usage(snapshot, "views", "view:d"));
        Assert.assertEquals(3, code(snapshot, "w120"));
    }

    /**
     * While consent is required, the calls of a feature without consent are not counted, and
     * neither are the log codes they record.
     */
    @Test
    public void methodUsage_followsConsent() {
        InternalConfig config = healthCheckConfig();
        config.setRequiresConsent(true);
        Countly.instance().init(config);

        Countly.instance().events().recordEvent("no consent");
        Countly.instance().events().endEvent("no consent");
        Assert.assertEquals(0, usage(runningCounter().snapshot(), "events", "record"));

        Countly.onConsent(Config.Feature.Events);
        Countly.instance().events().recordEvent("consent");
        Countly.instance().events().endEvent("consent");
        JSONObject snapshot = runningCounter().snapshot();
        Assert.assertEquals(1, usage(snapshot, "events", "record"));
        Assert.assertEquals(1, code(snapshot, "w720"));
    }

    /**
     * Misuse of the event, view and session methods records the codes of the guide.
     */
    @Test
    public void logCodes_misuse() {
        Countly.instance().init(healthCheckConfig());

        Countly.instance().events().recordEvent("");
        Countly.instance().events().recordEvent("count", 0);
        Countly.instance().events().startEvent("twice");
        Countly.instance().events().startEvent("twice");
        Countly.instance().events().endEvent("not running");
        Countly.instance().events().cancelEvent("not running");
        Countly.instance().events().startEvent("");
        Countly.instance().views().startView("");
        Countly.instance().views().stopViewWithID("unknown");
        String view = Countly.instance().views().startView("view");
        Countly.instance().views().pauseViewWithID(view);
        Countly.instance().views().pauseViewWithID(view);
        Countly.session().begin();
        Countly.session().begin();
        Countly.onConsent();

        JSONObject snapshot = runningCounter().snapshot();
        Assert.assertEquals(1, code(snapshot, "w701"));
        Assert.assertEquals(1, code(snapshot, "w750"));
        Assert.assertEquals(1, code(snapshot, "w722"));
        Assert.assertEquals(1, code(snapshot, "w720"));
        Assert.assertEquals(1, code(snapshot, "w721"));
        Assert.assertEquals(1, code(snapshot, "w700"));
        Assert.assertEquals(1, code(snapshot, "w710"));
        Assert.assertEquals(1, code(snapshot, "w730"));
        Assert.assertEquals(1, code(snapshot, "w731"));
        Assert.assertEquals(1, code(snapshot, "w760"));
        Assert.assertEquals(1, code(snapshot, "w402"));
    }

    /**
     * A call made before init is reported as w101 by the health check of the next start.
     */
    @Test
    public void logCodes_callBeforeInit() {
        Countly.instance().events().recordEvent("too early");
        Countly.instance().init(healthCheckConfig());

        Assert.assertEquals(1, code(healthChecks().get(0).healthCheck(), "w101"));
    }

    /**
     * A queued request the server refuses is reported in "sc" and "em" as plain text; a request
     * the server accepts changes neither.
     */
    @Test
    public void failedQueuedRequest_reported() {
        Countly.instance().init(healthCheckConfig());
        Transport transport = SDKCore.instance.networking.getTransport();

        Assert.assertFalse(transport.processResponse(400, "{\"result\":\"App does not exist\"}", 1L));
        Assert.assertTrue(transport.processResponse(200, "{\"result\":\"Success\"}", 2L));

        JSONObject snapshot = runningCounter().snapshot();
        Assert.assertEquals(400, snapshot.getInt("sc"));
        Assert.assertEquals("{\"result\":\"App does not exist\"}", snapshot.getString("em"));
    }

    /**
     * The counters are stored when the SDK stops and keeps its data, and the next start reports them.
     */
    @Test
    public void state_storedOnStopAndReportedNextStart() {
        serverAnswer = null;
        Countly.instance().init(healthCheckConfig());
        Countly.instance().events().cancelEvent("not running");
        Countly.instance().stop();

        JSONObject stored = new JSONObject((String) TestUtils.getJsonStorageProperty("HEALTH_CHECK"));
        Assert.assertEquals(1, stored.getJSONObject("LC").getInt("w721"));

        Countly.instance().init(healthCheckConfig());
        Assert.assertEquals(1, code(healthChecks().get(1).healthCheck(), "w721"));
    }

    /**
     * An automatically detected crash is counted as a stored number, which the next start reports
     * as "crashes" / "record:a".
     */
    @Test
    public void automaticCrash_reportedNextStart() {
        Countly.instance().init(healthCheckConfig());
        SDKCore.instance.config.storageProvider.incrementAutomaticCrashCount();
        Countly.instance().stop();

        Countly.instance().init(healthCheckConfig());
        Assert.assertEquals(1, usage(healthChecks().get(1).healthCheck(), "crashes", "record:a"));
    }
}
