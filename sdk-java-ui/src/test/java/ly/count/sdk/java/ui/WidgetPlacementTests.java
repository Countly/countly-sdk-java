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
}
