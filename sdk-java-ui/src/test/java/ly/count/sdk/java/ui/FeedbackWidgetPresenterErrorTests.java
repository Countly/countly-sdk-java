package ly.count.sdk.java.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import ly.count.sdk.java.internal.ContentPlacement;
import ly.count.sdk.java.internal.CountlyFeedbackWidget;
import ly.count.sdk.java.internal.FeedbackWidgetType;
import ly.count.sdk.java.internal.ModuleFeedback;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Error and edge paths of {@link FeedbackWidgetPresenter} that {@link FeedbackWidgetPresenterTests}
 * does not reach: external links, a plain in-page "link" signal outside of a resize, and a teardown
 * whose host or callback misbehaves. A NEW file, kept separate so the existing tests stay untouched.
 */
@RunWith(JUnit4.class)
public class FeedbackWidgetPresenterErrorTests {

    private static final String WIDGET_URL = "https://test.server.com/feedback/nps?widget_id=w1";

    /**
     * An external link signal (the whole URL is the destination) is handed to the system browser and
     * never reaches the resize/close handling in {@code handle}; a plain "link" carried on an
     * in-page action-event URL, with no resize and no close alongside it, is opened the same way
     * from inside {@code handle} instead; and a bridged postMessage payload that is not a widget
     * command at all is dropped before either channel is reached.
     */
    @Test
    public void navigation_opensExternalAndInPageLinksWithoutClosingOrPlacing() {
        FakeHost host = new FakeHost();
        ModuleFeedback.Feedback feedback = mock(ModuleFeedback.Feedback.class);
        when(feedback.constructFeedbackWidgetUrl(any(), anyInt(), anyInt())).thenReturn(WIDGET_URL);
        CountlyFeedbackWidget widget = new CountlyFeedbackWidget();
        widget.widgetId = "w1";
        widget.type = FeedbackWidgetType.nps;

        FeedbackWidgetPresenter presenter = new FeedbackWidgetPresenter(host, feedback, null);
        presenter.start(widget);

        host.listener.onNavigationStarting("https://example.com/offer?cly_x_int=1");
        Assert.assertTrue("an external link must not be read as a resize/close command", host.placements.isEmpty());
        Assert.assertEquals(0, host.closeCount);

        host.listener.onNavigationStarting("https://countly_action_event/?cly_x_action_event=1"
            + "&link=https%3A%2F%2Fexample.com%2Fpage");
        Assert.assertTrue("a link with no resize payload places nothing", host.placements.isEmpty());
        Assert.assertEquals("a link with no close flag must not close the widget", 0, host.closeCount);

        // Not a {cly_widget_command} payload at all: WidgetMessageParser.parse returns null and
        // onWidgetMessage must drop it before it ever reaches handle().
        host.listener.onWidgetMessage("{\"type\":\"something_else\"}");
        Assert.assertTrue(host.placements.isEmpty());
        Assert.assertEquals(0, host.closeCount);
    }

    /**
     * Teardown must isolate its two side effects from each other: a host whose {@code closeHost}
     * throws still lets the {@code onClosed} callback run, and an {@code onClosed} that itself
     * throws must not escape {@code finish()} (and so {@code start()}) either.
     */
    @Test
    public void finish_isolatesAThrowingHostFromAThrowingCallback() {
        ModuleFeedback.Feedback feedback = mock(ModuleFeedback.Feedback.class);
        when(feedback.constructFeedbackWidgetUrl(any(), anyInt(), anyInt())).thenReturn(WIDGET_URL);
        CountlyFeedbackWidget widget = new CountlyFeedbackWidget();
        widget.widgetId = "w1";
        widget.type = FeedbackWidgetType.nps;

        FakeHost throwingHost = new FakeHost();
        throwingHost.throwOnClose = true;
        AtomicInteger closedCallbacks = new AtomicInteger(0);
        new FeedbackWidgetPresenter(throwingHost, feedback, closedCallbacks::incrementAndGet).start(widget);

        throwingHost.listener.onLoadFailed();
        // The host blew up while closing itself, but the caller must still be told it is gone.
        Assert.assertEquals(1, throwingHost.closeCount);
        Assert.assertEquals(1, closedCallbacks.get());

        FakeHost plainHost = new FakeHost();
        Runnable throwingCallback = () -> {
            throw new RuntimeException("caller callback misbehaved");
        };
        new FeedbackWidgetPresenter(plainHost, feedback, throwingCallback).start(widget);
        // Must not throw out of start()/onLoadFailed(): the callback's own exception is swallowed.
        plainHost.listener.onLoadFailed();
        Assert.assertEquals(1, plainHost.closeCount);
    }

    private static class FakeHost implements WidgetWebHost {

        Listener listener;
        WidgetSurface surface = new WidgetSurface(0, 0, 1600, 900);
        final List<ContentPlacement> placements = new ArrayList<>();
        final List<ContentPlacement> followed = new ArrayList<>();
        boolean throwOnClose = false;
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
        public void setSurface(WidgetSurface updated) {
            surface = updated;
        }

        @Override
        public void navigate(String url) {
        }

        @Override
        public void reportSurfaceSize(int width, int height) {
        }

        @Override
        public void placeAndShow(ContentPlacement rect) {
            placements.add(rect);
        }

        @Override
        public void closeHost() {
            closeCount++;
            if (throwOnClose) {
                throw new RuntimeException("host failed to close");
            }
        }

        @Override
        public void followGeometry(ContentPlacement rect) {
            followed.add(rect);
        }
    }
}
