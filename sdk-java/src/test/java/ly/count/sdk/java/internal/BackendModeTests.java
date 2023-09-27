package ly.count.sdk.java.internal;

import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.util.*;

@RunWith(JUnit4.class)
public class BackendModeTests {
    private ModuleBackendMode moduleBackendMode;

    @BeforeClass
    public static void init() {
        Config cc = new Config("https://try.count.ly", "COUNTLY_APP_KEY");

        cc.setEventQueueSizeToSend(4).enableBackendMode();

        // System specific folder structure
        String[] sdkStorageRootPath = { System.getProperty("user.home"), "__COUNTLY", "java_test" };
        File sdkStorageRootDirectory = new File(String.join(File.separator, sdkStorageRootPath));

        if(sdkStorageRootDirectory.mkdirs()){
            System.out.println("Directory creation failed");
        }

        Countly.init(sdkStorageRootDirectory, cc);
    }

    @AfterClass
    public static void stop() throws Exception {
        Countly.stop(false);
    }

    @Before
    public void start() {
        moduleBackendMode = (ModuleBackendMode) Countly.backendMode().getModule();
    }

    @After
    public void end() {
        moduleBackendMode.eventQSize = 0;
        SDKCore.instance.requestQueueMemory.clear();
        moduleBackendMode.eventQueues.clear();
    }

    /**
     * It validates the SDK name and 'enableBackendMode' in configuration.
     */
    @Test
    public void testConfigurationValues() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();
        Assert.assertTrue(moduleBackendMode.internalConfig.isBackendModeEnabled());
        Assert.assertEquals(4, moduleBackendMode.internalConfig.getEventsBufferSize());
        Assert.assertEquals("java-native-backend", moduleBackendMode.internalConfig.getSDKNameInternal());
        Assert.assertEquals(1000, moduleBackendMode.internalConfig.getRequestQueueMaxSize());
    }

    /**
     * It validates the functionality of 'recordView' method.
     */
    @Test
    public void testMethodRecordView() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();

        Map<String, Object> segmentation = new HashMap<String, Object>() {{
            put("name", "SampleView");
            put("visit", "1");
            put("segment", "Windows");
            put("start", "1");
        }};

        Assert.assertEquals(0L, moduleBackendMode.eventQSize);
        backendMode.recordView("device-id-1", "SampleView", segmentation, 1646640780130L);

        JSONArray events = moduleBackendMode.eventQueues.get("device-id-1");
        Assert.assertEquals(1L, events.length());
        Assert.assertEquals(1L, moduleBackendMode.eventQSize);
        Assert.assertEquals(1, backendMode.getQueueSize());

        JSONObject event = events.getJSONObject(0);

        long expectedTimestamp = 1646640780130L;
        int expectedHour = getHourFromTimeStamp(expectedTimestamp);

        validateEventFields("[CLY]_view", 1, null, null, 1, expectedHour, expectedTimestamp, event);

        JSONObject segments = event.getJSONObject("segmentation");
        Assert.assertEquals("SampleView", segments.get("name"));
        Assert.assertEquals("1", segments.get("visit"));
        Assert.assertEquals("Windows", segments.get("segment"));
        Assert.assertEquals("1", segments.get("start"));

        backendMode.recordView("device-id-2", "SampleView2", null, 1646640780130L);

        events = moduleBackendMode.eventQueues.get("device-id-2");
        Assert.assertEquals(1L, events.length());
        Assert.assertEquals(2L, moduleBackendMode.eventQSize);
        Assert.assertEquals(2, backendMode.getQueueSize());

        event = events.getJSONObject(0);
        validateEventFields("[CLY]_view", 1, null, null, 1, expectedHour, expectedTimestamp, event);

        segments = event.getJSONObject("segmentation");
        Assert.assertEquals("SampleView2", segments.get("name"));
    }

    /**
     * It validates the functionality of 'recordView' method against invalid data.
     */
    @Test
    public void testMethodRecordViewWithInvalidData() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();

        Map<String, Object> segmentation = new HashMap<String, Object>() {{
            put("name", "SampleView");
            put("visit", "1");
            put("segment", "Windows");
            put("start", "1");
        }};

        /* Invalid Device ID */
        Assert.assertEquals(0L, moduleBackendMode.eventQSize);
        backendMode.recordView("", "SampleView1", segmentation, 1646640780130L);
        backendMode.recordView(null, "SampleView1", segmentation, 1646640780130L);

        Assert.assertTrue(moduleBackendMode.eventQueues.isEmpty());
        Assert.assertEquals(0L, moduleBackendMode.eventQSize);

        /* Invalid view name */
        Assert.assertEquals(0L, moduleBackendMode.eventQSize);
        backendMode.recordView("device-id-1", "", segmentation, 1646640780130L);
        backendMode.recordView("device-id-2", null, segmentation, 1646640780130L);

        Assert.assertTrue(moduleBackendMode.eventQueues.isEmpty());
        Assert.assertEquals(0L, moduleBackendMode.eventQSize);
        Assert.assertEquals(0, backendMode.getQueueSize());
    }

    /**
     * It validates the functionality of 'recordEvent' method and event queue size by using single device ID.
     */
    @Test
    public void testMethodRecordEventWithSingleDeviceID() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();

        Map<String, Object> segmentation = new HashMap<>();
        segmentation.put("key1", "value1");
        segmentation.put("key2", "value2");

        Assert.assertEquals(0, moduleBackendMode.eventQSize);
        backendMode.recordEvent("device-id-1", "key-1", 1, 0.1, 10.0, segmentation, 1646640780130L);

        JSONArray events = moduleBackendMode.eventQueues.get("device-id-1");
        Assert.assertEquals(1, events.length());
        Assert.assertEquals(1, moduleBackendMode.eventQSize);
        Assert.assertEquals(1, backendMode.getQueueSize());

        JSONObject event = events.getJSONObject(0);

        long expectedTimestamp = 1646640780130L;
        int expectedHour = getHourFromTimeStamp(expectedTimestamp);

        validateEventFields("key-1", 1, 0.1, 10.0, 1, expectedHour, expectedTimestamp, event);

        JSONObject segments = event.getJSONObject("segmentation");
        Assert.assertEquals("value1", segments.get("key1"));
        Assert.assertEquals("value2", segments.get("key2"));
    }

    /**
     * It validates the functionality of 'recordEvent' method against invalid data.
     */
    @Test
    public void testMethodRecordEventWithInvalidData() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();

        Map<String, Object> segmentation = new HashMap<>();
        segmentation.put("key1", "value1");
        segmentation.put("key2", "value2");

        Assert.assertEquals(0, moduleBackendMode.eventQSize);

        /* Invalid Device ID */
        backendMode.recordEvent("", "key-1", 1, 0.1, 10.0, segmentation, 1646640780130L);
        backendMode.recordEvent(null, "key-2", 1, 0.1, 10.0, segmentation, 1646640780130L);

        Assert.assertTrue(moduleBackendMode.eventQueues.isEmpty());
        Assert.assertEquals(0L, moduleBackendMode.eventQSize);

        /* Invalid view name */
        backendMode.recordEvent("device-id-1", "", 1, 0.1, 10.0, segmentation, 1646640780130L);
        backendMode.recordEvent("device-id-1", null, 1, 0.1, 10.0, segmentation, 1646640780130L);

        Assert.assertTrue(moduleBackendMode.eventQueues.isEmpty());
        Assert.assertEquals(0L, moduleBackendMode.eventQSize);
        Assert.assertEquals(0, backendMode.getQueueSize());

        //TODO: validate segmentation data type.
    }

    /**
     * It validates the functionality of 'recordEvent' method and event queue size by using multiple device IDs.
     */
    @Test
    public void testMethodRecordEventWithMultipleDeviceID() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();

        Map<String, Object> segmentation = new HashMap<>();
        segmentation.put("key1", "value1");
        segmentation.put("key2", "value2");

        Map<String, Object> segmentation1 = new HashMap<>();
        segmentation1.put("key3", "value3");
        segmentation1.put("key4", "value4");

        Assert.assertEquals(0, moduleBackendMode.eventQSize);
        backendMode.recordEvent("device-id-1", "key-1", 1, 0.1, 10.0, segmentation, 1646640780130L);

        Assert.assertEquals(1, moduleBackendMode.eventQSize);
        Assert.assertEquals(1, backendMode.getQueueSize());
        backendMode.recordEvent("device-id-2", "key-2", 1, 0.1, 10.0, segmentation, 1646640780130L);
        backendMode.recordEvent("device-id-2", "key-3", 2, 0.2, 20.0, segmentation1, 1646644457826L);

        Assert.assertEquals(3, moduleBackendMode.eventQSize);
        Assert.assertEquals(2, backendMode.getQueueSize());

        //Events with Device ID = 'device-id-1'
        JSONArray events = moduleBackendMode.eventQueues.get("device-id-2");
        Assert.assertEquals(2, events.length());

        JSONObject event = events.getJSONObject(0);

        long expectedTimestamp = 1646640780130L;
        int expectedHour = getHourFromTimeStamp(expectedTimestamp);

        validateEventFields("key-2", 1, 0.1, 10.0, 1, expectedHour, expectedTimestamp, event);

        JSONObject segments = event.getJSONObject("segmentation");
        Assert.assertEquals("value1", segments.get("key1"));
        Assert.assertEquals("value2", segments.get("key2"));

        //Events with Device ID = 'device-id-2'
        events = moduleBackendMode.eventQueues.get("device-id-2");
        Assert.assertEquals(2, events.length());

        event = events.getJSONObject(0);
        Assert.assertEquals("key-2", event.get("key"));
        validateEventFields("key-2", 1, 0.1, 10.0, 1, expectedHour, expectedTimestamp, event);

        segments = event.getJSONObject("segmentation");
        Assert.assertEquals("value1", segments.get("key1"));
        Assert.assertEquals("value2", segments.get("key2"));

        event = events.getJSONObject(1);
        Assert.assertEquals("key-3", event.get("key"));

        expectedTimestamp = 1646644457826L;
        expectedHour = getHourFromTimeStamp(expectedTimestamp);

        validateEventFields("key-3", 2, 0.2, 20.0, 1, expectedHour, expectedTimestamp, event);

        segments = event.getJSONObject("segmentation");
        Assert.assertEquals("value3", segments.get("key3"));
        Assert.assertEquals("value4", segments.get("key4"));
    }

    /**
     * It validates the event thresh hold against single and multiple device IDs.
     */
    @Test
    public void TestEventThreshHoldWithSingleAndMultiple() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();

        Map<String, Object> segmentation = new HashMap<>();
        segmentation.put("key1", "value1");
        segmentation.put("key2", "value2");

        Map<String, Object> segmentation1 = new HashMap<>();
        segmentation1.put("key3", "value3");
        segmentation1.put("key4", "value4");

        Assert.assertEquals(0, moduleBackendMode.eventQSize);

        backendMode.recordEvent("device-id-1", "key-1", 1, 0.1, 10.0, segmentation, 1646640780130L);
        Assert.assertEquals(1, moduleBackendMode.eventQSize);
        Assert.assertEquals(1, moduleBackendMode.eventQueues.get("device-id-1").length());
        Assert.assertEquals(1, backendMode.getQueueSize());

        backendMode.recordEvent("device-id-1", "key-2", 1, 0.1, 10.0, segmentation, 1646640780130L);
        Assert.assertEquals(2, moduleBackendMode.eventQSize);
        Assert.assertEquals(2, moduleBackendMode.eventQueues.get("device-id-1").length());
        Assert.assertEquals(1, backendMode.getQueueSize());

        backendMode.recordEvent("device-id-1", "key-3", 1, 0.1, 10.0, segmentation, 1646640780130L);
        Assert.assertEquals(3, moduleBackendMode.eventQSize);
        Assert.assertEquals(1, backendMode.getQueueSize());

        backendMode.recordEvent("device-id-1", "key-3", 1, 0.1, 10.0, segmentation, 1646640780130L);
        Assert.assertEquals(0, moduleBackendMode.eventQSize);
        Assert.assertNull(moduleBackendMode.eventQueues.get("device-id-1"));
        Assert.assertEquals(1, backendMode.getQueueSize());

        backendMode.recordEvent("device-id-1", "key-1", 1, 0.1, 10.0, segmentation, 1646640780130L);
        Assert.assertEquals(1, moduleBackendMode.eventQSize);
        Assert.assertEquals(1, moduleBackendMode.eventQueues.get("device-id-1").length());
        Assert.assertEquals(2, backendMode.getQueueSize());

        backendMode.recordEvent("device-id-2", "key-2", 1, 0.1, 10.0, segmentation, 1646640780130L);
        Assert.assertEquals(2, moduleBackendMode.eventQSize);
        Assert.assertEquals(1, moduleBackendMode.eventQueues.get("device-id-1").length());
        Assert.assertEquals(1, moduleBackendMode.eventQueues.get("device-id-2").length());
        Assert.assertEquals(3, backendMode.getQueueSize());

        backendMode.recordEvent("device-id-2", "key-3", 1, 0.1, 10.0, segmentation, 1646640780130L);
        Assert.assertEquals(3, moduleBackendMode.eventQSize);
        Assert.assertEquals(1, moduleBackendMode.eventQueues.get("device-id-1").length());
        Assert.assertEquals(2, moduleBackendMode.eventQueues.get("device-id-2").length());
        Assert.assertEquals(3, backendMode.getQueueSize());

        backendMode.recordEvent("device-id-2", "key-4", 2, 0.2, 20.0, segmentation1, 1646644457826L);
        Assert.assertEquals(0, moduleBackendMode.eventQSize);
        Assert.assertEquals(3, backendMode.getQueueSize());
        Assert.assertNull(moduleBackendMode.eventQueues.get("device-id-1"));
        Assert.assertNull(moduleBackendMode.eventQueues.get("device-id-2"));
    }

    /**
     * It validates the functionality of adding events into request queue on session update.
     */
    @Test
    public void testFunctionalityAddEventsIntoRequestQueueOnSessionUpdate() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();

        Map<String, Object> segmentation = new HashMap<>();
        segmentation.put("key1", "value1");
        segmentation.put("key2", "value2");

        Map<String, Object> segmentation1 = new HashMap<>();
        segmentation1.put("key3", "value3");
        segmentation1.put("key4", "value4");

        Assert.assertEquals(0, moduleBackendMode.eventQSize);
        Assert.assertEquals(0, SDKCore.instance.requestQueueMemory.size());

        backendMode.recordEvent("device-id-1", "key-1", 1, 0.1, 10.0, segmentation, 1646640780130L);
        Assert.assertEquals(1, moduleBackendMode.eventQSize);
        Assert.assertEquals(0, SDKCore.instance.requestQueueMemory.size());
        Assert.assertEquals(1, moduleBackendMode.eventQueues.get("device-id-1").length());

        backendMode.recordEvent("device-id-2", "key-3", 1, 0.1, 10.0, segmentation, 1646640780130L);
        Assert.assertEquals(2, moduleBackendMode.eventQSize);
        Assert.assertEquals(0, SDKCore.instance.requestQueueMemory.size());
        Assert.assertEquals(1, moduleBackendMode.eventQueues.get("device-id-1").length());
        Assert.assertEquals(1, moduleBackendMode.eventQueues.get("device-id-2").length());

        backendMode.recordEvent("device-id-2", "key-4", 2, 0.2, 20.0, segmentation1, 1646644457826L);
        Assert.assertEquals(3, moduleBackendMode.eventQSize);
        Assert.assertEquals(0, SDKCore.instance.requestQueueMemory.size());
        Assert.assertEquals(1, moduleBackendMode.eventQueues.get("device-id-1").length());
        Assert.assertEquals(2, moduleBackendMode.eventQueues.get("device-id-2").length());
    }

    /**
     * It validates the functionality of adding events into request queue on session end.
     */
    @Test
    public void testFunctionalityAddEventsIntoRequestQueueOnSessionEnd() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();

        Map<String, Object> segmentation = new HashMap<>();
        segmentation.put("key1", "value1");
        segmentation.put("key2", "value2");

        Map<String, Object> segmentation1 = new HashMap<>();
        segmentation1.put("key3", "value3");
        segmentation1.put("key4", "value4");

        Assert.assertEquals(0, moduleBackendMode.eventQSize);
        Assert.assertEquals(0, SDKCore.instance.requestQueueMemory.size());

        backendMode.recordEvent("device-id-1", "key-1", 1, 0.1, 10.0, segmentation, 1646640780130L);
        Assert.assertEquals(1, moduleBackendMode.eventQSize);
        Assert.assertEquals(0, SDKCore.instance.requestQueueMemory.size());
        Assert.assertEquals(1, moduleBackendMode.eventQueues.get("device-id-1").length());

        backendMode.recordEvent("device-id-2", "key-3", 1, 0.1, 10.0, segmentation, 1646640780130L);
        Assert.assertEquals(2, moduleBackendMode.eventQSize);
        Assert.assertEquals(0, SDKCore.instance.requestQueueMemory.size());
        Assert.assertEquals(1, moduleBackendMode.eventQueues.get("device-id-1").length());
        Assert.assertEquals(1, moduleBackendMode.eventQueues.get("device-id-2").length());

        backendMode.recordEvent("device-id-2", "key-4", 2, 0.2, 20.0, segmentation1, 1646644457826L);
        Assert.assertEquals(3, moduleBackendMode.eventQSize);
        Assert.assertEquals(0, SDKCore.instance.requestQueueMemory.size());
        Assert.assertEquals(1, moduleBackendMode.eventQueues.get("device-id-1").length());
        Assert.assertEquals(2, moduleBackendMode.eventQueues.get("device-id-2").length());

        backendMode.sessionEnd("device-id-2", 60, 1646644457826L);
        Assert.assertEquals(1, moduleBackendMode.eventQSize);
        Assert.assertEquals(2, SDKCore.instance.requestQueueMemory.size());
        Assert.assertNull(moduleBackendMode.eventQueues.get("device-id-2"));
        Assert.assertEquals(1, moduleBackendMode.eventQueues.get("device-id-1").length());
    }

    /**
     * It validates the request and functionality of 'sessionBegin' method.
     */
    @Test
    public void testMethodSessionBegin() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();
        Map<String, String> metrics = new HashMap<>();
        metrics.put("os", "windows");
        metrics.put("app-version", "0.1");

        Map<String, String> location = new HashMap<String, String>() {{
            put("ip_address", "192.168.1.1");
            put("city", "Lahore");
            put("country_code", "PK");
            put("location", "31.5204,74.3587");
        }};

        backendMode.sessionBegin("device-id-1", metrics, location, 1646640780130L);

        Assert.assertEquals(1, SDKCore.instance.requestQueueMemory.size());
        Request request = SDKCore.instance.requestQueueMemory.remove();

        String session = request.params.get("metrics");
        JSONObject sessionJson = new JSONObject(session);

        Assert.assertEquals("Lahore", request.params.get("city"));
        Assert.assertEquals("PK", request.params.get("country_code"));
        Assert.assertEquals("192.168.1.1", request.params.get("ip_address"));
        Assert.assertEquals("31.5204,74.3587", request.params.get("location"));

        Assert.assertEquals("windows", sessionJson.get("os"));
        Assert.assertEquals("0.1", sessionJson.get("app-version"));
        Assert.assertEquals("1", request.params.get("begin_session"));
        validateRequestTimeFields("device-id-1", 1646640780130L, request);
    }

    /**
     * It validates functionality of 'sessionBegin' method against invalid data.
     */
    @Test
    public void testMethodSessionBeginWithInvalidData() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();
        Map<String, String> metrics = new HashMap<>();
        metrics.put("os", "windows");
        metrics.put("app-version", "0.1");

        backendMode.sessionBegin("", metrics, null, 1646640780130L);
        backendMode.sessionBegin(null, metrics, null, 1646640780130L);

        Assert.assertTrue(SDKCore.instance.requestQueueMemory.isEmpty());
    }

    /**
     * It validates the request and functionality of 'sessionUpdate' method.
     */
    @Test
    public void testMethodSessionUpdate() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();
        backendMode.sessionUpdate("device-id-1", 10.5, 1646640780130L);

        Assert.assertEquals(1, SDKCore.instance.requestQueueMemory.size());
        Request request = SDKCore.instance.requestQueueMemory.remove();

        Assert.assertEquals("10.5", request.params.get("session_duration"));
        validateRequestTimeFields("device-id-1", 1646640780130L, request);
    }

    /**
     * It validates functionality of 'sessionUpdate' method against invalid data.
     */
    @Test
    public void testMethodSessionUpdateWithInvalidData() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();
        backendMode.sessionUpdate("", 10.5, 1646640780130L);
        backendMode.sessionUpdate(null, 10.5, 1646640780130L);

        Assert.assertTrue(SDKCore.instance.requestQueueMemory.isEmpty());
    }

    /**
     * It validates the request and functionality of 'sessionEnd' method.
     */
    @Test
    public void testSessionEnd() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();
        backendMode.sessionEnd("device-id-1", 10.5, 1646640780130L);

        Assert.assertEquals(1, SDKCore.instance.requestQueueMemory.size());
        Request request = SDKCore.instance.requestQueueMemory.remove();

        Assert.assertEquals("1", request.params.get("end_session"));
        Assert.assertEquals("10.5", request.params.get("session_duration"));
        validateRequestTimeFields("device-id-1", 1646640780130L, request);
    }

    /**
     * It validates functionality of 'sessionEnd' method against invalid data.
     */
    @Test
    public void testMethodSessionEndWithInvalidData() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();
        backendMode.sessionEnd("", 10.5, 1646640780130L);
        backendMode.sessionEnd(null, 20.5, 1646640780130L);

        Assert.assertTrue(SDKCore.instance.requestQueueMemory.isEmpty());
    }

    /**
     * It validates the request and functionality of 'recordException' method.
     */
    @Test
    public void testMethodRecordException() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();
        Map<String, Object> segmentation = new HashMap<String, Object>() {{
            put("key1", "value1");
        }};

        Map<String, String> crashDetails = new HashMap<String, String>() {{
            put("_error", "Custom Error");
            put("_logs", "Logs");
            put("_os", "Operating System");
        }};

        try {
            int a = 10 / 0;
        } catch (Exception e) {
            backendMode.recordException("device-id-1", e, segmentation, crashDetails, 1646640780130L);
            backendMode.recordException("device-id-2", "Divided By Zero", "stack traces", null, null, 1646640780130L);

            Assert.assertEquals(2, SDKCore.instance.requestQueueMemory.size());
            Request request = SDKCore.instance.requestQueueMemory.remove();

            String crash = request.params.get("crash");
            JSONObject crashJson = new JSONObject(crash);

            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);

            Assert.assertEquals(e.getMessage(), crashJson.get("_name"));
            Assert.assertEquals("Custom Error", crashJson.get("_error"));
            Assert.assertEquals("Logs", crashJson.get("_logs"));
            Assert.assertEquals("Operating System", crashJson.get("_os"));
            validateRequestTimeFields("device-id-1", 1646640780130L, request);

            JSONObject segments = crashJson.getJSONObject("_custom");
            Assert.assertEquals("value1", segments.get("key1"));

            Assert.assertEquals(1, SDKCore.instance.requestQueueMemory.size());
            request = SDKCore.instance.requestQueueMemory.remove();

            crash = request.params.get("crash");
            crashJson = new JSONObject(crash);

            Assert.assertEquals("Divided By Zero", crashJson.get("_name"));
            Assert.assertEquals("stack traces", crashJson.get("_error"));

            validateRequestTimeFields("device-id-2", 1646640780130L, request);

            segments = crashJson.getJSONObject("_custom");
            Assert.assertTrue(segments.isEmpty());
        }
    }

    /**
     * It validates functionality of 'recordException' method against invalid data.
     */
    @Test
    public void testMethodRecordExceptionWithInvalidData() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();
        Map<String, Object> segmentation = new HashMap<String, Object>() {{
            put("key1", "value1");
        }};

        backendMode.recordException("", null, segmentation, null, 1646640780130L);
        Assert.assertTrue(SDKCore.instance.requestQueueMemory.isEmpty());

        backendMode.recordException(null, null, segmentation, null, 1646640780130L);
        Assert.assertTrue(SDKCore.instance.requestQueueMemory.isEmpty());

        backendMode.recordException("device-id-1", null, segmentation, null, 1646640780130L);
        Assert.assertTrue(SDKCore.instance.requestQueueMemory.isEmpty());

        backendMode.recordException("device-id-2", "", "stack traces", null, null, 1646640780130L);
        Assert.assertTrue(SDKCore.instance.requestQueueMemory.isEmpty());

        backendMode.recordException("device-id-2", "device-id", "", null, null, 1646640780130L);
        Assert.assertTrue(SDKCore.instance.requestQueueMemory.isEmpty());

        backendMode.recordException("device-id-2", null, "stack traces", null, null, 1646640780130L);
        Assert.assertTrue(SDKCore.instance.requestQueueMemory.isEmpty());

        backendMode.recordException("device-id-2", "device-id", null, null, null, 1646640780130L);
        Assert.assertTrue(SDKCore.instance.requestQueueMemory.isEmpty());
    }

    /**
     * It validates the user detail, user custom detail and operations on custom properties.
     */
    @Test
    public void testUserDetailCustomDetailAndOperations() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();

        // User detail
        Map<String, Object> userDetail = populateUserProperties(true, true, false);

        backendMode.recordUserProperties("device-id-1", userDetail, 1646640780130L);

        Assert.assertEquals(1, SDKCore.instance.requestQueueMemory.size());
        Request request = SDKCore.instance.requestQueueMemory.remove();
        validateRequestTimeFields("device-id-1", 1646640780130L, request);

        String userDetails = request.params.get("user_details");
        validateUserProperties(userDetails, true, true, false);
    }

    /**
     * It validates the structure of user detail , custom user detail and operations.
     * Case 1: When custom detail and are provided, along with user detail.
     */
    @Test
    public void testUserDetailStructureAllDataAtSameLevel() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();

        // User detail
        Map<String, Object> userDetail = populateUserProperties(true, true, true);
        backendMode.recordUserProperties("device-id-1", userDetail, 1646640780130L);

        Assert.assertEquals(1, SDKCore.instance.requestQueueMemory.size());
        Request request = SDKCore.instance.requestQueueMemory.remove();
        validateRequestTimeFields("device-id-1", 1646640780130L, request);

        String userDetails = request.params.get("user_details");
        validateUserProperties(userDetails, true, true, true);
    }

    /**
     * It validates the structure of user detail , custom user detail and operations.
     * Case 2: When only user custom detail is provided.
     */
    @Test
    public void testUserDetailStructureWithOnlyCustomDetail() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();

        Map<String, Object> userDetail = populateUserProperties(false, true, false);

        backendMode.recordUserProperties("device-id-1", userDetail, 1646640780130L);

        Assert.assertEquals(1, SDKCore.instance.requestQueueMemory.size());
        Request request = SDKCore.instance.requestQueueMemory.remove();
        validateRequestTimeFields("device-id-1", 1646640780130L, request);

        String userDetails = request.params.get("user_details");
        validateUserProperties(userDetails, false, true, false);
    }

    /**
     * It validates the structure of user detail , custom user detail and operations.
     * Case 3: When only operation data is provided.
     */
    @Test
    public void testUserDetailStructureWithOnlyOperationData() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();

        Map<String, Object> userDetail = populateUserProperties(false, false, true);

        backendMode.recordUserProperties("device-id-1", userDetail, 1646640780130L);

        Assert.assertEquals(1, SDKCore.instance.requestQueueMemory.size());
        Request request = SDKCore.instance.requestQueueMemory.remove();
        validateRequestTimeFields("device-id-1", 1646640780130L, request);

        String userDetails = request.params.get("user_details");
        validateUserProperties(userDetails, false, false, true);
    }

    /**
     * It validates functionality of 'recordUserProperties' method against invalid data.
     */
    @Test
    public void testMethodRecordUserPropertiesWithInvalidData() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();
        Map<String, Object> userDetail = populateUserProperties(true, false, false);

        backendMode.recordUserProperties("", userDetail, 1646640780130L);
        Assert.assertTrue(SDKCore.instance.requestQueueMemory.isEmpty());

        backendMode.recordUserProperties(null, userDetail, 1646640780130L);
        Assert.assertTrue(SDKCore.instance.requestQueueMemory.isEmpty());

        backendMode.recordUserProperties("device-id", null, 1646640780130L);
        Assert.assertTrue(SDKCore.instance.requestQueueMemory.isEmpty());

        userDetail.clear();
        backendMode.recordUserProperties("device-id", userDetail, 1646640780130L);
        Assert.assertTrue(SDKCore.instance.requestQueueMemory.isEmpty());
    }

    /*
     * It validates the data type of Event's segment items.
     */
    @Test
    public void testEventSegmentDataType() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();

        Map<String, Object> segmentation = new HashMap<>();
        segmentation.put("key1", null); //invalid
        segmentation.put("key2", "value");
        segmentation.put("key3", 1);
        segmentation.put("key4", 20.5);
        segmentation.put("key5", true);
        segmentation.put("key6", backendMode); //invalid
        segmentation.put("key7", 10L);

        Assert.assertEquals(0, moduleBackendMode.eventQSize);
        backendMode.recordEvent("device-id-1", "key-1", 1, 0.1, 10.0, segmentation, 1646640780130L);

        JSONArray events = moduleBackendMode.eventQueues.get("device-id-1");
        Assert.assertEquals(1, events.length());
        Assert.assertEquals(1, moduleBackendMode.eventQSize);

        JSONObject event = events.getJSONObject(0);

        long expectedTimestamp = 1646640780130L;
        int expectedHour = getHourFromTimeStamp(expectedTimestamp);

        validateEventFields("key-1", 1, 0.1, 10.0, 1, expectedHour, expectedTimestamp, event);

        JSONObject segments = event.getJSONObject("segmentation");
        Assert.assertEquals(5, segments.length());
        Assert.assertEquals("value", segments.get("key2"));
        Assert.assertEquals(1, segments.get("key3"));
        Assert.assertEquals(20.5, segments.get("key4"));
        Assert.assertEquals(10L, segments.get("key7"));
        Assert.assertEquals(true, segments.get("key5"));

        Assert.assertFalse(segments.has("key1"));
        Assert.assertFalse(segments.has("key6"));
    }

    /*
     * It validates the data type of View's segment items.
     */
    @Test
    public void testViewSegmentDataType() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();

        Map<String, Object> segmentation = new HashMap<>();
        segmentation.put("key1", null); //invalid
        segmentation.put("key2", "value");
        segmentation.put("key3", 1);
        segmentation.put("key4", 20.5);
        segmentation.put("key5", true);
        segmentation.put("key6", backendMode); //invalid
        segmentation.put("key7", 10L);

        Assert.assertEquals(0, moduleBackendMode.eventQSize);
        backendMode.recordView("device-id-1", "view-1", segmentation, 1646640780130L);

        JSONArray events = moduleBackendMode.eventQueues.get("device-id-1");
        Assert.assertEquals(1, events.length());
        Assert.assertEquals(1, moduleBackendMode.eventQSize);

        JSONObject event = events.getJSONObject(0);
        JSONObject segments = event.getJSONObject("segmentation");
        Assert.assertEquals(6, segments.length());
        Assert.assertEquals("value", segments.get("key2"));
        Assert.assertEquals(1, segments.get("key3"));
        Assert.assertEquals(20.5, segments.get("key4"));
        Assert.assertEquals(10L, segments.get("key7"));
        Assert.assertEquals(true, segments.get("key5"));
        Assert.assertEquals("view-1", segments.get("name"));

        Assert.assertFalse(segments.has("key1"));
        Assert.assertFalse(segments.has("key6"));
    }

    /*
     * It validates the data type of Crash's segment items.
     */
    @Test
    public void testCrashSegmentDataType() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();

        Map<String, Object> segmentation = new HashMap<>();
        segmentation.put("key1", null); //invalid
        segmentation.put("key2", "value");
        segmentation.put("key3", 1);
        segmentation.put("key4", 20.5);
        segmentation.put("key5", true);
        segmentation.put("key6", backendMode); //invalid
        segmentation.put("key7", 10L);

        Map<String, String> crashDetails = new HashMap<>();
        crashDetails.put("K1", null); //invalid
        crashDetails.put("K2", "V2");

        Assert.assertEquals(0, moduleBackendMode.eventQSize);
        backendMode.recordException("device-id-1", "key-1", "stacktrace", segmentation, crashDetails, 1646640780130L);

        Request request = SDKCore.instance.requestQueueMemory.remove();
        String crash = request.params.get("crash");

        JSONObject crashJson = new JSONObject(crash);
        JSONObject segments = crashJson.getJSONObject("_custom");
        Assert.assertEquals(5, segments.length());
        Assert.assertEquals("value", segments.get("key2"));
        Assert.assertEquals(1, segments.get("key3"));
        Assert.assertEquals(BigDecimal.valueOf(20.5), segments.get("key4"));
        Assert.assertEquals(true, segments.get("key5"));
        Assert.assertEquals(10, segments.get("key7"));
        Assert.assertEquals(10, segments.get("key7"));

        Assert.assertFalse(crashJson.has("K1"));
        Assert.assertEquals("V2", crashJson.get("K2"));

        Assert.assertFalse(segments.has("key1"));
        Assert.assertFalse(segments.has("key6"));
    }

    /**
     * It validates the request and functionality of 'recordDirectRequest' method.
     */
    @Test
    public void testRecordDirectRequest() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();

        // Direct request with timestamp and device id
        Map<String, String> requestData = new HashMap<>();
        requestData.put("data1", "value1");
        requestData.put("device_id", "device-id-1");
        requestData.put("timestamp", "1647938191782");
        requestData.put("tz", "100");
        requestData.put("dow", "0");
        requestData.put("hour", "9");
        requestData.put("data3", "value3");

        Assert.assertEquals(0, moduleBackendMode.eventQSize);
        Assert.assertTrue(SDKCore.instance.requestQueueMemory.isEmpty());
        backendMode.recordDirectRequest("device-id-2", requestData, 1647938191782L);
        Assert.assertEquals(1, SDKCore.instance.requestQueueMemory.size());

        Request request = SDKCore.instance.requestQueueMemory.remove();
        Assert.assertEquals("value1", request.params.get("data1"));
        Assert.assertEquals("value3", request.params.get("data3"));
        validateRequestTimeFields("device-id-2", 1647938191782L, request);

        // Direct request without timestamp and device id
        requestData = new HashMap<>();
        requestData.put("data2", "value2");
        requestData.put("data4", "value4");

        Assert.assertEquals(0, moduleBackendMode.eventQSize);
        Assert.assertTrue(SDKCore.instance.requestQueueMemory.isEmpty());
        backendMode.recordDirectRequest("device-id-2", requestData, 987654321L);
        Assert.assertEquals(1, SDKCore.instance.requestQueueMemory.size());

        request = SDKCore.instance.requestQueueMemory.remove();
        Assert.assertEquals("value2", request.params.get("data2"));
        Assert.assertEquals("value4", request.params.get("data4"));
        validateRequestTimeFields("device-id-2", 987654321L, request);
    }

    private Map<String, Object> populateUserProperties(boolean addUserDetail, boolean addCustomDetail, boolean addOperation) {
        Map<String, Object> userDetail = new HashMap<>();
        if (addUserDetail) {
            userDetail.put("name", "Full Name");
            userDetail.put("username", "username1");
            userDetail.put("email", "user@gmail.com");
            userDetail.put("organization", "Countly");
            userDetail.put("phone", "000-111-000");
            userDetail.put("gender", "M");
            userDetail.put("byear", "1991");
        }

        if (addCustomDetail) {
            userDetail.put("hair", "black");
            userDetail.put("height", 5.9);
        }

        if (addOperation) {
            userDetail.put("weight", "{\"$inc\": 1}");
        }

        return userDetail;
    }

    private void validateEventFields(String key, int count, Double sum, Double dur, int dow, int hour, long timestamp, JSONObject event) {
        Assert.assertEquals(key, event.get("key"));
        Assert.assertEquals(sum, event.opt("sum"));
        Assert.assertEquals(count, event.get("count"));
        Assert.assertEquals(dur, event.opt("dur"));

        Assert.assertEquals(dow, event.get("dow"));
        Assert.assertEquals(hour, event.get("hour"));
        Assert.assertEquals(timestamp, event.get("timestamp"));
    }

    private void validateRequestTimeFields(String deviceID, long timestamp, Request request) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timestamp);

        final int hour = calendar.get(Calendar.HOUR_OF_DAY);
        final int dow = calendar.get(Calendar.DAY_OF_WEEK) - 1;

        Assert.assertEquals(Device.dev.getTimezoneOffset() + "", request.params.get("tz"));
        Assert.assertEquals(dow + "", request.params.get("dow"));
        Assert.assertEquals(hour + "", request.params.get("hour"));
        Assert.assertEquals(deviceID, request.params.get("device_id"));
        Assert.assertEquals(timestamp + "", request.params.get("timestamp"));
    }

    private void validateUserProperties(String userDetails, boolean validateUserDetail, boolean validateCustomDetail, boolean validateOperation) {
        JSONObject userDetailsJson = new JSONObject(userDetails);

        if (validateUserDetail) {
            Assert.assertEquals("Full Name", userDetailsJson.get("name"));
            Assert.assertEquals("username1", userDetailsJson.get("username"));
            Assert.assertEquals("user@gmail.com", userDetailsJson.get("email"));
            Assert.assertEquals("Countly", userDetailsJson.get("organization"));
            Assert.assertEquals("000-111-000", userDetailsJson.get("phone"));
            Assert.assertEquals("M", userDetailsJson.get("gender"));
            Assert.assertEquals("1991", userDetailsJson.get("byear"));
        }

        if (validateCustomDetail) {
            //Custom properties
            JSONObject customPropertiesJson = userDetailsJson.getJSONObject("custom");
            Assert.assertEquals("black", customPropertiesJson.get("hair"));
            Assert.assertEquals(BigDecimal.valueOf(5.9), customPropertiesJson.get("height"));
        }

        if (validateOperation) {
            JSONObject customPropertiesJson = userDetailsJson.getJSONObject("custom");
            JSONObject operationsJson = customPropertiesJson.getJSONObject("weight");
            Assert.assertEquals(1, operationsJson.get("$inc"));
        }
    }

    private int getHourFromTimeStamp(long timeStamp) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timeStamp);

        return calendar.get(Calendar.HOUR_OF_DAY);
    }
