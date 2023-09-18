package ly.count.sdk.java.internal;

import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.powermock.reflect.Whitebox;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import ly.count.sdk.java.Config;
import ly.count.sdk.java.Event;

import static org.mockito.Mockito.mock;

@RunWith(JUnit4.class)
public class EventImplTests {

    Log L = mock(Log.class);

    /**
     * This method tests the constructor of EventImpl class. It checks if the default values set in the
     * constructor are same
     */
    @Test
    public void eventImpl_TestDefaultConstructor() {
        EventImpl eventImpl = new EventImpl((event) -> {
        }, "test_event", L);

        Assert.assertEquals("test_event", eventImpl.key);
        Assert.assertEquals(1, eventImpl.count);
        Assert.assertNull(eventImpl.duration);
        Assert.assertNull(eventImpl.sum);
        Assert.assertTrue(eventImpl.getTimestamp() > 0);
        Assert.assertEquals(Device.dev.currentHour(), eventImpl.hour);
        Assert.assertEquals(Device.dev.currentDayOfWeek(), eventImpl.dow);
        Assert.assertNull(eventImpl.segmentation);
    }

    /**
     * This test case tests the record method of EventImpl class. It checks if the record method
     * is called.
     */
    @Test
    public void eventImpl_testRecorder() {

        EventImpl event = new EventImpl((event1) -> {

            EventImpl eventImpl1 = (EventImpl) event1;

            Assert.assertEquals(5, eventImpl1.count);
            Assert.assertEquals(new Double(21.0), eventImpl1.duration);
            Assert.assertEquals(new Double(17.0), eventImpl1.sum);
            Assert.assertEquals("test_event", eventImpl1.key);
            Assert.assertEquals("true", eventImpl1.getSegment("test"));
        }, "test_event", L);

        Map<String, String> segmentation = new HashMap<>();
        segmentation.put("test", "true");

        event.count = 5;
        event.duration = 21.0;
        event.sum = 17.0;
        event.segmentation = segmentation;

        event.record();
    }

    /**
     * This test case validates the toJSON method of EventImpl class. It checks if the JSON object
     * returned by the toJSON method is same as the JSON object created in the setupEveryTest method.
     */
    @Test
    public void eventImpl_validateToJson() {
        EventImpl event = new EventImpl((event1) -> {
        }, "test_buy_event", L);

        Map<String, String> segmentation = new HashMap<>();
        segmentation.put("valid", "false");

        event.duration = 34.0;
        event.sum = 9.0;
        event.segmentation = segmentation;

        JSONObject json = new JSONObject();

        json.put("key", "test_buy_event");
        json.put("count", 1);
        json.put("sum", 9.0);
        json.put("dur", 34.0);
        json.put("timestamp", event.timestamp);
        json.put("hour", Device.dev.currentHour());
        json.put("dow", Device.dev.currentDayOfWeek());
        json.put("segmentation", segmentation);

        Assert.assertEquals(json.toString(), event.toJSON(L));
    }

    /**
     * This test case validates the fromJSON method of EventImpl class. It checks if the EventImpl
     * object created by the fromJSON method is same as the EventImpl object created in the
     * setupEveryTest method.
     */
    @Test
    public void eventImpl_validateFromJson() {

        EventImpl event = new EventImpl((event1) -> {
        }, "test_sell_event", L);

        Map<String, String> segmentation = new HashMap<>();
        segmentation.put("sold", "true");

        event.count = 3;
        event.duration = 15.0;
        event.sum = 7.0;
        event.setSegmentation(segmentation);

        JSONObject json = new JSONObject();

        json.put("key", "test_sell_event");
        json.put("count", 3);
        json.put("sum", 7.0);
        json.put("dur", 15.0);
        json.put("timestamp", event.timestamp);
        json.put("hour", event.hour);
        json.put("dow", event.dow);
        json.put("segmentation", segmentation);

        EventImpl fromJson = EventImpl.fromJSON(json.toString(), event1 -> {
        }, L);

        Assert.assertEquals(event.key, fromJson.key);
        Assert.assertEquals(event.count, fromJson.count);
        Assert.assertEquals(event.sum, fromJson.sum);
        Assert.assertEquals(event.duration, fromJson.duration);
        Assert.assertEquals(event.timestamp, fromJson.timestamp);
        Assert.assertEquals(event.hour, fromJson.hour);
        Assert.assertEquals(event.dow, fromJson.dow);
        Assert.assertEquals(event.segmentation, fromJson.segmentation);
    }

    /**
     * This test case validates the getters of EventImpl class. It checks if the values returned by
     * the getters are same as the values set in the setupEveryTest method.
     */
    @Test
    public void eventImpl_testGetters() {
        EventImpl event = new EventImpl((event1) -> {
        }, "test_getter_event", L);

        Map<String, String> segmentation = new HashMap<>();
        segmentation.put("get_func", "yes");

        event.count = 47;
        event.duration = 59.5;
        event.sum = 37.0;
        event.segmentation = segmentation;

        Assert.assertEquals("test_getter_event", event.getKey());
        Assert.assertEquals(47, event.getCount());
        Assert.assertEquals(new Double(59.5), event.getDuration());
        Assert.assertEquals(new Double(37.0), event.getSum());
        Assert.assertEquals("yes", event.getSegment("get_func"));
    }

    /**
     * This test case validates the setters of EventImpl class. It checks if the values returned by
     * the getters are same as the values set in the setupEveryTest method.
     */
    @Test
    public void eventImpl_testSetters() {
        EventImpl event = new EventImpl((event1) -> {
        }, "test_setter_event", L);

        event.setCount(9);
        event.setDuration(78);
        event.setSum(46);

        Map<String, String> segmentation = new HashMap<>();
        segmentation.put("donated_amount", "37656387");
        event.setSegmentation(segmentation);

        Assert.assertEquals(9, event.count);
        Assert.assertEquals(new Double(78.0), event.duration);
        Assert.assertEquals(new Double(46.0), event.sum);
        Assert.assertEquals("37656387", event.getSegment("donated_amount"));
    }
}
