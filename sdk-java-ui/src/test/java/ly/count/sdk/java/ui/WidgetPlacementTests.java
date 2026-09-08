package ly.count.sdk.java.ui;

import ly.count.sdk.java.internal.ContentPlacement;
import ly.count.sdk.java.internal.WidgetAction;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Mapping a requested rectangle onto a surface, and reading the {@code postMessage} payloads a
 * widget sends.
 */
@RunWith(JUnit4.class)
public class WidgetPlacementTests {

    /**
     * A rectangle that fits is placed as asked, one that does not is clamped so it stays on the
     * surface, and both are offset by the surface origin.
     */
    @Test
    public void resolve_offsetsAndClampsToTheSurface() {
        WidgetSurface surface = new WidgetSurface(200, 100, 800, 600);

        ContentPlacement fits = WidgetPlacement.resolve(new ContentPlacement(10, 20, 300, 400), surface);
        Assert.assertEquals(210, fits.x);
        Assert.assertEquals(120, fits.y);
        Assert.assertEquals(300, fits.width);
        Assert.assertEquals(400, fits.height);

        // Too big: sized down to the surface and pinned to its origin.
        ContentPlacement tooBig = WidgetPlacement.resolve(new ContentPlacement(50, 50, 2000, 2000), surface);
        Assert.assertEquals(200, tooBig.x);
        Assert.assertEquals(100, tooBig.y);
        Assert.assertEquals(800, tooBig.width);
        Assert.assertEquals(600, tooBig.height);

        // Pushed off the right and bottom edges: slid back so the whole card stays visible.
        ContentPlacement offScreen = WidgetPlacement.resolve(new ContentPlacement(700, 500, 300, 200), surface);
        Assert.assertEquals(200 + 500, offScreen.x);
        Assert.assertEquals(100 + 400, offScreen.y);

        // Negative coordinates are pulled back onto the surface.
        ContentPlacement negative = WidgetPlacement.resolve(new ContentPlacement(-50, -50, 100, 100), surface);
        Assert.assertEquals(200, negative.x);
        Assert.assertEquals(100, negative.y);

        Assert.assertNull(WidgetPlacement.resolve((ContentPlacement) null, surface));
        Assert.assertNull(WidgetPlacement.resolve(new ContentPlacement(0, 0, 10, 10), null));
        Assert.assertNull(WidgetPlacement.resolve((WidgetAction) null, surface));
    }

    /**
     * The orientation of the surface decides which rectangle of a signal is used, and either one is
     * used when only one was sent.
     */
    @Test
    public void resolve_picksTheRectangleMatchingTheSurface() {
        WidgetAction action = new WidgetAction();
        action.hasResize = true;
        action.portrait = new ContentPlacement(0, 0, 100, 200);
        action.landscape = new ContentPlacement(0, 0, 200, 100);

        Assert.assertEquals(200, WidgetPlacement.resolve(action, new WidgetSurface(0, 0, 1600, 900)).width);
        Assert.assertEquals(100, WidgetPlacement.resolve(action, new WidgetSurface(0, 0, 900, 1600)).width);

        action.landscape = null;
        Assert.assertEquals(100, WidgetPlacement.resolve(action, new WidgetSurface(0, 0, 1600, 900)).width);

        action.hasResize = false;
        Assert.assertNull(WidgetPlacement.resolve(action, new WidgetSurface(0, 0, 1600, 900)));
    }

    /**
     * Every shape of the widget's own message channel: a close, a resize, a rectangle without a
     * usable size, and payloads that are not widget commands at all.
     */
    @Test
    public void widgetMessageParser_readsOnlyWidgetCommands() {
        WidgetAction close = WidgetMessageParser.parse("{\"cly_widget_command\":1,\"close\":1}");
        Assert.assertNotNull(close);
        Assert.assertTrue(close.isWidgetCommand);
        Assert.assertTrue(close.close);
        Assert.assertFalse(close.hasResize);

        Assert.assertTrue(WidgetMessageParser.parse("{\"cly_widget_command\":1,\"close\":true}").close);
        Assert.assertFalse(WidgetMessageParser.parse("{\"cly_widget_command\":1,\"close\":0}").close);
        Assert.assertFalse(WidgetMessageParser.parse("{\"cly_widget_command\":1}").close);

        WidgetAction resize = WidgetMessageParser.parse(
            "{\"cly_widget_command\":1,\"resize_me\":{\"p\":{\"x\":1,\"y\":2,\"w\":3,\"h\":4}}}");
        Assert.assertTrue(resize.hasResize);
        Assert.assertEquals(3, resize.portrait.width);
        Assert.assertNull(resize.landscape);

        WidgetAction unusable = WidgetMessageParser.parse(
            "{\"cly_widget_command\":1,\"resize_me\":{\"p\":{\"x\":1,\"y\":2,\"w\":0,\"h\":4}}}");
        Assert.assertFalse(unusable.hasResize);

        Assert.assertNull(WidgetMessageParser.parse("{\"type\":\"resize\",\"width\":10}"));
        Assert.assertNull(WidgetMessageParser.parse("not json"));
        Assert.assertNull(WidgetMessageParser.parse("[1,2,3]"));
        Assert.assertNull(WidgetMessageParser.parse(""));
        Assert.assertNull(WidgetMessageParser.parse(null));
    }

    /**
     * A window that changed size keeps the block's share of it. The server's own numbers: a large
     * sticky centre block is 60% of the width, capped at 1021, so 768 in a 1280 wide window.
     */
    @Test
    public void rescale_keepsTheBlocksShareOfTheWindow() {
        WidgetSurface asFetched = new WidgetSurface(0, 0, 1280, 820);
        ContentPlacement asked = new ContentPlacement(256, 16, 768, 95);

        ContentPlacement narrower = WidgetPlacement.rescale(asked, asFetched, new WidgetSurface(0, 0, 640, 820));
        Assert.assertEquals(384, narrower.width);
        Assert.assertEquals(128, narrower.x);
        Assert.assertEquals(95, narrower.height);

        ContentPlacement wider = WidgetPlacement.rescale(asked, asFetched, new WidgetSurface(0, 0, 2560, 820));
        Assert.assertEquals(1536, wider.width);
        Assert.assertEquals(512, wider.x);
    }

    /** Nothing to re-state when the surface has not changed, or when there is nothing to compare to. */
    @Test
    public void rescale_leavesTheRectangleAloneWhenItStillFits() {
        WidgetSurface surface = new WidgetSurface(100, 50, 1280, 820);
        ContentPlacement asked = new ContentPlacement(256, 16, 768, 95);

        Assert.assertSame(asked, WidgetPlacement.rescale(asked, surface, new WidgetSurface(-2560, -519, 1280, 820)));
        Assert.assertSame(asked, WidgetPlacement.rescale(asked, null, surface));
        Assert.assertSame(asked, WidgetPlacement.rescale(asked, new WidgetSurface(0, 0, 0, 0), surface));
    }

    /** A rescaled rectangle is still surface relative, so placing it lands inside the window. */
    @Test
    public void rescale_thenResolve_landsInsideTheWindow() {
        WidgetSurface asFetched = new WidgetSurface(0, 0, 1280, 820);
        WidgetSurface now = new WidgetSurface(-2560, -519, 640, 410);
        ContentPlacement asked = new ContentPlacement(256, 16, 768, 95);

        ContentPlacement placed = WidgetPlacement.resolve(WidgetPlacement.rescale(asked, asFetched, now), now);
        Assert.assertEquals(-2560 + 128, placed.x);
        Assert.assertEquals(-519 + 8, placed.y);
        Assert.assertEquals(384, placed.width);
        Assert.assertTrue(placed.x + placed.width <= now.x + now.width);
    }


    /**
     * Once the page has asked for a rectangle it is the one that gets used: it is the only party
     * that knows how tall its own content turned out.
     */
    @Test
    public void following_prefersWhatThePageAskedFor() {
        WidgetSurface surface = new WidgetSurface(0, 0, 1280, 820);
        ContentPlacement fromServer = new ContentPlacement(256, 16, 768, 95);
        ContentPlacement fromPage = new ContentPlacement(256, 16, 768, 240);

        ContentPlacement server = WidgetPlacement.following(null, null, fromServer, surface, surface);
        Assert.assertEquals(95, server.height);

        ContentPlacement page = WidgetPlacement.following(fromPage, surface, fromServer, surface, surface);
        Assert.assertEquals("the page's own height must win", 240, page.height);
    }

    /**
     * A page's own rectangle is a size, not a proportion: across a resize it is kept and clamped, never
     * scaled. Scaling it squeezed a hosted survey to a sliver as the window narrowed, while the same
     * survey as a widget kept its 500 wide card.
     */
    @Test
    public void following_keepsThePagesOwnSizeAcrossAResize() {
        WidgetSurface asAsked = new WidgetSurface(0, 0, 1280, 820);
        WidgetSurface narrower = new WidgetSurface(-2560, -519, 640, 820);
        ContentPlacement fromServer = new ContentPlacement(256, 16, 768, 95);
        ContentPlacement fromPage = new ContentPlacement(0, 200, 500, 620);

        ContentPlacement placed = WidgetPlacement.following(fromPage, asAsked, fromServer, asAsked, narrower);
        Assert.assertEquals("the page's width is kept, not scaled to 250", 500, placed.width);
        Assert.assertEquals(620, placed.height);
        Assert.assertEquals("anchored where the page put it, on the new surface", -2560, placed.x);
        Assert.assertEquals("and still against the bottom edge it was placed by", -519 + 820 - 620, placed.y);

        // Only when the window is narrower than the card does the card give way, by clamping.
        WidgetSurface tiny = new WidgetSurface(0, 0, 370, 820);
        ContentPlacement clamped = WidgetPlacement.following(fromPage, asAsked, fromServer, asAsked, tiny);
        Assert.assertEquals(370, clamped.width);
        Assert.assertEquals(620, clamped.height);

        // The server's rectangle is a proportion of the area and still scales.
        ContentPlacement scaled = WidgetPlacement.following(null, null, fromServer, asAsked, narrower);
        Assert.assertEquals(384, scaled.width);
    }


    /**
     * A hosted survey accepts the display's resize message only when the URL carries its own origin,
     * which the server's content URL does not.
     */
    @Test
    public void contentUrl_gainsItsOwnOriginOnce() {
        Assert.assertEquals(
            "https://master.count.ly/_external/content?app_id=1&id=2&origin=https://master.count.ly",
            JavaFxContentDisplay.withOrigin("https://master.count.ly/_external/content?app_id=1&id=2"));
        Assert.assertEquals("http://localhost:3001/content?origin=http://localhost:3001",
            JavaFxContentDisplay.withOrigin("http://localhost:3001/content"));

        // Already present, not a URL, or nothing at all: left alone.
        String carrying = "https://x.count.ly/content?origin=https://x.count.ly";
        Assert.assertSame(carrying, JavaFxContentDisplay.withOrigin(carrying));
        Assert.assertEquals("not a url", JavaFxContentDisplay.withOrigin("not a url"));
        Assert.assertNull(JavaFxContentDisplay.withOrigin(null));
    }


    /**
     * A page's rectangle follows the edge it was placed against, not its old coordinates. A bottom
     * left survey computed for an 820 tall window sat at y=246; when the window grew to 1000 tall it
     * kept y=246 and floated mid-window until the page happened to recompute. It belongs at the new
     * bottom, the same distance from the same edge.
     */
    @Test
    public void reanchor_keepsTheCardAgainstTheEdgeItWasPlacedBy() {
        WidgetSurface was = new WidgetSurface(53, -1475, 1280, 820);
        WidgetSurface taller = new WidgetSurface(53, -1475, 1339, 1000);

        // bottom left: left margin 0, bottom margin 0
        ContentPlacement bottomLeft = WidgetPlacement.reanchor(new ContentPlacement(0, 246, 500, 574), was, taller);
        Assert.assertEquals(0, bottomLeft.x);
        Assert.assertEquals(1000 - 574, bottomLeft.y);

        // bottom right: right margin 0
        ContentPlacement bottomRight = WidgetPlacement.reanchor(new ContentPlacement(780, 246, 500, 574), was, taller);
        Assert.assertEquals(1339 - 500, bottomRight.x);
        Assert.assertEquals(1000 - 574, bottomRight.y);

        // centred horizontally, bottom anchored - an NPS card - stays centred and at the bottom
        ContentPlacement centred = WidgetPlacement.reanchor(new ContentPlacement(400, 406, 480, 414), was, taller);
        Assert.assertEquals((1339 - 480) / 2, centred.x);
        Assert.assertEquals(1000 - 414, centred.y);

        // top anchored keeps its top margin
        ContentPlacement top = WidgetPlacement.reanchor(new ContentPlacement(0, 16, 500, 200), was, taller);
        Assert.assertEquals(16, top.y);

        // Same surface, or no surface to compare against: untouched.
        ContentPlacement same = new ContentPlacement(0, 246, 500, 574);
        Assert.assertSame(same, WidgetPlacement.reanchor(same, was, new WidgetSurface(0, 0, 1280, 820)));
        Assert.assertSame(same, WidgetPlacement.reanchor(same, null, taller));
    }

}
