package ly.count.sdk.java.internal;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ModuleEventsTests {

    private ModuleEvents moduleEvents;

    @BeforeClass
    public static void init() {
        if (Countly.isInitialized()) return;
        // System specific folder structure
        String[] sdkStorageRootPath = { System.getProperty("user.home"), "__COUNTLY", "java_test" };
        File sdkStorageRootDirectory = new File(String.join(File.separator, sdkStorageRootPath));

        if (sdkStorageRootDirectory.mkdirs()) {
            throw new RuntimeException("Directory creation failed");
        }
        Config cc = new Config("https://try.count.ly", "COUNTLY_APP_KEY", sdkStorageRootDirectory);

        cc.enableFeatures(Config.Feature.Events).setEventQueueSizeToSend(4);
        Countly.instance().init(cc);
    }

    @AfterClass
    public static void stop() {
        Countly.stop(false);
    }

    @Before
    public void start() {
        moduleEvents = (ModuleEvents) SDKCore.instance.module(CoreFeature.Events.getIndex());
    }

    /**
     * Records an event and checks if it was recorded correctly
     * by looking into event queue
     */
    @Test
    public void recordEvent() {
        moduleEvents.eventQueue.clear();
        Assert.assertEquals(0, moduleEvents.eventQueue.eqSize());

        //create segmentation
        Map<String, Object> segmentation = new HashMap<>();
        segmentation.put("name", "Johny");
        segmentation.put("weight", 67);
        segmentation.put("bald", true);

        //record event with key segmentation and count
        Countly.instance().events().recordEvent("test-recordEvent", 1, 45.9, segmentation, 32.0);

        //check if event was recorded correctly and size of event queue is equal to size of events in queue
        List<EventImpl> events = TestUtils.getCurrentEventQueue(moduleEvents.ctx.getContext(), moduleEvents.L);
        Assert.assertEquals(1, moduleEvents.eventQueue.eqSize());
        Assert.assertEquals(1, events.size());

        //check if event was recorded correctly
        EventImpl event = events.get(0);

        Assert.assertEquals("test-recordEvent", event.key);
        Assert.assertEquals(1, event.count);
        Assert.assertEquals(segmentation, event.segmentation);
        Assert.assertEquals(new Double(45.9), event.sum);
        Assert.assertEquals(new Double(32.0), event.duration);
    }

    /**
     * Records an event with for the next test case
     */
    @Test
    public void recordEvent_fillEventQueue() {
        recordEvent(); // fill the queue
        try {
            Assert.assertEquals(1, moduleEvents.eventQueue.eqSize());

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
            EventImpl q2 = events.get(1);
            Assert.assertEquals(2, moduleEvents.eventQueue.eqSize());
            Assert.assertEquals(2, events.size());
            Assert.assertEquals("test-recordEvent", q1.key);
            Assert.assertEquals(67, q1.segmentation.get("weight"));
            Assert.assertNotEquals(segmentation, q1.segmentation);
            Assert.assertEquals(segmentation, q2.segmentation);
            Assert.assertEquals("test-recordEvent-Filler", q2.key);
        } finally {
            moduleEvents.eventQueue.clear();
        }
    }

    /**
     * This test case re-inits Countly and tries to read
     * existing event queue from storage
     */
    @Test
    public void recordEvent_reInitCountly() {
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
        init();
        start();

        //check if event was recorded correctly and size of event queue is equal to size of events in queue
        List<EventImpl> events = TestUtils.getCurrentEventQueue(moduleEvents.ctx.getContext(), moduleEvents.L);
        Assert.assertEquals(1, moduleEvents.eventQueue.eqSize());
        Assert.assertEquals(1, events.size());

        //check if event was recorded correctly
        EventImpl event = events.get(0);
        Assert.assertEquals("testInDiskEventQueue", event.key);
        Assert.assertEquals(segmentation, event.segmentation);
    }

    /**
     * start event successfully created timed event and end it
     */
    @Test
    public void startEvent() {
        String eventName = "test-startEvent";

        try {
            startEvent(eventName);
        } finally {
            endEvent(eventName, null, 1, null);
        }
    }

    /**
     * start event should not create with empty key
     */
    @Test
    public void startEvent_emptyKey() {
        Assert.assertFalse(Countly.instance().events().startEvent(""));
    }

    /**
     * start event should not create with null key
     */
    @Test
    public void startEvent_nullKey() {
        Assert.assertFalse(Countly.instance().events().startEvent(null));
    }

    /**
     * start event should not create with already started event
     */
    @Test
    public void startEvent_alreadyStarted() {

        String eventName = "test-startEvent_alreadyStarted";

        try {
            startEvent(eventName);
            boolean result = Countly.instance().events().startEvent(eventName);
            Assert.assertFalse(result);
        } finally {
            endEvent(eventName, null, 1, null);
        }
    }

    /**
     * end event successfully ended the timed event
     */
    @Test
    public void endEvent() {
        String eventName = "test-endEvent";

        startEvent(eventName); // start event to end it
        endEvent(eventName, null, 1, null);
    }

    /**
     * End event with empty key should not net
     */
    @Test
    public void endEvent_emptyKey() {
        Assert.assertFalse(Countly.instance().events().endEvent(""));
    }

    /**
     * End event with null key should not end
     */
    @Test
    public void endEvent_nullKey() {
        Assert.assertFalse(Countly.instance().events().endEvent(null));
    }

    /**
     * End event with not started event should not work
     */
    @Test
    public void endEvent_notStarted() {
        Assert.assertFalse(Countly.instance().events().endEvent("test-endEvent_notStarted"));
    }

    /**
     * End event with already ended event should not process
     */
    @Test
    public void endEvent_alreadyEnded() {
        Assert.assertFalse(Countly.instance().events().endEvent("test-endEvent_alreadyEnded"));
    }

    /**
     * End event with segmentation should end successfully
     */
    @Test
    public void endEvent_withSegmentation() {

        String eventName = "test-endEvent_withSegmentation";

        startEvent(eventName); // start event to end it

        Map<String, Object> segmentation = new HashMap<>();
        segmentation.put("hair_color", "red");
        segmentation.put("hair_length", "short");
        segmentation.put("chauffeur", "g3chauffeur"); //

        endEvent(eventName, segmentation, 1, 5.0);
    }

    /**
     * End event with segmentation and negative count should throw <code>IllegalArgumentException</code>
     */
    @Test(expected = IllegalArgumentException.class)
    public void endEvent_withSegmentation_negativeCount() {

        String eventName = "test-endEvent_withSegmentation_negativeCount";

        startEvent(eventName); // start event to end it

        Map<String, Object> segmentation = new HashMap<>();
        segmentation.put("horse_name", "Alice");
        segmentation.put("bet_amount", 300);
        segmentation.put("currency", "Dollar"); //

        endEvent(eventName, segmentation, -7, 67.0);
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
