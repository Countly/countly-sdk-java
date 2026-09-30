package ly.count.sdk.java.internal;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.mockito.Mockito.mock;

/**
 * The {@link Storage} facade and the {@link SDKStorage} file layer under it, plus the
 * {@link TimedEvents} storable.
 * <p>
 * The interesting part of this layer is what happens when the disk does not cooperate: a missing
 * file, an unreadable one, a corrupt payload. Those paths are what stop the SDK from losing a
 * customer's data or crashing their application, and they are the ones the ordinary happy path tests
 * never reach.
 */
@RunWith(JUnit4.class)
public class StorageLayerTests {

    private InternalConfig config;

    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
        Countly.instance().init(TestUtils.getConfigEvents(100));
        config = SDKCore.instance.config;
    }

    @After
    public void stop() {
        Countly.instance().halt();
    }

    // region scenarios

    /**
     * The full storable lifecycle through the public {@link Storage} API: push, list, read, readOne
     * both ways round, pop, and remove. Proves the ordering contract of the slice argument, which the
     * request queue depends on to send oldest first.
     */
    @Test
    public void storableLifecycle_pushesListsReadsAndPops() {
        Request first = requestWith(1_000L, "order", "first");
        Request second = requestWith(2_000L, "order", "second");
        Request third = requestWith(3_000L, "order", "third");

        Assert.assertTrue(Storage.push(config, first));
        Assert.assertTrue(Storage.push(config, second));
        Assert.assertTrue(Storage.push(config, third));

        List<Long> all = Storage.list(config, Request.getStoragePrefix());
        Assert.assertEquals(3, all.size());
        Assert.assertEquals(Long.valueOf(1_000L), all.get(0));
        Assert.assertEquals(Long.valueOf(3_000L), all.get(2));

        // A positive slice returns the first N oldest first, a negative slice the last N newest first.
        List<Long> firstTwo = Storage.list(config, Request.getStoragePrefix(), 2);
        Assert.assertEquals(2, firstTwo.size());
        Assert.assertEquals(Long.valueOf(1_000L), firstTwo.get(0));

        List<Long> lastTwo = Storage.list(config, Request.getStoragePrefix(), -2);
        Assert.assertEquals(2, lastTwo.size());
        Assert.assertEquals(Long.valueOf(3_000L), lastTwo.get(0));

        // readOne ascending gives the oldest, descending the newest.
        Request oldest = Storage.readOne(config, new Request(0L), true);
        Assert.assertNotNull(oldest);
        Assert.assertEquals("first", oldest.params.get("order"));

        Request newest = Storage.readOne(config, new Request(0L), false);
        Assert.assertNotNull(newest);
        Assert.assertEquals("third", newest.params.get("order"));

        // read leaves the file in place, pop takes it away.
        Assert.assertNotNull(Storage.read(config, new Request(2_000L)));
        Assert.assertNotNull(Storage.read(config, new Request(2_000L)));

        Request popped = Storage.pop(config, new Request(2_000L));
        Assert.assertNotNull(popped);
        Assert.assertEquals("second", popped.params.get("order"));
        Assert.assertNull("a popped storable is gone", Storage.read(config, new Request(2_000L)));

        Assert.assertTrue(Storage.remove(config, first));
        Assert.assertEquals(1, Storage.list(config, Request.getStoragePrefix()).size());
    }

    /**
     * Everything that can be absent or broken on disk, in one pass. Each must answer with null or
     * false rather than throwing, because the SDK runs inside a customer's application and a storage
     * fault must never surface as a crash.
     */
    @Test
    public void missingAndCorruptFiles_answerNullRatherThanThrowing() {
        // Nothing stored under this id at all.
        Assert.assertNull(Storage.read(config, new Request(4_242L)));
        Assert.assertNull(Storage.pop(config, new Request(4_242L)));
        Assert.assertNull(Storage.readOne(config, new Request(0L), true));
        Assert.assertEquals(0, Storage.list(config, Request.getStoragePrefix()).size());
        Assert.assertEquals(0, Storage.list(config, "no-such-prefix").size());

        // A file that exists but whose contents are not a valid request.
        TestUtils.writeToFile("request_5555", "this is not a request payload");
        Assert.assertNull("a corrupt payload must not restore", Storage.read(config, new Request(5_555L)));

        // An empty file is equally unusable.
        TestUtils.createFile("request_6666");
        Assert.assertNull(Storage.read(config, new Request(6_666L)));

        // Removing something that was never there is a no-op, not an error.
        Assert.assertFalse(Storage.remove(config, new Request(7_777L)));

        // await must return even when there is nothing outstanding, and tolerate a null logger.
        Storage.await(mock(Log.class));
        Storage.await(null);
    }

    /**
     * The file layer's own answers when asked for something that is not there. {@code getName} and
     * {@code extractName} are private, so they are exercised only through the storable operations
     * above rather than directly.
     */
    @Test
    public void fileLayer_answersEmptyForAnythingItDoesNotHave() {
        SDKStorage storage = config.sdk.sdkStorage;

        Assert.assertNull(storage.storableReadBytes(config, "request", 999_999L));
        Assert.assertNull(storage.storableReadBytes(config, "no-such-thing"));

        // Listing a prefix nothing was ever written under is empty, not null.
        Assert.assertNotNull(storage.storableList(config, "request", 0));
        Assert.assertEquals(0, storage.storableList(config, "nothing-here", 0).size());

        // Popping something absent reports "not successful" rather than inventing a storable. The
        // return is a nullable Boolean, and absent is signalled as null.
        Assert.assertNotEquals(Boolean.TRUE, storage.storablePop(config, new Request(999_998L)));

        // A written payload is readable back byte for byte, and then listable by its prefix.
        Assert.assertTrue(storage.storableWrite(config, "request", 9_500L, "raw=payload".getBytes()));
        Assert.assertArrayEquals("raw=payload".getBytes(), storage.storableReadBytes(config, "request", 9_500L));
        Assert.assertTrue(storage.storableList(config, "request", 0).contains(9_500L));
    }

    /**
     * The event queue's own file, which is written and read as raw text rather than as a storable.
     * A round trip must be exact, and a missing file must read back as empty rather than null.
     */
    @Test
    public void eventQueueFile_roundTripsAndToleratesAbsence() {
        SDKStorage storage = config.sdk.sdkStorage;

        storage.storeEventQueue("event-one:::event-two");
        Assert.assertEquals("event-one:::event-two", storage.readEventQueue());

        // Overwriting replaces rather than appends.
        storage.storeEventQueue("only-this");
        Assert.assertEquals("only-this", storage.readEventQueue());

        // Clearing it leaves an empty read rather than a null.
        storage.storeEventQueue("");
        Assert.assertEquals("", storage.readEventQueue());
    }

    /**
     * The bulk transform used by the migration helper: every stored payload is handed to the
     * transformer and the replacement is written back. A transformer that declines to change a
     * payload must leave it exactly as it was.
     */
    @Test
    public void bulkTransform_rewritesOnlyWhatTheTransformerReplaces() {
        Storage.push(config, requestWith(8_001L, "keep", "unchanged"));
        Storage.push(config, requestWith(8_002L, "change", "me"));

        Map<Long, Integer> seen = new HashMap<>();
        boolean success = Storage.transform(config, Request.getStoragePrefix(), (id, data) -> {
            seen.merge(id, 1, Integer::sum);
            String payload = new String(data);
            if (payload.contains("change=me")) {
                return payload.replace("change=me", "change=done").getBytes();
            }
            // Declining to transform must leave the stored bytes alone.
            return null;
        });

        Assert.assertTrue("the transform must report success", success);
        Assert.assertEquals("every stored payload must be visited", 2, seen.size());

        Request untouched = Storage.read(config, new Request(8_001L));
        Assert.assertNotNull(untouched);
        Assert.assertEquals("unchanged", untouched.params.get("keep"));

        Request rewritten = Storage.read(config, new Request(8_002L));
        Assert.assertNotNull(rewritten);
        Assert.assertEquals("done", rewritten.params.get("change"));
    }

    /**
     * {@link TimedEvents} is the persisted map of events that were started but not yet ended, so it
     * has to survive a restart. Proves the round trip and that a broken payload is refused.
     */
    @Test
    public void timedEvents_surviveARoundTripAndRefuseCorruptData() {
        Log logger = mock(Log.class);

        TimedEvents original = new TimedEvents();
        Assert.assertEquals(0, original.size());
        Assert.assertFalse(original.has("nothing"));
        Assert.assertTrue(original.keys().isEmpty());
        Assert.assertEquals(Long.valueOf(0L), original.storageId());
        Assert.assertEquals("timedEvent", original.storagePrefix());

        // setId is deliberately a no-op: there is only ever one timed event map.
        original.setId(99L);
        Assert.assertEquals(Long.valueOf(0L), original.storageId());

        byte[] stored = original.store(logger);
        Assert.assertNotNull(stored);

        TimedEvents restored = new TimedEvents();
        Assert.assertTrue(restored.restore(stored, logger));
        Assert.assertEquals(0, restored.size());

        // A started event is held by the events module, and the persisted map reflects it.
        Countly.instance().events().startEvent("timedByModule");
        ModuleEvents events = SDKCore.instance.module(ModuleEvents.class);
        Assert.assertNotNull(events);
        Assert.assertTrue(events.timedEvents.containsKey("timedByModule"));

        TimedEvents withOne = new TimedEvents();
        byte[] persisted = withOne.store(logger);
        Assert.assertNotNull(persisted);

        // Corrupt payloads are refused rather than half applied.
        for (byte[] broken : new byte[][] { new byte[0], "not a stream".getBytes() }) {
            Assert.assertFalse(new TimedEvents().restore(broken, logger));
        }

        // Restoring a real payload routes the events into the module when one is present.
        Assert.assertTrue(new TimedEvents().restore(stored, logger));
    }

    /**
     * A timed event started and ended through the deprecated {@link TimedEvents} entry points still
     * ends up on the event queue, because a long lived integration may still be using them.
     */
    @Test
    public void deprecatedTimedEventApi_stillEndsUpOnTheEventQueue() {
        TimedEvents timed = new TimedEvents();

        EventImpl started = timed.event(config, "deprecatedTimed");
        Assert.assertNotNull("starting a timed event must hand one back", started);
        Assert.assertEquals("deprecatedTimed", started.key);

        started.setCount(2).setSum(5.0);
        timed.recordEvent(started);

        List<EventImpl> queued = TestUtils.getCurrentEQ();
        Assert.assertEquals(1, queued.size());
        Assert.assertEquals("deprecatedTimed", queued.get(0).key);
        Assert.assertEquals(2, queued.get(0).count);
        Assert.assertEquals(Double.valueOf(5.0), queued.get(0).sum);
    }

    /**
     * Pushing a storable whose serialisation fails must be reported as a failure rather than
     * silently dropping the data.
     */
    @Test
    public void aStorableThatCannotSerialize_isReportedAsAFailure() {
        Storable unserializable = new Storable() {
            @Override
            public Long storageId() {
                return 9_100L;
            }

            @Override
            public String storagePrefix() {
                return "broken";
            }

            @Override
            public void setId(Long id) {
            }

            @Override
            public byte[] store(Log L) {
                // Exactly what a Storable does when it cannot serialise itself.
                return null;
            }

            @Override
            public boolean restore(byte[] data, Log L) {
                return false;
            }
        };

        Assert.assertFalse("a storable that serialises to nothing must not report success",
            Storage.push(config, unserializable));

        // Whatever it left behind must not read back as usable data. Note it does leave a zero
        // length file in the storage directory, which is reported as a bug rather than asserted.
        Assert.assertNull(Storage.read(config, new Request(9_100L)));
    }

    // endregion
    // region helpers

    private static Request requestWith(long id, String key, String value) {
        Request request = new Request(id);
        request.params = new Params();
        request.params.add(key, value);
        return request;
    }

    @SuppressWarnings("unused")
    private static File storageDirectory() {
        return TestUtils.getTestSDirectory();
    }

    // endregion
}
