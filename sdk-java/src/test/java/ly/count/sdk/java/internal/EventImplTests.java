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

    EventImpl event;

    JSONObject json;

    @Before
    public void setupEveryTest() {
        EventImpl eventImpl = new EventImpl((event) -> {

            EventImpl eventImpl1 = (EventImpl) event;

            Assert.assertEquals(5, eventImpl1.getCount());
            Assert.assertEquals(new Double(21.0), eventImpl1.getDuration());
            Assert.assertEquals(new Double(17.0), eventImpl1.getSum());
            Assert.assertEquals("test_event", eventImpl1.getKey());
            Assert.assertEquals("true", eventImpl1.getSegment("test"));
        }, "test_event", L);

        Map<String, String> segmentation = new HashMap<>();
        segmentation.put("test", "true");

        eventImpl.setCount(5);
        eventImpl.setDuration(21);
        eventImpl.setSum(17);
        eventImpl.setSegmentation(segmentation);

        event = eventImpl;

        JSONObject jsonValue = new JSONObject();

        jsonValue.put("key", "test_event");
        jsonValue.put("count", 5);
        jsonValue.put("sum", 17.0);
        jsonValue.put("dur", 21.0);
        jsonValue.put("timestamp", event.getTimestamp());
        jsonValue.put("hour", event.getHour());
        jsonValue.put("dow", event.getDow());
        jsonValue.put("segmentation", event.getSegmentation());

        json = jsonValue;
    }

    @Test
    public void eventImpl_testRecorder() {
        event.record();
    }

    @Test
    public void eventImpl_validateToJson() {
        Assert.assertEquals(json.toString(), event.toJSON(L));
    }

    @Test
    public void eventImpl_validateFromJson() {

        EventImpl fromJson = EventImpl.fromJSON(json.toString(), event1 -> {
        }, L);

        Assert.assertEquals(event.getKey(), fromJson.getKey());
        Assert.assertEquals(event.getCount(), fromJson.getCount());
        Assert.assertEquals(event.getSum(), fromJson.getSum());
        Assert.assertEquals(event.getDuration(), fromJson.getDuration());
        Assert.assertEquals(event.getTimestamp(), fromJson.getTimestamp());
        Assert.assertEquals(event.getHour(), fromJson.getHour());
        Assert.assertEquals(event.getDow(), fromJson.getDow());
        Assert.assertEquals(event.getSegmentation(), fromJson.getSegmentation());
    }

    @Test
    public void eventImpl_testGetters() {
        Assert.assertEquals("test_event", event.getKey());
        Assert.assertEquals(5, event.getCount());
        Assert.assertEquals(new Double(21.0), event.getDuration());
        Assert.assertEquals(new Double(17.0), event.getSum());
        Assert.assertEquals("true", event.getSegment("test"));
    }

    @Test
    public void eventImpl_testSetters() {
        event.setCount(10);
        event.setDuration(42);
        event.setSum(34);

        Map<String, String> segmentation = new HashMap<>();
        segmentation.put("test", "false");
        event.setSegmentation(segmentation);

        Assert.assertEquals(10, event.getCount());
        Assert.assertEquals(new Double(42.0), event.getDuration());
        Assert.assertEquals(new Double(34.0), event.getSum());
        Assert.assertEquals("false", event.getSegment("test"));
    }
}
