package ly.count.sdk.java.internal;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
        Countly.instance().init(TestUtils.getConfigEvents(2).setUpdateSessionTimerDelay(3000));
        Event tEvent = Countly.instance().timedEvent("key");
        tEvent.setCount(5).setSum(133).setDuration(456);

        Map<String, String> segm = new ConcurrentHashMap<>();
        segm.put("1", "a");
        segm.put("5", "b");

        Map<String, Object> targetSegm = new ConcurrentHashMap<>();
        targetSegm.put("1", "a");
        targetSegm.put("5", "b");

        tEvent.setSegmentation(segm);

        TestUtils.validateEQSize(0);

        Thread.sleep(1000);

        double targetDuration;
        if (regularRecord) {
            targetDuration = 456;
            tEvent.record();
        } else {
            targetDuration = 1;
            tEvent.endAndRecord();
        }

        TestUtils.validateEventInEQ("key", targetSegm, 5, 133.0, targetDuration, 0, 1, "_CLY_", null, "", null);
    }
}
