package ly.count.sdk.java.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import ly.count.sdk.java.internal.ContentPlacement;
import ly.count.sdk.java.internal.CountlyFeedbackWidget;
import ly.count.sdk.java.internal.FeedbackWidgetType;
import ly.count.sdk.java.internal.ModuleFeedback;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The feedback widget presentation flow, driven against a fake browser host so no JavaFX toolkit is
 * needed.
 */
@RunWith(JUnit4.class)
public class FeedbackWidgetPresenterTests {

    private static final String WIDGET_URL = "https://test.server.com/feedback/nps?widget_id=w1";
    private static final String CLOSE_URL = "https://countly_action_event/?cly_widget_command=1&close=1";

    private FakeHost host;
    private ModuleFeedback.Feedback feedback;
    private CountlyFeedbackWidget widget;
    private AtomicInteger closedCallbacks;

    @Before
    public void beforeTest() {
        host = new FakeHost();
        feedback = mock(ModuleFeedback.Feedback.class);
        closedCallbacks = new AtomicInteger(0);

        widget = new CountlyFeedbackWidget();
        widget.widgetId = "w1";
        widget.type = FeedbackWidgetType.nps;

        when(feedback.constructFeedbackWidgetUrl(any())).thenReturn(WIDGET_URL);
    }

    /**
     * The whole happy path of one widget: load, report the viewport once the page is up, place the
     * card where the widget asks for it, then report the dismissal exactly once when it closes.
     */
    @Test
    public void present_loadsPlacesAndReportsTheDismissalOnce() {
        FeedbackWidgetPresenter presenter = newPresenter();
        presenter.start(widget);

        Assert.assertEquals(1, host.navigations.size());
        Assert.assertEquals(WIDGET_URL, host.navigations.get(0));
        Assert.assertTrue(host.reportedSizes.isEmpty());

        host.listener.onPageLoaded();
        Assert.assertEquals(1, host.reportedSizes.size());
        Assert.assertArrayEquals(new int[] { 1600, 900 }, host.reportedSizes.get(0));

        // A second load of the same page must not report the viewport again.
        host.listener.onPageLoaded();
        Assert.assertEquals(1, host.reportedSizes.size());

        host.listener.onWidgetMessage("{\"cly_widget_command\":1,\"action\":\"resize_me\","
            + "\"resize_me\":{\"p\":{\"x\":10,\"y\":10,\"w\":300,\"h\":400},\"l\":{\"x\":1200,\"y\":40,\"w\":360,\"h\":500}}}");

        Assert.assertEquals(1, host.placements.size());
        ContentPlacement placed = host.placements.get(0);
        Assert.assertEquals(1200, placed.x);
        Assert.assertEquals(40, placed.y);
        Assert.assertEquals(360, placed.width);
        Assert.assertEquals(500, placed.height);

        host.listener.onNavigationStarting(CLOSE_URL);

        verify(feedback, times(1)).reportFeedbackWidgetManually(eq(widget), isNull(), isNull());
        Assert.assertEquals(1, host.closeCount);
        Assert.assertEquals(1, closedCallbacks.get());

        // A widget that signals close more than once must not report or close twice.
        host.listener.onNavigationStarting(CLOSE_URL);
        host.listener.onLoadFailed();
        verify(feedback, times(1)).reportFeedbackWidgetManually(eq(widget), isNull(), isNull());
        Assert.assertEquals(1, host.closeCount);
        Assert.assertEquals(1, closedCallbacks.get());
    }

    /**
     * Nothing to present: no widget, no feedback interface, or no URL. Each one has to tear down
     * cleanly instead of leaving an invisible card and a caller waiting for a callback.
     */
    @Test
    public void present_withNothingToShow_tearsDownCleanly() {
        newPresenter().start(null);
        Assert.assertTrue(host.navigations.isEmpty());
        Assert.assertEquals(1, host.closeCount);
        Assert.assertEquals(1, closedCallbacks.get());

        beforeTest();
        new FeedbackWidgetPresenter(host, null, closedCallbacks::incrementAndGet).start(widget);
        Assert.assertTrue(host.navigations.isEmpty());
        Assert.assertEquals(1, closedCallbacks.get());
        verify(feedback, never()).reportFeedbackWidgetManually(any(), any(), any());

        beforeTest();
        when(feedback.constructFeedbackWidgetUrl(any())).thenReturn(null);
        newPresenter().start(widget);
        Assert.assertTrue(host.navigations.isEmpty());
        Assert.assertEquals(1, host.closeCount);
        Assert.assertEquals(1, closedCallbacks.get());

        beforeTest();
        when(feedback.constructFeedbackWidgetUrl(any())).thenReturn("   ");
        newPresenter().start(widget);
        Assert.assertTrue(host.navigations.isEmpty());
        Assert.assertEquals(1, closedCallbacks.get());
    }

    /**
     * A widget page that never loads has to dismiss its own card and report the dismissal, so the
     * caller is not left hanging.
     */
    @Test
    public void loadFailure_dismissesTheCard() {
        FeedbackWidgetPresenter presenter = newPresenter();
        presenter.start(widget);

        host.listener.onLoadFailed();

        Assert.assertEquals(1, host.closeCount);
        Assert.assertEquals(1, closedCallbacks.get());
        verify(feedback, times(1)).reportFeedbackWidgetManually(eq(widget), isNull(), isNull());
    }

    /**
     * Signals arriving as navigations rather than messages, on a portrait surface, plus the plain
     * page navigations that have to be left alone.
     */
    @Test
    public void urlSignals_arePlacedAndFilteredCorrectly() {
        host.surface = new WidgetSurface(100, 50, 600, 1000);
        FeedbackWidgetPresenter presenter = newPresenter();
        presenter.start(widget);

        // The initial widget URL is a real navigation, not a signal.
        host.listener.onNavigationStarting(WIDGET_URL);
        Assert.assertTrue(host.placements.isEmpty());
        Assert.assertEquals(0, host.closeCount);

        host.listener.onNavigationStarting("https://countly_action_event/?cly_x_action_event=1"
            + "&resize_me=%7B%22p%22%3A%7B%22x%22%3A20%2C%22y%22%3A30%2C%22w%22%3A900%2C%22h%22%3A2000%7D%7D&close=0");

        Assert.assertEquals(1, host.placements.size());
        ContentPlacement placed = host.placements.get(0);
        // Clamped to the surface and offset by its origin.
        Assert.assertEquals(100, placed.x);
        Assert.assertEquals(50, placed.y);
        Assert.assertEquals(600, placed.width);
        Assert.assertEquals(1000, placed.height);
        Assert.assertEquals(0, host.closeCount);
    }

    private FeedbackWidgetPresenter newPresenter() {
        return new FeedbackWidgetPresenter(host, feedback, closedCallbacks::incrementAndGet);
    }

    private static class FakeHost implements WidgetWebHost {

        Listener listener;
        WidgetSurface surface = new WidgetSurface(0, 0, 1600, 900);
        final List<String> navigations = new ArrayList<>();
        final List<int[]> reportedSizes = new ArrayList<>();
        final List<ContentPlacement> placements = new ArrayList<>();
        int closeCount = 0;

        @Override
        public void setListener(Listener widgetListener) {
            listener = widgetListener;
        }

        @Override
        public WidgetSurface getSurface() {
            return surface;
        }

        @Override
        public void navigate(String url) {
            navigations.add(url);
        }

        @Override
        public void reportSurfaceSize(int width, int height) {
            reportedSizes.add(new int[] { width, height });
        }

        @Override
        public void placeAndShow(ContentPlacement rect) {
            placements.add(rect);
        }

        @Override
        public void closeHost() {
            closeCount++;
        }
    }
}
