package ly.count.sdk.java.internal;

import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.mockito.Mockito.mock;

@RunWith(JUnit4.class)

public class TimedEventsTests {

    private TimedEvents timedEvents;

    private ModuleEvents moduleEvents;

    private final Log L = mock(Log.class);

    private void init(Config cc) {
        Countly.instance().init(cc);
        timedEvents = new TimedEvents(L);
        moduleEvents = SDKCore.instance.module(ModuleEvents.class);
    }

    @After
    public void stop() {
        Countly.instance().halt();
    }

    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
    }

    /**
     * "recordEvent" with mocked and not started event,
     * event should not be recorded because it is not started
     * event queue should be empty
     */
    @Test
    public void recordEvent_notStarted() {
        init(TestUtils.getConfigEvents(2));

        timedEvents.recordEvent(new EventImpl("key", 1, 1.2, 3.4, null, L));
        TestUtils.validateEventQueueSize(0, moduleEvents.eventQueue);
    }

    /**
     * "recordEvent" with mocked event,
     * event should be recorded because it is started
     * event queue should exist it
     */
    @Test
    public void recordEvent() {
        init(TestUtils.getConfigEvents(2));
        EventImpl event = timedEvents.event(TestUtils.getCtxCore(), "key_test");

        timedEvents.recordEvent(event);
        TestUtils.validateEventQueueSize(1, moduleEvents.eventQueue);
    }
}
