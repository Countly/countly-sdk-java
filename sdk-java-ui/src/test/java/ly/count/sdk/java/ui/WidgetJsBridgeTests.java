package ly.count.sdk.java.ui;

import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The Java object exposed to a widget page's JavaScript. Its {@code post} method is called
 * directly here, exactly the way the JavaFX web engine would call it, so no toolkit is needed.
 */
@RunWith(JUnit4.class)
public class WidgetJsBridgeTests {

    /**
     * A page posting before Java is ready (no listener yet) is dropped, a normal post is forwarded
     * unchanged, and a listener that throws while handling it must not bring the page's JS thread
     * down with it.
     */
    @Test
    public void post_forwardsToTheListenerAndSurvivesAThrowingOne() {
        new WidgetJsBridge(null, null).post("{\"cly_widget_command\":1,\"close\":1}");

        WidgetWebHost.Listener listener = mock(WidgetWebHost.Listener.class);
        WidgetJsBridge bridge = new WidgetJsBridge(listener, null);

        bridge.post("{\"cly_widget_command\":1,\"close\":1}");
        verify(listener).onWidgetMessage(eq("{\"cly_widget_command\":1,\"close\":1}"));

        doThrow(new RuntimeException("boom")).when(listener).onWidgetMessage("bad payload");
        // Must not propagate: a widget page must never see a Java exception surface back into it.
        bridge.post("bad payload");

        verify(listener, never()).onWidgetMessage("{\"never\":\"sent\"}");
    }

    /**
     * The card report the page makes for itself: forwarded with the numbers rounded, dropped when
     * nothing is listening, and never allowed to throw back into the page.
     */
    @Test
    public void cardChanged_forwardsRoundedNumbersAndSurvivesAThrowingObserver() {
        // Nothing listening: a page reporting into the void must not throw.
        new WidgetJsBridge(null, null).cardChanged("480,414,0");

        List<int[]> observed = new ArrayList<>();
        WidgetJsBridge bridge = new WidgetJsBridge(null,
            (width, height, overflow) -> observed.add(new int[] { width, height, overflow }));

        bridge.cardChanged("480,414,55");
        Assert.assertEquals(1, observed.size());
        Assert.assertArrayEquals(new int[] { 480, 414, 55 }, observed.get(0));

        // Anything that is not three numbers is ignored rather than thrown back into the page.
        bridge.cardChanged("garbage");
        bridge.cardChanged(null);
        bridge.cardChanged("1,2");
        Assert.assertEquals(1, observed.size());

        WidgetJsBridge throwing = new WidgetJsBridge(null, (width, height, overflow) -> {
            throw new RuntimeException("boom");
        });
        throwing.cardChanged("480,414,0");
    }

}
