package ly.count.sdk.java.internal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import org.junit.After;
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

    @After
    public void stop() {
        Countly.instance().halt();
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

        String eventKey = TestUtils.randomUUID();

        //record event with key segmentation and count
        Countly.instance().events().recordEvent(eventKey, 1, 45.9, segmentation, 32.0);

        //check if event was recorded correctly and size of event queue is equal to size of events in queue
        events = TestUtils.getCurrentEventQueue(moduleEvents.ctx.getContext(), moduleEvents.L);
        validateQueueSize(1, events);

        //check if event was recorded correctly
        EventImpl event = events.get(0);
        EventImpl eventInMemory = moduleEvents.eventQueue.eventQueueMemoryCache.get(0);
        validateEvent(event, eventKey, segmentation, 1, 45.9, 32.0, event.dow, event.hour, event.timestamp);
        validateEvent(eventInMemory, eventKey, segmentation, 1, 45.9, 32.0, event.dow, event.hour, event.timestamp);
    }

    /**
     * Recording an event with negative count
     * "recordEvent" function should not create an event with given key and negative count
     * in memory and cache queue should be empty
     */
    @Test
    public void recordEvent_negativeCount() {
        Config config = TestUtils.getBaseConfig();
        config.enableFeatures(Config.Feature.Events).setEventQueueSizeToSend(4);
        init(config);

        List<EventImpl> events = TestUtils.getCurrentEventQueue(moduleEvents.ctx.getContext(), moduleEvents.L);

        validateQueueSize(0, events);

        Countly.instance().events().recordEvent(TestUtils.randomUUID(), -1);
        events = TestUtils.getCurrentEventQueue(moduleEvents.ctx.getContext(), moduleEvents.L);

        validateQueueSize(0, events);
    }

    /**
     * Recording an event with null key
     * "recordEvent" function should not create an event with given key null key
     * in memory and cache queue should be empty
     */
    @Test
    public void recordEvent_nullKey() {
        Config config = TestUtils.getBaseConfig();
        config.enableFeatures(Config.Feature.Events).setEventQueueSizeToSend(4);
        init(config);

        List<EventImpl> events = TestUtils.getCurrentEventQueue(moduleEvents.ctx.getContext(), moduleEvents.L);

        validateQueueSize(0, events);

        Countly.instance().events().recordEvent(null);
        events = TestUtils.getCurrentEventQueue(moduleEvents.ctx.getContext(), moduleEvents.L);

        validateQueueSize(0, events);
    }

    /**
     * Recording an event with empty key
     * "recordEvent" function should not create an event with given key empty key
     * in memory and cache queue should be empty
     */
    @Test
    public void recordEvent_emptyKey() {
        Config config = TestUtils.getBaseConfig();
        config.enableFeatures(Config.Feature.Events).setEventQueueSizeToSend(4);
        init(config);

        List<EventImpl> events = TestUtils.getCurrentEventQueue(moduleEvents.ctx.getContext(), moduleEvents.L);
        validateQueueSize(0, events);

        Countly.instance().events().recordEvent("");
        events = TestUtils.getCurrentEventQueue(moduleEvents.ctx.getContext(), moduleEvents.L);

        validateQueueSize(0, events);
    }

    /**
     * Recording an event with invalid segment data
     * "recordEvent" function should create an event with given segment
     * in memory and cache queue should contain it and invalid segment should not exist
     */
    @Test
    public void recordEvent_invalidSegment() {
        Config config = TestUtils.getBaseConfig();
        config.enableFeatures(Config.Feature.Events).setEventQueueSizeToSend(4);
        init(config);

        List<EventImpl> events = TestUtils.getCurrentEventQueue(moduleEvents.ctx.getContext(), moduleEvents.L);
        validateQueueSize(0, events);
        //create segmentation
        Map<String, Object> segmentation = new HashMap<>();
        segmentation.put("exam_name", "CENG 101");
        segmentation.put("score", 67);
        segmentation.put("cheated", false);
        segmentation.put("invalid", new HashMap<>());

        Map<String, Object> expectedSegmentation = new HashMap<>();
        expectedSegmentation.put("exam_name", "CENG 101");
        expectedSegmentation.put("score", 67);
        expectedSegmentation.put("cheated", false);

        String eventKey = TestUtils.randomUUID();
        //record event with key segmentation
        Countly.instance().events().recordEvent(eventKey, segmentation);

        events = TestUtils.getCurrentEventQueue(moduleEvents.ctx.getContext(), moduleEvents.L);
        validateQueueSize(1, events);

        //check if event was recorded correctly
        EventImpl event = events.get(0);
        EventImpl eventInMemory = moduleEvents.eventQueue.eventQueueMemoryCache.get(0);
        validateEvent(event, eventKey, expectedSegmentation, 1, null, null, event.dow, event.hour, event.timestamp);
        validateEvent(eventInMemory, eventKey, expectedSegmentation, 1, null, null, event.dow, event.hour, event.timestamp);
        Assert.assertEquals(3, event.segmentation.size());
        Assert.assertEquals(3, eventInMemory.segmentation.size());
        Assert.assertNull(event.getSegment("invalid"));
        Assert.assertNull(eventInMemory.getSegment("invalid"));
    }

    /**
     * Start an event
     * "startEvent" function should create a timed event
     * in memory and cache queue should contain it
     */
    @Test
    public void startEvent() {
        Config config = TestUtils.getBaseConfig();
        config.enableFeatures(Config.Feature.Events).setEventQueueSizeToSend(4);
        init(config);

        List<EventImpl> events;
        validateSize(0, 0);
        String eventName = TestUtils.randomUUID();

        startEvent(eventName);
        validateSize(0, 1);

        EventImpl timedEvent = moduleEvents.timedEvents.get(eventName);
        validateEvent(timedEvent, eventName, null, 1, null, null, timedEvent.dow, timedEvent.hour, timedEvent.timestamp);

        endEvent(eventName, null, 1, null);
        //duration testing is not possible because division is error-prone for small numbers like .212 and .211
        events = TestUtils.getCurrentEventQueue(moduleEvents.ctx.getContext(), moduleEvents.L);
        validateSize(1, 0);

        validateEvent(events.get(0), eventName, null, 1, null, events.get(0).duration, events.get(0).dow, events.get(0).hour, events.get(0).timestamp);
        validateEvent(moduleEvents.eventQueue.eventQueueMemoryCache.get(0), eventName, null, 1, null, events.get(0).duration, events.get(0).dow, events.get(0).hour, events.get(0).timestamp);
    }

    /**
     * Start an event
     * "startEvent" function should not create a timed event with empty key
     * in memory , timed events and cache queue should not contain it
     */
    @Test
    public void startEvent_emptyKey() {
        Config config = TestUtils.getBaseConfig();
        config.enableFeatures(Config.Feature.Events).setEventQueueSizeToSend(4);
        init(config);

        validateSize(0, 0);
        Assert.assertFalse(Countly.instance().events().startEvent(""));
        validateSize(0, 0);
    }

    /**
     * Start an event
     * "startEvent" function should not create a timed event with null key
     * in memory , timed events and cache queue should not contain it
     */
    @Test
    public void startEvent_nullKey() {
        Config config = TestUtils.getBaseConfig();
        config.enableFeatures(Config.Feature.Events).setEventQueueSizeToSend(4);
        init(config);

        validateSize(0, 0);
        Assert.assertFalse(Countly.instance().events().startEvent(null));
        validateSize(0, 0);
    }

    /**
     * Start an event with already started key
     * "startEvent" function should not create a timed event with same key as already started
     * in memory , timed events and cache queue not contain it
     */
    @Test
    public void startEvent_alreadyStarted() {
        Config config = TestUtils.getBaseConfig();
        config.enableFeatures(Config.Feature.Events).setEventQueueSizeToSend(4);
        init(config);

        List<EventImpl> events = null;
        validateSize(0, 0);

        String eventName = TestUtils.randomUUID();
        startEvent(eventName);

        validateSize(0, 1);

        EventImpl timedEvent = moduleEvents.timedEvents.get(eventName);
        validateEvent(timedEvent, eventName, null, 1, null, null, timedEvent.dow, timedEvent.hour, timedEvent.timestamp);

        boolean result = Countly.instance().events().startEvent(eventName);
        Assert.assertFalse(result);

        events = TestUtils.getCurrentEventQueue(moduleEvents.ctx.getContext(), moduleEvents.L);
        validateQueueSize(0, events);
        Assert.assertEquals(1, moduleEvents.timedEvents.size());

        endEvent(eventName, null, 1, null);

        events = TestUtils.getCurrentEventQueue(moduleEvents.ctx.getContext(), moduleEvents.L);

        validateSize(1, 0);
        validateEvent(events.get(0), eventName, null, 1, null, events.get(0).duration, events.get(0).dow, events.get(0).hour, events.get(0).timestamp);
    }

    /**
     * End an event with empty key
     * "endEvent" function should not work with empty key
     * in memory , timed events and cache queue not contain it
     */
    @Test
    public void endEvent_emptyKey() {
        Config config = TestUtils.getBaseConfig();
        config.enableFeatures(Config.Feature.Events).setEventQueueSizeToSend(4);
        init(config);

        validateSize(0, 0);
        Assert.assertFalse(Countly.instance().events().endEvent(""));
        validateSize(0, 0);
    }

    /**
     * End an event with empty key
     * "endEvent" function should not work with null key
     * in memory , timed events and cache queue not contain it
     */
    @Test
    public void endEvent_nullKey() {
        Config config = TestUtils.getBaseConfig();
        config.enableFeatures(Config.Feature.Events).setEventQueueSizeToSend(4);
        init(config);

        validateSize(0, 0);
        Assert.assertFalse(Countly.instance().events().endEvent(null));
        validateSize(0, 0);
    }

    /**
     * End a not existing event
     * "endEvent" function should not work with not existing event key
     * in memory , timed events and cache queue not contain it
     */
    @Test
    public void endEvent_notExist() {
        Config config = TestUtils.getBaseConfig();
        config.enableFeatures(Config.Feature.Events).setEventQueueSizeToSend(4);
        init(config);

        validateSize(0, 0);
        Assert.assertFalse(Countly.instance().events().endEvent(TestUtils.randomUUID()));
        validateSize(0, 0);
    }

    /**
     * End an event with segmentation
     * "endEvent" function should end an event with segmentation
     * in memory , timed events and cache queue should contain it
     */
    @Test
    public void endEvent_withSegmentation() {
        Config config = TestUtils.getBaseConfig();
        config.enableFeatures(Config.Feature.Events).setEventQueueSizeToSend(4);
        init(config);

        validateSize(0, 0);
        String eventName = TestUtils.randomUUID();

        startEvent(eventName); // start event to end it
        validateSize(0, 1);

        EventImpl timedEvent = moduleEvents.timedEvents.get(eventName);
        validateEvent(timedEvent, eventName, null, 1, null, null, timedEvent.dow, timedEvent.hour, timedEvent.timestamp);

        Map<String, Object> segmentation = new HashMap<>();
        segmentation.put("hair_color", "red");
        segmentation.put("hair_length", "short");
        segmentation.put("chauffeur", "g3chauffeur"); //

        endEvent(eventName, segmentation, 1, 5.0);
        validateSize(1, 0);
        List<EventImpl> events = TestUtils.getCurrentEventQueue(moduleEvents.ctx.getContext(), moduleEvents.L);
        EventImpl imEvent = moduleEvents.eventQueue.eventQueueMemoryCache.get(0);

        validateEvent(imEvent, eventName, segmentation, 1, 5.0, events.get(0).duration, imEvent.dow, imEvent.hour, imEvent.timestamp);
        validateEvent(events.get(0), eventName, segmentation, 1, 5.0, events.get(0).duration, events.get(0).dow, events.get(0).hour, events.get(0).timestamp);
    }

    /**
     * End an event with segmentation and negative count
     * "endEvent" function should not end an event with negative count
     * in memory and cache queue should not contain it, timed events should
     * and data should not be set
     */
    @Test(expected = IllegalArgumentException.class)
    public void endEvent_withSegmentation_negativeCount() {
        Config config = TestUtils.getBaseConfig();
        config.enableFeatures(Config.Feature.Events).setEventQueueSizeToSend(4);
        init(config);

        validateSize(0, 0);
        String eventName = TestUtils.randomUUID();

        startEvent(eventName); // start event to end it
        validateSize(0, 1);
        EventImpl timedEvent = moduleEvents.timedEvents.get(eventName);
        validateEvent(timedEvent, eventName, null, 1, null, null, timedEvent.dow, timedEvent.hour, timedEvent.timestamp);

        Map<String, Object> segmentation = new HashMap<>();
        segmentation.put("horse_name", "Alice");
        segmentation.put("bet_amount", 300);
        segmentation.put("currency", "Dollar"); //

        endEvent(eventName, segmentation, -7, 67.0);
        validateSize(0, 1);
        timedEvent = moduleEvents.timedEvents.get(eventName);
        validateEvent(timedEvent, eventName, null, 1, null, null, timedEvent.dow, timedEvent.hour, timedEvent.timestamp);
    }

    private void validateSize(int expectedQueueSize, int expectedTimedEventSize) {
        validateQueueSize(expectedQueueSize, TestUtils.getCurrentEventQueue(moduleEvents.ctx.getContext(), moduleEvents.L));
        Assert.assertEquals(expectedTimedEventSize, moduleEvents.timedEvents.size());
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
