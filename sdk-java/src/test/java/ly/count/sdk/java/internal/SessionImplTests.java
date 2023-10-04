package ly.count.sdk.java.internal;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
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
        init(TestUtils.getConfigSessions());

        SessionImpl session = (SessionImpl) Countly.session();
        session.begin();
        validateBeganSession(session);

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
        init(TestUtils.getConfigSessions());

        SessionImpl session = (SessionImpl) Countly.session();
        session.begin();
        validateBeganSession(session);
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
        init(TestUtils.getConfigSessions());
        SDKCore.instance = null;

        SessionImpl session = (SessionImpl) Countly.session();
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
        init(TestUtils.getConfigSessions());

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
        init(TestUtils.getConfigSessions().enableBackendMode());

        SessionImpl session = (SessionImpl) Countly.session();
        session = (SessionImpl) session.begin();

        validateNotStarted(session);
    }

    /**
     * "update(long)" with not began session
     * Try to update a session, validate that it is not started and updated
     * returned result should be null
     */
    @Test
    public void update_notStarted() {
        init(TestUtils.getConfigSessions());

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
        init(TestUtils.getConfigSessions());

        SessionImpl session = (SessionImpl) Countly.session().begin();
        validateBeganSession(session);
        session.end();
        validateEndedSession(session);

        Assert.assertNull(session.update(0L));
    }

    /**
     * "update(long)" with null SDKCore instance
     * Try to update a session, validate that it is not started and updated
     * returned result should be null
     */
    @Test
    public void update_nullInstance() {
        init(TestUtils.getConfigSessions());
        SDKCore.instance = null;

        SessionImpl session = (SessionImpl) Countly.session();
        Assert.assertNull(session.update(0L));
        validateNotStarted(session);
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
        init(TestUtils.getConfigSessions());

        SessionImpl session = (SessionImpl) Countly.session();

        Assert.assertTrue(session.begin(0L).get());
        validateBeganSession(session);
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
        init(TestUtils.getConfigSessions().enableBackendMode());

        SessionImpl session = (SessionImpl) Countly.session();
        session = (SessionImpl) session.begin().update();

        validateNotStarted(session);
    }

    /**
     * End a session not began
     * "end(long, Callback, Object)" function should not end the session
     * returned value should be null
     */
    @Test
    public void end_nullBegan() {
        init(TestUtils.getConfigSessions());

        SessionImpl session = (SessionImpl) Countly.session();

        Assert.assertNull(session.end(0L, null, null));
        validateNotStarted(session);
    }

    /**
     * End a session already ended
     * "end(long, Callback, Object)" function should not end the session
     * returned value should be null
     */
    @Test
    public void end_notNullEnded() {
        init(TestUtils.getConfigSessions());

        SessionImpl session = (SessionImpl) Countly.session();
        session.begin();
        validateBeganSession(session);
        session.end();
        validateEndedSession(session);
        Assert.assertNull(session.end(0L, null, null));
    }

    /**
     * End a session with null SDKCore instance
     * "end(long, Callback, Object)" function should not end the session
     * returned value should be null
     */
    @Test
    public void end_nullInstance() {
        init(TestUtils.getConfigSessions());
        SDKCore.instance = null;

        SessionImpl session = (SessionImpl) Countly.session().begin();
        validateNotStarted(session);
        Assert.assertNull(session.end(0L, null, null));
        validateNotStarted(session);
    }

    /**
     * End a session
     * "end(long, Callback, Object)" function should end the session
     * returned value should be true and callback should be called
     * and session should be ended and not active anymore after callback is called
     * and callback value should be true
     *
     * @throws ExecutionException if the computation threw an exception
     * @throws InterruptedException if thread is interrupted
     */
    @Test
    public void end() throws ExecutionException, InterruptedException {
        init(TestUtils.getConfigSessions());

        SessionImpl session = (SessionImpl) Countly.session();
        AtomicBoolean callbackCalled = new AtomicBoolean(false);

        Assert.assertTrue(session.begin(0L).get());
        Assert.assertTrue(session.update(0L).get());
        Assert.assertTrue(session.end(0L, callbackCalled::set, null).get());
        Thread.sleep(200);
        Assert.assertTrue(callbackCalled.get());
        validateEndedSession(session);
    }

    /**
     * End a session with backendMode enabled
     * "end()" function should not end the session
     * returned value should be null
     */
    @Test
    public void end_backendModeEnabled() {
        init(TestUtils.getConfigSessions().enableBackendMode());

        SessionImpl session = (SessionImpl) Countly.session();
        session.begin().end();

        validateNotStarted(session);
    }

    /**
     * Change device id with merge
     * "changeDeviceIdWithMerge(String)" function should change the device id.
     * should be same as the one passed to the function
     */
    @Test
    public void changeDeviceIdWithMerge() {
        init(TestUtils.getConfigSessions());
        validateDeviceIdMerge("newDeviceId", "newDeviceId", true);
    }

    /**
     * Change device id with merge null id
     * "changeDeviceIdWithMerge(String)" function should not change the device id.
     * should be same as the test device id
     */
    @Test
    public void changeDeviceIdWithMerge_nullId() {
        init(TestUtils.getConfigSessions());
        validateDeviceIdMerge(null, TestUtils.DEVICE_ID, true);
    }

    /**
     * Change device id with merge empty id
     * "changeDeviceIdWithMerge(String)" function should not change the device id.
     * should be same as the test device id
     */
    @Test
    public void changeDeviceIdWithMerge_emptyId() {
        init(TestUtils.getConfigSessions());
        validateDeviceIdMerge("", TestUtils.DEVICE_ID, true);
    }

    /**
     * Change device id with merge with backend mode enabled.
     * "changeDeviceIdWithMerge(String)" function should not change the device id.
     * should be same as the test device id
     */
    @Test
    public void changeDeviceIdWithMerge_backendModeEnabled() {
        init(TestUtils.getConfigSessions().enableBackendMode());
        validateDeviceIdMerge("newDeviceId", TestUtils.DEVICE_ID, true);
    }

    /**
     * Change device id without merge
     * "changeDeviceIdWithoutMerge(String)" function should change the device id.
     * should be same as the one passed to the function
     */
    @Test
    public void changeDeviceIdWithoutMerge() {
        init(TestUtils.getConfigSessions());
        validateDeviceIdMerge("newDeviceId", "newDeviceId", false);
    }

    /**
     * Change device id without merge null id
     * "changeDeviceIdWithoutMerge(String)" function should not change the device id.
     * should be same as the test device id
     */
    @Test
    public void changeDeviceIdWithoutMerge_nullId() {
        init(TestUtils.getConfigSessions());
        validateDeviceIdMerge(null, TestUtils.DEVICE_ID, false);
    }

    /**
     * Change device id without merge empty id
     * "changeDeviceIdWithoutMerge(String)" function should not change the device id.
     * should be same as the test device id
     */
    @Test
    public void changeDeviceIdWithoutMerge_emptyId() {
        init(TestUtils.getConfigSessions());
        validateDeviceIdMerge("", TestUtils.DEVICE_ID, false);
    }

    /**
     * Change device id without merge with backend mode enabled.
     * "changeDeviceIdWithoutMerge(String)" function should not change the device id.
     * should be same as the test device id
     */
    @Test
    public void changeDeviceIdWithoutMerge_backendModeEnabled() {
        init(TestUtils.getConfigSessions().enableBackendMode());
        validateDeviceIdMerge("newDeviceId", TestUtils.DEVICE_ID, false);
    }

    /**
     * Get user with null SDKCore instance
     * "user()" function should return null because SDKCore is null
     * returned value should be null
     */
    @Test
    public void user_instanceNull() {
        init(TestUtils.getConfigSessions());
        SDKCore.instance = null;

        Assert.assertNull(Countly.session().user());
    }

    /**
     * Get user
     * "user()" function should return user
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

    private void validateBeganSession(SessionImpl session) {
        Assert.assertNotNull(session.began);
        Assert.assertNull(session.updated);
        Assert.assertNull(session.ended);
        Assert.assertTrue(session.isActive());
    }

    private void validateUpdatedSession(SessionImpl session) {
        Assert.assertNotNull(session.began);
        Assert.assertNotNull(session.updated);
        Assert.assertNull(session.ended);
        Assert.assertTrue(session.isActive());
    }

    private void validateEndedSession(SessionImpl session) {
        Assert.assertNotNull(session.began);
        Assert.assertNotNull(session.ended);
        Assert.assertFalse(session.isActive());
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
}
