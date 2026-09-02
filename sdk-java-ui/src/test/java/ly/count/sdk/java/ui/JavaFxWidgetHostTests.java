package ly.count.sdk.java.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javafx.scene.Scene;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import ly.count.sdk.java.internal.ContentPlacement;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * The JavaFX browser adapter, driven against real pages loaded from disk on the headless toolkit.
 */
@RunWith(JUnit4.class)
public class JavaFxWidgetHostTests {

    private static final String CLOSE_SIGNAL = "https://countly_action_event/?cly_widget_command=1&close=1";

    @BeforeClass
    public static void startToolkit() {
        FxTestToolkit.assumeToolkitAvailable();
        FxTestToolkit.start();
    }

    /**
     * A page loads, the host reports it, and the signal the page navigates to afterwards is handed
     * to the listener rather than being followed as a navigation.
     */
    @Test
    public void load_reportsThePageThenTheSignalItNavigatesTo() {
        RecordingListener listener = new RecordingListener();
        AtomicReference<Stage> stageRef = new AtomicReference<>();

        FxTestToolkit.onFx(() -> {
            JavaFxWidgetHost host = newHost(new WidgetSurface(0, 0, 900, 700), listener, stageRef);
            Assert.assertEquals(900, host.getSurface().width);
            Assert.assertNotNull(host.getWebView());
            host.navigate(FxTestToolkit.pageUrl(FxTestToolkit.pageThatSignals(CLOSE_SIGNAL)));
        });

        FxTestToolkit.waitUntil("the page to load", () -> listener.pageLoads > 0);
        FxTestToolkit.waitUntil("the close signal", () -> !listener.navigations.isEmpty());

        Assert.assertEquals(1, listener.pageLoads);
        Assert.assertEquals(0, listener.loadFailures);
        Assert.assertTrue(listener.navigations.get(0).contains("cly_widget_command=1"));

        FxTestToolkit.onFx(() -> stageRef.get().close());
    }

    /**
     * Reporting the surface, placing the card and dismissing it: the stage really moves, really
     * shows, and really closes.
     */
    @Test
    public void placeAndShow_movesAndShowsTheStage_andCloseHostDismissesIt() {
        RecordingListener listener = new RecordingListener();
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        AtomicReference<JavaFxWidgetHost> hostRef = new AtomicReference<>();

        FxTestToolkit.onFx(() -> {
            hostRef.set(newHost(new WidgetSurface(0, 0, 900, 700), listener, stageRef));
            // Reporting a size before any page has loaded must not throw, it simply has nowhere to post.
            hostRef.get().reportSurfaceSize(900, 700);
            hostRef.get().placeAndShow(new ContentPlacement(120, 90, 340, 260));
        });

        FxTestToolkit.waitUntil("the stage to show", () -> stageRef.get().isShowing());

        FxTestToolkit.onFx(() -> {
            Stage stage = stageRef.get();
            Assert.assertEquals(120.0, stage.getX(), 0.5);
            Assert.assertEquals(90.0, stage.getY(), 0.5);
            Assert.assertEquals(340.0, stage.getWidth(), 0.5);
            Assert.assertEquals(260.0, stage.getHeight(), 0.5);

            // Showing again is a no-op rather than a second window.
            hostRef.get().placeAndShow(new ContentPlacement(10, 10, 200, 200));
            Assert.assertTrue(stage.isShowing());

            hostRef.get().closeHost();
        });

        FxTestToolkit.waitUntil("the stage to close", () -> !stageRef.get().isShowing());
    }

    /**
     * A widget reporting its own card size through the page's message channel reaches the listener,
     * which is what the JavaScript bridge exists for.
     */
    @Test
    public void widgetMessages_reachTheListenerThroughTheJavaScriptBridge() {
        RecordingListener listener = new RecordingListener();
        AtomicReference<Stage> stageRef = new AtomicReference<>();

        String page = "<html><head><title>test</title></head><body><p id='widget-body'>x</p><script>"
            + "setTimeout(function(){window.postMessage(JSON.stringify({cly_widget_command:1,"
            + "action:'resize_me',resize_me:{p:{x:1,y:2,w:100,h:120},l:{x:3,y:4,w:200,h:150}}}),'*');},120);"
            + "</script></body></html>";

        FxTestToolkit.onFx(() -> {
            JavaFxWidgetHost host = newHost(new WidgetSurface(0, 0, 900, 700), listener, stageRef);
            host.navigate(FxTestToolkit.pageUrl(page));
        });

        FxTestToolkit.waitUntil("the widget message", () -> !listener.messages.isEmpty());

        String message = listener.messages.get(0);
        Assert.assertTrue(message, message.contains("cly_widget_command"));
        Assert.assertTrue(message, message.contains("resize_me"));

        FxTestToolkit.onFx(() -> stageRef.get().close());
    }

    /**
     * A widget that never reports its own size still gets placed: the host measures the rendered
     * page and centres a card on the surface. Without this a rating widget would never appear.
     */
    @Test
    public void aPageThatNeverReportsASize_isPlacedByMeasuringIt() {
        RecordingListener listener = new RecordingListener();
        AtomicReference<Stage> stageRef = new AtomicReference<>();

        String page = "<html><head><title>test</title></head><body>"
            + "<div id='widget-body' style='height:240px'>quiet</div></body></html>";

        FxTestToolkit.onFx(() -> {
            JavaFxWidgetHost host = newHost(new WidgetSurface(0, 0, 900, 700), listener, stageRef);
            host.navigate(FxTestToolkit.pageUrl(page));
        });

        // Nothing in the test places it, so only the host's own fallback can.
        FxTestToolkit.waitUntil("the measured placement", () -> stageRef.get().isShowing());

        FxTestToolkit.onFx(() -> {
            Stage stage = stageRef.get();
            Assert.assertTrue("card should be no wider than the surface", stage.getWidth() <= 900);
            Assert.assertTrue("card should have a real height", stage.getHeight() > 0);
            // Centred horizontally on the surface.
            Assert.assertEquals((900 - stage.getWidth()) / 2, stage.getX(), 2.0);
            stage.close();
        });
    }

    /**
     * A page that cannot be loaded is reported as a failure, so the presenter can dismiss an
     * otherwise invisible card instead of leaving the caller waiting.
     */
    @Test
    public void aPageThatCannotLoad_isReportedAsAFailure() {
        RecordingListener listener = new RecordingListener();
        AtomicReference<Stage> stageRef = new AtomicReference<>();

        FxTestToolkit.onFx(() -> {
            JavaFxWidgetHost host = newHost(new WidgetSurface(0, 0, 900, 700), listener, stageRef);
            host.navigate("file:///countly-does-not-exist/nothing-here.html");
        });

        FxTestToolkit.waitUntil("the load failure", () -> listener.loadFailures > 0);
        Assert.assertEquals(0, listener.pageLoads);

        FxTestToolkit.onFx(() -> stageRef.get().close());
    }

    /**
     * A host with no listener attached must not blow up when a page loads and signals.
     */
    @Test
    public void aHostWithNoListener_doesNotThrow() {
        AtomicReference<Stage> stageRef = new AtomicReference<>();

        FxTestToolkit.onFx(() -> {
            JavaFxWidgetHost host = newHost(new WidgetSurface(0, 0, 900, 700), null, stageRef);
            host.navigate(FxTestToolkit.pageUrl(FxTestToolkit.pageThatSignals(CLOSE_SIGNAL)));
        });

        FxTestToolkit.sleep(1500);
        FxTestToolkit.onFx(() -> stageRef.get().close());
    }

    /**
     * A widget opening a link in a new window has it handed to the system browser instead of being
     * rendered over the card, which is what the popup handler exists for.
     */
    @Test
    public void aPopupLink_isHandedToTheSystemBrowser() {
        RecordingListener listener = new RecordingListener();
        AtomicReference<Stage> stageRef = new AtomicReference<>();

        String page = "<html><head><title>test</title></head><body><div id='widget-body'>x</div><script>"
            + "setTimeout(function(){window.open('https://count.ly/pricing','_blank');},80);"
            + "</script></body></html>";

        FxTestToolkit.onFx(() -> {
            JavaFxWidgetHost host = newHost(new WidgetSurface(0, 0, 900, 700), listener, stageRef);
            host.navigate(FxTestToolkit.pageUrl(page));
        });

        FxTestToolkit.waitUntil("the page to load", () -> listener.pageLoads > 0);
        // The popup is handled off the card: nothing closes and the listener sees no signal for it.
        FxTestToolkit.sleep(1200);
        Assert.assertTrue(listener.navigations.isEmpty());

        FxTestToolkit.onFx(() -> stageRef.get().close());
    }

    private static JavaFxWidgetHost newHost(WidgetSurface surface, WidgetWebHost.Listener listener, AtomicReference<Stage> stageRef) {
        WebView webView = new WebView();
        Stage stage = new Stage(StageStyle.UNDECORATED);
        stage.setScene(new Scene(webView, 1, 1));
        stageRef.set(stage);

        JavaFxWidgetHost host = new JavaFxWidgetHost(stage, webView, surface);
        host.initialize();
        if (listener != null) {
            host.setListener(listener);
        }
        return host;
    }

    private static class RecordingListener implements WidgetWebHost.Listener {

        final List<String> navigations = Collections.synchronizedList(new ArrayList<>());
        final List<String> messages = Collections.synchronizedList(new ArrayList<>());
        volatile int pageLoads = 0;
        volatile int loadFailures = 0;

        @Override
        public void onNavigationStarting(String url) {
            navigations.add(url);
        }

        @Override
        public void onWidgetMessage(String json) {
            messages.add(json);
        }

        @Override
        public void onPageLoaded() {
            pageLoads++;
        }

        @Override
        public void onLoadFailed() {
            loadFailures++;
        }
    }
}
