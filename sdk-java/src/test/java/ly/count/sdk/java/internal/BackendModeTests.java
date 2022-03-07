package ly.count.sdk.java.internal;

import ly.count.sdk.java.Config;
import ly.count.sdk.java.Event;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.powermock.reflect.Whitebox;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.mock;

@RunWith(JUnit4.class)
public class BackendModeTests {
    private CtxCore ctx;
    InternalConfig config;
    ModuleBackendMode moduleBackendMode;

    @Before
    public void setupEveryTest() throws Exception {
        config = new InternalConfig(new Config("https://try.count.ly", "COUNTLY_APP_KEY"));
        config.setEventsBufferSize(3);
        moduleBackendMode = new ModuleBackendMode();
        moduleBackendMode.init(config);
    }

    @Test
    public void singleDeviceIdEventTest() {
        ModuleBackendMode.BackendMode backendMode = moduleBackendMode.new BackendMode();

        Map<String, String> segmentation = new HashMap<>();
        segmentation.put("key1", "value1");
        segmentation.put("key2", "value2");

        Assert.assertEquals(0L, moduleBackendMode.getEventQSize());
        backendMode.recordEvent("device-id-1", "key-1", 1, 0.1, 10, segmentation, 1646640780130L);

        JSONArray events = moduleBackendMode.getEventQueues().get("device-id-1");
        Assert.assertEquals(1L, events.length());
        Assert.assertEquals(1L, moduleBackendMode.getEventQSize());

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

        Assert.assertEquals(0L, moduleBackendMode.getEventQSize());
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
        Assert.assertEquals(0, moduleBackendMode.getEventQSize());
        Assert.assertEquals(null, moduleBackendMode.getEventQueues().get("device-id-1"));

        backendMode.recordEvent("device-id-1", "key-1", 1, 0.1, 10, segmentation, 1646640780130L);
        Assert.assertEquals(1, moduleBackendMode.getEventQSize());
        Assert.assertEquals(1, moduleBackendMode.getEventQueues().get("device-id-1").length());

        backendMode.recordEvent("device-id-2", "key-2", 1, 0.1, 10, segmentation, 1646640780130L);
        Assert.assertEquals(2, moduleBackendMode.getEventQSize());
        Assert.assertEquals(1, moduleBackendMode.getEventQueues().get("device-id-1").length());
        Assert.assertEquals(1, moduleBackendMode.getEventQueues().get("device-id-2").length());

        
        backendMode.recordEvent("device-id-2", "key-3", 2, 0.2, 20, segmentation1, 1646644457826L);
        Assert.assertEquals(0, moduleBackendMode.getEventQSize());
        Assert.assertEquals(null, moduleBackendMode.getEventQueues().get("device-id-1"));
        Assert.assertEquals(null, moduleBackendMode.getEventQueues().get("device-id-2"));
    }
}
