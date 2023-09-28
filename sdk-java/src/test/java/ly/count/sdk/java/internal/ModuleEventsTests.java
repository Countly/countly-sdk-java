package ly.count.sdk.java.internal;

import java.io.File;
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

import static ly.count.sdk.java.internal.TestUtils.validateEvent;

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

        String eventKey = "recordEvent";

        //record event with key segmentation and count
        Countly.instance().events().recordEvent(eventKey, 1, 45.9, segmentation, 32.0);

        //check if event was recorded correctly and size of event queue is equal to size of events in queue
        validateEventInQueue(TestUtils.getTestSDirectory(), eventKey, segmentation, 1, 45.9, 32.0, 1, 0);
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

        Countly.instance().events().recordEvent("recordEvent_negativeCount", -1);
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

        String eventKey = "recordEvent_invalidSegment";
        //record event with key segmentation
        Countly.instance().events().recordEvent(eventKey, segmentation);

        validateEventInQueue(TestUtils.getTestSDirectory(), eventKey, expectedSegmentation, 1, null, null, 1, 0);
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

        validateTimedEventSize(0, 0);
        String eventName = "startEvent";

        startEvent(eventName);
        long start = System.currentTimeMillis();
        validateTimedEventSize(0, 1);

        EventImpl timedEvent = moduleEvents.timedEvents.get(eventName);
        validateEvent(timedEvent, eventName, null, 1, null, null);

        endEvent(eventName, null, 1, null);
        long end = System.currentTimeMillis();

        Assert.assertEquals(0, moduleEvents.timedEvents.size());
        validateEventInQueue(TestUtils.getTestSDirectory(), eventName, null, 1, null, (double) (end - start) / 1000, 1, 0);
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

        validateTimedEventSize(0, 0);
        Assert.assertFalse(Countly.instance().events().startEvent(""));
        validateTimedEventSize(0, 0);
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

        validateTimedEventSize(0, 0);
        Assert.assertFalse(Countly.instance().events().startEvent(null));
        validateTimedEventSize(0, 0);
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

        validateTimedEventSize(0, 0);

        String eventName = "startEvent_alreadyStarted";
        startEvent(eventName);
        long start = System.currentTimeMillis();

        validateTimedEventSize(0, 1);

        EventImpl timedEvent = moduleEvents.timedEvents.get(eventName);
        validateEvent(timedEvent, eventName, null, 1, null, null);

        boolean result = Countly.instance().events().startEvent(eventName);
        Assert.assertFalse(result);

        validateTimedEventSize(0, 1);

        endEvent(eventName, null, 1, null);
        long end = System.currentTimeMillis();

        Assert.assertEquals(0, moduleEvents.timedEvents.size());
        validateEventInQueue(TestUtils.getTestSDirectory(), eventName, null, 1, null, (double) (end - start) / 1000, 1, 0);
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

        validateTimedEventSize(0, 0);
        Assert.assertFalse(Countly.instance().events().endEvent(""));
        validateTimedEventSize(0, 0);
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

        validateTimedEventSize(0, 0);
        Assert.assertFalse(Countly.instance().events().endEvent(null));
        validateTimedEventSize(0, 0);
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

        validateTimedEventSize(0, 0);
        Assert.assertFalse(Countly.instance().events().endEvent("endEvent_notExist"));
        validateTimedEventSize(0, 0);
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

        validateTimedEventSize(0, 0);
        String eventName = "endEvent_withSegmentation";

        startEvent(eventName); // start event to end it
        long start = System.currentTimeMillis();
        validateTimedEventSize(0, 1);

        EventImpl timedEvent = moduleEvents.timedEvents.get(eventName);
        validateEvent(timedEvent, eventName, null, 1, null, null);

        Map<String, Object> segmentation = new HashMap<>();
        segmentation.put("hair_color", "red");
        segmentation.put("hair_length", "short");
        segmentation.put("chauffeur", "g3chauffeur"); //

        endEvent(eventName, segmentation, 1, 5.0);
        long end = System.currentTimeMillis();

        Assert.assertEquals(0, moduleEvents.timedEvents.size());
        validateEventInQueue(TestUtils.getTestSDirectory(), eventName, segmentation, 1, 5.0, (double) (end - start) / 1000, 1, 0);
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

        validateTimedEventSize(0, 0);
        String eventName = "endEvent_withSegmentation_negativeCount";

        startEvent(eventName); // start event to end it
        validateTimedEventSize(0, 1);
        EventImpl timedEvent = moduleEvents.timedEvents.get(eventName);
        validateEvent(timedEvent, eventName, null, 1, null, null);

        Map<String, Object> segmentation = new HashMap<>();
        segmentation.put("horse_name", "Alice");
        segmentation.put("bet_amount", 300);
        segmentation.put("currency", "Dollar"); //

        endEvent(eventName, segmentation, -7, 67.0);
        validateTimedEventSize(0, 1);
        timedEvent = moduleEvents.timedEvents.get(eventName);
        validateEvent(timedEvent, eventName, null, 1, null, null);
    }

    private void validateTimedEventSize(int expectedQueueSize, int expectedTimedEventSize) {
        validateQueueSize(expectedQueueSize, TestUtils.getCurrentEventQueue(moduleEvents.ctx.getContext(), moduleEvents.L));
        Assert.assertEquals(expectedTimedEventSize, moduleEvents.timedEvents.size());
    }

    private void validateQueueSize(int expectedSize, List<EventImpl> events) {
        Assert.assertEquals(expectedSize, events.size());
        Assert.assertEquals(expectedSize, moduleEvents.eventQueue.eqSize());
    }

    private void endEvent(String key, Map<String, Object> segmentation, int count, Double sum) {
        boolean result = Countly.instance().events().endEvent(key, segmentation, count, sum);
        Assert.assertTrue(result);
    }

    private void startEvent(String key) {
        boolean result = Countly.instance().events().startEvent(key);
        Assert.assertTrue(result);
    }

    void validateEventInQueue(File targetFolder, String key, Map<String, Object> segmentation,
        int count, Double sum, Double duration, int queueSize, int elementInQueue) {
        List<EventImpl> events = TestUtils.getCurrentEventQueue(targetFolder, moduleEvents.L);
        validateQueueSize(queueSize, events);

        //check if event was recorded correctly
        EventImpl event = events.get(elementInQueue);
        EventImpl eventInMemory = moduleEvents.eventQueue.eventQueueMemoryCache.get(0);
        validateEvent(event, key, segmentation, count, sum, duration);
        validateEvent(eventInMemory, key, segmentation, count, sum, duration);
    }
}
