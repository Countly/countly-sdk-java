package ly.count.sdk.java.internal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ModuleEventsTests {

    private ModuleEvents moduleEvents;

    private void init(Config cc) {
        Countly.instance().init(cc);
        moduleEvents = (ModuleEvents) SDKCore.instance.module(CoreFeature.Events.getIndex());
    }

    private void stop() {
        Countly.stop(true);
    }

    /**
     * Recording an event with segmentation
     * "recordEvent" function should create an event with given key and segmentation and add it to event queue
     * recorded event should have correct key, segmentation, count, sum, duration, dow, hour and timestamp
     */
    @Test
    public void recordEvent() {
        Config config = TestUtils.getBaseConfig();
        config.enableFeatures(Config.Feature.Events).setEventQueueSizeToSend(4);
        init(config);

        List<EventImpl> events = TestUtils.getCurrentEventQueue(moduleEvents.ctx.getContext(), moduleEvents.L);
        validateQueueSize(0, events);

        //create segmentation
        Map<String, Object> segmentation = new HashMap<>();
        segmentation.put("name", "Johny");
        segmentation.put("weight", 67);
        segmentation.put("bald", true);

        //record event with key segmentation and count
        Countly.instance().events().recordEvent("test-recordEvent", 1, 45.9, segmentation, 32.0);

        //check if event was recorded correctly and size of event queue is equal to size of events in queue
        events = TestUtils.getCurrentEventQueue(moduleEvents.ctx.getContext(), moduleEvents.L);
        validateQueueSize(1, events);

        //check if event was recorded correctly
        EventImpl event = events.get(0);
        EventImpl eventInMemory = moduleEvents.eventQueue.eventQueueMemoryCache.get(0);
        validateEvent(event, "test-recordEvent", segmentation, 1, 45.9, 32.0, event.dow, event.hour, event.timestamp);
        validateEvent(eventInMemory, "test-recordEvent", segmentation, 1, 45.9, 32.0, event.dow, event.hour, event.timestamp);
        stop();
    }

    /**
     * Records an event with for the next test case
     */
    @Test
    public void recordEvent_fillEventQueue() {
        Config config = TestUtils.getBaseConfig();
        config.enableFeatures(Config.Feature.Events).setEventQueueSizeToSend(4);
        init(config);
        Assert.assertEquals(0, moduleEvents.eventQueue.eqSize());

        //create segmentation
        Map<String, Object> segmentation = new HashMap<>();
        segmentation.put("size", "xl");
        segmentation.put("height", 184);
        segmentation.put("married", false);

        //record event with key segmentation
        Countly.instance().events().recordEvent("test-recordEvent-Filler", segmentation);

        //check if event was recorded correctly and size of event queue is equal to size of events in queue
        List<EventImpl> events = TestUtils.getCurrentEventQueue(moduleEvents.ctx.getContext(), moduleEvents.L);
        EventImpl q1 = events.get(0);
        Assert.assertEquals(1, moduleEvents.eventQueue.eqSize());
        Assert.assertEquals(1, events.size());
        validateEvent(q1, "test-recordEvent-Filler", segmentation, 1, null, null, q1.dow, q1.hour, q1.timestamp);
        stop();
    }

    /**
     * This test case re-inits Countly and tries to read
     * existing event queue from storage
     */
    @Test
    public void recordEvent_reInitCountly() {
        Config config = TestUtils.getBaseConfig();
        config.enableFeatures(Config.Feature.Events).setEventQueueSizeToSend(4);
        init(config);
        moduleEvents.eventQueue.clear();
        Assert.assertEquals(0, moduleEvents.eventQueue.eqSize());

        //create segmentation
        Map<String, Object> segmentation = new HashMap<>();
        segmentation.put("exam_name", "CENG 101");
        segmentation.put("score", 100);
        segmentation.put("cheated", false);

        //record event with key segmentation
        Countly.instance().events().recordEvent("testInDiskEventQueue", segmentation);

        //now purposely re-init Countly
        stop();
    }

    /**
     * start event successfully created timed event and end it
     */
    @Test
    public void startEvent() {
        Config config = TestUtils.getBaseConfig();
        config.enableFeatures(Config.Feature.Events).setEventQueueSizeToSend(4);
        init(config);
        String eventName = "test-startEvent";

        try {
            startEvent(eventName);
        } finally {
            endEvent(eventName, null, 1, null);
        }
        stop();
    }

    /**
     * start event should not create with empty key
     */
    @Test
    public void startEvent_emptyKey() {
        Config config = TestUtils.getBaseConfig();
        config.enableFeatures(Config.Feature.Events).setEventQueueSizeToSend(4);
        init(config);
        Assert.assertFalse(Countly.instance().events().startEvent(""));
        stop();
    }

    /**
     * start event should not create with null key
     */
    @Test
    public void startEvent_nullKey() {
        Config config = TestUtils.getBaseConfig();
        config.enableFeatures(Config.Feature.Events).setEventQueueSizeToSend(4);
        init(config);
        Assert.assertFalse(Countly.instance().events().startEvent(null));
        stop();
    }

    /**
     * start event should not create with already started event
     */
    @Test
    public void startEvent_alreadyStarted() {
        Config config = TestUtils.getBaseConfig();
        config.enableFeatures(Config.Feature.Events).setEventQueueSizeToSend(4);
        init(config);

        String eventName = "test-startEvent_alreadyStarted";

        try {
            startEvent(eventName);
            boolean result = Countly.instance().events().startEvent(eventName);
            Assert.assertFalse(result);
        } finally {
            endEvent(eventName, null, 1, null);
        }
        stop();
    }

    /**
     * end event successfully ended the timed event
     */
    @Test
    public void endEvent() {
        Config config = TestUtils.getBaseConfig();
        config.enableFeatures(Config.Feature.Events).setEventQueueSizeToSend(4);
        init(config);
        String eventName = "test-endEvent";

        startEvent(eventName); // start event to end it
        endEvent(eventName, null, 1, null);

        stop();
    }

    /**
     * End event with empty key should not net
     */
    @Test
    public void endEvent_emptyKey() {
        Config config = TestUtils.getBaseConfig();
        config.enableFeatures(Config.Feature.Events).setEventQueueSizeToSend(4);
        init(config);
        Assert.assertFalse(Countly.instance().events().endEvent(""));
        stop();
    }

    /**
     * End event with null key should not end
     */
    @Test
    public void endEvent_nullKey() {
        Config config = TestUtils.getBaseConfig();
        config.enableFeatures(Config.Feature.Events).setEventQueueSizeToSend(4);
        init(config);
        Assert.assertFalse(Countly.instance().events().endEvent(null));
        stop();
    }

    /**
     * End event with not started event should not work
     */
    @Test
    public void endEvent_notStarted() {
        Config config = TestUtils.getBaseConfig();
        config.enableFeatures(Config.Feature.Events).setEventQueueSizeToSend(4);
        init(config);
        Assert.assertFalse(Countly.instance().events().endEvent("test-endEvent_notStarted"));
        stop();
    }

    /**
     * End event with already ended event should not process
     */
    @Test
    public void endEvent_alreadyEnded() {
        Config config = TestUtils.getBaseConfig();
        config.enableFeatures(Config.Feature.Events).setEventQueueSizeToSend(4);
        init(config);
        Assert.assertFalse(Countly.instance().events().endEvent("test-endEvent_alreadyEnded"));
        stop();
    }

    /**
     * End event with segmentation should end successfully
     */
    @Test
    public void endEvent_withSegmentation() {
        Config config = TestUtils.getBaseConfig();
        config.enableFeatures(Config.Feature.Events).setEventQueueSizeToSend(4);
        init(config);

        String eventName = "test-endEvent_withSegmentation";

        startEvent(eventName); // start event to end it

        Map<String, Object> segmentation = new HashMap<>();
        segmentation.put("hair_color", "red");
        segmentation.put("hair_length", "short");
        segmentation.put("chauffeur", "g3chauffeur"); //

        endEvent(eventName, segmentation, 1, 5.0);
        stop();
    }

    /**
     * End event with segmentation and negative count should throw <code>IllegalArgumentException</code>
     */
    @Test(expected = IllegalArgumentException.class)
    public void endEvent_withSegmentation_negativeCount() {
        Config config = TestUtils.getBaseConfig();
        config.enableFeatures(Config.Feature.Events).setEventQueueSizeToSend(4);
        init(config);

        String eventName = "test-endEvent_withSegmentation_negativeCount";

        startEvent(eventName); // start event to end it

        Map<String, Object> segmentation = new HashMap<>();
        segmentation.put("horse_name", "Alice");
        segmentation.put("bet_amount", 300);
        segmentation.put("currency", "Dollar"); //

        endEvent(eventName, segmentation, -7, 67.0);
        stop();
    }

    private void validateQueueSize(int expectedSize, List<EventImpl> events) {
        Assert.assertEquals(expectedSize, events.size());
        Assert.assertEquals(expectedSize, moduleEvents.eventQueue.eqSize());
    }

    private void validateEvent(EventImpl gonnaValidate, String key, Map<String, Object> segmentation,
        int count, Double sum, Double duration, int dow, int hour, long timestamp) {
        Assert.assertEquals(key, gonnaValidate.key);
        Assert.assertEquals(segmentation, gonnaValidate.segmentation);
        Assert.assertEquals(count, gonnaValidate.count);
        Assert.assertEquals(sum, gonnaValidate.sum);
        Assert.assertEquals(duration, gonnaValidate.duration);
        Assert.assertEquals(dow, gonnaValidate.dow);
        Assert.assertEquals(hour, gonnaValidate.hour);
        Assert.assertEquals(timestamp, gonnaValidate.timestamp);
    }

    private void endEvent(String key, Map<String, Object> segmentation, int count, Double sum) {
        boolean result = Countly.instance().events().endEvent(key, segmentation, count, sum);
        Assert.assertTrue(result);
    }

    private void startEvent(String key) {
        boolean result = Countly.instance().events().startEvent(key);
        Assert.assertTrue(result);
    }
}
