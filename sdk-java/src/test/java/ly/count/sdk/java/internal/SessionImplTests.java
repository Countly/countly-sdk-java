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
import static org.mockito.Mockito.mock;

@RunWith(JUnit4.class)
public class SessionImplTests {

    CtxCore ctx = mock(CtxCore.class);

    private void init(Config cc) {
        Countly.instance().init(cc);
    }

    @After
    public void stop() {
        Countly.instance().halt();
    }

    @Test
    public void constructor() {
        // Arrange
        Long id = 12345L;

        // Act
        SessionImpl session = new SessionImpl(ctx, id);

        // Assert
        assertNotNull(session);
        assertEquals(ctx.getLogger(), session.L);
        assertEquals(id, session.getId());
    }

    @Test
    public void constructor_nullId() {
        // Act
        SessionImpl session = new SessionImpl(ctx, null);

        // Assert
        assertNotNull(session);
        assertEquals(ctx.getLogger(), session.L);
        assertNotNull(session.getId()); // The ID should be generated and not null
    }

    @Test
    public void begin_notNullBegan() {
        init(TestUtils.getConfigSessions());

        SessionImpl session = (SessionImpl) Countly.session();
        session.began = 0L;

        Assert.assertNull(session.begin(0L));
        validateBeganSession(session);
    }

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

    @Test
    public void begin_nullInstance() {
        init(TestUtils.getConfigSessions());
        SDKCore.instance = null;

        SessionImpl session = (SessionImpl) Countly.session();
        Assert.assertNull(session.begin(0L));
        validateNotStarted(session);
    }

    @Test
    public void begin() throws ExecutionException, InterruptedException {
        init(TestUtils.getConfigSessions());

        SessionImpl session = (SessionImpl) Countly.session();

        Assert.assertTrue(session.begin(0L).get());
        Assert.assertNotNull(session.began);
        validateBeganSession(session);
    }

    @Test
    public void begin_backendModeEnabled() {
        init(TestUtils.getConfigSessions().enableBackendMode());

        SessionImpl session = (SessionImpl) Countly.session();
        session = (SessionImpl) session.begin();

        Assert.assertNull(session.began);
        validateNotStarted(session);
    }

    @Test
    public void update_nullBegan() {
        init(TestUtils.getConfigSessions());

        SessionImpl session = (SessionImpl) Countly.session();

        Assert.assertNull(session.update(0L));
        validateNotStarted(session);
    }

    @Test
    public void update_notNullEnded() {
        init(TestUtils.getConfigSessions());

        SessionImpl session = (SessionImpl) Countly.session().begin();
        validateBeganSession(session);

        session.ended = 0L;
        Assert.assertNull(session.update(0L));
    }

    @Test
    public void update_nullInstance() {
        init(TestUtils.getConfigSessions());
        SDKCore.instance = null;

        SessionImpl session = (SessionImpl) Countly.session();
        Assert.assertNull(session.update(0L));
        validateNotStarted(session);
    }

    @Test
    public void update() throws ExecutionException, InterruptedException {
        init(TestUtils.getConfigSessions());

        SessionImpl session = (SessionImpl) Countly.session();

        Assert.assertTrue(session.begin(0L).get());
        validateBeganSession(session);
        Assert.assertTrue(session.update(0L).get());
        validateUpdatedSession(session);
    }

    @Test
    public void update_backendModeEnabled() {
        init(TestUtils.getConfigSessions().enableBackendMode());

        SessionImpl session = (SessionImpl) Countly.session();
        session = (SessionImpl) session.begin().update();

        validateNotStarted(session);
    }

    @Test
    public void end_nullBegan() {
        init(TestUtils.getConfigSessions());

        SessionImpl session = (SessionImpl) Countly.session();

        Assert.assertNull(session.end(0L, null, null));
        validateNotStarted(session);
    }

    @Test
    public void end_notNullEnded() {
        init(TestUtils.getConfigSessions());

        SessionImpl session = (SessionImpl) Countly.session();
        session.ended = 0L;

        Assert.assertNull(session.end(0L, null, null));
    }

    @Test
    public void end_nullInstance() {
        init(TestUtils.getConfigSessions());
        SDKCore.instance = null;

        SessionImpl session = (SessionImpl) Countly.session().begin();
        validateNotStarted(session);
        Assert.assertNull(session.end(0L, null, null));
        validateNotStarted(session);
    }

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

    @Test
    public void end_backendModeEnabled() {
        init(TestUtils.getConfigSessions().enableBackendMode());

        SessionImpl session = (SessionImpl) Countly.session();
        session.begin().end();

        validateNotStarted(session);
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
