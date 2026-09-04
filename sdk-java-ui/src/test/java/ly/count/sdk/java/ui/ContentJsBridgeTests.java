package ly.count.sdk.java.ui;

import java.util.ArrayList;
import java.util.List;
import ly.count.sdk.java.internal.WidgetAction;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * The object a content page posts to when it hosts a survey. Called here the way the engine calls
 * it, so no toolkit is needed.
 */
@RunWith(JUnit4.class)
public class ContentJsBridgeTests {

    @Test
    public void post_forwardsResizeAndCloseAndIgnoresEverythingElse() {
        List<WidgetAction> actions = new ArrayList<>();
        ContentJsBridge bridge = new ContentJsBridge(actions::add);

        // A hosted survey's resize, as the template posts it (an object, stringified by the script).
        bridge.post("{\"cly_widget_command\":1,\"action\":\"resize_me\",\"resize_me\":"
            + "{\"p\":{\"x\":0,\"y\":200,\"w\":500,\"h\":620},\"l\":{\"x\":0,\"y\":200,\"w\":500,\"h\":620}}}");
        Assert.assertEquals(1, actions.size());
        Assert.assertTrue(actions.get(0).hasResize);
        Assert.assertEquals(620, actions.get(0).resizeFor(true).height);
        Assert.assertFalse(actions.get(0).close);

        bridge.post("{\"cly_widget_command\":1,\"close\":1}");
        Assert.assertEquals(2, actions.size());
        Assert.assertTrue(actions.get(1).close);

        // Not a widget command, not JSON, nothing at all: none of it reaches the display.
        bridge.post("{\"type\":\"resize\",\"width\":1280,\"height\":820}");
        bridge.post("not json");
        bridge.post(null);
        new ContentJsBridge(null).post("{\"cly_widget_command\":1,\"close\":1}");
        Assert.assertEquals(2, actions.size());
    }

    @Test
    public void post_survivesAThrowingHandler() {
        ContentJsBridge bridge = new ContentJsBridge(action -> {
            throw new IllegalStateException("boom");
        });
        // Must not propagate: a page must never see a Java exception surface back into it.
        bridge.post("{\"cly_widget_command\":1,\"close\":1}");
    }
}
