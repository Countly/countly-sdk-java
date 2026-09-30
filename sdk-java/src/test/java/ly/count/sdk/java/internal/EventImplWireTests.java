package ly.count.sdk.java.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import ly.count.sdk.java.Event;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.mockito.Mockito.mock;

/**
 * The fluent {@link Event} builder, its validation, and its JSON form.
 * <p>
 * Driven through {@code Countly.instance().event(key)}, which is the builder a customer still has,
 * so these also exercise {@code SessionImpl.event} and {@code SessionImpl.recordEvent} on the way to
 * the event queue. Assertions are on the queued event, which is what later goes on the wire.
 */
@RunWith(JUnit4.class)
public class EventImplWireTests {

    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
    }

    @After
    public void stop() {
        Countly.instance().halt();
    }

    // region scenarios

    /**
     * A complete event built with the fluent setters reaches the queue with every field intact, and
     * the JSON it serialises to is the shape the server expects.
     */
    @Test
    public void fluentEvent_carriesEveryFieldIntoTheQueue() {
        Countly.instance().init(TestUtils.getConfigEvents(4));

        Countly.instance().event("fluentKey")
            .setCount(3)
            .setSum(9.5)
            .setDuration(4.25)
            .addSegment("colour", "amber")
            .addSegments("shape", "round", "size", "large")
            .record();

        List<EventImpl> queued = TestUtils.getCurrentEQ();
        Assert.assertEquals(1, queued.size());

        Map<String, Object> expected = new HashMap<>();
        expected.put("colour", "amber");
        expected.put("shape", "round");
        expected.put("size", "large");
        TestUtils.validateEvent(queued.get(0), "fluentKey", expected, 3, 9.5, 4.25, "_CLY_", null, "", null);

        // The JSON form is what gets url-encoded into the "events" parameter.
        String json = queued.get(0).toJSON(mock(Log.class));
        Assert.assertTrue(json.contains("\"key\":\"fluentKey\""));
        Assert.assertTrue(json.contains("\"count\":3"));
        Assert.assertTrue(json.contains("\"sum\":9.5"));
        Assert.assertTrue(json.contains("\"dur\":4.25"));
        Assert.assertTrue(json.contains("\"amber\""));
    }

    /**
     * Every way of giving the builder a nonsense value marks the event invalid, and an invalid event
     * is never recorded. One flow per bad value, so a regression in any single guard shows up as this
     * test failing rather than as a silent extra event on a customer's dashboard.
     */
    @Test
    public void fluentEvent_rejectsEveryBadValueAndRecordsNothing() {
        Countly.instance().init(TestUtils.getConfigEvents(100));

        List<String> cases = new ArrayList<>();
        List<Event> bad = new ArrayList<>();

        cases.add("zero count");
        bad.add(Countly.instance().event("badCount").setCount(0));
        cases.add("negative count");
        bad.add(Countly.instance().event("negCount").setCount(-4));
        cases.add("NaN sum");
        bad.add(Countly.instance().event("nanSum").setSum(Double.NaN));
        cases.add("infinite sum");
        bad.add(Countly.instance().event("infSum").setSum(Double.POSITIVE_INFINITY));
        cases.add("NaN duration");
        bad.add(Countly.instance().event("nanDur").setDuration(Double.NaN));
        cases.add("negative duration");
        bad.add(Countly.instance().event("negDur").setDuration(-1.0));
        cases.add("empty segmentation key");
        bad.add(Countly.instance().event("emptyKey").addSegment("", "value"));
        cases.add("null segmentation key");
        bad.add(Countly.instance().event("nullKey").addSegment(null, "value"));
        cases.add("empty segmentation value");
        bad.add(Countly.instance().event("emptyValue").addSegment("key", ""));
        cases.add("null segmentation value");
        bad.add(Countly.instance().event("nullValue").addSegment("key", null));
        cases.add("odd length varargs");
        bad.add(Countly.instance().event("oddVarargs").addSegments("only"));
        cases.add("empty varargs");
        bad.add(Countly.instance().event("emptyVarargs").addSegments());
        cases.add("null segmentation map");
        bad.add(Countly.instance().event("nullMap").setSegmentation(null));

        for (int i = 0; i < bad.size(); i++) {
            Assert.assertTrue(cases.get(i) + " must mark the event invalid", ((EventImpl) bad.get(i)).isInvalid());
            bad.get(i).record();
        }

        Assert.assertEquals("no invalid event may be recorded", 0, TestUtils.getCurrentEQ().size());

        // The very same builder, with sane values, still works: the guards are not a blanket block.
        Countly.instance().event("goodAfterBad").setCount(1).setSum(1.0).record();
        List<EventImpl> queued = TestUtils.getCurrentEQ();
        Assert.assertEquals(1, queued.size());
        Assert.assertEquals("goodAfterBad", queued.get(0).key);
    }

    /**
     * A recorded event is recorded once. The second {@code record()} is dropped, which is what stops
     * a retry loop in customer code from multiplying a metric.
     */
    @Test
    public void recordingTheSameEventTwice_onlyQueuesItOnce() {
        Countly.instance().init(TestUtils.getConfigEvents(100));

        Event event = Countly.instance().event("recordedOnce").setCount(1);
        event.record();
        event.record();
        event.record();

        Assert.assertEquals(1, TestUtils.getCurrentEQ().size());
    }

    /**
     * {@code endAndRecord} stamps the elapsed time as the duration. Proves the duration is derived
     * rather than left unset, without asserting a wall-clock value.
     */
    @Test
    public void endAndRecord_stampsAnElapsedDuration() {
        Countly.instance().init(TestUtils.getConfigEvents(100));

        Countly.instance().event("timedByHand").setCount(1).endAndRecord();

        List<EventImpl> queued = TestUtils.getCurrentEQ();
        Assert.assertEquals(1, queued.size());
        Assert.assertNotNull("endAndRecord must set a duration", queued.get(0).duration);
        Assert.assertTrue("duration must not be negative", queued.get(0).duration >= 0);
    }

    /**
     * Backend mode owns the wire, so the ordinary event builder must stand down entirely rather than
     * queue events nobody will send.
     */
    @Test
    public void backendModeEnabled_makesTheEventBuilderInert() {
        Config config = TestUtils.getBaseConfig().enableFeatures(Config.Feature.Events);
        config.enableBackendMode();
        Countly.instance().init(config);

        EventImpl direct = new EventImpl(event -> Assert.fail("backend mode must not record events"), "backendModeEvent", mock(Log.class));
        direct.setCount(1);
        direct.record();
        direct.endAndRecord();

        Assert.assertEquals(0, TestUtils.getCurrentEQ().size());
    }

    /**
     * Equality is identity for the event queue's purposes: same key, timestamp and payload is the
     * same event. One table, both directions, plus the hash code agreeing with it.
     */
    @Test
    public void eventEquality_comparesThePayloadNotTheInstance() {
        Log logger = mock(Log.class);

        EventImpl base = event(logger, "sameKey", 1000L, 2, 3.0, 4.0, "a", "b");
        EventImpl identical = event(logger, "sameKey", 1000L, 2, 3.0, 4.0, "a", "b");

        Assert.assertEquals(base, identical);
        Assert.assertEquals(identical, base);
        Assert.assertEquals(base.hashCode(), identical.hashCode());
        Assert.assertEquals(base, base);

        Map<String, EventImpl> differing = new HashMap<>();
        differing.put("key", event(logger, "otherKey", 1000L, 2, 3.0, 4.0, "a", "b"));
        differing.put("timestamp", event(logger, "sameKey", 2000L, 2, 3.0, 4.0, "a", "b"));
        differing.put("count", event(logger, "sameKey", 1000L, 9, 3.0, 4.0, "a", "b"));
        differing.put("sum", event(logger, "sameKey", 1000L, 2, 9.0, 4.0, "a", "b"));
        differing.put("duration", event(logger, "sameKey", 1000L, 2, 3.0, 9.0, "a", "b"));
        differing.put("segmentation", event(logger, "sameKey", 1000L, 2, 3.0, 4.0, "a", "z"));
        differing.put("null sum", event(logger, "sameKey", 1000L, 2, null, 4.0, "a", "b"));
        differing.put("null duration", event(logger, "sameKey", 1000L, 2, 3.0, null, "a", "b"));

        differing.forEach((what, other) -> {
            Assert.assertNotEquals("differing " + what + " must not be equal", base, other);
            Assert.assertNotEquals("differing " + what + " must not be equal either way", other, base);
        });

        // Anything that is not an event is simply not equal, never a class cast.
        Assert.assertNotEquals(base, "not an event");
        Assert.assertNotEquals(base, null);
    }

    /**
     * The JSON round trip the event queue relies on, plus every way the stored form can be broken.
     * A corrupt entry must come back as null rather than take the queue down with it.
     */
    @Test
    public void jsonRoundTrip_survivesAndRejectsCorruptEntries() {
        Log logger = mock(Log.class);

        EventImpl original = event(logger, "roundTrip", 1234567890L, 7, 1.5, 2.5, "seg", "val");
        original.id = "eid";
        original.pvid = "pvid";
        original.cvid = "cvid";
        original.peid = "peid";

        EventImpl restored = EventImpl.fromJSON(original.toJSON(logger), null, logger);
        Assert.assertNotNull(restored);
        Assert.assertEquals(original, restored);
        Assert.assertEquals("roundTrip", restored.getKey());
        Assert.assertEquals(7, restored.getCount());
        Assert.assertEquals(Double.valueOf(1.5), restored.getSum());
        Assert.assertEquals(Double.valueOf(2.5), restored.getDuration());
        Assert.assertEquals("val", restored.getSegment("seg"));
        Assert.assertEquals("eid", restored.id);
        Assert.assertEquals("pvid", restored.pvid);
        Assert.assertEquals("cvid", restored.cvid);
        Assert.assertEquals("peid", restored.peid);
        Assert.assertTrue(restored.getHour() >= 0 && restored.getHour() < 24);
        Assert.assertTrue(restored.getDow() >= 0 && restored.getDow() < 7);
        Assert.assertEquals(1, restored.getSegmentation().size());

        // Every broken stored form must be refused, not half-parsed.
        Map<String, String> broken = new HashMap<>();
        broken.put("not json at all", "}{");
        broken.put("missing key", "{\"count\":1}");
        broken.put("null key", "{\"key\":null,\"count\":1}");
        broken.put("empty object", "{}");
        broken.forEach((what, json) ->
            Assert.assertNull(what + " must not deserialize", EventImpl.fromJSON(json, null, logger)));

        // A segmentation value of an unsupported type is dropped, the rest of the event survives.
        EventImpl mixed = EventImpl.fromJSON(
            "{\"key\":\"mixed\",\"count\":1,\"segmentation\":{\"ok\":\"yes\",\"bad\":{\"nested\":1},\"nulled\":null}}",
            null, logger);
        Assert.assertNotNull(mixed);
        Assert.assertEquals("yes", mixed.getSegment("ok"));
        Assert.assertEquals("only the supported segmentation value survives", 1, mixed.getSegmentation().size());

        // A restored event has no recorder, so recording it is a logged no-op rather than a crash.
        Assert.assertNotNull(restored);
        restored.record();
    }

    // endregion
    // region helpers

    /**
     * Builds an event directly, which is the only way to pin the timestamp that equality depends on.
     */
    private static EventImpl event(Log logger, String key, long timestamp, int count, Double sum, Double duration,
        String segmentKey, String segmentValue) {
        Map<String, Object> segmentation = new HashMap<>();
        segmentation.put(segmentKey, segmentValue);
        EventImpl event = new EventImpl(key, count, sum, duration, segmentation, logger, null, null, null, null);
        event.timestamp = timestamp;
        return event;
    }

    // endregion
}
