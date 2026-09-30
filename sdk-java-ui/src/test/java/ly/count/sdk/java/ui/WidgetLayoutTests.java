package ly.count.sdk.java.ui;

import ly.count.sdk.java.internal.ContentPlacement;
import ly.count.sdk.java.internal.FeedbackWidgetType;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Where each widget type is placed. The numbers the tests feed in are the ones the real widget
 * pages on a Countly server post, captured from them: an NPS asks for {@code 480x563} at
 * {@code x = parentWidth / 2}, a survey for {@code 500x620} at {@code x = 0}, and a rating asks for
 * nothing at all.
 */
@RunWith(JUnit4.class)
public class WidgetLayoutTests {

    /** A wide desktop surface, the case the web stylesheet's >=1025px rules target. */
    private static final WidgetSurface DESKTOP = new WidgetSurface(0, 0, 3840, 2130);

    /** What an NPS page really reports on {@link #DESKTOP}. */
    private static final ContentPlacement NPS_REQUEST = new ContentPlacement(1920, 1567, 480, 563);

    /** What a survey page really reports on {@link #DESKTOP}. */
    private static final ContentPlacement SURVEY_REQUEST = new ContentPlacement(0, 1510, 500, 620);

    /**
     * NPS sits at the bottom, horizontally centred, at the size it asked for.
     * <p>
     * The reported x is {@code parentWidth / 2}, and the web stylesheet pulls the card back by half
     * its own width with {@code translateX(-50%)}. Applying the rect as it stands left the card half
     * a card right of centre.
     */
    @Test
    public void nps_isCentredAtTheBottom() {
        ContentPlacement nps = WidgetLayout.resolve(FeedbackWidgetType.nps, null, DESKTOP, NPS_REQUEST);

        Assert.assertEquals("centred, not left-edge-at-centre", (3840 - 480) / 2, nps.x);
        Assert.assertEquals("flush with the bottom", 2130 - 563, nps.y);
        Assert.assertEquals(480, nps.width);
        Assert.assertEquals("the page measured its own content, so this must not be trimmed", 563, nps.height);
    }

    /**
     * The size a widget reports is authoritative, whatever the stylesheet's own max-height says.
     * <p>
     * The surveys stylesheet caps the iframe at 450px for NPS, but the page itself lays out up to
     * 620px and reports that; capping it here cut the bottom off the card.
     */
    @Test
    public void theReportedSizeIsHonoured() {
        ContentPlacement tall = WidgetLayout.resolve(
            FeedbackWidgetType.nps, null, DESKTOP, new ContentPlacement(1920, 1510, 480, 620));
        Assert.assertEquals(620, tall.height);
        Assert.assertEquals(2130 - 620, tall.y);

        ContentPlacement wide = WidgetLayout.resolve(
            FeedbackWidgetType.survey, "bLeft", DESKTOP, SURVEY_REQUEST);
        Assert.assertEquals(500, wide.width);
        Assert.assertEquals(620, wide.height);
    }

    /**
     * A rating is a fixed 400x800 card centred on both axes.
     * <p>
     * Its page reports no size and paints only the little sticky tab, so there are two ways to get
     * this wrong, and both were seen: taking a rect it never sent made it fullscreen, and measuring
     * what it painted made it a 50px sliver.
     */
    @Test
    public void rating_isACentredCardWhateverItReports() {
        for (ContentPlacement request : new ContentPlacement[] {
            null,
            new ContentPlacement(8, 8, 50, 669),                 // the sticky tab it paints
            new ContentPlacement(0, 0, 3840, 2130) }) {          // the whole surface

            ContentPlacement rating = WidgetLayout.resolve(FeedbackWidgetType.rating, null, DESKTOP, request);

            Assert.assertEquals(WidgetLayout.RATING_WIDTH, rating.width);
            Assert.assertEquals(WidgetLayout.RATING_HEIGHT, rating.height);
            Assert.assertEquals((3840 - 400) / 2, rating.x);
            Assert.assertEquals((2130 - 800) / 2, rating.y);
        }
    }

    /**
     * A survey is anchored by its {@code appearance.position}, 50px in from that edge, at the
     * bottom. Its own reported x is always 0, so the anchor can only come from the position.
     */
    @Test
    public void survey_isAnchoredByItsAppearancePosition() {
        ContentPlacement left = WidgetLayout.resolve(FeedbackWidgetType.survey, "bLeft", DESKTOP, SURVEY_REQUEST);
        Assert.assertEquals(WidgetLayout.SIDE_MARGIN, left.x);
        Assert.assertEquals(2130 - 620, left.y);

        ContentPlacement right = WidgetLayout.resolve(FeedbackWidgetType.survey, "bRight", DESKTOP, SURVEY_REQUEST);
        Assert.assertEquals(3840 - 500 - WidgetLayout.SIDE_MARGIN, right.x);

        // Case and padding come from a server field, so they must not decide the anchor.
        Assert.assertEquals(right.x, WidgetLayout.resolve(FeedbackWidgetType.survey, "  bright  ", DESKTOP, SURVEY_REQUEST).x);

        // No position sent: the left anchor is the default the web SDK falls back to.
        Assert.assertEquals(left.x, WidgetLayout.resolve(FeedbackWidgetType.survey, null, DESKTOP, SURVEY_REQUEST).x);
    }

    /**
     * A widget that reported nothing usable still gets a sane card, rather than a 0x0 window.
     */
    @Test
    public void nothingReported_fallsBackToTheStylesheetSize() {
        ContentPlacement nps = WidgetLayout.resolve(FeedbackWidgetType.nps, null, DESKTOP, null);
        Assert.assertEquals(WidgetLayout.DEFAULT_WIDTH, nps.width);
        Assert.assertEquals(WidgetLayout.NPS_DEFAULT_HEIGHT, nps.height);

        ContentPlacement survey = WidgetLayout.resolve(
            FeedbackWidgetType.survey, "bLeft", DESKTOP, new ContentPlacement(0, 0, 0, 0));
        Assert.assertEquals(WidgetLayout.DEFAULT_WIDTH, survey.width);
        Assert.assertEquals(WidgetLayout.SURVEY_DEFAULT_HEIGHT, survey.height);
    }

    /**
     * A surface smaller than the card, which is what a small window or a phone-sized screen gives:
     * nothing may be placed off screen or sized beyond the surface.
     */
    @Test
    public void aSurfaceSmallerThanTheCard_isNeverOverflowed() {
        WidgetSurface tiny = new WidgetSurface(100, 50, 320, 400);

        for (FeedbackWidgetType type : FeedbackWidgetType.values()) {
            for (ContentPlacement request : new ContentPlacement[] { null, NPS_REQUEST, SURVEY_REQUEST }) {
                ContentPlacement placed = WidgetLayout.resolve(type, "bRight", tiny, request);
                Assert.assertTrue(type + " too wide", placed.width <= tiny.width);
                Assert.assertTrue(type + " too tall", placed.height <= tiny.height);
                Assert.assertTrue(type + " off the left", placed.x >= tiny.x);
                Assert.assertTrue(type + " off the top", placed.y >= tiny.y);
                Assert.assertTrue(type + " off the right", placed.x + placed.width <= tiny.x + tiny.width);
                Assert.assertTrue(type + " off the bottom", placed.y + placed.height <= tiny.y + tiny.height);
            }
        }
    }

    /**
     * The surface origin is honoured, so a card lands on the monitor the application is on rather
     * than on the primary screen.
     */
    @Test
    public void theSurfaceOriginIsHonoured() {
        WidgetSurface secondMonitor = new WidgetSurface(-3840, -1174, 3840, 2130);

        ContentPlacement nps = WidgetLayout.resolve(FeedbackWidgetType.nps, null, secondMonitor, NPS_REQUEST);
        Assert.assertEquals(-3840 + (3840 - 480) / 2, nps.x);
        Assert.assertEquals(-1174 + 2130 - 563, nps.y);

        ContentPlacement rating = WidgetLayout.resolve(FeedbackWidgetType.rating, null, secondMonitor, null);
        Assert.assertEquals(-3840 + (3840 - 400) / 2, rating.x);
        Assert.assertEquals(-1174 + (2130 - 800) / 2, rating.y);
    }

    /**
     * An unknown type is centred rather than guessed at, and must not throw.
     */
    @Test
    public void anUnknownType_isCentred() {
        ContentPlacement placed = WidgetLayout.resolve(null, null, DESKTOP, NPS_REQUEST);
        Assert.assertEquals((3840 - 480) / 2, placed.x);
        Assert.assertEquals((2130 - 563) / 2, placed.y);
    }
}
