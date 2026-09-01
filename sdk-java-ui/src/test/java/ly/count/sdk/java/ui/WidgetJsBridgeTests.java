package ly.count.sdk.java.ui;

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
        new WidgetJsBridge(null).post("{\"cly_widget_command\":1,\"close\":1}");

        WidgetWebHost.Listener listener = mock(WidgetWebHost.Listener.class);
        WidgetJsBridge bridge = new WidgetJsBridge(listener);

        bridge.post("{\"cly_widget_command\":1,\"close\":1}");
        verify(listener).onWidgetMessage(eq("{\"cly_widget_command\":1,\"close\":1}"));

        doThrow(new RuntimeException("boom")).when(listener).onWidgetMessage("bad payload");
        // Must not propagate: a widget page must never see a Java exception surface back into it.
        bridge.post("bad payload");

        verify(listener, never()).onWidgetMessage("{\"never\":\"sent\"}");
    }
}
