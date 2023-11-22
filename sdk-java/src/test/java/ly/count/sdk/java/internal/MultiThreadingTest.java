package ly.count.sdk.java.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class MultiThreadingTest {
    @After
    public void stop() {
        Countly.instance().halt();
    }

    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
    }

    @Test
    public void multiThread() throws BrokenBarrierException, InterruptedException {
        CountlyTimer.TIMER_DELAY_MS = 1;
        Countly.instance().init(getAllConfig());
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

        for (Thread t : runs) {
            t.join();
        }

        int rqSize = TestUtils.getCurrentRQ().length;
        List<EventImpl> events = new ArrayList<>();

        for (int rqIdx = 0; rqIdx < rqSize; rqIdx++) {
            events.addAll(TestUtils.readEventsFromRequest(rqIdx, TestUtils.DEVICE_ID));
        }

        events.addAll(TestUtils.getCurrentEQ());
        System.out.println(events.stream().filter(e -> e.key.equals("[CLY]_survey")).count());
        System.out.println(events.stream().filter(e -> e.key.equals("[CLY]_view")).count());
        System.out.println(events.stream().filter(e -> !e.key.equals("[CLY]_view") && !e.key.equals("[CLY]_survey")).count());
        System.out.println(Arrays.stream(TestUtils.getCurrentRQ()).filter(r -> r.containsKey("crash")).count());
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
            }));
        }
    }

    private void submitCrashes(int crashThreads, List<Thread> runs, CyclicBarrier gate) {
        for (int a = 0; a < crashThreads; a++) {
            int finalA = a;
            runs.add(new Thread(() -> {
                gateAwait(gate);
                Countly.instance().addCrashReport(new Exception("testThreadCrash_" + finalA), true);
            }));
        }
    }

    private void submitViews(int viewThreads, List<Thread> runs, CyclicBarrier gate) {
        for (int a = 0; a < viewThreads; a++) {
            int finalA = a;
            runs.add(new Thread(() -> {
                gateAwait(gate);
                Countly.instance().view("testThreadView_" + finalA).start(false);
            }));
        }
    }

    private void submitEvents(int eventThreads, List<Thread> runs, CyclicBarrier gate) {
        for (int a = 0; a < eventThreads; a++) {
            int finalA = a;
            runs.add(new Thread(() -> {
                gateAwait(gate);
                Countly.instance().events().recordEvent("testThreadEvent_" + finalA, finalA);
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
