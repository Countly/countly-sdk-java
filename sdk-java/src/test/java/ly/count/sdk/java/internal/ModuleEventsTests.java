package ly.count.sdk.java.internal;

import java.io.File;
import java.io.IOException;
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

import static ly.count.sdk.java.internal.TestUtils.eKeys;
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
        init(TestUtils.getConfigEvents(4));

        List<EventImpl> events = TestUtils.getCurrentEventQueue(moduleEvents.ctx.getContext(), moduleEvents.L);
        validateQueueSize(0, events);

        //create segmentation
        Map<String, Object> segmentation = new HashMap<>();
        segmentation.put("name", "Johny");
        segmentation.put("weight", 67);
        segmentation.put("bald", true);

        //record event with key segmentation and count
        Countly.instance().events().recordEvent(eKeys[0], 1, 45.9, segmentation, 32.0);

        //check if event was recorded correctly and size of event queue is equal to size of events in queue
        validateEventInEventQueue(TestUtils.getTestSDirectory(), eKeys[0], segmentation, 1, 45.9, 32.0, 1, 0);
    }

    /**
     * Recording an event and no event to recover
     * "recordEvent" function should create an event with given key
     * event queue should be empty when reached to event queue size to send
     */
    @Test
    public void recordEvent_queueSizeOver() {
        init(TestUtils.getConfigEvents(2));

        validateQueueSize(0);
        Assert.assertEquals(0, TestUtils.getCurrentRequestQueue().length);

        Countly.instance().events().recordEvent(eKeys[0], 1, 45.9, null, 32.0);
        validateQueueSize(1);
        Assert.assertEquals(0, TestUtils.getCurrentRequestQueue().length);

        Countly.instance().events().recordEvent(eKeys[1], 1, 45.9, null, 32.0);
        validateQueueSize(0);
        Storage.await(moduleEvents.L); // wait for request to be write to the disk
        Assert.assertEquals(1, TestUtils.getCurrentRequestQueue().length);

        List<EventImpl> eventsInRequest = TestUtils.readEventsFromRequest();
        validateEvent(eventsInRequest.get(0), eKeys[0], null, 1, 45.9, 32.0);
        validateEvent(eventsInRequest.get(1), eKeys[1], null, 1, 45.9, 32.0);
    }

    /**
     * Recording an event with recovered events
     * "recordEvent" function should create an event with given key and create a request with memory data
     * event queue should be empty when reached to event queue size to send
     */
    @Test
    public void recordEvent_queueSizeOverMemory() throws IOException {
        EventQueueTests.writeToEventQueue("{\"hour\":10,\"count\":5,\"dow\":4,\"key\":\"test-joinEvents-1\",\"timestamp\":1695887006647}:::{\"hour\":10,\"count\":1,\"dow\":4,\"key\":\"test-joinEvents-2\",\"timestamp\":1695887006657}", false);
        init(TestUtils.getConfigEvents(2));

        Assert.assertEquals(0, TestUtils.getCurrentRequestQueue().length);
        validateQueueSize(2);
        Countly.instance().events().recordEvent(eKeys[0], 1, 45.9, null, 32.0);
        validateQueueSize(0);
        Storage.await(moduleEvents.L); // wait for request to be written to the disk
        Assert.assertEquals(1, TestUtils.getCurrentRequestQueue().length);

        List<EventImpl> eventsInRequest = TestUtils.readEventsFromRequest();
        validateEvent(eventsInRequest.get(0), "test-joinEvents-1", null, 5, null, null);
        validateEvent(eventsInRequest.get(1), "test-joinEvents-2", null, 1, null, null);
        validateEvent(eventsInRequest.get(2), eKeys[0], null, 1, 45.9, 32.0);
    }

    /**
     * Recording an event with negative count
     * "recordEvent" function should not create an event with given key and negative count
     * in memory and cache queue should be empty
     */
    @Test
    public void recordEvent_negativeCount() {
        init(TestUtils.getConfigEvents(4));

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
        init(TestUtils.getConfigEvents(4));

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
        init(TestUtils.getConfigEvents(4));

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
        init(TestUtils.getConfigEvents(4));

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

        //record event with key segmentation
        Countly.instance().events().recordEvent(eKeys[0], segmentation);

        validateEventInEventQueue(TestUtils.getTestSDirectory(), eKeys[0], expectedSegmentation, 1, null, null, 1, 0);
    }

    /**
     * Start an event
     * "startEvent" function should create a timed event
     * in memory and cache queue should contain it
     */
    @Test
    public void startEvent() {
        init(TestUtils.getConfigEvents(4));

        validateTimedEventSize(0, 0);

        startEvent(eKeys[0]);
        validateTimedEventSize(0, 1);

        EventImpl timedEvent = moduleEvents.timedEvents.get(eKeys[0]);
        validateEvent(timedEvent, eKeys[0], null, 1, null, null);

        endEvent(eKeys[0], null, 1, null);

        Assert.assertEquals(0, moduleEvents.timedEvents.size());
        validateEventInEventQueue(TestUtils.getTestSDirectory(), eKeys[0], null, 1, null, 0.0, 1, 0);
    }

    /**
     * Start an event
     * "startEvent" function should not create a timed event with empty key
     * in memory , timed events and cache queue should not contain it
     */
    @Test
    public void startEvent_emptyKey() {
        init(TestUtils.getConfigEvents(4));

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
        init(TestUtils.getConfigEvents(4));

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
        init(TestUtils.getConfigEvents(4));

        validateTimedEventSize(0, 0);

        startEvent(eKeys[0]);

        validateTimedEventSize(0, 1);

        EventImpl timedEvent = moduleEvents.timedEvents.get(eKeys[0]);
        validateEvent(timedEvent, eKeys[0], null, 1, null, null);

        boolean result = Countly.instance().events().startEvent(eKeys[0]);
        Assert.assertFalse(result);

        validateTimedEventSize(0, 1);

        endEvent(eKeys[0], null, 1, null);

        Assert.assertEquals(0, moduleEvents.timedEvents.size());
        validateEventInEventQueue(TestUtils.getTestSDirectory(), eKeys[0], null, 1, null, 0.0, 1, 0);
    }

    /**
     * End an event with empty key
     * "endEvent" function should not work with empty key
     * in memory , timed events and cache queue not contain it
     */
    @Test
    public void endEvent_emptyKey() {
        init(TestUtils.getConfigEvents(4));

        validateTimedEventSize(0, 0);
        Assert.assertFalse(Countly.instance().events().endEvent(""));
        validateTimedEventSize(0, 0);
    }

    /**
     * End an event with null key
     * "endEvent" function should not work with null key
     * in memory , timed events and cache queue not contain it
     */
    @Test
    public void endEvent_nullKey() {
        init(TestUtils.getConfigEvents(4));

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
        init(TestUtils.getConfigEvents(4));

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
        init(TestUtils.getConfigEvents(4));

        validateTimedEventSize(0, 0);

        startEvent(eKeys[0]); // start event to end it
        validateTimedEventSize(0, 1);

        EventImpl timedEvent = moduleEvents.timedEvents.get(eKeys[0]);
        validateEvent(timedEvent, eKeys[0], null, 1, null, null);

        Map<String, Object> segmentation = new HashMap<>();
        segmentation.put("hair_color", "red");
        segmentation.put("hair_length", "short");
        segmentation.put("chauffeur", "g3chauffeur"); //

        endEvent(eKeys[0], segmentation, 1, 5.0);

        Assert.assertEquals(0, moduleEvents.timedEvents.size());
        validateEventInEventQueue(TestUtils.getTestSDirectory(), eKeys[0], segmentation, 1, 5.0, 0.0, 1, 0);
    }

    /**
     * End an event with segmentation and negative count
     * "endEvent" function should not end an event with negative count
     * in memory and cache queue should not contain it, timed events should
     * and data should not be set
     */
    @Test(expected = IllegalArgumentException.class)
    public void endEvent_withSegmentation_negativeCount() {
        init(TestUtils.getConfigEvents(4));

        validateTimedEventSize(0, 0);

        startEvent(eKeys[0]); // start event to end it
        validateTimedEventSize(0, 1);
        EventImpl timedEvent = moduleEvents.timedEvents.get(eKeys[0]);
        validateEvent(timedEvent, eKeys[0], null, 1, null, null);

        Map<String, Object> segmentation = new HashMap<>();
        segmentation.put("horse_name", "Alice");
        segmentation.put("bet_amount", 300);
        segmentation.put("currency", "Dollar"); //

        endEvent(eKeys[0], segmentation, -7, 67.0);
        validateTimedEventSize(0, 1);
        timedEvent = moduleEvents.timedEvents.get(eKeys[0]);
        validateEvent(timedEvent, eKeys[0], null, 1, null, null);
    }

    /**
     * Cancel an event with empty key
     * "cancelEvent" function should not work with empty key
     * in memory, timed events and cache queue not contain it
     */
    @Test
    public void cancelEvent_emptyKey() {
        init(TestUtils.getConfigEvents(4));

        validateTimedEventSize(0, 0);
        Assert.assertFalse(Countly.instance().events().cancelEvent(""));
        validateTimedEventSize(0, 0);
    }

    /**
     * Cancel an event with null key
     * "cancelEvent" function should not work with null key
     * in memory, timed events and cache queue not contain it
     */
    @Test
    public void cancelEvent_nullKey() {
        init(TestUtils.getConfigEvents(4));

        validateTimedEventSize(0, 0);
        Assert.assertFalse(Countly.instance().events().cancelEvent(null));
        validateTimedEventSize(0, 0);
    }

    /**
     * Cancel a not existing event
     * "cancelEvent" function should not work with not existing event key
     * in memory, timed events and cache queue not contain it
     */
    @Test
    public void cancelEvent_notExist() {
        init(TestUtils.getConfigEvents(4));

        validateTimedEventSize(0, 0);
        Assert.assertFalse(Countly.instance().events().cancelEvent("cancelEvent_notExist"));
        validateTimedEventSize(0, 0);
    }

    /**
     * Cancel an event
     * "cancelEvent" function should cancel an event
     * in memory, timed events and cache queue should contain it
     */
    @Test
    public void cancelEvent() {
        init(TestUtils.getConfigEvents(4));

        validateTimedEventSize(0, 0);

        startEvent(eKeys[0]); // start event to end it
        validateTimedEventSize(0, 1);

        EventImpl timedEvent = moduleEvents.timedEvents.get(eKeys[0]);
        validateEvent(timedEvent, eKeys[0], null, 1, null, null);

        Assert.assertTrue(Countly.instance().events().cancelEvent(eKeys[0]));
        Assert.assertEquals(0, moduleEvents.timedEvents.size());
        validateQueueSize(0);
    }

    @Test
    public void timedEventFlow() throws InterruptedException {
        init(TestUtils.getConfigEvents(4));
        validateTimedEventSize(0, 0);

        startEvent(eKeys[0]); // start event to end it
        validateTimedEventSize(0, 1);

        Thread.sleep(1000);
        startEvent(eKeys[1]); // start event to end it
        validateTimedEventSize(0, 2);

        Thread.sleep(1000);
        endEvent(eKeys[1], null, 3, 15.0);

        Assert.assertEquals(1, moduleEvents.timedEvents.size());
        validateEventInEventQueue(TestUtils.getTestSDirectory(), eKeys[1], null, 3, 15.0, 1.0, 1, 0);

        endEvent(eKeys[0], null, 2, 4.0);

        Assert.assertEquals(0, moduleEvents.timedEvents.size());
        validateEventInEventQueue(TestUtils.getTestSDirectory(), eKeys[0], null, 2, 4.0, 2.0, 2, 1);
    }

    private void validateTimedEventSize(int expectedQueueSize, int expectedTimedEventSize) {
        validateQueueSize(expectedQueueSize, TestUtils.getCurrentEventQueue(moduleEvents.ctx.getContext(), moduleEvents.L));
        Assert.assertEquals(expectedTimedEventSize, moduleEvents.timedEvents.size());
    }

    private void validateQueueSize(int expectedSize, List<EventImpl> events) {
        Assert.assertEquals(expectedSize, events.size());
        Assert.assertEquals(expectedSize, moduleEvents.eventQueue.eqSize());
    }

    private void validateQueueSize(int expectedSize) {
        validateQueueSize(expectedSize, TestUtils.getCurrentEventQueue(moduleEvents.ctx.getContext(), moduleEvents.L));
    }

    private void endEvent(String key, Map<String, Object> segmentation, int count, Double sum) {
        boolean result = Countly.instance().events().endEvent(key, segmentation, count, sum);
        Assert.assertTrue(result);
    }

    private void startEvent(String key) {
        boolean result = Countly.instance().events().startEvent(key);
        Assert.assertTrue(result);
    }

    void validateEventInEventQueue(File targetFolder, String key, Map<String, Object> segmentation,
        int count, Double sum, Double duration, int queueSize, int elementInQueue) {
        List<EventImpl> events = TestUtils.getCurrentEventQueue(targetFolder, moduleEvents.L);
        validateQueueSize(queueSize, events);

        //check if event was recorded correctly
        EventImpl event = events.get(elementInQueue);
        EventImpl eventInMemory = moduleEvents.eventQueue.eventQueueMemoryCache.get(elementInQueue);
        validateEvent(event, key, segmentation, count, sum, duration);
        validateEvent(eventInMemory, key, segmentation, count, sum, duration);
    }
}
