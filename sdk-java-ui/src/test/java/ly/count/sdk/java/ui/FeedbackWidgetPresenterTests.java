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
import static org.mockito.ArgumentMatchers.anyInt;
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

        when(feedback.constructFeedbackWidgetUrl(any(), anyInt(), anyInt())).thenReturn(WIDGET_URL);
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
        // The widget's own rect supplies the size (360x500 for this landscape surface); the anchor
        // comes from the widget type, the way the web SDK's stylesheet applies it. This one is an
        // NPS, so: bottom, centred.
        Assert.assertEquals((1600 - 360) / 2, placed.x);
        Assert.assertEquals(900 - 500, placed.y);
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
        when(feedback.constructFeedbackWidgetUrl(any(), anyInt(), anyInt())).thenReturn(null);
        newPresenter().start(widget);
        Assert.assertTrue(host.navigations.isEmpty());
        Assert.assertEquals(1, host.closeCount);
        Assert.assertEquals(1, closedCallbacks.get());

        beforeTest();
        when(feedback.constructFeedbackWidgetUrl(any(), anyInt(), anyInt())).thenReturn("   ");
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
        // A request bigger than the surface is clamped to it, then anchored bottom centre, origin
        // included.
        Assert.assertEquals(100, placed.x);
        Assert.assertEquals(50, placed.y);
        Assert.assertEquals(600, placed.width);
        Assert.assertEquals(1000, placed.height);
        Assert.assertEquals(0, host.closeCount);
    }

    /**
     * A rating's card is a fixed size, so a measurement of its page is not worth re-placing for:
     * the rectangle would not change, and every answer costs the host another measuring round
     * before the card is allowed to appear.
     */
    @Test
    public void aRating_isNotRefittedFromItsPage() {
        widget.type = FeedbackWidgetType.rating;

        FeedbackWidgetPresenter presenter = newPresenter();
        presenter.start(widget);
        host.listener.onPageLoaded();
        host.listener.onSizeNotReported(50, 669);
        Assert.assertEquals(1, host.placements.size());

        host.listener.onCardMeasured(50, 669);
        Assert.assertEquals("nothing to re-place", 1, host.placements.size());
    }

    /**
     * A widget that never reports a size still gets a card, and it is the card its type calls for
     * rather than the sliver its page painted. This is every rating widget: they report nothing and
     * paint only a sticky tab, which came out as a 50px wide window.
     */
    @Test
    public void aWidgetThatReportsNoSize_isPlacedByItsType() {
        widget.type = FeedbackWidgetType.rating;

        FeedbackWidgetPresenter presenter = newPresenter();
        presenter.start(widget);
        host.listener.onPageLoaded();
        host.listener.onSizeNotReported(50, 669);

        Assert.assertEquals(1, host.placements.size());
        ContentPlacement placed = host.placements.get(0);
        Assert.assertEquals(WidgetLayout.RATING_WIDTH, placed.width);
        Assert.assertEquals(WidgetLayout.RATING_HEIGHT, placed.height);
        Assert.assertEquals((1600 - WidgetLayout.RATING_WIDTH) / 2, placed.x);
    }

    /**
     * With no type to go by, the size the page painted is the only thing left to place, and a page
     * that painted nothing at all still has to end up somewhere sane.
     */
    @Test
    public void aWidgetOfUnknownType_isPlacedByWhatItPainted() {
        widget.type = null;

        FeedbackWidgetPresenter presenter = newPresenter();
        presenter.start(widget);
        host.listener.onSizeNotReported(320, 240);

        ContentPlacement painted = host.placements.get(0);
        Assert.assertEquals(320, painted.width);
        Assert.assertEquals(240, painted.height);

        host.listener.onSizeNotReported(0, 0);

        ContentPlacement fallback = host.placements.get(1);
        Assert.assertEquals(WidgetLayout.DEFAULT_WIDTH, fallback.width);
        Assert.assertEquals(WidgetLayout.SURVEY_DEFAULT_HEIGHT, fallback.height);
    }

    /**
     * The card is fitted to what the page actually drew.
     * <p>
     * A page lays its card out at the top of the viewport, so a window taller than the card leaves
     * transparent space below it, and an NPS anchored to the bottom edge visibly floats above it.
     */
    @Test
    public void theCardIsFittedToWhatThePageDrew() {
        FeedbackWidgetPresenter presenter = newPresenter();
        presenter.start(widget);
        host.listener.onPageLoaded();

        host.listener.onWidgetMessage("{\"cly_widget_command\":1,\"action\":\"resize_me\","
            + "\"resize_me\":{\"l\":{\"x\":800,\"y\":0,\"w\":480,\"h\":563}}}");
        Assert.assertEquals(563, host.placements.get(0).height);

        host.listener.onCardMeasured(480, 414);

        ContentPlacement fitted = host.placements.get(1);
        Assert.assertEquals("the drawn card's height wins", 414, fitted.height);
        Assert.assertEquals(480, fitted.width);
        Assert.assertEquals("still flush with the bottom", 900 - 414, fitted.y);
        Assert.assertEquals((1600 - 480) / 2, fitted.x);
    }

    /**
     * A card follows the window it was placed against, so moving or resizing the application window
     * does not leave the card stranded where the window used to be.
     */
    @Test
    public void aMovedSurface_takesTheCardWithIt() {
        FeedbackWidgetPresenter presenter = newPresenter();

        // Nothing to re-place before the widget has asked for anything.
        presenter.refreshPlacement();
        Assert.assertTrue(host.placements.isEmpty());

        presenter.start(widget);
        host.listener.onPageLoaded();
        host.listener.onWidgetMessage("{\"cly_widget_command\":1,\"action\":\"resize_me\","
            + "\"resize_me\":{\"l\":{\"x\":800,\"y\":0,\"w\":480,\"h\":400}}}");

        host.setSurface(new WidgetSurface(-1000, -200, 1200, 800));
        presenter.refreshPlacement();

        ContentPlacement moved = host.placements.get(host.placements.size() - 1);
        Assert.assertEquals("centred on the new surface", -1000 + (1200 - 480) / 2, moved.x);
        Assert.assertEquals("bottom of the new surface", -200 + 800 - 400, moved.y);
        Assert.assertEquals("the widget's own size is kept", 400, moved.height);

        // A dismissed card has nothing left to follow with.
        host.listener.onNavigationStarting(CLOSE_URL);
        int placementsAtClose = host.placements.size();
        presenter.refreshPlacement();
        Assert.assertEquals(placementsAtClose, host.placements.size());
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
        final List<ContentPlacement> followed = new ArrayList<>();
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
            navigations.add(url);
        }

        @Override
        public void followGeometry(ContentPlacement rect) {
            followed.add(rect);
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

    /**
     * A survey asked for 574 and drew 580 - a border its own arithmetic leaves out. Honouring the
     * request, measuring the card, honouring the next request and so on made the card flicker
     * forever. A request within a border's width of what the page draws now resolves to the drawn
     * size, so nothing moves; a request further away is a real step and is honoured.
     */
    @Test
    public void aRequestWithinABorderOfTheDrawnCard_keepsTheDrawnCard() {
        CountlyFeedbackWidget widget = new CountlyFeedbackWidget();
        widget.widgetId = "survey_1";
        widget.type = FeedbackWidgetType.survey;
        widget.position = "bLeft";
        host.surface = new WidgetSurface(0, 0, 1600, 900);
        FeedbackWidgetPresenter presenter = newPresenter();
        presenter.start(widget);

        // The page drew 580.
        presenter.onCardMeasured(500, 580);
        ContentPlacement drawn = host.placements.get(host.placements.size() - 1);
        Assert.assertEquals(580, drawn.height);

        // It then asks for 574: six pixels off, the same card. Nothing changes.
        int before = host.placements.size();
        presenter.onWidgetMessage("{\"cly_widget_command\":1,\"resize_me\":"
            + "{\"p\":{\"x\":0,\"y\":326,\"w\":500,\"h\":574},\"l\":{\"x\":0,\"y\":326,\"w\":500,\"h\":574}}}");
        ContentPlacement after = host.placements.get(host.placements.size() - 1);
        Assert.assertEquals("the drawn height is kept", 580, after.height);
        Assert.assertEquals("and the anchor with it", drawn.y, after.y);

        // A genuinely new step is honoured as asked.
        presenter.onWidgetMessage("{\"cly_widget_command\":1,\"resize_me\":"
            + "{\"p\":{\"x\":0,\"y\":100,\"w\":500,\"h\":800},\"l\":{\"x\":0,\"y\":100,\"w\":500,\"h\":800}}}");
        Assert.assertEquals(800, host.placements.get(host.placements.size() - 1).height);
        Assert.assertTrue(host.placements.size() > before);
    }

}
