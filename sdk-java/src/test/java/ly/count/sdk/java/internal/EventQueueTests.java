package ly.count.sdk.java.internal;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static ly.count.sdk.java.internal.SDKStorage.EVENT_QUEUE_FILE_NAME;
import static ly.count.sdk.java.internal.SDKStorage.FILE_NAME_PREFIX;
import static ly.count.sdk.java.internal.SDKStorage.FILE_NAME_SEPARATOR;
import static ly.count.sdk.java.internal.TestUtils.validateEvent;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@RunWith(JUnit4.class)
public class EventQueueTests {

    Log L = mock(Log.class);

    EventQueue eventQueue;

    private void init(Config cc) {
        Countly.instance().init(cc);
        eventQueue = new EventQueue(L, cc.getEventsBufferSize());
    }

    @After
    public void stop() {
        Countly.instance().halt();
        eventQueue = null;
    }

    /**
     * Add an event to queue
     * "addEvent" function should add event to both queue and memory
     * in memory and cache queue should contain it
     */
    @Test
    public void addEvent() {
        Config config = TestUtils.getBaseConfig();
        config.setEventQueueSizeToSend(2);
        init(config);

        validateQueueSize(0);
        EventImpl event = createEvent("test-addEvent", null, 1, null, null);
        eventQueue.addEvent(event);
        validateQueueSize(1);

        validateEventInQueue(event.key, null, 1, null, null, 1, 0);
    }

    /**
     * Add a null event to queue
     * "addEvent" function should not add event to both queue and memory
     * in memory and cache queue size should be 0
     */
    @Test
    public void addEvent_null() {
        Config config = TestUtils.getBaseConfig();
        config.setEventQueueSizeToSend(2);
        init(config);

        validateQueueSize(0);
        eventQueue.addEvent(null);
        validateQueueSize(0);
    }

    /**
     * Write in memory events to storage
     * "writeEventQueueToStorage" function should write events from memory to storage
     * in memory and cache queue size should be 1 and should contain event
     */
    @Test
    public void writeEventQueueToStorage() {
        Config config = TestUtils.getBaseConfig();
        config.setEventQueueSizeToSend(2);
        init(config);

        validateQueueSize(0);
        EventImpl event = createEvent("test-writeEventQueueToStorage", null, 1, null, null);
        eventQueue.eventQueueMemoryCache.add(event);
        eventQueue.writeEventQueueToStorage();
        validateEventInQueue(event.key, null, 1, null, null, 1, 0);
    }

    /**
     * Write empty in memory events
     * "writeEventQueueToStorage" function should not call "joinEvents"
     * joinEvents should not be called
     */
    @Test
    public void writeEventQueueToStorage_emptyCache() {
        eventQueue = mock(EventQueue.class);

        eventQueue.writeEventQueueToStorage();
        verify(eventQueue, never()).joinEvents(anyCollection());
    }

    /**
     * Join events with delimiter
     * "joinEvents" function should join events
     * joinEvents should return expected String
     */
    @Test
    public void joinEvents() {
        Config config = TestUtils.getBaseConfig();
        config.setEventQueueSizeToSend(2);
        init(config);

        List<EventImpl> list = new ArrayList<>();
        list.add(createEvent("test-joinEvents-1", null, 1, null, null));
        list.add(createEvent("test-joinEvents-2", null, 1, null, null));

        String result = eventQueue.joinEvents(list);

        String expected = list.stream().map(event -> event.toJSON(L)).reduce((a, b) -> a + EventQueue.DELIMITER + b).orElse("");
        Assert.assertEquals(expected, result);
    }

    /**
     * Join events with empty collection
     * "joinEvents" function should join events
     * joinEvents should return expected String
     */
    @Test
    public void joinEvents_emptyCollection() {
        Config config = TestUtils.getBaseConfig();
        config.setEventQueueSizeToSend(2);
        init(config);

        List<EventImpl> list = new ArrayList<>();

        String result = eventQueue.joinEvents(list);
        Assert.assertEquals("", result);
    }

    /**
     * Join events with delimiter null collection
     * "joinEvents" function should not work
     * joinEvents should throw NullPointerException
     */
    @Test(expected = NullPointerException.class)
    public void joinEvents_nullCollection() {
        Config config = TestUtils.getBaseConfig();
        config.setEventQueueSizeToSend(2);
        init(config);

        eventQueue.joinEvents(null);
    }

    /**
     * Clear events from storage and cache
     * "clear" function should work
     * both memory and cache should be empty
     */
    @Test
    public void clear() {
        Config config = TestUtils.getBaseConfig();
        config.setEventQueueSizeToSend(2);
        init(config);

        validateQueueSize(0);

        eventQueue.addEvent(createEvent("test-clear", null, 1, null, null));
        validateQueueSize(1);
        eventQueue.addEvent(createEvent("test-clear", null, 1, null, null));
        validateQueueSize(2);

        eventQueue.clear();
        validateQueueSize(0);
    }

    /**
     * Restore events from storage
     * "restoreFromDisk" function should work
     * both memory and cache should have desired size
     */
    @Test
    public void restoreFromDisk() throws IOException {
        Config config = TestUtils.getBaseConfig();
        config.setEventQueueSizeToSend(2);
        init(config);

        validateQueueSize(0);
        writeToEventQueue("{\"hour\":10,\"count\":1,\"dow\":4,\"key\":\"test-joinEvents-1\",\"timestamp\":1695887006647}:::{\"hour\":10,\"count\":1,\"dow\":4,\"key\":\"test-joinEvents-2\",\"timestamp\":1695887006657}", false);

        eventQueue.restoreFromDisk();
        validateQueueSize(2);
        validateEvent(eventQueue.eventQueueMemoryCache.get(0), "test-joinEvents-1", null, 1, null, null);
        validateEvent(eventQueue.eventQueueMemoryCache.get(1), "test-joinEvents-2", null, 1, null, null);
    }

    /**
     * Restore events from storage not existing file
     * "restoreFromDisk" function should work
     * both memory and cache should be empty
     */
    @Test
    public void restoreFromDisk_notExist() throws IOException {
        Config config = TestUtils.getBaseConfig();
        config.setEventQueueSizeToSend(2);
        init(config);

        validateQueueSize(0);
        writeToEventQueue(null, true);

        eventQueue.restoreFromDisk();
        validateQueueSize(0);
    }

    /**
     * Restore events from storage garbage file
     * "restoreFromDisk" function should work
     * both memory and cache should be empty
     */
    @Test
    public void restoreFromDisk_garbageFile() throws IOException {
        Config config = TestUtils.getBaseConfig();
        config.setEventQueueSizeToSend(2);
        init(config);

        validateQueueSize(0);
        writeToEventQueue("{\"hour\":10,\"asdasd\":\"askjdn\",\"timestamp\":1695887006647}::{\"hour\":10,\"count\":1,\"dow\":4,\"asda\":\"test-joinEvents-2\"}", false);

        eventQueue.restoreFromDisk();
        validateQueueSize(0);
    }

    /**
     * Restore events from storage corrupted file
     * "restoreFromDisk" function should read only not corrupted events
     * both memory and cache should have desired size and contain only not corrupted events
     */
    @Test
    public void restoreFromDisk_corruptedData() throws IOException {
        Config config = TestUtils.getBaseConfig();
        config.setEventQueueSizeToSend(2);
        init(config);

        validateQueueSize(0);
        writeToEventQueue("{\"hour\":10,\"count\":1,\"dow\":4,\"key\":\"test-joinEvents-1\",\"timestamp\":1695887006647}:::{\"hour\":10,\"count\":1,\"dow\":4,\"keya\":\"test-joinEvents-2\",\"timestamp\":1695887006657}", false);

        eventQueue.restoreFromDisk();
        validateQueueSize(1);
        validateEvent(eventQueue.eventQueueMemoryCache.get(0), "test-joinEvents-1", null, 1, null, null);
    }

    static void writeToEventQueue(String fileContent, boolean delete) throws IOException {
        File file = new File(TestUtils.getTestSDirectory(), FILE_NAME_PREFIX + FILE_NAME_SEPARATOR + EVENT_QUEUE_FILE_NAME);
        file.createNewFile();
        if (delete) {
            file.delete();
            return;
        }
        FileWriter writer = new FileWriter(file);
        writer.write(fileContent);
        writer.close();
    }

    private EventImpl createEvent(String key, Map<String, Object> segmentation, int count, Double sum, Double dur) {
        return new EventImpl(key, count, sum, dur, segmentation, L);
    }

    private void validateQueueSize(int expectedSize) {
        Assert.assertEquals(expectedSize, TestUtils.getCurrentEventQueue(TestUtils.getTestSDirectory(), L).size());
        Assert.assertEquals(expectedSize, eventQueue.eqSize());
    }

    void validateEventInQueue(String key, Map<String, Object> segmentation,
        int count, Double sum, Double duration, int queueSize, int elementInQueue) {
        List<EventImpl> events = TestUtils.getCurrentEventQueue(TestUtils.getTestSDirectory(), L);
        validateQueueSize(queueSize);

        //check if event was recorded correctly
        EventImpl event = events.get(elementInQueue);
        EventImpl eventInMemory = eventQueue.eventQueueMemoryCache.get(0);
        validateEvent(event, key, segmentation, count, sum, duration);
        validateEvent(eventInMemory, key, segmentation, count, sum, duration);
    }
}
