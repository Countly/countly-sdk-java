package ly.count.sdk.java.internal;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
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

@RunWith(JUnit4.class)
public class ModuleCrashesTests {

    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
    }

    @After
    public void stop() {
        Countly.instance().halt();
    }

    /**
     * "recordHandledException"
     * Validating that handled exception is recorded correctly to request queue
     * Request queue should contain one request with crash object
     */
    @Test
    public void recordHandledException() throws InterruptedException {
        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.CrashReporting));
        TestUtils.setAdditionalDeviceMetrics();
        Throwable testThrowable = new Exception("test");
        Thread.sleep(200);
        Countly.instance().crashes().recordHandledException(testThrowable);
        validateCrashInRQ(testThrowable, false, null);
    }

    /**
     * "recordHandledException" with custom crash processor
     * Validating that crash processor is called and request queue is empty because it returns null
     * Request queue should be empty
     */
    @Test
    public void recordHandledException_customCrashProcessor() throws InterruptedException {
        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.CrashReporting)
            .setCrashProcessorClass(TestCrashProcessor.class));
        TestUtils.setAdditionalDeviceMetrics();
        Throwable testThrowable = new Exception("test");
        Thread.sleep(200);
        Countly.instance().crashes().recordHandledException(testThrowable);
        Assert.assertEquals(0, TestUtils.getCurrentRQ().length);
    }

    /**
     * "recordHandledException"
     * Validating that handled exception is recorded correctly to request queue with custom segment
     * Request queue should contain one request with crash object
     */
    @Test
    public void recordHandledException_withSegment() throws InterruptedException {
        recordExceptionWithSegment_base(false, (throwable, customSegment) ->
            Countly.instance().crashes().recordHandledException(throwable, customSegment));
    }

    /**
     * "recordHandledException"
     * Validating that request queue is empty because null is passed as throwable
     * Request queue should not contain one request with crash object
     */
    @Test
    public void recordHandledException_null() {
        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.CrashReporting));
        TestUtils.setAdditionalDeviceMetrics();
        Countly.instance().crashes().recordHandledException(null);
        Assert.assertEquals(0, TestUtils.getCurrentRQ().length);
    }

    /**
     * "recordUnhandledException"
     * Validating that request queue is empty because null is passed as throwable
     * Request queue should not contain one request with crash object
     */
    @Test
    public void recordUnhandledException_null() {
        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.CrashReporting));
        TestUtils.setAdditionalDeviceMetrics();
        Countly.instance().crashes().recordUnhandledException(null);
        Assert.assertEquals(0, TestUtils.getCurrentRQ().length);
    }

    /**
     * "recordUnhandledException"
     * Validating that handled exception is recorded correctly to request queue,
     * Request queue should contain one request with crash object
     */
    @Test
    public void recordUnhandledException() {
        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.CrashReporting));
        TestUtils.setAdditionalDeviceMetrics();
        Throwable testThrowable = new Exception("test");
        Countly.instance().crashes().recordUnhandledException(testThrowable);
        validateCrashInRQ(testThrowable, true, null);
    }

    /**
     * "recordUnhandledException"
     * Validating that handled exception is recorded correctly to request queue with custom segment
     * Request queue should contain one request with crash object
     */
    @Test
    public void recordUnhandledException_withSegment() throws InterruptedException {
        recordExceptionWithSegment_base(true, (throwable, customSegment) ->
            Countly.instance().crashes().recordUnhandledException(throwable, customSegment));
    }

    /**
     * Validate crash in request queue and has expected required parameters
     *
     * @param expectedError throwable to validate
     * @param fatal is fatal
     * @param customSegment custom segment to validate
     */
    private void validateCrashInRQ(Throwable expectedError, boolean fatal, JSONObject customSegment) {
        Map<String, String>[] rq = TestUtils.getCurrentRQ();
        Assert.assertEquals(1, rq.length);
        Assert.assertEquals(10, rq[0].size());
        TestUtils.validateRequiredParams(rq[0]);
        JSONObject crashObj = new JSONObject(rq[0].get("crash"));
        Assert.assertEquals(customSegment == null ? 19 : 20, crashObj.length());

        Assert.assertTrue(crashObj.getDouble("_run") >= 0);
        Assert.assertTrue(crashObj.getInt("_disk_total") > 0);
        Assert.assertTrue(crashObj.getInt("_disk_current") > 0);
        Assert.assertTrue(crashObj.getInt("_ram_current") > 0);
        Assert.assertTrue(crashObj.getInt("_ram_total") > 0);
        Assert.assertEquals("Device", crashObj.get("_device"));
        Assert.assertEquals("Manufacturer", crashObj.get("_manufacture"));
        Assert.assertEquals("CPU1.2", crashObj.get("_cpu"));
        Assert.assertEquals("OpenGL2.3.1", crashObj.get("_opengl"));
        Assert.assertEquals("portrait", crashObj.get("_orientation"));
        Assert.assertEquals("100x100", crashObj.get("_resolution"));
        Assert.assertEquals(true, crashObj.get("_online"));
        Assert.assertEquals(true, crashObj.get("_muted"));
        Assert.assertEquals(0.52f, crashObj.getFloat("_bat"), 0.0001);
        Assert.assertEquals(TestUtils.APPLICATION_VERSION, crashObj.getString("_app_version"));
        Assert.assertEquals(System.getProperty("os.version", "n/a"), crashObj.get("_os_version"));
        Assert.assertEquals(System.getProperty("os.name"), crashObj.get("_os"));

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        expectedError.printStackTrace(pw);
        Assert.assertEquals(sw.toString(), crashObj.get("_error"));
        Assert.assertEquals(!fatal, crashObj.get("_nonfatal"));
        if (customSegment != null) {
            Assert.assertEquals(customSegment.toString(), crashObj.getJSONObject("_custom").toString());
        }
    }

    private void recordExceptionWithSegment_base(boolean fatal, BiConsumer<Throwable, Map<String, Object>> crashReporter) throws InterruptedException {
        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.CrashReporting));
        TestUtils.setAdditionalDeviceMetrics();
        Throwable testThrowable = new RuntimeException("Someting Happened");
        Map<String, Object> customSegment = new ConcurrentHashMap<>();
        customSegment.put("test", "test");
        customSegment.put("test2", "2");
        customSegment.put("test3", "3.0");
        customSegment.put("test4", "true");
        customSegment.put("test5", new JSONObject());
        customSegment.put("test6", new String[] { "test" });
        customSegment.put("test7", new int[] { 1 });
        customSegment.put("test8", new double[] { 1.0 });
        customSegment.put("test9", new boolean[] { true });
        customSegment.put("test10", new JSONObject[] { new JSONObject() });
        customSegment.put("test11", new String[][] { { "test" } });
        Thread.sleep(200); // wait for a time for the throwable
        crashReporter.accept(testThrowable, customSegment);

        validateCrashInRQ(testThrowable, fatal, new JSONObject(customSegment));
    }

    public static class TestCrashProcessor implements CrashProcessor {

        public TestCrashProcessor() {
        }

        @Override
        public Crash process(Crash crash) {
            return null;
        }
    }
}
