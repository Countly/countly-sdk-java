package ly.count.sdk.java.internal;

import java.util.HashMap;
import java.util.Map;
import ly.count.sdk.java.Countly;
import ly.count.sdk.java.Event;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class TimedEventsTests {
    @After
    public void stop() {
        Countly.instance().halt();
    }

    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
    }

    /**
     * "record" with mocked and not started event,
     * event should be recorded because it is using directly record
     * event queue should not be empty
     */
    @Test
    public void recordEventRegularFlow_record() throws InterruptedException {
        recordEventRegularFlow_base(true);
    }

    /**
     * "endAndRecord" with mocked and started event,
     * event should be recorded because it is using endAndRecord
     * event queue should not be empty
     */
    @Test
    public void recordEventRegularFlow_endAndRecord() throws InterruptedException {
        recordEventRegularFlow_base(false);
    }

    public void recordEventRegularFlow_base(boolean regularRecord) throws InterruptedException {
        Countly.instance().init(TestUtils.getConfigEvents(2).setUpdateSessionTimerDelay(300));
        Event tEvent = Countly.instance().timedEvent("key");
        tEvent.setCount(5).setSum(133).setDuration(456);

        Map<String, String> segm = new HashMap<>();
        segm.put("1", "a");
        segm.put("5", "b");

        Map<String, Object> targetSegm = new HashMap<>();
        targetSegm.put("1", "a");
        targetSegm.put("5", "b");

        tEvent.setSegmentation(segm);

        TestUtils.validateEQSize(0);

        double targetDuration;
        if (regularRecord) {
            targetDuration = 456;
            tEvent.record();
        } else {
            synchronized (tEvent) {
                tEvent.wait(1000);
            }
            targetDuration = 1;
            tEvent.endAndRecord();
        }
        TestUtils.validateEventInEQ("key", targetSegm, 5, 133.0, targetDuration, 0, 1);
    }
}
