package ly.count.sdk.java.internal;

import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import ly.count.sdk.java.Event;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.powermock.reflect.Whitebox;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.mock;

@RunWith(JUnit4.class)
public class BackendModeTests {
    private CtxCore ctx;
    InternalConfig config;
    ModuleBackendMode moduleBackendMode;

    @BeforeClass
    public static void init() {
        Config cc = new Config("https://try.count.ly", "COUNTLY_APP_KEY");
        cc.setEventsBufferSize(4)
                .enableBackendMode(true);
        File targetFolder = new File("C:\\Users\\zahid\\Documents\\Countly\\data");
        Countly.init(targetFolder, cc);
    }

    @Before
    public void start() {
        moduleBackendMode = (ModuleBackendMode) Countly.backendMode().getModule();
        moduleBackendMode.defferUpload = true;
    }

    @After
    public void end() {
        moduleBackendMode.setEventQSize(0);
        moduleBackendMode.getRequestQ().clear();
        moduleBackendMode.getEventQueues().clear();
    }

    @AfterClass
    public static void stop() throws Exception {
        Countly.stop(false);
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

        Map<String, String> segmentation = new HashMap<String, String>() {{
            put("name", "SampleView");
            put("visit", "1");
            put("segment", "Windows");
            put("start", "1");
        }};

        Assert.assertEquals(0L, moduleBackendMode.getEventQSize());
        backendMode.recordView("device-id-1", "[CLY]_view", segmentation, 1646640780130L);

        JSONArray events = moduleBackendMode.getEventQueues().get("device-id-1");
        Assert.assertEquals(1L, events.length());
        Assert.assertEquals(1L, moduleBackendMode.getEventQSize());

        JSONObject event = events.getJSONObject(0);
        Assert.assertEquals("[CLY]_view", event.get("key"));

        Assert.assertEquals(1, event.get("count"));
        Assert.assertEquals(1, event.get("dow"));
        Assert.assertEquals(13, event.get("hour"));
        Assert.assertEquals(1646640780130L, event.get("timestamp"));

        JSONObject segments = event.getJSONObject("segmentation");
        Assert.assertEquals("SampleView", segments.get("name"));
        Assert.assertEquals("1", segments.get("visit"));
        Assert.assertEquals("Windows", segments.get("segment"));
        Assert.assertEquals("1", segments.get("start"));
    }

    @Test
    public void singleDeviceIdEventTest() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();

        Map<String, String> segmentation = new HashMap<>();
        segmentation.put("key1", "value1");
        segmentation.put("key2", "value2");

        Assert.assertEquals(0, moduleBackendMode.getEventQSize());
        backendMode.recordEvent("device-id-1", "key-1", 1, 0.1, 10, segmentation, 1646640780130L);

        JSONArray events = moduleBackendMode.getEventQueues().get("device-id-1");
        Assert.assertEquals(1, events.length());
        Assert.assertEquals(1, moduleBackendMode.getEventQSize());

        JSONObject event = events.getJSONObject(0);
        Assert.assertEquals("key-1", event.get("key"));
        Assert.assertEquals(0.1, event.get("sum"));
        Assert.assertEquals(1, event.get("count"));
        Assert.assertEquals(10.0, event.get("dur"));
        Assert.assertEquals(1, event.get("dow"));
        Assert.assertEquals(13, event.get("hour"));
        Assert.assertEquals(1646640780130L, event.get("timestamp"));

        JSONObject segments = event.getJSONObject("segmentation");
        Assert.assertEquals("value1", segments.get("key1"));
        Assert.assertEquals("value2", segments.get("key2"));
    }

    @Test
    public void multipleDeviceIdEventTest() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();

        Map<String, String> segmentation = new HashMap<>();
        segmentation.put("key1", "value1");
        segmentation.put("key2", "value2");

        Map<String, String> segmentation1 = new HashMap<>();
        segmentation1.put("key3", "value3");
        segmentation1.put("key4", "value4");

        Assert.assertEquals(0, moduleBackendMode.getEventQSize());
        backendMode.recordEvent("device-id-1", "key-1", 1, 0.1, 10, segmentation, 1646640780130L);

        Assert.assertEquals(1, moduleBackendMode.getEventQSize());
        backendMode.recordEvent("device-id-2", "key-2", 1, 0.1, 10, segmentation, 1646640780130L);
        backendMode.recordEvent("device-id-2", "key-3", 2, 0.2, 20, segmentation1, 1646644457826L);

        Assert.assertEquals(3, moduleBackendMode.getEventQSize());

        //Events with Device ID = 'device-id-1'
        JSONArray events = moduleBackendMode.getEventQueues().get("device-id-2");
        Assert.assertEquals(2, events.length());

        JSONObject event = events.getJSONObject(0);
        Assert.assertEquals("key-2", event.get("key"));
        Assert.assertEquals(0.1, event.get("sum"));
        Assert.assertEquals(1, event.get("count"));
        Assert.assertEquals(10.0, event.get("dur"));
        Assert.assertEquals(1, event.get("dow"));
        Assert.assertEquals(13, event.get("hour"));
        Assert.assertEquals(1646640780130L, event.get("timestamp"));

        JSONObject segments = event.getJSONObject("segmentation");
        Assert.assertEquals("value1", segments.get("key1"));
        Assert.assertEquals("value2", segments.get("key2"));

        //Events with Device ID = 'device-id-2'
        events = moduleBackendMode.getEventQueues().get("device-id-2");
        Assert.assertEquals(2, events.length());

        event = events.getJSONObject(0);
        Assert.assertEquals("key-2", event.get("key"));
        Assert.assertEquals(0.1, event.get("sum"));
        Assert.assertEquals(1, event.get("count"));
        Assert.assertEquals(10.0, event.get("dur"));
        Assert.assertEquals(1, event.get("dow"));
        Assert.assertEquals(13, event.get("hour"));
        Assert.assertEquals(1646640780130L, event.get("timestamp"));

        segments = event.getJSONObject("segmentation");
        Assert.assertEquals("value1", segments.get("key1"));
        Assert.assertEquals("value2", segments.get("key2"));

        event = events.getJSONObject(1);
        Assert.assertEquals("key-3", event.get("key"));
        Assert.assertEquals(0.2, event.get("sum"));
        Assert.assertEquals(2, event.get("count"));
        Assert.assertEquals(20.0, event.get("dur"));
        Assert.assertEquals(1, event.get("dow"));
        Assert.assertEquals(14, event.get("hour"));
        Assert.assertEquals(1646644457826L, event.get("timestamp"));

        segments = event.getJSONObject("segmentation");
        Assert.assertEquals("value3", segments.get("key3"));
        Assert.assertEquals("value4", segments.get("key4"));
    }

    @Test
    public void eventThreshHoldTest() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();

        Map<String, String> segmentation = new HashMap<>();
        segmentation.put("key1", "value1");
        segmentation.put("key2", "value2");

        Map<String, String> segmentation1 = new HashMap<>();
        segmentation1.put("key3", "value3");
        segmentation1.put("key4", "value4");

        Assert.assertEquals(0, moduleBackendMode.getEventQSize());

        backendMode.recordEvent("device-id-1", "key-1", 1, 0.1, 10, segmentation, 1646640780130L);
        Assert.assertEquals(1, moduleBackendMode.getEventQSize());
        Assert.assertEquals(1, moduleBackendMode.getEventQueues().get("device-id-1").length());

        backendMode.recordEvent("device-id-1", "key-2", 1, 0.1, 10, segmentation, 1646640780130L);
        Assert.assertEquals(2, moduleBackendMode.getEventQSize());
        Assert.assertEquals(2, moduleBackendMode.getEventQueues().get("device-id-1").length());

        backendMode.recordEvent("device-id-1", "key-3", 1, 0.1, 10, segmentation, 1646640780130L);
        Assert.assertEquals(3, moduleBackendMode.getEventQSize());

        backendMode.recordEvent("device-id-1", "key-3", 1, 0.1, 10, segmentation, 1646640780130L);
        Assert.assertEquals(0, moduleBackendMode.getEventQSize());
        Assert.assertEquals(null, moduleBackendMode.getEventQueues().get("device-id-1"));

        backendMode.recordEvent("device-id-1", "key-1", 1, 0.1, 10, segmentation, 1646640780130L);
        Assert.assertEquals(1, moduleBackendMode.getEventQSize());
        Assert.assertEquals(1, moduleBackendMode.getEventQueues().get("device-id-1").length());

        backendMode.recordEvent("device-id-2", "key-2", 1, 0.1, 10, segmentation, 1646640780130L);
        Assert.assertEquals(2, moduleBackendMode.getEventQSize());
        Assert.assertEquals(1, moduleBackendMode.getEventQueues().get("device-id-1").length());
        Assert.assertEquals(1, moduleBackendMode.getEventQueues().get("device-id-2").length());

        backendMode.recordEvent("device-id-2", "key-3", 1, 0.1, 10, segmentation, 1646640780130L);
        Assert.assertEquals(3, moduleBackendMode.getEventQSize());
        Assert.assertEquals(1, moduleBackendMode.getEventQueues().get("device-id-1").length());
        Assert.assertEquals(2, moduleBackendMode.getEventQueues().get("device-id-2").length());

        backendMode.recordEvent("device-id-2", "key-4", 2, 0.2, 20, segmentation1, 1646644457826L);
        Assert.assertEquals(0, moduleBackendMode.getEventQSize());
        Assert.assertEquals(null, moduleBackendMode.getEventQueues().get("device-id-1"));
        Assert.assertEquals(null, moduleBackendMode.getEventQueues().get("device-id-2"));
    }

    @Test
    public void eventPackagingOnSessionUpdateTest() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();

        Map<String, String> segmentation = new HashMap<>();
        segmentation.put("key1", "value1");
        segmentation.put("key2", "value2");

        Map<String, String> segmentation1 = new HashMap<>();
        segmentation1.put("key3", "value3");
        segmentation1.put("key4", "value4");

        Assert.assertEquals(0, moduleBackendMode.getEventQSize());
        Assert.assertEquals(0, moduleBackendMode.getRequestQ().size());

        backendMode.recordEvent("device-id-1", "key-1", 1, 0.1, 10, segmentation, 1646640780130L);
        Assert.assertEquals(1, moduleBackendMode.getEventQSize());
        Assert.assertEquals(0, moduleBackendMode.getRequestQ().size());
        Assert.assertEquals(1, moduleBackendMode.getEventQueues().get("device-id-1").length());

        backendMode.recordEvent("device-id-2", "key-3", 1, 0.1, 10, segmentation, 1646640780130L);
        Assert.assertEquals(2, moduleBackendMode.getEventQSize());
        Assert.assertEquals(0, moduleBackendMode.getRequestQ().size());
        Assert.assertEquals(1, moduleBackendMode.getEventQueues().get("device-id-1").length());
        Assert.assertEquals(1, moduleBackendMode.getEventQueues().get("device-id-2").length());

        backendMode.recordEvent("device-id-2", "key-4", 2, 0.2, 20, segmentation1, 1646644457826L);
        Assert.assertEquals(3, moduleBackendMode.getEventQSize());
        Assert.assertEquals(0, moduleBackendMode.getRequestQ().size());
        Assert.assertEquals(1, moduleBackendMode.getEventQueues().get("device-id-1").length());
        Assert.assertEquals(2, moduleBackendMode.getEventQueues().get("device-id-2").length());

        backendMode.sessionUpdate("device-id-2", 60, 1646644457826L);
        Assert.assertEquals(0, moduleBackendMode.getEventQSize());
        Assert.assertEquals(3, moduleBackendMode.getRequestQ().size());
        Assert.assertNull(moduleBackendMode.getEventQueues().get("device-id-1"));
        Assert.assertNull(moduleBackendMode.getEventQueues().get("device-id-2"));
    }

    @Test
    public void eventPackagingOnSessionEndTest() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();

        Map<String, String> segmentation = new HashMap<>();
        segmentation.put("key1", "value1");
        segmentation.put("key2", "value2");

        Map<String, String> segmentation1 = new HashMap<>();
        segmentation1.put("key3", "value3");
        segmentation1.put("key4", "value4");

        Assert.assertEquals(0, moduleBackendMode.getEventQSize());
        Assert.assertEquals(0, moduleBackendMode.getRequestQ().size());

        backendMode.recordEvent("device-id-1", "key-1", 1, 0.1, 10, segmentation, 1646640780130L);
        Assert.assertEquals(1, moduleBackendMode.getEventQSize());
        Assert.assertEquals(0, moduleBackendMode.getRequestQ().size());
        Assert.assertEquals(1, moduleBackendMode.getEventQueues().get("device-id-1").length());

        backendMode.recordEvent("device-id-2", "key-3", 1, 0.1, 10, segmentation, 1646640780130L);
        Assert.assertEquals(2, moduleBackendMode.getEventQSize());
        Assert.assertEquals(0, moduleBackendMode.getRequestQ().size());
        Assert.assertEquals(1, moduleBackendMode.getEventQueues().get("device-id-1").length());
        Assert.assertEquals(1, moduleBackendMode.getEventQueues().get("device-id-2").length());

        backendMode.recordEvent("device-id-2", "key-4", 2, 0.2, 20, segmentation1, 1646644457826L);
        Assert.assertEquals(3, moduleBackendMode.getEventQSize());
        Assert.assertEquals(0, moduleBackendMode.getRequestQ().size());
        Assert.assertEquals(1, moduleBackendMode.getEventQueues().get("device-id-1").length());
        Assert.assertEquals(2, moduleBackendMode.getEventQueues().get("device-id-2").length());

        backendMode.sessionEnd("device-id-2", 60, 1646644457826L);
        Assert.assertEquals(1, moduleBackendMode.getEventQSize());
        Assert.assertEquals(2, moduleBackendMode.getRequestQ().size());
        Assert.assertNull(moduleBackendMode.getEventQueues().get("device-id-2"));
        Assert.assertEquals(1, moduleBackendMode.getEventQueues().get("device-id-1").length());
    }

    @Test
    public void sessionBeginTest() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();
        backendMode.sessionBegin("device-id-1", 1646640780130L);

        Assert.assertEquals(1, moduleBackendMode.getRequestQ().size());
        Request request = moduleBackendMode.getRequestQ().remove();

        Assert.assertEquals("1", request.params.get("begin_session"));
        Assert.assertEquals("device-id-1", request.params.get("device_id"));

        Assert.assertEquals("300", request.params.get("tz"));
        Assert.assertEquals("1", request.params.get("dow"));
        Assert.assertEquals("13", request.params.get("hour"));
        Assert.assertEquals("1646640780130", request.params.get("timestamp"));
    }

    @Test
    public void sessionUpdateTest() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();
        backendMode.sessionUpdate("device-id-1", 10.5, 1646640780130L);

        Assert.assertEquals(1, moduleBackendMode.getRequestQ().size());
        Request request = moduleBackendMode.getRequestQ().remove();

        Assert.assertEquals("10.5", request.params.get("session_duration"));
        Assert.assertEquals("device-id-1", request.params.get("device_id"));

        Assert.assertEquals("300", request.params.get("tz"));
        Assert.assertEquals("1", request.params.get("dow"));
        Assert.assertEquals("13", request.params.get("hour"));
        Assert.assertEquals("1646640780130", request.params.get("timestamp"));
    }

    @Test
    public void sessionEndTest() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();
        backendMode.sessionEnd("device-id-1", 10.5, 1646640780130L);

        Assert.assertEquals(1, moduleBackendMode.getRequestQ().size());
        Request request = moduleBackendMode.getRequestQ().remove();

        Assert.assertEquals("1", request.params.get("end_session"));
        Assert.assertEquals("10.5", request.params.get("session_duration"));
        Assert.assertEquals("device-id-1", request.params.get("device_id"));

        Assert.assertEquals("300", request.params.get("tz"));
        Assert.assertEquals("1", request.params.get("dow"));
        Assert.assertEquals("13", request.params.get("hour"));
        Assert.assertEquals("1646640780130", request.params.get("timestamp"));
    }

    @Test
    public void crashTest() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();
        Map<String, String> segmentation = new HashMap<String, String>() {{
            put("key1", "value1");
        }};
        try {
            int a = 10 / 0;
        } catch (Exception e) {
            backendMode.recordException("device-id-1", e, segmentation, 1646640780130L);
            backendMode.recordException("device-id-2", "Divided By Zero", "stack traces", null, 0);

            Assert.assertEquals(2, moduleBackendMode.getRequestQ().size());
            Request request = moduleBackendMode.getRequestQ().remove();

            String crash = request.params.get("crash");
            JSONObject crashJson = new JSONObject(crash);

            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);

            Assert.assertEquals(e.getMessage(), crashJson.get("_name"));
            Assert.assertEquals(sw.toString(), crashJson.get("_error"));

            Assert.assertEquals("1", request.params.get("dow"));
            Assert.assertEquals("300", request.params.get("tz"));
            Assert.assertEquals("13", request.params.get("hour"));
            Assert.assertEquals("device-id-1", request.params.get("device_id"));
            Assert.assertEquals("1646640780130", request.params.get("timestamp"));

            JSONObject segments = crashJson.getJSONObject("_custom");
            Assert.assertEquals("value1", segments.get("key1"));

            Assert.assertEquals(1, moduleBackendMode.getRequestQ().size());
            request = moduleBackendMode.getRequestQ().remove();

            crash = request.params.get("crash");
            crashJson = new JSONObject(crash);


            Assert.assertEquals("Divided By Zero", crashJson.get("_name"));
            Assert.assertEquals("stack traces", crashJson.get("_error"));

            long timestamp = System.currentTimeMillis();

            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(timestamp);

            final int hour = calendar.get(Calendar.HOUR_OF_DAY);
            final int dow = calendar.get(Calendar.DAY_OF_WEEK) - 1;

            Assert.assertEquals(dow + "", request.params.get("dow"));
            Assert.assertEquals("300", request.params.get("tz"));
            Assert.assertEquals(hour + "", request.params.get("hour"));
            Assert.assertEquals("device-id-2", request.params.get("device_id"));

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

        Assert.assertEquals(1, moduleBackendMode.getRequestQ().size());
        Request request = moduleBackendMode.getRequestQ().remove();

        Assert.assertEquals("1", request.params.get("dow"));
        Assert.assertEquals("300", request.params.get("tz"));
        Assert.assertEquals("13", request.params.get("hour"));
        Assert.assertEquals("device-id-1", request.params.get("device_id"));
        Assert.assertEquals("1646640780130", request.params.get("timestamp"));

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
}
