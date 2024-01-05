package ly.count.sdk.java.internal;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
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
import static org.mockito.Mockito.spy;
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
        init(TestUtils.getConfigEvents(2));

        TestUtils.validateEQSize(0, eventQueue);
        EventImpl event = createEvent("test-addEvent", null, 1, null, null);
        eventQueue.addEvent(event);
        validateEventInQueue(event.key, null, 1, null, null, 1, 0, eventQueue, L);
    }

    /**
     * Add a null event to queue
     * "addEvent" function should not add event to both queue and memory
     * in memory and cache queue size should be 0
     */
    @Test
    public void addEvent_null() {
        init(TestUtils.getConfigEvents(2));

        TestUtils.validateEQSize(0, eventQueue);
        eventQueue.addEvent(null);
        TestUtils.validateEQSize(0, eventQueue);
    }

    /**
     * Write in memory events to storage
     * "writeEventQueueToStorage" function should write events from memory to storage
     * in memory and cache queue size should be 1 and should contain event
     */
    @Test
    public void writeEventQueueToStorage() {
        init(TestUtils.getConfigEvents(2));

        TestUtils.validateEQSize(0, eventQueue);
        EventImpl event = createEvent("test-writeEventQueueToStorage", null, 1, null, null);
        eventQueue.eventQueueMemoryCache.add(event);
        eventQueue.writeEventQueueToStorage();
        validateEventInQueue(event.key, null, 1, null, null, 1, 0, eventQueue, L);
    }

    /**
     * Write empty in memory events
     * "writeEventQueueToStorage" function should not call "joinEvents"
     * joinEvents should not be called
     */
    @Test
    public void writeEventQueueToStorage_emptyCache() {
        eventQueue = spy(EventQueue.class);
        eventQueue.L = mock(Log.class);
        eventQueue.eventQueueMemoryCache = new ArrayList<>();

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
        init(TestUtils.getConfigEvents(2));

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
        init(TestUtils.getConfigEvents(2));

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
        init(TestUtils.getConfigEvents(2));

        eventQueue.joinEvents(null);
    }

    /**
     * Clear events from storage and cache
     * "clear" function should work
     * both memory and cache should be empty
     */
    @Test
    public void clear() {
        init(TestUtils.getConfigEvents(2));

        TestUtils.validateEQSize(0, eventQueue);
        eventQueue.addEvent(createEvent("test-clear", null, 1, null, null));
        TestUtils.validateEQSize(1, eventQueue);
        eventQueue.addEvent(createEvent("test-clear", null, 1, null, null));
        TestUtils.validateEQSize(2, eventQueue);
        eventQueue.clear();
        TestUtils.validateEQSize(0, eventQueue);
    }

    /**
     * Restore events from storage
     * "restoreFromDisk" function should work
     * both memory and cache should have desired size
     */
    @Test
    public void restoreFromDisk() throws IOException {
        init(TestUtils.getConfigEvents(2));

        TestUtils.validateEQSize(0, eventQueue);
        writeToEventQueue("{\"id\":\"id\",\"cvid\":\"cvid\",\"pvid\":\"pvid\",\"peid\":\"peid\",\"hour\":10,\"count\":1,\"dow\":4,\"key\":\"test-joinEvents-1\",\"timestamp\":1695887006647}:::{\"hour\":10,\"count\":1,\"dow\":4,\"key\":\"test-joinEvents-2\",\"timestamp\":1695887006657}", false);

        eventQueue.restoreFromDisk();
        TestUtils.validateEQSize(2, eventQueue);
        validateEvent(eventQueue.eventQueueMemoryCache.get(0), "test-joinEvents-1", null, 1, null, null, "id", "pvid", "cvid", "peid");
        validateEvent(eventQueue.eventQueueMemoryCache.get(1), "test-joinEvents-2", null, 1, null, null, null, null, null, null);
    }

    /**
     * Restore events from storage not existing file
     * "restoreFromDisk" function should work
     * both memory and cache should be empty
     */
    @Test
    public void restoreFromDisk_notExist() throws IOException {
        init(TestUtils.getConfigEvents(2));

        TestUtils.validateEQSize(0, eventQueue);
        writeToEventQueue(null, true);

        eventQueue.restoreFromDisk();
        TestUtils.validateEQSize(0, eventQueue);
    }

    /**
     * Restore events from storage garbage file
     * "restoreFromDisk" function should work
     * both memory and cache should be empty
     */
    @Test
    public void restoreFromDisk_garbageFile() throws IOException {
        init(TestUtils.getConfigEvents(2));

        TestUtils.validateEQSize(0, eventQueue);
        writeToEventQueue("{\"hour\":10,\"asdasd\":\"askjdn\",\"timestamp\":1695887006647}::{\"hour\":10,\"count\":1,\"dow\":4,\"asda\":\"test-joinEvents-2\"}", false);

        eventQueue.restoreFromDisk();
        TestUtils.validateEQSize(0, eventQueue);
    }

    /**
     * Restore events from storage corrupted file
     * "restoreFromDisk" function should read only not corrupted events
     * both memory and cache should have desired size and contain only not corrupted events
     */
    @Test
    public void restoreFromDisk_corruptedData() throws IOException {
        init(TestUtils.getConfigEvents(2));

        TestUtils.validateEQSize(0, eventQueue);
        writeToEventQueue("{\"hour\":10,\"count\":1,\"dow\":4,\"key\":\"test-joinEvents-1\",\"timestamp\":1695887006647}:::{\"hour\":10,\"count\":1,\"dow\":4,\"keya\":\"test-joinEvents-2\",\"timestamp\":1695887006657}", false);

        eventQueue.restoreFromDisk();
        TestUtils.validateEQSize(1, eventQueue);
        validateEvent(eventQueue.eventQueueMemoryCache.get(0), "test-joinEvents-1", null, 1, null, null);
    }

    static void writeToEventQueue(String fileContent, boolean delete) throws IOException {
        File file = new File(TestUtils.getTestSDirectory(), FILE_NAME_PREFIX + FILE_NAME_SEPARATOR + EVENT_QUEUE_FILE_NAME);
        file.createNewFile();
        if (delete) {
            file.delete();
            return;
        }
        BufferedWriter writer = Files.newBufferedWriter(file.toPath());
        writer.write(fileContent);
        writer.close();
    }

    private EventImpl createEvent(String key, Map<String, Object> segmentation, int count, Double sum, Double dur) {
        return new EventImpl(key, count, sum, dur, segmentation, L, null, null, null, null);
    }

    public static void validateEventInQueue(String key, Map<String, Object> segmentation,
        int count, Double sum, Double duration, int queueSize, int elementInQueue, EventQueue eventQueue, Log L) {
        TestUtils.validateEventInEQ(key, segmentation, count, sum, duration, elementInQueue, queueSize);

        TestUtils.validateEQSize(queueSize, eventQueue);
        EventImpl eventInMemory = eventQueue.eventQueueMemoryCache.get(0);
        validateEvent(eventInMemory, key, segmentation, count, sum, duration);
    }
}
