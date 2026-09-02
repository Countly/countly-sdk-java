package ly.count.sdk.java.ui;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javafx.scene.Scene;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;
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

        FxTestToolkit.waitUntil("the stage to show", () -> showing(stageRef));

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

        FxTestToolkit.waitUntil("the stage to close", () -> !showing(stageRef));
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
     * The card is fitted to what the page drew before it is ever visible, so it appears once at the
     * size it keeps rather than at the size the widget guessed and then jumping to the right one.
     */
    @Test
    public void aCardIsFittedBeforeItIsVisible() {
        RecordingListener listener = new RecordingListener();
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        AtomicReference<JavaFxWidgetHost> hostRef = new AtomicReference<>();
        // Every rectangle the window was ever visible at, which is the whole point of the test.
        List<String> seen = Collections.synchronizedList(new ArrayList<>());

        // A 300px card in a page it will be asked to fill 500px of.
        String page = "<html><head><title>test</title></head><body style='margin:0'>"
            + "<div id='widget-body' style='height:300px;background:#fff'>card</div></body></html>";

        // Re-places the way the presenter does, keeping the bottom edge.
        listener.onMeasured = (width, height) -> hostRef.get().placeAndShow(
            new ContentPlacement(0, 200 + 500 - height, width, height));

        FxTestToolkit.onFx(() -> {
            hostRef.set(newHost(new WidgetSurface(0, 0, 900, 700), listener, stageRef));
            Stage stage = stageRef.get();
            stage.opacityProperty().addListener((observable, was, now) -> {
                if (now.doubleValue() > 0) {
                    seen.add((int) stage.getWidth() + "x" + (int) stage.getHeight() + " at " + (int) stage.getY());
                }
            });
            hostRef.get().navigate(FxTestToolkit.pageUrl(page));
        });

        FxTestToolkit.waitUntil("the page to load", () -> listener.pageLoads > 0);
        FxTestToolkit.onFx(() -> hostRef.get().placeAndShow(new ContentPlacement(0, 200, 480, 500)));

        FxTestToolkit.waitUntil("the card to be measured", () -> !listener.measuredCards.isEmpty());
        Assert.assertEquals("300 tall, not the 500 it was placed at", "480x300", listener.measuredCards.get(0));

        FxTestToolkit.waitUntil("the card to be revealed", () -> !seen.isEmpty());
        Assert.assertEquals("only ever visible at the fitted size", "480x300 at 400", seen.get(0));

        FxTestToolkit.onFx(() -> {
            Assert.assertTrue(showing(stageRef));
            stageRef.get().close();
        });
    }

    /**
     * A widget that never reports its own size is reported along with the size of what it did paint,
     * rather than being placed here: only the presenter knows the widget type, and the type is what
     * decides the card. A rating reports no size and paints only its sticky tab, so placing the
     * measurement gave a 50px sliver of a window.
     */
    @Test
    public void aPageThatNeverReportsASize_isReportedWithWhatItPainted() {
        RecordingListener listener = new RecordingListener();
        AtomicReference<Stage> stageRef = new AtomicReference<>();

        String page = "<html><head><title>test</title></head><body>"
            + "<div id='widget-body' style='height:240px'>quiet</div></body></html>";

        FxTestToolkit.onFx(() -> {
            JavaFxWidgetHost host = newHost(new WidgetSurface(0, 0, 900, 700), listener, stageRef);
            host.navigate(FxTestToolkit.pageUrl(page));
        });

        FxTestToolkit.waitUntil("the missing size report", () -> !listener.missingSizes.isEmpty());

        Assert.assertEquals(0, listener.loadFailures);
        Assert.assertFalse("the host must not place it itself", showing(stageRef));

        FxTestToolkit.onFx(() -> stageRef.get().close());
    }

    /**
     * A load that never resolves is reported as a failure once the deadline passes.
     * <p>
     * WebKit reports neither success nor failure for a connection that is accepted and then goes
     * quiet, which left the caller with no card and no callback at all: a click that did nothing.
     */
    @Test
    public void aLoadThatNeverResolves_isReportedAsAFailure() throws Exception {
        RecordingListener listener = new RecordingListener();
        AtomicReference<Stage> stageRef = new AtomicReference<>();

        // Accepts the connection and then says nothing, which is what stalls a load.
        ServerSocket silent = new ServerSocket(0);
        Thread accepting = new Thread(() -> {
            try {
                while (!silent.isClosed()) {
                    silent.accept();
                }
            } catch (IOException expected) {
                // The socket was closed by the test.
            }
        });
        accepting.setDaemon(true);
        accepting.start();

        Duration original = JavaFxWidgetHost.loadTimeout;
        JavaFxWidgetHost.loadTimeout = Duration.millis(400);
        try {
            FxTestToolkit.onFx(() -> {
                JavaFxWidgetHost host = newHost(new WidgetSurface(0, 0, 900, 700), listener, stageRef);
                host.navigate("http://127.0.0.1:" + silent.getLocalPort() + "/stalls");
            });

            FxTestToolkit.waitUntil("the deadline to pass", () -> listener.loadFailures > 0);
            Assert.assertEquals(0, listener.pageLoads);
        } finally {
            JavaFxWidgetHost.loadTimeout = original;
            silent.close();
        }

        FxTestToolkit.onFx(() -> stageRef.get().close());
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

    /**
     * A stage's showing state read on the JavaFX thread, which is the only place it is reliably
     * observable. Polling it from the test thread only ever appeared to work under Monocle.
     *
     * @param stageRef holds the stage
     * @return whether it is on screen
     */
    private static boolean showing(AtomicReference<Stage> stageRef) {
        AtomicReference<Boolean> shown = new AtomicReference<>(false);
        FxTestToolkit.onFx(() -> shown.set(stageRef.get() != null && stageRef.get().isShowing()));
        return shown.get();
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
        final List<String> missingSizes = Collections.synchronizedList(new ArrayList<>());
        final List<String> measuredCards = Collections.synchronizedList(new ArrayList<>());
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

        @Override
        public void onSizeNotReported(int paintedWidth, int paintedHeight) {
            missingSizes.add(paintedWidth + "x" + paintedHeight);
        }

        /** What to do about a measurement, when a test wants to react like the presenter does. */
        volatile java.util.function.BiConsumer<Integer, Integer> onMeasured;

        @Override
        public void onCardMeasured(int width, int height) {
            measuredCards.add(width + "x" + height);
            if (onMeasured != null) {
                onMeasured.accept(width, height);
            }
        }
    }
}
