package ly.count.sdk.java.internal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import ly.count.sdk.java.Crash;
import ly.count.sdk.java.CrashProcessor;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.mockito.Mockito.mock;

/**
 * Crash reporting: the {@link CrashImpl} payload, its {@link Storable} form, and the
 * {@link ModuleCrashes} flow that turns a {@link Throwable} into a request.
 * <p>
 * A crash is the one report a customer cannot reproduce on demand, so the assertions here are on the
 * crash JSON that reaches the wire rather than on internal state.
 */
@RunWith(JUnit4.class)
public class CrashFlowTests {

    /**
     * Records what the SDK handed it and optionally vetoes the crash.
     */
    public static class RecordingProcessor implements CrashProcessor {
        static volatile Crash lastSeen;
        static volatile boolean veto;

        @Override
        public Crash process(Crash crash) {
            lastSeen = crash;
            return veto ? null : crash;
        }
    }

    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
        RecordingProcessor.lastSeen = null;
        RecordingProcessor.veto = false;
    }

    @After
    public void stop() {
        Countly.instance().halt();
    }

    // region scenarios

    /**
     * A handled exception with segmentation and breadcrumbs, end to end. Proves the crash JSON on the
     * wire carries the stack trace, the non-fatal marker, the custom segmentation, the breadcrumbs
     * and the device metrics.
     */
    @Test
    public void handledException_reachesTheWireWithEverythingAttached() {
        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.CrashReporting));

        Countly.instance().crashes().addCrashBreadcrumb("first breadcrumb");
        Countly.instance().crashes().addCrashBreadcrumb("second breadcrumb");

        Map<String, Object> segmentation = new HashMap<>();
        segmentation.put("screen", "checkout");
        segmentation.put("retries", 2);
        Countly.instance().crashes().recordHandledException(
            new IllegalStateException("handled boom"), segmentation);

        JSONObject crash = crashOnTheWire();
        Assert.assertTrue("the stack trace must be present", crash.getString("_error").contains("handled boom"));
        Assert.assertTrue("a handled exception is non fatal", crash.getBoolean("_nonfatal"));
        Assert.assertEquals("checkout", crash.getJSONObject("_custom").getString("screen"));
        Assert.assertEquals(2, crash.getJSONObject("_custom").getInt("retries"));
        Assert.assertTrue(crash.getString("_logs").contains("first breadcrumb"));
        Assert.assertTrue(crash.getString("_logs").contains("second breadcrumb"));
        Assert.assertTrue("device metrics must ride along", crash.has("_os"));
        Assert.assertTrue(crash.has("_run"));
    }

    /**
     * An unhandled exception is the fatal variant, and the breadcrumb log is cleared once it has been
     * attached so the next crash does not repeat it.
     */
    @Test
    public void unhandledException_isFatalAndConsumesTheBreadcrumbs() {
        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.CrashReporting));

        Countly.instance().crashes().addCrashBreadcrumb("only once");
        Countly.instance().crashes().recordUnhandledException(new RuntimeException("fatal boom"));

        JSONObject first = crashOnTheWire();
        Assert.assertFalse("an unhandled exception is fatal", first.getBoolean("_nonfatal"));
        Assert.assertTrue(first.getString("_logs").contains("only once"));

        Countly.instance().crashes().recordHandledException(new RuntimeException("second boom"));
        JSONObject second = crashOnTheWire(1);
        Assert.assertFalse("breadcrumbs must not be repeated on the next crash", second.has("_logs"));
    }

    /**
     * Everything the module must refuse: a null throwable, an empty breadcrumb, and any crash at all
     * while backend mode owns the wire. None may produce a request.
     */
    @Test
    public void crashReporting_refusesBadInputAndStandsDownUnderBackendMode() {
        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.CrashReporting));

        Countly.instance().crashes().recordHandledException(null);
        Countly.instance().crashes().recordUnhandledException(null);
        Countly.instance().crashes().recordHandledException(null, new HashMap<>());
        for (String badBreadcrumb : new String[] { null, "" }) {
            Countly.instance().crashes().addCrashBreadcrumb(badBreadcrumb);
        }

        Storage.await(SDKCore.instance.config.getLogger());
        Assert.assertEquals("a null throwable must not produce a crash", 0, crashRequestCount());

        Countly.instance().halt();
        TestUtils.createCleanTestState();

        Config backendConfig = TestUtils.getBaseConfig().enableFeatures(Config.Feature.CrashReporting);
        backendConfig.enableBackendMode();
        Countly.instance().init(backendConfig);

        ModuleCrashes module = SDKCore.instance.module(ModuleCrashes.class);
        Assert.assertNotNull(module);
        module.recordExceptionInternal(new RuntimeException("ignored under backend mode"), true, null, null);

        Storage.await(SDKCore.instance.config.getLogger());
        Assert.assertEquals("backend mode must suppress the ordinary crash path", 0, crashRequestCount());
    }

    /**
     * Consent gating. With consent required and CrashReporting not granted the interface is withheld
     * entirely; once granted the crash flows.
     */
    @Test
    public void crashReporting_isGatedOnConsent() {
        Config config = TestUtils.getBaseConfig()
            .enableFeatures(Config.Feature.CrashReporting)
            .setRequiresConsent(true);
        Countly.instance().init(config);

        Assert.assertNull("no consent means no crash interface", Countly.instance().crashes());

        Countly.onConsent(Config.Feature.CrashReporting);
        Assert.assertNotNull(Countly.instance().crashes());

        Countly.instance().crashes().recordHandledException(new RuntimeException("after consent"));
        Storage.await(SDKCore.instance.config.getLogger());
        Assert.assertEquals(1, crashRequestCount());
    }

    /**
     * The {@link CrashProcessor} hook a customer installs to scrub or drop crashes. It must see the
     * crash, and returning null from it must stop the crash from being sent at all.
     */
    @Test
    public void crashProcessor_seesTheCrashAndCanVetoIt() {
        Countly.instance().init(TestUtils.getBaseConfig()
            .enableFeatures(Config.Feature.CrashReporting)
            .setCrashProcessorClass(RecordingProcessor.class));

        Countly.instance().crashes().recordHandledException(new IllegalArgumentException("inspected"));
        Assert.assertNotNull("the processor must be handed the crash", RecordingProcessor.lastSeen);
        Assert.assertTrue(RecordingProcessor.lastSeen.getThrowable() instanceof IllegalArgumentException);
        Storage.await(SDKCore.instance.config.getLogger());
        Assert.assertEquals(1, crashRequestCount());

        // Vetoing must remove the crash rather than send it anyway.
        RecordingProcessor.veto = true;
        RecordingProcessor.lastSeen = null;
        Countly.instance().crashes().recordHandledException(new IllegalArgumentException("vetoed"));
        Assert.assertNotNull(RecordingProcessor.lastSeen);

        Storage.await(SDKCore.instance.config.getLogger());
        Assert.assertEquals("a vetoed crash must not be sent", 1, crashRequestCount());
    }

    /**
     * The ANR shaped report: {@code addTraces} dumps every thread's stack, naming the main thread
     * first and marking the report as an ANR rather than a crash.
     */
    @Test
    public void addTraces_buildsAnAnrReportOverEveryThread() {
        Log logger = mock(Log.class);
        CrashImpl crash = new CrashImpl(logger);

        Thread main = Thread.currentThread();
        Thread other = new Thread(() -> {
        }, "worker-thread");

        Map<Thread, StackTraceElement[]> traces = new HashMap<>();
        traces.put(main, new StackTraceElement[] {
            new StackTraceElement("MainClass", "mainMethod", "MainClass.java", 11)
        });
        // A null element must be tolerated: a live stack can be raced while it is being read.
        traces.put(other, new StackTraceElement[] {
            new StackTraceElement("WorkerClass", "workerMethod", "WorkerClass.java", 22), null
        });

        Assert.assertSame(crash, crash.addTraces(main, traces));
        Assert.assertSame(traces, crash.getTraces());

        JSONObject data = crash.getData();
        Assert.assertEquals("anr", data.getString("_type"));
        String error = data.getString("_error");
        Assert.assertTrue(error.startsWith("Thread [main]:"));
        Assert.assertTrue(error.contains("MainClass.mainMethod"));
        Assert.assertTrue(error.contains("Thread [worker-thread]:"));
        Assert.assertTrue(error.contains("WorkerClass.workerMethod"));
        Assert.assertTrue("an unreadable frame must not break the dump", error.contains("<<Unknown>>"));

        // Null traces are refused rather than producing an empty ANR report.
        CrashImpl refused = new CrashImpl(logger);
        Assert.assertSame(refused, refused.addTraces(main, null));
        Assert.assertFalse(refused.getData().has("_type"));

        // A main thread that is not in the map at all simply gets no "main" section.
        CrashImpl noMain = new CrashImpl(logger);
        noMain.addTraces(null, traces);
        Assert.assertFalse(noMain.getData().getString("_error").contains("Thread [main]:"));
    }

    /**
     * The crash accessors and the storable round trip, which is how a crash survives the process
     * death that produced it.
     */
    @Test
    public void crashPayload_readsBackEverythingAndSurvivesStorage() {
        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.CrashReporting));
        InternalConfig config = SDKCore.instance.config;
        Log logger = mock(Log.class);

        IllegalStateException thrown = new IllegalStateException("readable");
        CrashImpl crash = new CrashImpl(logger);
        Assert.assertSame(crash, crash.addException(thrown));
        Assert.assertSame(thrown, crash.getThrowable());

        // A fresh crash is non-fatal by default; setFatal flips it and isFatal reports it.
        Assert.assertFalse(crash.isFatal());
        crash.setFatal(true);
        Assert.assertTrue(crash.isFatal());
        crash.setFatal(false);
        Assert.assertFalse(crash.isFatal());

        Assert.assertNull("an unnamed crash has no name", crash.getName());
        crash.setName("NamedCrash");
        Assert.assertEquals("NamedCrash", crash.getName());

        Assert.assertNull("no segments means null, not an empty map", crash.getSegments());
        Map<String, String> segments = new HashMap<>();
        segments.put("area", "billing");
        crash.setSegments(segments);
        Assert.assertEquals("billing", crash.getSegments().get("area"));

        Assert.assertNull(crash.getLogs());
        crash.setLogs(new String[] { "line one", "line two" });
        Assert.assertEquals(2, crash.getLogs().size());
        Assert.assertEquals("line one", crash.getLogs().get(0));
        // Empty and null log arrays are ignored rather than clearing what is there.
        crash.setLogs(new String[0]);
        crash.setLogs(null);
        Assert.assertEquals(2, crash.getLogs().size());

        // add() ignores a null value and an empty key rather than writing junk into the payload.
        Assert.assertSame(crash, crash.add("ignoredNull", null));
        Assert.assertSame(crash, crash.add("", "ignoredKey"));
        Assert.assertFalse(crash.getData().has("ignoredNull"));
        Assert.assertFalse(crash.getData().has(""));

        // The storable round trip: the JSON payload comes back intact.
        Assert.assertTrue(Storage.push(config, crash));
        CrashImpl restored = Storage.read(config, new CrashImpl(crash.storageId(), logger));
        Assert.assertNotNull(restored);
        Assert.assertEquals("NamedCrash", restored.getName());
        Assert.assertEquals("billing", restored.getSegments().get("area"));
        Assert.assertTrue(restored.getData().getString("_error").contains("readable"));
        Assert.assertEquals(CrashImpl.getStoragePrefix(), restored.storagePrefix());

        // Corrupt bytes are logged and skipped, leaving the crash usable rather than throwing.
        CrashImpl corrupt = new CrashImpl(logger);
        Assert.assertTrue("restore reports success even when the payload was unreadable",
            corrupt.restore("not json".getBytes(), logger));

        corrupt.setId(4321L);
        Assert.assertEquals(Long.valueOf(4321L), corrupt.storageId());
        Assert.assertEquals(corrupt.getJSON(), corrupt.toString());
    }

    // endregion
    // region helpers

    private int crashRequestCount() {
        int found = 0;
        for (Map<String, String> request : TestUtils.getCurrentRQ()) {
            if (request != null && request.containsKey("crash")) {
                found++;
            }
        }
        return found;
    }

    private JSONObject crashOnTheWire() {
        return crashOnTheWire(0);
    }

    /**
     * @param index which crash request to read, in queue order
     * @return the decoded "crash" parameter of that request
     */
    private JSONObject crashOnTheWire(int index) {
        Storage.await(SDKCore.instance.config.getLogger());
        List<String> crashes = new java.util.ArrayList<>();
        for (Map<String, String> request : TestUtils.getCurrentRQ()) {
            if (request != null && request.containsKey("crash")) {
                crashes.add(request.get("crash"));
            }
        }
        Assert.assertTrue("expected at least " + (index + 1) + " crash request(s), got " + crashes.size(),
            crashes.size() > index);
        return new JSONObject(crashes.get(index));
    }

    // endregion
}
