package ly.count.sdk.java.ui;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import javafx.concurrent.Worker;
import javafx.scene.web.WebView;
import netscape.javascript.JSObject;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * The engine keeps only a weak reference to a Java object handed to a page, so the object has to be
 * kept alive on the Java side or the page ends up calling into nothing. This drives the collector
 * hard between the install and the call, which is what a long running application does by itself.
 */
@RunWith(JUnit4.class)
public class BridgeLifetimeTests {

    @BeforeClass
    public static void toolkit() {
        FxTestToolkit.assumeToolkitAvailable();
        FxTestToolkit.start();
    }

    @Test
    public void aBridgeHeldByJavaSurvivesGarbageCollection() {
        List<String> received = new CopyOnWriteArrayList<>();
        WidgetWebHost.Listener listener = new RecordingListener(received);

        // Kept in a local for the whole test: this is the strong reference the host now holds too.
        WidgetJsBridge held = new WidgetJsBridge(listener, (w, h, o) -> received.add("card " + w + "x" + h));

        AtomicReference<WebView> viewRef = new AtomicReference<>();
        AtomicReference<Boolean> loaded = new AtomicReference<>(false);
        FxTestToolkit.onFx(() -> {
            WebView webView = new WebView();
            viewRef.set(webView);
            webView.getEngine().getLoadWorker().stateProperty().addListener((o, old, state) -> {
                if (state == Worker.State.SUCCEEDED) {
                    JSObject window = (JSObject) webView.getEngine().executeScript("window");
                    window.setMember(WidgetJsBridge.MEMBER_NAME, held);
                    loaded.set(true);
                }
            });
            webView.getEngine().loadContent("<html><body>x</body></html>");
        });
        for (int i = 0; i < 60 && !loaded.get(); i++) {
            ScenarioDriver.pause(100);
        }
        Assert.assertTrue(loaded.get());

        // The collector, several times over, with garbage to chew on.
        for (int round = 0; round < 5; round++) {
            byte[][] pressure = new byte[64][];
            for (int i = 0; i < pressure.length; i++) {
                pressure[i] = new byte[1 << 20];
            }
            System.gc();
            ScenarioDriver.pause(50);
        }

        FxTestToolkit.onFx(() -> viewRef.get().getEngine().executeScript(
            "window." + WidgetJsBridge.MEMBER_NAME + ".post('{\"cly_widget_command\":1,\"close\":1}');"
                + "window." + WidgetJsBridge.MEMBER_NAME + ".cardChanged('480,469,0');"));

        Assert.assertTrue("the page's message must still reach Java after collection: " + received,
            received.contains("{\"cly_widget_command\":1,\"close\":1}"));
        Assert.assertTrue("the page's card report must still reach Java after collection: " + received,
            received.contains("card 480x469"));
        // Still referenced here, so the collector could not have taken it before the calls above.
        Assert.assertNotNull(held);
    }

    private static final class RecordingListener implements WidgetWebHost.Listener {
        private final List<String> received;

        RecordingListener(List<String> received) {
            this.received = received;
        }

        @Override public void onNavigationStarting(String url) { }
        @Override public void onWidgetMessage(String json) { received.add(json); }
        @Override public void onPageLoaded() { }
        @Override public void onLoadFailed() { }
        @Override public void onSizeNotReported(int paintedWidth, int paintedHeight) { }
        @Override public void onCardMeasured(int width, int height) { }
        @Override public void onContentOverflow(int extraHeight) { }
        @Override public void onCardFollowing(int width, int height) { }
    }
}
