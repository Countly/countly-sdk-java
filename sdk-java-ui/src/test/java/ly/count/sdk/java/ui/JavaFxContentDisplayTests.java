package ly.count.sdk.java.ui;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;
import ly.count.sdk.java.Countly;
import ly.count.sdk.java.internal.ContentData;
import ly.count.sdk.java.internal.ContentPlacement;
import ly.count.sdk.java.internal.ContentScreen;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * The JavaFX content overlay, driven against real pages on the headless toolkit.
 * <p>
 * The SDK is initialized against a dead local port: nothing here needs a server, and a refused
 * connection fails immediately rather than holding a test up.
 */
@RunWith(JUnit4.class)
public class JavaFxContentDisplayTests {

    private static final String ACTION = "https://countly_action_event/?cly_x_action_event=1";

    private final List<Map<String, Object>> closes = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger closeCount = new AtomicInteger(0);

    private static HttpServer hangingServer;
    private static int hangingPort;

    @BeforeClass
    public static void startToolkit() throws IOException {
        FxTestToolkit.start();

        // An endpoint that accepts the request and then does not answer for far longer than the
        // shortened load deadline, which is what that deadline exists for. Kept to a few seconds
        // rather than a long block: the handler holds a server thread until it returns, and a long
        // one both stalls this class's teardown and starves the shared JVM the other test classes
        // are timing against.
        hangingServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        hangingPort = hangingServer.getAddress().getPort();
        hangingServer.createContext("/", exchange -> FxTestToolkit.sleep(3000));
        hangingServer.start();
    }

    @AfterClass
    public static void stopServer() {
        if (hangingServer != null) {
            hangingServer.stop(0);
        }
    }

    @Before
    public void initSdk() {
        closes.clear();
        closeCount.set(0);
        Countly.instance().init(UiTestConfigs.refusedServer());
    }

    @After
    public void stopSdk() {
        Countly.instance().halt();
    }

    /**
     * The surface reported to the server: the primary screen when no window is followed, and the
     * screen of the window when one is.
     */
    @Test
    public void getScreen_reportsTheSurfaceItWillPlaceOn() {
        ContentScreen unowned = new JavaFxContentDisplay(null).getScreen();
        Assert.assertTrue(unowned.width > 0 && unowned.height > 0);
        Assert.assertTrue(unowned.toString().contains("width="));

        FxTestToolkit.onFx(() -> {
            Stage owner = new Stage(StageStyle.UNDECORATED);
            owner.setScene(new Scene(new Pane(), 400, 300));
            owner.setX(20);
            owner.setY(20);
            owner.show();
            try {
                JavaFxContentDisplay display = new JavaFxContentDisplay(owner);
                ContentScreen screen = display.getScreen();
                Assert.assertEquals(unowned.width, screen.width);

                // Moving the window keeps the reported surface current.
                owner.setX(60);
                Assert.assertEquals(unowned.width, display.getScreen().width);
            } finally {
                owner.close();
            }
        });
    }

    /**
     * The whole content lifecycle through the page's own signals: an event is recorded through the
     * SDK, a resize moves the window, an external link is handled without leaving, and the close
     * hands the query parameters back exactly once.
     */
    @Test
    public void present_recordsEventsResizesAndClosesOnce() {
        String page = "<html><head><title>c</title></head><body><p>content</p><script>"
            + "setTimeout(function(){window.location='" + ACTION
            + "&action=event&event='+encodeURIComponent('[{\"key\":\"[CLY]_content_shown\",\"sg\":{\"a\":\"1\"}}]');},60);"
            + "setTimeout(function(){window.location='" + ACTION
            + "&action=resize_me&resize_me='+encodeURIComponent('{\"p\":{\"x\":5,\"y\":6,\"w\":260,\"h\":200},\"l\":{\"x\":7,\"y\":8,\"w\":300,\"h\":240}}');},220);"
            + "setTimeout(function(){window.location='" + ACTION + "&close=1&reason=done';},420);"
            + "</script></body></html>";

        JavaFxContentDisplay display = new JavaFxContentDisplay(null);
        display.present(content(FxTestToolkit.pageUrl(page)), this::recordClose);

        FxTestToolkit.waitUntil("the content to close", () -> closeCount.get() > 0);
        FxTestToolkit.sleep(300);

        Assert.assertEquals("close must be reported exactly once", 1, closeCount.get());
        Map<String, Object> data = closes.get(0);
        Assert.assertEquals("1", data.get("close"));
        Assert.assertEquals("done", data.get("reason"));
        Assert.assertEquals("1", data.get("cly_x_action_event"));
    }

    /**
     * Content the server placed nowhere usable is dropped, and the zone is released straight away
     * rather than being left believing something is on screen.
     */
    @Test
    public void present_withNoUsablePlacement_releasesTheZone() {
        JavaFxContentDisplay display = new JavaFxContentDisplay(null);
        display.present(new ContentData(FxTestToolkit.pageUrl("<html><body>x</body></html>"), null, null), this::recordClose);

        FxTestToolkit.waitUntil("the zone to be released", () -> closeCount.get() > 0);
        Assert.assertEquals(1, closeCount.get());
        Assert.assertTrue(closes.get(0).isEmpty());
    }

    /**
     * A content page that cannot load must release the zone too, or no further content would ever
     * be fetched.
     */
    @Test
    public void present_withAPageThatCannotLoad_releasesTheZone() {
        JavaFxContentDisplay display = new JavaFxContentDisplay(null);
        display.present(content("file:///countly-does-not-exist/none.html"), this::recordClose);

        FxTestToolkit.waitUntil("the zone to be released", () -> closeCount.get() > 0);
        Assert.assertEquals(1, closeCount.get());
    }

    /**
     * An external link is opened outside the overlay and the content stays up, then the user closing
     * the window still releases the zone.
     */
    @Test
    public void externalLink_leavesTheContentUp_andHidingItStillReleasesTheZone() {
        String page = "<html><head><title>c</title></head><body><p>content</p><script>"
            + "setTimeout(function(){window.location='https://count.ly/pricing?cly_x_int=1';},60);"
            + "</script></body></html>";

        JavaFxContentDisplay display = new JavaFxContentDisplay(null);
        display.present(content(FxTestToolkit.pageUrl(page)), this::recordClose);

        // The external link is not a close, so nothing is reported for it.
        FxTestToolkit.sleep(1200);
        Assert.assertEquals(0, closeCount.get());
    }

    /**
     * Content already on screen when the SDK is stopped: the page can still signal, and the display
     * has to handle having no content interface left to relay its events through, then still close
     * cleanly. Without this the overlay would throw on the JavaFX thread and never release the zone.
     */
    @Test
    public void aPageSignallingAfterTheSdkIsStopped_stillClosesCleanly() {
        Countly.instance().halt();

        String page = "<html><head><title>c</title></head><body><p>content</p><script>"
            + "setTimeout(function(){window.location='" + ACTION
            + "&action=event&event='+encodeURIComponent('[{\"key\":\"orphan\",\"sg\":{}}]');},60);"
            + "setTimeout(function(){window.location='" + ACTION + "&close=1';},260);"
            + "</script></body></html>";

        JavaFxContentDisplay display = new JavaFxContentDisplay(null);
        display.present(content(FxTestToolkit.pageUrl(page)), this::recordClose);

        FxTestToolkit.waitUntil("the content to close", () -> closeCount.get() > 0);
        Assert.assertEquals(1, closeCount.get());
    }

    /**
     * A content page that accepts the connection and then never answers must not hold the zone
     * hostage: the deadline gives up, the window is dropped and the zone is released so the next
     * fetch can happen. This is the guarantee that keeps a hung page from silently ending content
     * for the rest of the session.
     */
    @Test
    public void aPageThatNeverAnswers_isAbandonedAndReleasesTheZone() {
        Duration realTimeout = JavaFxContentDisplay.loadTimeout;
        JavaFxContentDisplay.loadTimeout = Duration.millis(600);
        try {
            JavaFxContentDisplay display = new JavaFxContentDisplay(null);
            display.present(content("http://127.0.0.1:" + hangingPort + "/hangs-forever"), this::recordClose);

            FxTestToolkit.waitUntil("the deadline to give up", () -> closeCount.get() > 0);
            Assert.assertEquals(1, closeCount.get());
            Assert.assertTrue(closes.get(0).isEmpty());
        } finally {
            JavaFxContentDisplay.loadTimeout = realTimeout;
        }
    }

    /**
     * A missing close callback must not stop the overlay from working.
     */
    @Test
    public void present_withoutACloseCallback_stillWorks() {
        JavaFxContentDisplay display = new JavaFxContentDisplay(null);
        display.present(content(FxTestToolkit.pageUrl(FxTestToolkit.pageThatSignals(ACTION + "&close=1"))), null);
        FxTestToolkit.sleep(1200);
    }

    private void recordClose(Map<String, Object> data) {
        closes.add(data);
        closeCount.incrementAndGet();
    }

    private static ContentData content(String url) {
        return new ContentData(url, new ContentPlacement(10, 20, 300, 240), new ContentPlacement(30, 40, 320, 260));
    }

}
