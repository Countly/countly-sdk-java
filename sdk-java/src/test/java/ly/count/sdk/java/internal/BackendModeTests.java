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
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

@RunWith(JUnit4.class)
public class BackendModeTests {
    InternalConfig config;
    ModuleBackendMode moduleBackendMode;
    private CtxCore ctx;

    @BeforeClass
    public static void init() {
        Config cc = new Config("https://try.count.ly", "COUNTLY_APP_KEY");
        cc.setEventsBufferSize(4)
                .enableBackendMode();
        File targetFolder = new File("C:\\Users\\zahid\\Documents\\Countly\\data");
        Countly.init(targetFolder, cc);
    }

    @AfterClass
    public static void stop() throws Exception {
        Countly.stop(false);
    }

    @Before
    public void start() {
        moduleBackendMode = (ModuleBackendMode) Countly.backendMode().getModule();
        moduleBackendMode.defferUpload = true;
    }

    @After
    public void end() {
        moduleBackendMode.eventQSize = 0;
        moduleBackendMode.requestQ.clear();
        moduleBackendMode.eventQueues.clear();
    }

    @Test
    public void backendModeConfigTest() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();
        Assert.assertTrue(moduleBackendMode.internalConfig.isBackendModeEnable());
        Assert.assertEquals("java-native-backend", moduleBackendMode.internalConfig.getSdkName());
    }

    @Test
    public void viewFieldsTest() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();

        Map<String, Object> segmentation = new HashMap<String, Object>() {{
            put("name", "SampleView");
            put("visit", "1");
            put("segment", "Windows");
            put("start", "1");
        }};

        Assert.assertEquals(0L, moduleBackendMode.eventQSize);
        backendMode.recordView("device-id-1", "[CLY]_view", segmentation, 1646640780130L);

        JSONArray events = moduleBackendMode.eventQueues.get("device-id-1");
        Assert.assertEquals(1L, events.length());
        Assert.assertEquals(1L, moduleBackendMode.eventQSize);

        JSONObject event = events.getJSONObject(0);
        validateEventFields("[CLY]_view", 1, null, null, 1, 13, 1646640780130L, event);

        JSONObject segments = event.getJSONObject("segmentation");
        Assert.assertEquals("SampleView", segments.get("name"));
        Assert.assertEquals("1", segments.get("visit"));
        Assert.assertEquals("Windows", segments.get("segment"));
        Assert.assertEquals("1", segments.get("start"));
    }

    @Test
    public void singleDeviceIdEventTest() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();

        Map<String, Object> segmentation = new HashMap<>();
        segmentation.put("key1", "value1");
        segmentation.put("key2", "value2");

        Assert.assertEquals(0, moduleBackendMode.eventQSize);
        backendMode.recordEvent("device-id-1", "key-1", 1, 0.1, 10, segmentation, 1646640780130L);

        JSONArray events = moduleBackendMode.eventQueues.get("device-id-1");
        Assert.assertEquals(1, events.length());
        Assert.assertEquals(1, moduleBackendMode.eventQSize);

        JSONObject event = events.getJSONObject(0);
        validateEventFields("key-1", 1, 0.1, 10.0, 1, 13, 1646640780130L, event);

        JSONObject segments = event.getJSONObject("segmentation");
        Assert.assertEquals("value1", segments.get("key1"));
        Assert.assertEquals("value2", segments.get("key2"));
    }

    @Test
    public void multipleDeviceIdEventTest() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();

        Map<String, Object> segmentation = new HashMap<>();
        segmentation.put("key1", "value1");
        segmentation.put("key2", "value2");

        Map<String, Object> segmentation1 = new HashMap<>();
        segmentation1.put("key3", "value3");
        segmentation1.put("key4", "value4");

        Assert.assertEquals(0, moduleBackendMode.eventQSize);
        backendMode.recordEvent("device-id-1", "key-1", 1, 0.1, 10, segmentation, 1646640780130L);

        Assert.assertEquals(1, moduleBackendMode.eventQSize);
        backendMode.recordEvent("device-id-2", "key-2", 1, 0.1, 10, segmentation, 1646640780130L);
        backendMode.recordEvent("device-id-2", "key-3", 2, 0.2, 20, segmentation1, 1646644457826L);

        Assert.assertEquals(3, moduleBackendMode.eventQSize);

        //Events with Device ID = 'device-id-1'
        JSONArray events = moduleBackendMode.eventQueues.get("device-id-2");
        Assert.assertEquals(2, events.length());

        JSONObject event = events.getJSONObject(0);
        validateEventFields("key-2", 1, 0.1, 10.0, 1, 13, 1646640780130L, event);


        JSONObject segments = event.getJSONObject("segmentation");
        Assert.assertEquals("value1", segments.get("key1"));
        Assert.assertEquals("value2", segments.get("key2"));

        //Events with Device ID = 'device-id-2'
        events = moduleBackendMode.eventQueues.get("device-id-2");
        Assert.assertEquals(2, events.length());

        event = events.getJSONObject(0);
        Assert.assertEquals("key-2", event.get("key"));
        validateEventFields("key-2", 1, 0.1, 10.0, 1, 13, 1646640780130L, event);


        segments = event.getJSONObject("segmentation");
        Assert.assertEquals("value1", segments.get("key1"));
        Assert.assertEquals("value2", segments.get("key2"));

        event = events.getJSONObject(1);
        Assert.assertEquals("key-3", event.get("key"));
        validateEventFields("key-3", 2, 0.2, 20.0, 1, 14, 1646644457826L, event);


        segments = event.getJSONObject("segmentation");
        Assert.assertEquals("value3", segments.get("key3"));
        Assert.assertEquals("value4", segments.get("key4"));
    }

    @Test
    public void eventThreshHoldTest() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();

        Map<String, Object> segmentation = new HashMap<>();
        segmentation.put("key1", "value1");
        segmentation.put("key2", "value2");

        Map<String, Object> segmentation1 = new HashMap<>();
        segmentation1.put("key3", "value3");
        segmentation1.put("key4", "value4");

        Assert.assertEquals(0, moduleBackendMode.eventQSize);

        backendMode.recordEvent("device-id-1", "key-1", 1, 0.1, 10, segmentation, 1646640780130L);
        Assert.assertEquals(1, moduleBackendMode.eventQSize);
        Assert.assertEquals(1, moduleBackendMode.eventQueues.get("device-id-1").length());

        backendMode.recordEvent("device-id-1", "key-2", 1, 0.1, 10, segmentation, 1646640780130L);
        Assert.assertEquals(2, moduleBackendMode.eventQSize);
        Assert.assertEquals(2, moduleBackendMode.eventQueues.get("device-id-1").length());

        backendMode.recordEvent("device-id-1", "key-3", 1, 0.1, 10, segmentation, 1646640780130L);
        Assert.assertEquals(3, moduleBackendMode.eventQSize);

        backendMode.recordEvent("device-id-1", "key-3", 1, 0.1, 10, segmentation, 1646640780130L);
        Assert.assertEquals(0, moduleBackendMode.eventQSize);
        Assert.assertEquals(null, moduleBackendMode.eventQueues.get("device-id-1"));

        backendMode.recordEvent("device-id-1", "key-1", 1, 0.1, 10, segmentation, 1646640780130L);
        Assert.assertEquals(1, moduleBackendMode.eventQSize);
        Assert.assertEquals(1, moduleBackendMode.eventQueues.get("device-id-1").length());

        backendMode.recordEvent("device-id-2", "key-2", 1, 0.1, 10, segmentation, 1646640780130L);
        Assert.assertEquals(2, moduleBackendMode.eventQSize);
        Assert.assertEquals(1, moduleBackendMode.eventQueues.get("device-id-1").length());
        Assert.assertEquals(1, moduleBackendMode.eventQueues.get("device-id-2").length());

        backendMode.recordEvent("device-id-2", "key-3", 1, 0.1, 10, segmentation, 1646640780130L);
        Assert.assertEquals(3, moduleBackendMode.eventQSize);
        Assert.assertEquals(1, moduleBackendMode.eventQueues.get("device-id-1").length());
        Assert.assertEquals(2, moduleBackendMode.eventQueues.get("device-id-2").length());

        backendMode.recordEvent("device-id-2", "key-4", 2, 0.2, 20, segmentation1, 1646644457826L);
        Assert.assertEquals(0, moduleBackendMode.eventQSize);
        Assert.assertEquals(null, moduleBackendMode.eventQueues.get("device-id-1"));
        Assert.assertEquals(null, moduleBackendMode.eventQueues.get("device-id-2"));
    }

    @Test
    public void eventPackagingOnSessionUpdateTest() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();

        Map<String, Object> segmentation = new HashMap<>();
        segmentation.put("key1", "value1");
        segmentation.put("key2", "value2");

        Map<String, Object> segmentation1 = new HashMap<>();
        segmentation1.put("key3", "value3");
        segmentation1.put("key4", "value4");

        Assert.assertEquals(0, moduleBackendMode.eventQSize);
        Assert.assertEquals(0, moduleBackendMode.requestQ.size());

        backendMode.recordEvent("device-id-1", "key-1", 1, 0.1, 10, segmentation, 1646640780130L);
        Assert.assertEquals(1, moduleBackendMode.eventQSize);
        Assert.assertEquals(0, moduleBackendMode.requestQ.size());
        Assert.assertEquals(1, moduleBackendMode.eventQueues.get("device-id-1").length());

        backendMode.recordEvent("device-id-2", "key-3", 1, 0.1, 10, segmentation, 1646640780130L);
        Assert.assertEquals(2, moduleBackendMode.eventQSize);
        Assert.assertEquals(0, moduleBackendMode.requestQ.size());
        Assert.assertEquals(1, moduleBackendMode.eventQueues.get("device-id-1").length());
        Assert.assertEquals(1, moduleBackendMode.eventQueues.get("device-id-2").length());

        backendMode.recordEvent("device-id-2", "key-4", 2, 0.2, 20, segmentation1, 1646644457826L);
        Assert.assertEquals(3, moduleBackendMode.eventQSize);
        Assert.assertEquals(0, moduleBackendMode.requestQ.size());
        Assert.assertEquals(1, moduleBackendMode.eventQueues.get("device-id-1").length());
        Assert.assertEquals(2, moduleBackendMode.eventQueues.get("device-id-2").length());

        backendMode.sessionUpdate("device-id-2", 60, 1646644457826L);
        Assert.assertEquals(0, moduleBackendMode.eventQSize);
        Assert.assertEquals(3, moduleBackendMode.requestQ.size());
        Assert.assertNull(moduleBackendMode.eventQueues.get("device-id-1"));
        Assert.assertNull(moduleBackendMode.eventQueues.get("device-id-2"));
    }

    @Test
    public void eventPackagingOnSessionEndTest() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();

        Map<String, Object> segmentation = new HashMap<>();
        segmentation.put("key1", "value1");
        segmentation.put("key2", "value2");

        Map<String, Object> segmentation1 = new HashMap<>();
        segmentation1.put("key3", "value3");
        segmentation1.put("key4", "value4");

        Assert.assertEquals(0, moduleBackendMode.eventQSize);
        Assert.assertEquals(0, moduleBackendMode.requestQ.size());

        backendMode.recordEvent("device-id-1", "key-1", 1, 0.1, 10, segmentation, 1646640780130L);
        Assert.assertEquals(1, moduleBackendMode.eventQSize);
        Assert.assertEquals(0, moduleBackendMode.requestQ.size());
        Assert.assertEquals(1, moduleBackendMode.eventQueues.get("device-id-1").length());

        backendMode.recordEvent("device-id-2", "key-3", 1, 0.1, 10, segmentation, 1646640780130L);
        Assert.assertEquals(2, moduleBackendMode.eventQSize);
        Assert.assertEquals(0, moduleBackendMode.requestQ.size());
        Assert.assertEquals(1, moduleBackendMode.eventQueues.get("device-id-1").length());
        Assert.assertEquals(1, moduleBackendMode.eventQueues.get("device-id-2").length());

        backendMode.recordEvent("device-id-2", "key-4", 2, 0.2, 20, segmentation1, 1646644457826L);
        Assert.assertEquals(3, moduleBackendMode.eventQSize);
        Assert.assertEquals(0, moduleBackendMode.requestQ.size());
        Assert.assertEquals(1, moduleBackendMode.eventQueues.get("device-id-1").length());
        Assert.assertEquals(2, moduleBackendMode.eventQueues.get("device-id-2").length());

        backendMode.sessionEnd("device-id-2", 60, 1646644457826L);
        Assert.assertEquals(1, moduleBackendMode.eventQSize);
        Assert.assertEquals(2, moduleBackendMode.requestQ.size());
        Assert.assertNull(moduleBackendMode.eventQueues.get("device-id-2"));
        Assert.assertEquals(1, moduleBackendMode.eventQueues.get("device-id-1").length());
    }

    @Test
    public void sessionBeginTest() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();
        backendMode.sessionBegin("device-id-1", 1646640780130L);

        Assert.assertEquals(1, moduleBackendMode.requestQ.size());
        Request request = moduleBackendMode.requestQ.remove();

        Assert.assertEquals("1", request.params.get("begin_session"));
        validateRequestTimeFields("device-id-1", 1646640780130L, request);
    }

    @Test
    public void sessionUpdateTest() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();
        backendMode.sessionUpdate("device-id-1", 10.5, 1646640780130L);

        Assert.assertEquals(1, moduleBackendMode.requestQ.size());
        Request request = moduleBackendMode.requestQ.remove();

        Assert.assertEquals("10.5", request.params.get("session_duration"));
        validateRequestTimeFields("device-id-1", 1646640780130L, request);
    }

    @Test
    public void sessionEndTest() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();
        backendMode.sessionEnd("device-id-1", 10.5, 1646640780130L);

        Assert.assertEquals(1, moduleBackendMode.requestQ.size());
        Request request = moduleBackendMode.requestQ.remove();

        Assert.assertEquals("1", request.params.get("end_session"));
        Assert.assertEquals("10.5", request.params.get("session_duration"));
        validateRequestTimeFields("device-id-1", 1646640780130L, request);
    }

    @Test
    public void crashTest() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();
        Map<String, Object> segmentation = new HashMap<String, Object>() {{
            put("key1", "value1");
        }};
        try {
            int a = 10 / 0;
        } catch (Exception e) {
            backendMode.recordException("device-id-1", e, segmentation, 1646640780130L);
            backendMode.recordException("device-id-2", "Divided By Zero", "stack traces", null, 0);

            Assert.assertEquals(2, moduleBackendMode.requestQ.size());
            Request request = moduleBackendMode.requestQ.remove();

            String crash = request.params.get("crash");
            JSONObject crashJson = new JSONObject(crash);

            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);

            Assert.assertEquals(e.getMessage(), crashJson.get("_name"));
            Assert.assertEquals(sw.toString(), crashJson.get("_error"));
            validateRequestTimeFields("device-id-1", 1646640780130L, request);


            JSONObject segments = crashJson.getJSONObject("_custom");
            Assert.assertEquals("value1", segments.get("key1"));

            Assert.assertEquals(1, moduleBackendMode.requestQ.size());
            request = moduleBackendMode.requestQ.remove();

            crash = request.params.get("crash");
            crashJson = new JSONObject(crash);

            Assert.assertEquals("Divided By Zero", crashJson.get("_name"));
            Assert.assertEquals("stack traces", crashJson.get("_error"));

            validateRequestTimeFields("device-id-2", System.currentTimeMillis(), request);

            segments = crashJson.getJSONObject("_custom");
            Assert.assertTrue(segments.isEmpty());
        }
    }

    @Test
    public void userPropertiesTest() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();

        // User detail
        Map<String, Object> userDetail = new HashMap<>();
        userDetail.put("name", "Full Name");
        userDetail.put("username", "username1");
        userDetail.put("email", "user@gmail.com");
        userDetail.put("organization", "Countly");
        userDetail.put("phone", "000-111-000");
        userDetail.put("gender", "M");
        userDetail.put("byear", "1991");

        // User custom detail
        Map<String, Object> customDetail = new HashMap<>();
        customDetail.put("hair", "black");
        customDetail.put("height", 5.9);

        // Operations: inc, mul
        Map<String, Object> operations = new HashMap<>();
        operations.put("$inc", 1);

        //property 'weight', operation 'increment'
        customDetail.put("weight", operations);
        userDetail.put("custom", customDetail);

        backendMode.recordUserProperties("device-id-1", userDetail, 1646640780130L);

        Assert.assertEquals(1, moduleBackendMode.requestQ.size());
        Request request = moduleBackendMode.requestQ.remove();
        validateRequestTimeFields("device-id-1", 1646640780130L, request);

        String userDetails = request.params.get("user_details");

        JSONObject userDetailsJson = new JSONObject(userDetails);
        JSONObject customPropertiesJson = userDetailsJson.getJSONObject("custom");
        JSONObject operationsJson = customPropertiesJson.getJSONObject("weight");

        //User details
        Assert.assertEquals("Full Name", userDetailsJson.get("name"));
        Assert.assertEquals("username1", userDetailsJson.get("username"));
        Assert.assertEquals("user@gmail.com", userDetailsJson.get("email"));
        Assert.assertEquals("Countly", userDetailsJson.get("organization"));
        Assert.assertEquals("000-111-000", userDetailsJson.get("phone"));
        Assert.assertEquals("M", userDetailsJson.get("gender"));
        Assert.assertEquals("1991", userDetailsJson.get("byear"));

        //Custom properties
        Assert.assertEquals("black", customPropertiesJson.get("hair"));
        Assert.assertEquals(5.9, customPropertiesJson.get("height"));

        //Operations
        Assert.assertEquals(1, operationsJson.get("$inc"));
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

        Assert.assertEquals(DeviceCore.dev.getTimezoneOffset() + "", request.params.get("tz"));
        Assert.assertEquals(dow + "", request.params.get("dow"));
        Assert.assertEquals(hour + "", request.params.get("hour"));
        Assert.assertEquals(deviceID, request.params.get("device_id"));
        Assert.assertEquals(timestamp + "", request.params.get("timestamp"));
    }
}
