package ly.count.sdk.java.internal;

import java.util.HashMap;
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
 * {@link SessionImpl} as a {@link Storable}, plus the recovery path that runs on the next start after
 * a crash.
 * <p>
 * A session is the one object the SDK persists across process death, so its serialisation is what
 * decides whether a customer loses a session or gets a duplicate. Everything here goes through the
 * real {@link Storage} rather than calling {@code store}/{@code restore} directly, so the on-disk
 * form is what is being asserted.
 */
@RunWith(JUnit4.class)
public class SessionStorableTests {

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
     * A session with every field populated survives a round trip through storage. Proves the events,
     * the extra params and the consent mask all come back, and that the restored session compares
     * equal to the one that was written.
     */
    @Test
    public void fullSession_survivesARoundTripThroughStorage() {
        Countly.instance().init(TestUtils.getConfigSessions(Config.Feature.Events));
        InternalConfig config = SDKCore.instance.config;

        SessionImpl original = new SessionImpl(config, 5_000_000_000L);
        original.begin(1_000L);
        original.update(2_000L);
        original.addParam("extraKey", "extraValue");
        original.setConsents(config, CoreFeature.Events.getIndex() | CoreFeature.Sessions.getIndex());
        original.recordEvent(original.event("storedEvent").setCount(2).setSum(3.0));

        Assert.assertTrue(Storage.push(config, original));

        SessionImpl restored = Storage.read(config, new SessionImpl(config, original.getId()));
        Assert.assertNotNull("the session must be readable back off disk", restored);
        Assert.assertEquals(original.getId(), restored.getId());
        Assert.assertEquals(original.getBegan(), restored.getBegan());
        Assert.assertEquals(original.getEnded(), restored.getEnded());
        Assert.assertEquals("extraValue", restored.params.get("extraKey"));
        Assert.assertTrue(restored.hasConsent(CoreFeature.Events.getIndex()));
        Assert.assertTrue(restored.hasConsent(CoreFeature.Sessions.getIndex()));
        Assert.assertFalse(restored.hasConsent(CoreFeature.Location.getIndex()));
        Assert.assertEquals(original, restored);

        // Removing it really takes it off disk, so a recovered session cannot be sent twice.
        Assert.assertTrue(Storage.remove(config, restored));
        Assert.assertNull(Storage.read(config, new SessionImpl(config, original.getId())));
    }

    /**
     * A corrupt or truncated session file must be refused rather than half restored, because a
     * half restored session would send nonsense durations to the server.
     */
    @Test
    public void corruptSessionData_isRefusedRatherThanHalfRestored() {
        Countly.instance().init(TestUtils.getConfigSessions());
        InternalConfig config = SDKCore.instance.config;
        Log logger = mock(Log.class);

        SessionImpl good = new SessionImpl(config, 6_000_000_000L);
        good.begin(1_000L);
        byte[] stored = good.store(logger);
        Assert.assertNotNull(stored);

        Map<String, byte[]> broken = new HashMap<>();
        broken.put("empty", new byte[0]);
        broken.put("not a serialized stream", "definitely not a session".getBytes());
        broken.put("truncated halfway", java.util.Arrays.copyOf(stored, stored.length / 2));

        broken.forEach((what, data) ->
            Assert.assertFalse(what + " must not restore", new SessionImpl(config, 6_000_000_000L).restore(data, logger)));

        // The intact bytes still restore, so the guard is not rejecting everything.
        Assert.assertTrue(new SessionImpl(config, 6_000_000_000L).restore(stored, logger));

        // A restore with a mismatched id logs but still reads the payload, which is the documented
        // behaviour: the id comes from the file name, not the contents.
        SessionImpl mismatched = new SessionImpl(config, 9_999L);
        Assert.assertTrue(mismatched.restore(stored, logger));
        Assert.assertEquals(Long.valueOf(1_000L), mismatched.getBegan());
    }

    /**
     * Recovery of the sessions left behind by a process that died. Each shape a leftover session can
     * have is driven through {@code recover} in one flow: never begun, begun but never updated or
     * ended, begun and updated, and already ended.
     */
    @Test
    public void leftoverSessions_areRecoveredOrDiscardedByShape() {
        Countly.instance().init(TestUtils.getConfigSessions(Config.Feature.Events));
        InternalConfig config = SDKCore.instance.config;

        // Never begun: nothing happened, so it is simply dropped.
        SessionImpl neverBegun = new SessionImpl(config, 1_000_000L);
        Storage.push(config, neverBegun);
        Assert.assertTrue(neverBegun.recover(config));
        Assert.assertNull(Storage.read(config, new SessionImpl(config, 1_000_000L)));

        // Begun and already ended: also nothing to do but drop it.
        SessionImpl alreadyEnded = new SessionImpl(config, 2_000_000L);
        alreadyEnded.begin(1_000L);
        alreadyEnded.ended = 2_000L;
        Storage.push(config, alreadyEnded);
        Assert.assertTrue(alreadyEnded.recover(config));

        // Begun, never updated, never ended: ended at its begin time so no duration is invented.
        SessionImpl begunOnly = new SessionImpl(config, 3_000_000L);
        begunOnly.begin(1_000L);
        Storage.push(config, begunOnly);
        Assert.assertNotNull(begunOnly.recover(config));
        Assert.assertNotNull("recovery must close the session", begunOnly.getEnded());

        // Begun and updated: ended at the last update, which is the last moment we know it was alive.
        SessionImpl updated = new SessionImpl(config, 4_000_000L);
        updated.begin(1_000L);
        updated.update(2_000L);
        Storage.push(config, updated);
        Assert.assertNotNull(updated.recover(config));
        Assert.assertNotNull(updated.getEnded());

        // A session stamped in the future cannot be reasoned about, so recovery declines to guess.
        SessionImpl fromTheFuture = new SessionImpl(config, System.currentTimeMillis() + 600_000L);
        fromTheFuture.begin(1_000L);
        Assert.assertNull("a session from the future must not be recovered", fromTheFuture.recover(config));
    }

    /**
     * Session equality, which storage relies on to tell two persisted sessions apart. One table over
     * every field that participates, checked both ways round.
     */
    @Test
    public void sessionEquality_comparesTheStoredFields() {
        Countly.instance().init(TestUtils.getConfigSessions());
        InternalConfig config = SDKCore.instance.config;

        SessionImpl base = session(config, 7_000_000L, 10L, 20L, 30L);
        SessionImpl identical = session(config, 7_000_000L, 10L, 20L, 30L);
        Assert.assertEquals(base, identical);
        Assert.assertEquals(identical, base);
        Assert.assertEquals(base, base);

        Map<String, SessionImpl> differing = new HashMap<>();
        differing.put("id", session(config, 8_000_000L, 10L, 20L, 30L));
        differing.put("began", session(config, 7_000_000L, 99L, 20L, 30L));
        differing.put("updated", session(config, 7_000_000L, 10L, 99L, 30L));
        differing.put("ended", session(config, 7_000_000L, 10L, 20L, 99L));
        differing.put("null began", session(config, 7_000_000L, null, 20L, 30L));
        differing.put("null ended", session(config, 7_000_000L, 10L, 20L, null));

        differing.forEach((what, other) -> {
            Assert.assertNotEquals("differing " + what, base, other);
            Assert.assertNotEquals("differing " + what + " either way", other, base);
        });

        Assert.assertNotEquals(base, "not a session");
    }

    /**
     * The session's own event recorder. An event recorded on a session with the Events feature off
     * must be dropped, and one recorded with it on must reach the event queue.
     */
    @Test
    public void sessionEventRecording_honoursTheEventsFeature() {
        Countly.instance().init(TestUtils.getConfigSessions());
        Assert.assertFalse(SDKCore.enabled(CoreFeature.Events));

        SessionImpl withoutEvents = (SessionImpl) Countly.session();
        Assert.assertNotNull(withoutEvents);
        withoutEvents.recordEvent(withoutEvents.event("droppedEvent").setCount(1));
        Assert.assertEquals("an event needs the Events feature", 0, TestUtils.getCurrentEQ().size());

        Countly.instance().halt();
        TestUtils.createCleanTestState();

        Countly.instance().init(TestUtils.getConfigSessions(Config.Feature.Events).setEventQueueSizeToSend(100));
        SessionImpl withEvents = (SessionImpl) Countly.session();
        Assert.assertNotNull(withEvents);
        withEvents.recordEvent(withEvents.event("keptEvent").setCount(4).setSum(2.0));

        Assert.assertEquals(1, TestUtils.getCurrentEQ().size());
        TestUtils.validateEvent(TestUtils.getCurrentEQ().get(0), "keptEvent", null, 4, 2.0, null, "_CLY_", null, "", null);
    }

    /**
     * Backend mode owns the wire, so the session API must stand down: begin does nothing and neither
     * device id change is forwarded.
     */
    @Test
    public void backendMode_makesTheSessionApiInert() {
        Config config = TestUtils.getBaseConfig().enableFeatures(Config.Feature.Sessions);
        config.enableBackendMode();
        Countly.instance().init(config);

        SessionImpl session = new SessionImpl(SDKCore.instance.config, 12_000_000L);
        Assert.assertSame(session, session.begin());
        Assert.assertNull("backend mode must not begin a session", session.getBegan());

        Assert.assertSame(session, session.changeDeviceIdWithMerge("ignored_merge"));
        Assert.assertSame(session, session.changeDeviceIdWithoutMerge("ignored_no_merge"));
        Assert.assertEquals("the device id must be untouched", TestUtils.DEVICE_ID, session.getDeviceId());
    }

    /**
     * Beginning a session twice, or beginning one that already ended, must be refused so a customer
     * cannot double count a session.
     */
    @Test
    public void beginningASessionTwice_isRefused() {
        Countly.instance().init(TestUtils.getConfigSessions());
        InternalConfig config = SDKCore.instance.config;

        SessionImpl session = new SessionImpl(config, 13_000_000L);
        Assert.assertNotNull(session.begin(1_000L));
        Assert.assertNull("a second begin must be refused", session.begin(2_000L));

        SessionImpl ended = new SessionImpl(config, 14_000_000L);
        ended.ended = 5_000L;
        Assert.assertNull("an ended session cannot begin", ended.begin(1_000L));

        // The single argument constructor stamps its own id, which must be usable straight away.
        SessionImpl fresh = new SessionImpl(config);
        Assert.assertNotNull(fresh.getId());
        Assert.assertTrue(fresh.getId() > 0);
        Assert.assertNull(fresh.getBegan());
        Assert.assertNull(fresh.getEnded());
        Assert.assertEquals(SessionImpl.getStoragePrefix(), fresh.storagePrefix());
        Assert.assertEquals(fresh.getId(), fresh.storageId());

        fresh.setId(15_000_000L);
        Assert.assertEquals(Long.valueOf(15_000_000L), fresh.getId());
        Assert.assertSame(fresh, fresh.setPushOnChange(false));
    }

    // endregion
    // region helpers

    private static SessionImpl session(InternalConfig config, long id, Long began, Long updated, Long ended) {
        SessionImpl session = new SessionImpl(config, id);
        session.began = began;
        session.updated = updated;
        session.ended = ended;
        return session;
    }

    // endregion
}
