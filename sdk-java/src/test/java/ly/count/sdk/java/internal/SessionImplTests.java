package ly.count.sdk.java.internal;

import java.util.List;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@RunWith(JUnit4.class)
public class SessionImplTests {

    private void init(Config cc) {
        Countly.instance().init(cc);
    }

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
        init(TestUtils.getConfigSessions());
        assertEquals(new Long(12345L), createSessionImpl(12345L).getId());
    }

    /**
     * "constructor"
     * gets a null ID to create a session with
     * returned session should have ID > 0 that is generated automatically
     */
    @Test
    public void constructor_nullId() {
        init(TestUtils.getConfigSessions());
        assertTrue(createSessionImpl(null).getId() > 0);// The ID should be generated and not null
    }

    /**
     * "begin(long)" with already began session
     * Try to begin already began session, validate that it is not changed
     * returned result should be null
     */
    @Test
    public void begin_began() {
        SessionImpl session = beganSession();

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
        Assert.assertNull(endedSession().begin(0L));
    }

    /**
     * "begin(long)" with null SDKCore instance
     * Try to begin a session, validate that it is not started
     * returned result should be null
     */
    @Test
    public void begin_nullInstance() {
        SessionImpl session = notStarted();
        SDKCore.instance = null;

        Assert.assertNull(session.begin(0L));
        validateNotStarted(session);
    }

    /**
     * "begin(long)"
     * Try to begin a session, validate that it began
     * returned result should be true
     *
     * @throws ExecutionException if the computation threw an exception
     * @throws InterruptedException if thread is interrupted
     */
    @Test
    public void begin() throws ExecutionException, InterruptedException {
        SessionImpl session = notStarted();

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
        SessionImpl session = notStarted(TestUtils.getConfigSessions().enableBackendMode());

        validateNotStarted((SessionImpl) session.begin());
    }

    /**
     * "update(long)" with not began session
     * Try to update a session, validate that it is not started and updated
     * returned result should be null
     */
    @Test
    public void update_notStarted() {
        SessionImpl session = notStarted();

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
        Assert.assertNull(endedSession().update(0L));
    }

    /**
     * "update(long)" with null SDKCore instance
     * Try to update a session, validate that it is not updated
     * returned result should be null
     */
    @Test
    public void update_nullInstance() {
        SessionImpl session = beganSession();
        SDKCore.instance = null;

        Assert.assertNull(session.update(0L));
        validateBeganSession(session);
    }

    /**
     * "update(long)"
     * Try to update a session, validate that it is updated
     * returned result should be true
     *
     * @throws ExecutionException if the computation threw an exception
     * @throws InterruptedException if thread is interrupted
     */
    @Test
    public void update() throws ExecutionException, InterruptedException {
        SessionImpl session = beganSession();

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
        validateNotStarted((SessionImpl) notStarted(TestUtils.getConfigSessions().enableBackendMode()).update());
    }

    /**
     * "end" with not started session
     * Try to end a session, validate that it is not ended
     * returned result should be null
     */
    @Test
    public void end_notStarted() {
        Assert.assertNull(notStarted().end(0L, null, null));
    }

    /**
     * "end" with ended session
     * Try to end a session, validate the result
     * returned result should be null
     */
    @Test
    public void end_ended() {
        Assert.assertNull(endedSession().end(0L, null, null));
    }

    /**
     * "end" with null SDKCore instance
     * Try to end a session, validate that it is not started and ended
     * returned result should be null
     */
    @Test
    public void end_nullInstance() {
        SessionImpl session = notStarted();
        SDKCore.instance = null;

        validateNotStarted((SessionImpl) session.begin());
        Assert.assertNull(session.end(0L, null, null));
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
        SessionImpl session = notStarted();

        Assert.assertTrue(session.begin(0L).get());
        Assert.assertTrue(session.update(0L).get());
        Assert.assertTrue(session.end(0L, Assert::assertTrue, null).get());
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
        SessionImpl session = notStarted(TestUtils.getConfigSessions().enableBackendMode());
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
        init(TestUtils.getConfigSessions());
        validateDeviceIdMerge("newDeviceId", "newDeviceId", true);
    }

    /**
     * "changeDeviceIdWithMerge" with null id
     * Passing null device id and validating that it is not set
     * should be same as the initial device id
     */
    @Test
    public void changeDeviceIdWithMerge_nullId() {
        init(TestUtils.getConfigSessions());
        validateDeviceIdMerge(null, TestUtils.DEVICE_ID, true);
    }

    /**
     * "changeDeviceIdWithMerge" with empty id
     * Passing empty device id and validating that it is not set
     * should be same as the initial device id
     */
    @Test
    public void changeDeviceIdWithMerge_emptyId() {
        init(TestUtils.getConfigSessions());
        validateDeviceIdMerge("", TestUtils.DEVICE_ID, true);
    }

    /**
     * "changeDeviceIdWithMerge" with backend mode enabled
     * Passing mock device id and validating that it is not set
     * should be same as the initial device id
     */
    @Test
    public void changeDeviceIdWithMerge_backendModeEnabled() {
        init(TestUtils.getConfigSessions().enableBackendMode());
        validateDeviceIdMerge("newDeviceId", TestUtils.DEVICE_ID, true);
    }

    /**
     * "changeDeviceIdWithoutMerge"
     * Passing mock device id and validating that it is set
     * device id should be same as the one passed to the function
     */
    @Test
    public void changeDeviceIdWithoutMerge() {
        init(TestUtils.getConfigSessions());
        validateDeviceIdMerge("newDeviceId", "newDeviceId", false);
    }

    /**
     * "changeDeviceIdWithoutMerge" with null id
     * Passing null device id and validating that it is not set
     * should be same as the initial device id
     */
    @Test
    public void changeDeviceIdWithoutMerge_nullId() {
        init(TestUtils.getConfigSessions());
        validateDeviceIdMerge(null, TestUtils.DEVICE_ID, false);
    }

    /**
     * "changeDeviceIdWithoutMerge" with empty id
     * Passing empty device id and validating that it is not set
     * should be same as the initial device id
     */
    @Test
    public void changeDeviceIdWithoutMerge_emptyId() {
        init(TestUtils.getConfigSessions());
        validateDeviceIdMerge("", TestUtils.DEVICE_ID, false);
    }

    /**
     * "changeDeviceIdWithoutMerge" with backend mode enabled
     * Passing mock device id and validating that it is not set
     * should be same as the initial device id
     */
    @Test
    public void changeDeviceIdWithoutMerge_backendModeEnabled() {
        init(TestUtils.getConfigSessions().enableBackendMode());
        validateDeviceIdMerge("newDeviceId", TestUtils.DEVICE_ID, false);
    }

    /**
     * "user" with null SDKCore instance
     * Setting SDKCore.instance to null and calling "user()" function
     * returned value should be null
     */
    @Test
    public void user_instanceNull() {
        init(TestUtils.getConfigSessions());
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
        init(TestUtils.getConfigSessions());
        Assert.assertNotNull(Countly.session().user());
    }

    /**
     * Add param with null key and value
     * "addParam(String, Object)" function should add param
     * added value should be null
     */
    @Test
    public void addParam_nullKeyValue() {
        init(TestUtils.getConfigSessions());
        SessionImpl session = (SessionImpl) Countly.session();
        session.addParam(null, null);
        Assert.assertTrue(session.params.get("null") == null);
    }

    /**
     * Add location with no consent to location
     * "addLocation(double, double)" function should not add location
     * added value should be null
     */
    @Test
    public void addLocation_locationNotEnabled() {
        init(TestUtils.getConfigSessions());
        SessionImpl session = (SessionImpl) Countly.session();
        session.addLocation(1.0, 2.0);
        Assert.assertTrue(session.params.get("location") == null);
    }

    /**
     * Add location with consent to location
     * "addLocation(double, double)" function should add location
     * added value should be same
     */
    @Test
    public void addLocation() {
        init(TestUtils.getConfigSessions(Config.Feature.Location));
        SessionImpl session = (SessionImpl) Countly.session();
        session.addLocation(1.0, 2.0);
        Assert.assertTrue(session.params.get("location").equals("1.0,2.0"));
    }

    /**
     * Add location with backend mode enabled
     * "addLocation(double, double)" function should not add location
     * added value should be null
     */
    @Test
    public void addLocation_backendModeEnabled() {
        init(TestUtils.getConfigSessions().enableBackendMode());
        SessionImpl session = (SessionImpl) Countly.session();
        session.addLocation(1.0, 2.0);
        Assert.assertTrue(session.params.get("location") == null);
    }

    /**
     * Add crash report with no consent to crash reporting
     * "addCrashReport(Throwable, boolean)" function should not add crash report
     * SDKCore.instance().onCrash() should not be called
     */
    @Test
    public void addCrashReport_crashReportingNotEnabled() {
        init(TestUtils.getConfigSessions());
        SessionImpl session = (SessionImpl) Countly.session();
        SDKCore.instance = spy(SDKCore.instance);
        session.addCrashReport(new Exception(), true);

        verify(SDKCore.instance, never()).onCrash(any(), any(), anyBoolean(), any(), any(), any());
    }

    /**
     * Add crash report with consent to crash reporting
     * "addCrashReport(Throwable, boolean)" function should add crash report
     * SDKCore.instance().onCrash() should be called once
     */
    @Test
    public void addCrashReport() {
        init(TestUtils.getConfigSessions(Config.Feature.CrashReporting));
        SessionImpl session = (SessionImpl) Countly.session();
        SDKCore.instance = spy(SDKCore.instance);
        session.addCrashReport(new Exception(), false);

        verify(SDKCore.instance, times(1)).onCrash(any(), any(), anyBoolean(), any(), any(), any());
    }

    /**
     * Add crash report with backend mode enabled
     * "addCrashReport(Throwable, boolean)" function should not add crash report
     * SDKCore.instance().onCrash() should not be called and desired warning should be logged
     */
    @Test
    public void addCrashReport_backendModeEnabled() {
        init(TestUtils.getConfigSessions().enableBackendMode());
        SessionImpl session = (SessionImpl) Countly.session();
        SDKCore.instance = spy(SDKCore.instance);
        session.L = spy(session.L);
        session.addCrashReport(new Exception(), false);

        verify(SDKCore.instance, never()).onCrash(any(), any(), anyBoolean(), any(), any(), any());
        verify(session.L, times(1)).w("[SessionImpl] addCrashReport: Skipping crash, backend mode is enabled!");
    }

    /**
     * "hashCode" function of SessionImpl
     * should return the same value as the ID
     */
    @Test
    public void hashCode_id() {
        init(TestUtils.getConfigSessions());
        assertEquals(new Long(12345L).hashCode(), createSessionImpl(12345L).hashCode());
    }

    /**
     * "equals" function of SessionImpl
     * should return true if the IDs are the same
     */
    @Test
    public void equals() {
        init(TestUtils.getConfigSessions());
        SessionImpl session = createSessionImpl(12345L);
        session.begin().update().end();
        session.addParam("test", "value");
        SessionImpl session2 = createSessionImpl(12345L);
        session2.began = session.began;
        session2.updated = session.updated;
        session2.ended = session.ended;
        session2.addParam("test", "value");
        Assert.assertTrue(session.equals(session2));
    }

    /**
     * "equals" function of SessionImpl
     * should return false if the object is from different class
     */
    @Test
    public void equals_notInstanceOf() {
        init(TestUtils.getConfigSessions());
        Assert.assertFalse(createSessionImpl(12345L).equals(new Object()));
    }

    /**
     * "equals" function of SessionImpl
     * should return false if the IDs are different
     */
    @Test
    public void equals_differentId() {
        validateNotEquals(1, ((session, session2) -> ts -> {
        }));
    }

    /**
     * "equals" function of SessionImpl IDs are same
     * should return false if the began values are different
     */
    @Test
    public void equals_differentBegan() {
        validateNotEquals(0, (session1, session2) -> ts -> {
            session1.began = ts;
            session2.began = ts + 1;
        });
    }

    /**
     * "equals" function of SessionImpl IDs are same
     * should return false if the updated values are different
     */
    @Test
    public void equals_differentUpdated() {
        validateNotEquals(0, (session1, session2) -> ts -> {
            session1.updated = ts;
            session2.updated = ts + 1;
        });
    }

    /**
     * "equals" function of SessionImpl IDs are same
     * should return false if the ended values are different
     */
    @Test
    public void equals_differentEnded() {
        validateNotEquals(0, (session1, session2) -> ts -> {
            session1.ended = ts;
            session2.ended = ts + 1;
        });
    }

    /**
     * "equals" function of SessionImpl IDs are same
     * should return false if the params values are different
     */
    @Test
    public void equals_differentParams() {
        validateNotEquals(0, (session1, session2) -> ts -> session1.addParam("key", "value"));
    }

    /**
     * Create a view with no consent to views
     * "view(String)" function should not create a view
     * returned value should be null and desired log should be logged
     */
    @Test
    public void view_viewsNotEnabled() {
        init(TestUtils.getConfigSessions());
        SessionImpl session = (SessionImpl) Countly.session();
        session.L = spy(session.L);
        View view = session.view("view");
        verify(session.L, times(1)).i("[SessionImpl] view: Skipping view - feature is not enabled");
        Assert.assertNull(view);
    }

    /**
     * Create a view
     * "view(String)" function should create a view and save it to EQ
     * event queue should contain it
     */
    @Test
    public void view() {
        init(TestUtils.getConfigSessions(Config.Feature.Views, Config.Feature.Events).setEventQueueSizeToSend(4));
        Session session = Countly.session();
        TestUtils.validateEventQueueSize(0);
        validateViewInEQ((ViewImpl) session.view("view"), 0, 1);
    }

    /**
     * Create a view and stop it with <code>session::view</code> call
     * "view(String)" function should create a view and save it to EQ
     * event queue should contain 3 events - start, stop, and next view
     */
    @Test
    public void view_stopStartedAndNext() {
        init(TestUtils.getConfigSessions(Config.Feature.Views, Config.Feature.Events).setEventQueueSizeToSend(4));
        Session session = Countly.session();
        TestUtils.validateEventQueueSize(0);
        session.view("start");
        TestUtils.validateEventQueueSize(1);
        validateViewInEQ((ViewImpl) session.view("next"), 2, 3);
    }

    private void validateViewInEQ(ViewImpl view, int eqIdx, int eqSize) {
        List<EventImpl> eventList = TestUtils.getCurrentEventQueue();
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
        init(TestUtils.getConfigSessions());
        long ts = TimeUtils.uniqueTimestampMs();
        SessionImpl session = createSessionImpl(12345L);
        SessionImpl session2 = createSessionImpl(12345L + idOffset);
        setter.apply(session, session).accept(ts);
        Assert.assertFalse(session.equals(session2));
    }

    private void validateDeviceIdMerge(String deviceId, String expected, boolean merge) {
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
        return new SessionImpl(TestUtils.getCtxCore(), id);
    }

    SessionImpl notStarted() {
        return notStarted(TestUtils.getConfigSessions());
    }

    SessionImpl notStarted(Config config) {
        init(config);
        return (SessionImpl) Countly.session();
    }

    SessionImpl beganSession() {
        return validateBeganSession((SessionImpl) notStarted().begin());
    }

    SessionImpl updatedSession() {
        return validateUpdatedSession((SessionImpl) beganSession().update());
    }

    SessionImpl endedSession() {
        SessionImpl session = updatedSession();
        session.end();
        return validateEndedSession(session);
    }
}
