package ly.count.sdk.java.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.mockito.Mockito;

//@RunWith(JUnit4.class)
public class MultiThreadingTest {
    //todo try out micro times with ids
    @After
    public void stop() {
        Countly.instance().halt();
    }

    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
    }

    AtomicInteger feedbackWidgetCounter = new AtomicInteger(0);
    AtomicInteger crashCounter = new AtomicInteger(0);
    AtomicInteger viewCounter = new AtomicInteger(0);
    AtomicInteger eventCounter = new AtomicInteger(0);
    AtomicInteger locationCounter = new AtomicInteger(0);

    /**
     * Test that all modules are thread safe, and called at the desired count
     *
     * @throws BrokenBarrierException BrokenBarrierException
     * @throws InterruptedException InterruptedException
     */
    //@Test
    public void multiThread() throws BrokenBarrierException, InterruptedException {
        CountlyTimer.TIMER_DELAY_MS = 1;
        Countly.instance().init(getAllConfig());

        int rqSize = TestUtils.getCurrentRQ().length;
        List<EventImpl> events = new ArrayList<>();
        for (int rqIdx = 0; rqIdx < rqSize; rqIdx++) {
            events.addAll(TestUtils.readEventsFromRequest(rqIdx, TestUtils.DEVICE_ID));
        }
        events.addAll(TestUtils.getCurrentEQ());

        Assert.assertEquals(0, feedbackWidgetCounter.get());
        Assert.assertEquals(0, crashCounter.get());
        Assert.assertEquals(0, viewCounter.get());
        Assert.assertEquals(0, eventCounter.get());
        Assert.assertEquals(0, locationCounter.get());
        //print(events);
        int eventThreads = 50;
        int viewThreads = 50;
        int locationThreads = 50;
        int crashThreads = 50;
        int feedbackThreads = 50;
        final CyclicBarrier gate = new CyclicBarrier(eventThreads + viewThreads + crashThreads + locationThreads + feedbackThreads + 1);
        List<Thread> runs = new ArrayList<>();

        submitEvents(eventThreads, runs, gate);
        submitViews(viewThreads, runs, gate);
        submitCrashes(crashThreads, runs, gate);
        submitLocations(locationThreads, runs, gate);
        submitFeedbackWidget(feedbackThreads, runs, gate);

        for (Thread t : runs) {
            t.start();
        }

        gate.await();
        Storage.await(Mockito.mock(Log.class));

        for (Thread t : runs) {
            t.join();
        }

        rqSize = TestUtils.getCurrentRQ().length;
        events = new ArrayList<>();

        for (int rqIdx = 0; rqIdx < rqSize; rqIdx++) {
            events.addAll(TestUtils.readEventsFromRequest(rqIdx, TestUtils.DEVICE_ID));
        }

        events.addAll(TestUtils.getCurrentEQ());
        //print(events);

        Arrays.stream(TestUtils.getCurrentRQ()).filter(r -> r.containsKey("crash") && !r.get("crash").contains("java.lang.Exception")).forEach(r -> {
            Assert.assertNull(r.get("crash")); // validate that there is no unhandled sdk crash occurs
        });
        Assert.assertEquals(feedbackThreads, feedbackWidgetCounter.get());
        Assert.assertEquals(crashThreads, crashCounter.get());
        Assert.assertEquals(viewThreads, viewCounter.get());
        Assert.assertEquals(eventThreads, eventCounter.get());
        Assert.assertEquals(locationThreads, locationCounter.get());
    }

    private void print(List<EventImpl> events) {
        System.out.println(events.stream().filter(e -> e.key.equals("[CLY]_survey")).count());
        System.out.println(events.stream().filter(e -> e.key.equals("[CLY]_view")).count());
        System.out.println(events.stream().filter(e -> !e.key.equals("[CLY]_view") && !e.key.equals("[CLY]_survey")).count());
        System.out.println((int) Arrays.stream(TestUtils.getCurrentRQ()).filter(r -> r.containsKey("crash")).count());
        Arrays.stream(TestUtils.getCurrentRQ()).filter(r -> r.containsKey("crash") && !r.get("crash").contains("java.lang.Exception")).forEach(r -> {
            System.out.println(r.get("crash"));
        });
        System.out.println("-------------------- CALL COUNTS -----------------");
        System.out.println("feedbackWidgetCounter: " + feedbackWidgetCounter.get());
        System.out.println("crashCounter: " + crashCounter.get());
        System.out.println("viewCounter: " + viewCounter.get());
        System.out.println("eventCounter: " + eventCounter.get());
        System.out.println("locationCounter: " + locationCounter.get());
    }

    private void submitFeedbackWidget(int feedbackThreads, List<Thread> runs, CyclicBarrier gate) {
        for (int a = 0; a < feedbackThreads; a++) {
            int finalA = a;
            runs.add(new Thread(() -> {
                gateAwait(gate);
                CountlyFeedbackWidget feedbackWidget = new CountlyFeedbackWidget();
                feedbackWidget.widgetId = "testThreadFeedbackWidget_" + finalA;
                feedbackWidget.type = FeedbackWidgetType.survey;
                feedbackWidget.name = "testThreadFeedbackWidget_" + finalA;
                feedbackWidget.tags = new String[] { "testThreadFeedbackWidget_" + finalA };
                Countly.instance().feedback().reportFeedbackWidgetManually(feedbackWidget, null, null);
                feedbackWidgetCounter.incrementAndGet();
            }));
        }
    }

    private void gateAwait(CyclicBarrier gate) {
        try {
            gate.await();
        } catch (BrokenBarrierException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void submitLocations(int locationThreads, List<Thread> runs, CyclicBarrier gate) {
        for (int a = 0; a < locationThreads; a++) {
            int finalA = a;
            runs.add(new Thread(() -> {
                gateAwait(gate);
                Countly.instance().addLocation(finalA, finalA + 1);
                locationCounter.incrementAndGet();
            }));
        }
    }

    private void submitCrashes(int crashThreads, List<Thread> runs, CyclicBarrier gate) {
        for (int a = 0; a < crashThreads; a++) {
            int finalA = a;
            runs.add(new Thread(() -> {
                gateAwait(gate);
                Countly.instance().addCrashReport(new Exception("testThreadCrash_" + finalA), true);
                crashCounter.incrementAndGet();
            }));
        }
    }

    private void submitViews(int viewThreads, List<Thread> runs, CyclicBarrier gate) {
        for (int a = 0; a < viewThreads; a++) {
            int finalA = a;
            runs.add(new Thread(() -> {
                gateAwait(gate);
                Countly.instance().view("testThreadView_" + finalA).start(false);
                viewCounter.incrementAndGet();
            }));
        }
    }

    private void submitEvents(int eventThreads, List<Thread> runs, CyclicBarrier gate) {
        for (int a = 0; a < eventThreads; a++) {
            int finalA = a;
            runs.add(new Thread(() -> {
                gateAwait(gate);
                Countly.instance().events().recordEvent("testThreadEvent_" + finalA, finalA);
                eventCounter.incrementAndGet();
            }));
        }
    }

    private Config getAllConfig() {
        Config config = TestUtils.getBaseConfig();
        config.enableFeatures(Config.Feature.Events, Config.Feature.Sessions, Config.Feature.Location, Config.Feature.CrashReporting, Config.Feature.Feedback, Config.Feature.UserProfiles, Config.Feature.Views, Config.Feature.RemoteConfig);
        config.enableRemoteConfigValueCaching().setRequiresConsent(false);
        return config;
    }
}
