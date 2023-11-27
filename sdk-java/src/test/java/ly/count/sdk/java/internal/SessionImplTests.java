package ly.count.sdk.java.internal;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import ly.count.sdk.java.Session;
import ly.count.sdk.java.View;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@RunWith(JUnit4.class)
public class SessionImplTests {
    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
    }

    @After
    public void stop() {
        Countly.instance().halt();
    }

    /**
     * "constructor"
     * gets a valid long ID to create a session with
     * returned session should have the input ID
     */
    @Test
    public void constructor() {
        Countly.instance().init(TestUtils.getConfigSessions());
        assertEquals(new Long(12345L), createSessionImpl(12345L).getId());
    }

    /**
     * "constructor"
     * gets a null ID to create a session with
     * returned session should have ID > 0 that is generated automatically
     */
    @Test
    public void constructor_nullId() {
        Countly.instance().init(TestUtils.getConfigSessions());
        assertTrue(createSessionImpl(null).getId() > 0);// The ID should be generated and not null
    }

    /**
     * "begin(long)" with already began session
     * Try to begin already began session, validate that it is not changed
     * returned result should be null
     */
    @Test
    public void begin_began() {
        Countly.instance().init(TestUtils.getConfigSessions());
        SessionImpl session = (SessionImpl) Countly.session();
        validateBeganSession((SessionImpl) session.begin());

        Assert.assertNull(session.begin(0L));
        validateBeganSession(session);
    }

    /**
     * "begin(long)" with already ended session
     * Try to begin already ended session, validate that it is not changed
     * returned result should be null
     */
    @Test
    public void begin_ended() {
        Countly.instance().init(TestUtils.getConfigSessions());
        SessionImpl session = (SessionImpl) Countly.session();
        validateBeganSession((SessionImpl) session.begin());
        validateUpdatedSession((SessionImpl) session.update());
        session.end();
        validateEndedSession(session);
        Assert.assertNull(session.begin(0L));
    }

    /**
     * "begin(long)" with null SDKCore instance
     * Try to begin a session, validate that it is not started
     * returned result should be null
     */
    @Test
    public void begin_nullInstance() {
        Countly.instance().init(TestUtils.getConfigSessions());
        SessionImpl session = (SessionImpl) Countly.session();
        SDKCore.instance = null;

        Assert.assertNull(session.begin(0L));
        validateNotStarted(session);
    }

    /**
     * "begin(long)"
     * Try to begin a session, validate that it began
     * returned result should be true
     */
    @Test
    public void begin() throws ExecutionException, InterruptedException {
        Countly.instance().init(TestUtils.getConfigSessions());
        SessionImpl session = (SessionImpl) Countly.session();

        Assert.assertTrue(session.begin(0L).get());
        validateBeganSession(session);
    }

    /**
     * "begin()" with backend mode enabled
     * Try to begin a session, validate that it is not started
     * returned result should be null
     */
    @Test
    public void begin_backendModeEnabled() {
        Countly.instance().init(TestUtils.getConfigSessions().enableBackendMode());
        validateNotStarted((SessionImpl) Countly.session());
    }

    /**
     * "update(long)" with not began session
     * Try to update a session, validate that it is not started and updated
     * returned result should be null
     */
    @Test
    public void update_notStarted() throws ExecutionException, InterruptedException {
        Countly.instance().init(TestUtils.getConfigSessions());
        SessionImpl session = (SessionImpl) Countly.session();

        Assert.assertNull(session.update(0L));
        validateNotStarted(session);
    }

    /**
     * "update(long)" with ended session
     * Try to update a session, validate that it's result is null
     * returned result should be null
     */
    @Test
    public void update_ended() {
        Countly.instance().init(TestUtils.getConfigSessions());
        SessionImpl session = (SessionImpl) Countly.session();
        validateBeganSession((SessionImpl) session.begin());
        validateUpdatedSession((SessionImpl) session.update());
        session.end();
        validateEndedSession(session);
        Assert.assertNull(session.update(0L));
    }

    /**
     * "update(long)" with null SDKCore instance
     * Try to update a session, validate that it is not updated
     * returned result should be null
     */
    @Test
    public void update_nullInstance() throws ExecutionException, InterruptedException {
        Countly.instance().init(TestUtils.getConfigSessions());
        SessionImpl session = (SessionImpl) Countly.session();
        validateBeganSession((SessionImpl) session.begin());
        SDKCore.instance = null;

        Assert.assertNull(session.update(0L));
        validateBeganSession(session);
    }

    /**
     * "update(long)"
     * Try to update a session, validate that it is updated
     * returned result should be true
     */
    @Test
    public void update() throws ExecutionException, InterruptedException {
        Countly.instance().init(TestUtils.getConfigSessions());
        SessionImpl session = (SessionImpl) Countly.session();
        validateBeganSession((SessionImpl) session.begin());

        Assert.assertTrue(session.update(0L).get());
        validateUpdatedSession(session);
    }

    /**
     * "update()" with backend mode enabled
     * Try to update a session, validate that it is not started and updated
     * returned result should be not started and updated
     */
    @Test
    public void update_backendModeEnabled() {
        Countly.instance().init(TestUtils.getConfigSessions().enableBackendMode());
        SessionImpl session = (SessionImpl) Countly.session();

        validateNotStarted((SessionImpl) session.update());
    }

    /**
     * "end" with not started session
     * Try to end a session, validate that it is not ended
     * returned result should be null
     */
    @Test
    public void end_notStarted() {
        Countly.instance().init(TestUtils.getConfigSessions());
        SessionImpl session = (SessionImpl) Countly.session();

        Assert.assertNull(session.end(0L, null));
    }

    /**
     * "end" with ended session
     * Try to end a session, validate the result
     * returned result should be null
     */
    @Test
    public void end_ended() {
        Countly.instance().init(TestUtils.getConfigSessions());
        SessionImpl session = (SessionImpl) Countly.session();

        validateBeganSession((SessionImpl) session.begin());
        validateUpdatedSession((SessionImpl) session.update());
        session.end();
        validateEndedSession(session);
        Assert.assertNull(session.end(0L, null));
    }

    /**
     * "end" with null SDKCore instance
     * Try to end a session, validate that it is not started and ended
     * returned result should be null
     */
    @Test
    public void end_nullInstance() {
        Countly.instance().init(TestUtils.getConfigSessions());
        SessionImpl session = (SessionImpl) Countly.session();
        SDKCore.instance = null;

        Assert.assertNull(session.end(0L, null));
        validateNotStarted(session);
    }

    /**
     * "end"
     * Try to end a session, validate that it is ended, started and updated
     * returned value result be true
     *
     * @throws ExecutionException if the computation threw an exception
     * @throws InterruptedException if thread is interrupted
     */
    @Test
    public void end() throws ExecutionException, InterruptedException {
        Countly.instance().init(TestUtils.getConfigSessions());
        SessionImpl session = (SessionImpl) Countly.session();

        Assert.assertTrue(session.begin(0L).get());
        Assert.assertTrue(session.update(0L).get());
        Assert.assertTrue(session.end(0L, null).get());
        Thread.sleep(200);
        validateEndedSession(session);
    }

    /**
     * "end" with backend mode enabled
     * Try to end a session, validate that it is not started and ended
     * returned result should be not started, updated and ended
     */
    @Test
    public void end_backendModeEnabled() {
        Countly.instance().init(TestUtils.getConfigSessions().enableBackendMode());
        SessionImpl session = (SessionImpl) Countly.session();
        session.begin().end();

        validateNotStarted(session);
    }

    /**
     * "changeDeviceIdWithMerge"
     * Passing mock device id and validating that it is set
     * device id should be same as the one passed to the function
     */
    @Test
    public void changeDeviceIdWithMerge() {
        validateDeviceIdMerge("newDeviceId", "newDeviceId", true);
    }

    /**
     * "changeDeviceIdWithMerge" with null id
     * Passing null device id and validating that it is not set
     * should be same as the initial device id
     */
    @Test
    public void changeDeviceIdWithMerge_nullId() {
        validateDeviceIdMerge(null, TestUtils.DEVICE_ID, true);
    }

    /**
     * "changeDeviceIdWithMerge" with empty id
     * Passing empty device id and validating that it is not set
     * should be same as the initial device id
     */
    @Test
    public void changeDeviceIdWithMerge_emptyId() {
        validateDeviceIdMerge("", TestUtils.DEVICE_ID, true);
    }

    /**
     * "changeDeviceIdWithMerge" with backend mode enabled
     * Passing mock device id and validating that it is not set
     * should be same as the initial device id
     */
    @Test
    public void changeDeviceIdWithMerge_backendModeEnabled() {
        validateDeviceIdMerge("newDeviceId", TestUtils.DEVICE_ID, true, TestUtils.getConfigSessions().enableBackendMode());
    }

    /**
     * "changeDeviceIdWithoutMerge"
     * Passing mock device id and validating that it is set
     * device id should be same as the one passed to the function
     */
    @Test
    public void changeDeviceIdWithoutMerge() {
        validateDeviceIdMerge("newDeviceId", "newDeviceId", false);
    }

    /**
     * "changeDeviceIdWithoutMerge" with null id
     * Passing null device id and validating that it is not set
     * should be same as the initial device id
     */
    @Test
    public void changeDeviceIdWithoutMerge_nullId() {
        validateDeviceIdMerge(null, TestUtils.DEVICE_ID, false);
    }

    /**
     * "changeDeviceIdWithoutMerge" with empty id
     * Passing empty device id and validating that it is not set
     * should be same as the initial device id
     */
    @Test
    public void changeDeviceIdWithoutMerge_emptyId() {
        validateDeviceIdMerge("", TestUtils.DEVICE_ID, false);
    }

    /**
     * "changeDeviceIdWithoutMerge" with backend mode enabled
     * Passing mock device id and validating that it is not set
     * should be same as the initial device id
     */
    @Test
    public void changeDeviceIdWithoutMerge_backendModeEnabled() {
        validateDeviceIdMerge("newDeviceId", TestUtils.DEVICE_ID, false, TestUtils.getConfigSessions().enableBackendMode());
    }

    /**
     * "user" with null SDKCore instance
     * Setting SDKCore.instance to null and calling "user()" function
     * returned value should be null
     */
    @Test
    public void user_instanceNull() {
        Countly.instance().init(TestUtils.getConfigSessions());
        SDKCore.instance = null;

        Assert.assertNull(Countly.session().user());
    }

    /**
     * "user"
     * Calling function should return user
     * returned value should be not null
     */
    @Test
    public void user() {
        Countly.instance().init(TestUtils.getConfigSessions());
        SessionImpl session = (SessionImpl) Countly.session();

        Assert.assertNotNull(session.user());
    }

    /**
     * "addParam" with null key and value
     * Given key and value are null, validating that they are added,
     * added mock null key and value should exist in the params
     */
    @Test
    public void addParam_nullKeyValue() {
        Countly.instance().init(TestUtils.getConfigSessions());
        SessionImpl session = (SessionImpl) Countly.session();
        session.addParam(null, null);

        Assert.assertNull(session.params.get("null"));
    }

    /**
     * "addLocation" with no consent to location
     * mocked location given to function and validating from params
     * location should not be existed in the params
     */
    @Test
    public void addLocation_locationNotEnabled() {
        addLocation_base(TestUtils.getConfigSessions(), null);
    }

    /**
     * "addLocation"
     * mocked latitude longitude given to function and validating from params
     * given location should be existed in the params
     */
    @Test
    public void addLocation() {
        addLocation_base(TestUtils.getConfigSessions(Config.Feature.Location), "1.0,2.0");
    }

    /**
     * "addLocation" with backend mode enabled
     * mocked location given to function and validating from params
     * location should not be existed in the params
     */
    @Test
    public void addLocation_backendModeEnabled() {
        addLocation_base(TestUtils.getConfigSessions().enableBackendMode(), null);
    }

    private void addLocation_base(Config config, Object expected) {
        Countly.instance().init(config);
        SessionImpl session = (SessionImpl) Countly.session();
        session.addLocation(1.0, 2.0);

        Assert.assertEquals(expected, session.params.get("location"));
    }

    /**
     * "addCrashReport" with no consent to crash reporting
     * mocked exception given to function and validating function calls
     * SDKCore.instance().onCrash() should not be called
     */
    @Test
    public void addCrashReport_crashReportingNotEnabled() {
        addCrashReport_base(TestUtils.getConfigSessions(), 0);
    }

    /**
     * "addCrashReport"
     * mocked exception given to function and validating function calls
     * SDKCore.instance().onCrash() should be called once
     */
    @Test
    public void addCrashReport() {
        addCrashReport_base(TestUtils.getConfigSessions(Config.Feature.CrashReporting), 1);
        Map<String, String>[] requests = TestUtils.getCurrentRQ();
        Assert.assertTrue(requests[0].containsKey("crash"));
        Assert.assertTrue(!requests[0].get("crash").isEmpty());
    }

    /**
     * "addCrashReport" with backend mode enabled
     * mocked exception given to function and validating function calls
     * SDKCore.instance().onCrash() should not be called and expected log should be logged
     */
    @Test
    public void addCrashReport_backendModeEnabled() {
        addCrashReport_base(TestUtils.getConfigSessions().enableBackendMode(), 0);
    }

    private void addCrashReport_base(Config config, int rqSize) {
        Countly.instance().init(config);
        SessionImpl session = (SessionImpl) Countly.session();
        session.addCrashReport(new Exception(), false);
        Assert.assertEquals(rqSize, TestUtils.getCurrentRQ().length);
    }

    /**
     * "hashCode"
     * should return the same value as the ID
     */
    @Test
    public void hashCode_id() {
        Countly.instance().init(TestUtils.getConfigSessions());
        assertEquals(new Long(12345L).hashCode(), createSessionImpl(12345L).hashCode());
    }

    /**
     * "equals"
     * both mocked sessions are same
     * should return true
     */
    @Test
    public void equals_test() {
        //test keyword added for codacy
        Countly.instance().init(TestUtils.getConfigSessions());
        SessionImpl session = (SessionImpl) Countly.session().update();
        session.end();
        session.addParam("test", "value");
        SessionImpl session2 = (SessionImpl) Countly.session().update();
        session2.end();
        session2.began = session.began;
        session2.updated = session.updated;
        session2.ended = session.ended;
        session2.addParam("test", "value");
        session.id = 12345L;
        session2.id = 12345L;
        Assert.assertTrue(session.equals(session2));
    }

    /**
     * "equals" different class
     * should return false
     */
    @Test
    public void equals_notInstanceOf() {
        Countly.instance().init(TestUtils.getConfigSessions());
        Assert.assertFalse(createSessionImpl(12345L).equals(new Object()));
    }

    /**
     * "equals" different IDs
     * should return false
     */
    @Test
    public void equals_differentId() {
        validateNotEquals(1, ((session, session2) -> ts -> {
        }));
    }

    /**
     * "equals" different began
     * should return false
     */
    @Test
    public void equals_differentBegan() {
        validateNotEquals(0, (session1, session2) -> ts -> {
            session1.began = ts;
            session2.began = ts + 1;
        });
    }

    /**
     * "equals" different updated
     * should return false
     */
    @Test
    public void equals_differentUpdated() {
        validateNotEquals(0, (session1, session2) -> ts -> {
            session1.updated = ts;
            session2.updated = ts + 1;
        });
    }

    /**
     * "equals" different ended
     * should return false
     */
    @Test
    public void equals_differentEnded() {
        validateNotEquals(0, (session1, session2) -> ts -> {
            session1.ended = ts;
            session2.ended = ts + 1;
        });
    }

    /**
     * "equals" different params
     * should return false
     */
    @Test
    public void equals_differentParams() {
        validateNotEquals(0, (session1, session2) -> ts -> session1.addParam("key", "value"));
    }

    /**
     * "view" with no consent to views
     * mocked view name given to function and validating function calls
     * expected log should be logged
     */
    @Test
    public void view_viewsNotEnabled() {
        Countly.instance().init(TestUtils.getConfigSessions());
        SessionImpl session = (SessionImpl) Countly.session();
        session.L = spy(session.L);
        View view = session.view("view");
        verify(session.L, times(1)).i("[SessionImpl] view: Skipping view - feature is not enabled");
        Assert.assertNull(view);
    }

    /**
     * "view"
     * mocked view name given to function and validating from EQ
     * view should be recorded to EQ
     */
    @Test
    public void view() {
        Countly.instance().init(TestUtils.getConfigSessions(Config.Feature.Views, Config.Feature.Events).setEventQueueSizeToSend(4));
        SessionImpl session = (SessionImpl) Countly.session();
        TestUtils.validateEQSize(0);
        validateViewInEQ((ViewImpl) session.view("view"), 0, 1);
    }

    /**
     * "view"
     * mocked view name given to function and validating EQ size and from EQ
     * start, stop and next view should be recorded to EQ
     */
    @Test
    public void view_stopStartedAndNext() {
        Countly.instance().init(TestUtils.getConfigSessions(Config.Feature.Views, Config.Feature.Events).setEventQueueSizeToSend(4));
        SessionImpl session = (SessionImpl) Countly.session();
        TestUtils.validateEQSize(0);
        session.view("start");
        TestUtils.validateEQSize(1);
        validateViewInEQ((ViewImpl) session.view("next"), 2, 3);
    }

    private void validateViewInEQ(ViewImpl view, int eqIdx, int eqSize) {
        List<EventImpl> eventList = TestUtils.getCurrentEQ();
        assertEquals(eqSize, eventList.size());
        EventImpl event = eventList.get(eqIdx);
        assertEquals(event.sum, view.start.sum);
        assertEquals(event.count, view.start.count);
        assertEquals(event.key, view.start.key);
        assertEquals(event.segmentation, view.start.segmentation);
        assertEquals(event.hour, view.start.hour);
        assertEquals(event.dow, view.start.dow);
        assertEquals(event.duration, view.start.duration);
    }

    private void validateNotEquals(int idOffset, BiFunction<SessionImpl, SessionImpl, Consumer<Long>> setter) {
        Countly.instance().init(TestUtils.getConfigSessions());
        long ts = TimeUtils.uniqueTimestampMs();
        SessionImpl session = createSessionImpl(12345L);
        SessionImpl session2 = createSessionImpl(12345L + idOffset);
        setter.apply(session, session).accept(ts);
        Assert.assertFalse(session.equals(session2));
    }

    private void validateDeviceIdMerge(String deviceId, String expected, boolean merge) {
        validateDeviceIdMerge(deviceId, expected, merge, TestUtils.getConfigSessions());
    }

    private void validateDeviceIdMerge(String deviceId, String expected, boolean merge, Config config) {
        Countly.instance().init(config);
        Session session = Countly.session();
        if (merge) {
            session.changeDeviceIdWithMerge(deviceId);
        } else {
            session.changeDeviceIdWithoutMerge(deviceId);
        }
        assertEquals(expected, session.getDeviceId());
    }

    private SessionImpl validateBeganSession(SessionImpl session) {
        Assert.assertNotNull(session.began);
        Assert.assertNull(session.updated);
        Assert.assertNull(session.ended);
        Assert.assertTrue(session.isActive());
        return session;
    }

    private SessionImpl validateUpdatedSession(SessionImpl session) {
        Assert.assertNotNull(session.began);
        Assert.assertNotNull(session.updated);
        Assert.assertNull(session.ended);
        Assert.assertTrue(session.isActive());
        return session;
    }

    private SessionImpl validateEndedSession(SessionImpl session) {
        Assert.assertNotNull(session.began);
        Assert.assertNotNull(session.ended);
        Assert.assertFalse(session.isActive());
        return session;
    }

    private void validateNotStarted(SessionImpl session) {
        Assert.assertNull(session.began);
        Assert.assertNull(session.updated);
        Assert.assertNull(session.ended);
        Assert.assertFalse(session.isActive());
    }

    private SessionImpl createSessionImpl(Long id) {
        InternalConfig ic = new InternalConfig(TestUtils.getBaseConfig());
        ic.setLogger(new Log(Config.LoggingLevel.OFF, null));

        return new SessionImpl(ic, id);
    }
}
