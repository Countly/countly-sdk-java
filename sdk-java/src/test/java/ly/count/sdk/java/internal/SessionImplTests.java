package ly.count.sdk.java.internal;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(JUnit4.class)
public class SessionImplTests {

    CtxCore ctx;

    private void init(Config cc) {
        Countly.instance().init(cc);
        ctx = TestUtils.getCtxCore();
    }

    @After
    public void stop() {
        Countly.instance().halt();
    }

    /**
     * Constructor test
     * Constructor should create new instance of SessionImpl
     */
    @Test
    public void constructor() {
        init(TestUtils.getConfigSessions());
        // Arrange
        Long id = 12345L;

        // Act
        SessionImpl session = new SessionImpl(ctx, id);

        // Assert
        assertNotNull(session);
        assertEquals(id, session.getId());
    }

    /**
     * Constructor test
     * Constructor should create new instance of SessionImpl with generated ID
     */
    @Test
    public void constructor_nullId() {
        init(TestUtils.getConfigSessions());
        // Act
        SessionImpl session = new SessionImpl(ctx, null);

        // Assert
        assertNotNull(session);
        assertTrue(session.getId() > 0);// The ID should be generated and not null
    }

    /**
     * Begin a session already began
     * "begin(long)" function should not begin the session
     * returned value should be null
     */
    @Test
    public void begin_notNullBegan() {
        init(TestUtils.getConfigSessions());

        SessionImpl session = (SessionImpl) Countly.session();
        session.begin();
        validateBeganSession(session);

        Assert.assertNull(session.begin(0L));
        validateBeganSession(session);
    }

    /**
     * Begin a session already ended
     * "begin(long)" function should not begin the session
     * returned value should be null
     */
    @Test
    public void begin_notNullEnded() {
        init(TestUtils.getConfigSessions());

        SessionImpl session = (SessionImpl) Countly.session();
        session.begin();
        validateBeganSession(session);
        session.end();
        validateEndedSession(session);
        Assert.assertNull(session.begin(0L));
    }

    /**
     * Begin a session with null SDKCore instance
     * "begin(long)" function should not begin the session
     * returned value should be null
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
     * Begin a session
     * "begin(long)" function should begin the session
     * returned value should be true
     *
     * @throws ExecutionException if the computation threw an exception
     * @throws InterruptedException if thread is interrupted
     */
    @Test
    public void begin() throws ExecutionException, InterruptedException {
        init(TestUtils.getConfigSessions());

        SessionImpl session = (SessionImpl) Countly.session();

        Assert.assertTrue(session.begin(0L).get());
        Assert.assertNotNull(session.began);
        validateBeganSession(session);
    }

    /**
     * Begin a session with backendMode enabled
     * "begin()" function should not begin the session
     * returned value should be null
     */
    @Test
    public void begin_backendModeEnabled() {
        init(TestUtils.getConfigSessions().enableBackendMode());

        SessionImpl session = (SessionImpl) Countly.session();
        session = (SessionImpl) session.begin();

        Assert.assertNull(session.began);
        validateNotStarted(session);
    }

    /**
     * Update a session not began
     * "update(long)" function should not update the session
     * returned value should be null
     */
    @Test
    public void update_nullBegan() {
        init(TestUtils.getConfigSessions());

        SessionImpl session = (SessionImpl) Countly.session();

        Assert.assertNull(session.update(0L));
        validateNotStarted(session);
    }

    /**
     * Update a session already ended
     * "update(long)" function should not update the session
     * returned value should be null
     */
    @Test
    public void update_notNullEnded() {
        init(TestUtils.getConfigSessions());

        SessionImpl session = (SessionImpl) Countly.session().begin();
        validateBeganSession(session);
        session.end();
        validateEndedSession(session);

        Assert.assertNull(session.update(0L));
    }

    /**
     * Update a session with null SDKCore instance
     * "update(long)" function should not update the session
     * returned value should be null
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
     * Update a session
     * "update(long)" function should update the session
     * returned value should be true
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
     * Update a session with backendMode enabled
     * "update()" function should not update the session
     * returned value should be null
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
     * Change device id with merge null id
     * "changeDeviceIdWithoutMerge(String)" function should not change the device id.
     * should be same as the test device id
     */
    @Test
    public void changeDeviceIdWithoutMerge_nullId() {
        init(TestUtils.getConfigSessions());
        validateDeviceIdMerge(null, TestUtils.DEVICE_ID, false);
    }

    /**
     * Change device id with merge empty id
     * "changeDeviceIdWithoutMerge(String)" function should not change the device id.
     * should be same as the test device id
     */
    @Test
    public void changeDeviceIdWithoutMerge_emptyId() {
        init(TestUtils.getConfigSessions());
        validateDeviceIdMerge("", TestUtils.DEVICE_ID, false);
    }

    /**
     * Change device id with merge with backend mode enabled.
     * "changeDeviceIdWithoutMerge(String)" function should not change the device id.
     * should be same as the test device id
     */
    @Test
    public void changeDeviceIdWithoutMerge_backendModeEnabled() {
        init(TestUtils.getConfigSessions().enableBackendMode());
        validateDeviceIdMerge("newDeviceId", TestUtils.DEVICE_ID, false);
    }

    private void validateDeviceIdMerge(String deviceId, String expected, boolean merge) {
        if (merge) {
            Countly.session().changeDeviceIdWithMerge(deviceId);
        } else {
            Countly.session().changeDeviceIdWithoutMerge(deviceId);
        }
        Assert.assertEquals(expected, ctx.getConfig().getDeviceId().id);
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
}
