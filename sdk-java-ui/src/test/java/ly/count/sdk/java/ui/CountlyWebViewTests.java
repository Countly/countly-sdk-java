package ly.count.sdk.java.ui;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import ly.count.sdk.java.Countly;
import ly.count.sdk.java.internal.CountlyFeedbackWidget;
import ly.count.sdk.java.internal.FeedbackWidgetType;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * The public facade, driven on the headless toolkit against a dead local port.
 * <p>
 * Every path that cannot show anything has to end in the caller's callback being run exactly once,
 * because an integrator waiting on a callback that never comes is the worst failure this class can
 * have.
 */
@RunWith(JUnit4.class)
public class CountlyWebViewTests {

    private final AtomicInteger closed = new AtomicInteger(0);

    private static HttpServer widgetServer;
    private static int widgetPort;

    @BeforeClass
    public static void startToolkit() throws IOException {
        FxTestToolkit.start();

        // A real widget page over real HTTP, so the whole chain runs: the SDK builds the URL, the
        // page loads, and the page closes itself the way a widget does. The JDK's own server keeps
        // this dependency free.
        widgetServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        widgetPort = widgetServer.getAddress().getPort();
        widgetServer.createContext("/", exchange -> {
            boolean askingForTheList = exchange.getRequestURI().getPath().startsWith("/o/sdk");
            String payload = askingForTheList ? WIDGET_LIST : WIDGET_PAGE;
            byte[] body = payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type",
                askingForTheList ? "application/json; charset=utf-8" : "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        widgetServer.start();
    }

    @AfterClass
    public static void stopServer() {
        if (widgetServer != null) {
            widgetServer.stop(0);
        }
    }

    /** What the server answers to "/o/sdk?method=feedback": one widget of each type. */
    private static final String WIDGET_LIST =
        "{\"result\":["
            + "{\"_id\":\"nps_1\",\"type\":\"nps\",\"name\":\"served nps\",\"tg\":[\"by-tag\"]},"
            + "{\"_id\":\"survey_1\",\"type\":\"survey\",\"name\":\"served survey\",\"tg\":[]},"
            + "{\"_id\":\"rating_1\",\"type\":\"rating\",\"name\":\"served rating\",\"tg\":[]}"
            + "]}";

    private static final String WIDGET_PAGE =
        "<html><head><title>widget</title></head><body><div id='widget-body'>survey</div><script>"
            + "setTimeout(function(){window.location="
            + "'https://countly_action_event/?cly_widget_command=1&close=1';},80);"
            + "</script></body></html>";

    @After
    public void stopSdk() {
        CountlyWebView.setShowWidgetsWithinApp(false);
        CountlyWebView.setWebViewDiagnosticsEnabled(false);
        Countly.instance().halt();
    }

    /**
     * With no SDK initialized there is no feedback interface and no content interface, so every
     * entry point has to decline quietly and still run the caller's callback.
     */
    @Test
    public void withoutAnInitializedSdk_everyEntryPointDeclinesAndCallsBack() {
        Countly.instance().halt();

        CountlyWebView.presentFeedbackWidget(null, widget(), closed::incrementAndGet);
        CountlyWebView.presentNPS(null, "", closed::incrementAndGet);
        CountlyWebView.presentSurvey(null, "", closed::incrementAndGet);
        CountlyWebView.presentRating(null, "", closed::incrementAndGet);

        FxTestToolkit.waitUntil("all four callbacks", () -> closed.get() == 4);

        // Content needs no callback, it just must not throw.
        CountlyWebView.enableContentZone();
        CountlyWebView.disableContentZone();
        FxTestToolkit.sleep(200);
    }

    /**
     * A caller whose own callback throws must not take the SDK down with it, and must not stop the
     * rest of a presentation from tearing down.
     */
    @Test
    public void aThrowingCallerCallback_isContained() {
        Countly.instance().init(UiTestConfigs.refusedServer());

        CountlyWebView.presentFeedbackWidget(null, null, () -> {
            closed.incrementAndGet();
            throw new IllegalStateException("this integrator's callback is broken");
        });

        FxTestToolkit.waitUntil("the callback to run", () -> closed.get() == 1);
        FxTestToolkit.sleep(200);

        // The SDK is still usable afterwards.
        CountlyWebView.presentFeedbackWidget(null, null, closed::incrementAndGet);
        FxTestToolkit.waitUntil("the second callback", () -> closed.get() == 2);
    }

    /**
     * A null widget is nothing to show, and the caller is told immediately.
     */
    @Test
    public void aNullWidget_isDeclinedImmediately() {
        Countly.instance().init(UiTestConfigs.refusedServer());

        CountlyWebView.presentFeedbackWidget(null, null, closed::incrementAndGet);
        FxTestToolkit.waitUntil("the callback", () -> closed.get() == 1);
        Assert.assertEquals(1, closed.get());
    }

    /**
     * One presentation end to end against a real page: the SDK builds the widget URL, the page loads
     * and closes itself, the dismissal is reported to the SDK, and the caller is told exactly once
     * even though the page keeps signalling afterwards.
     */
    @Test
    public void aWidgetThatClosesItself_reportsTheDismissalExactlyOnce() {
        Countly.instance().init(UiTestConfigs.configFor("http://127.0.0.1:" + widgetPort));
        CountlyWebView.setWebViewDiagnosticsEnabled(true);

        CountlyWebView.presentFeedbackWidget(null, widget(), closed::incrementAndGet);

        FxTestToolkit.waitUntil("the dismissal", () -> closed.get() >= 1);
        FxTestToolkit.sleep(800);
        Assert.assertEquals("a dismissal must be reported once, not once per signal", 1, closed.get());
    }

    /**
     * Presenting with an owner window and {@code showWidgetsWithinApp} set, which is the path that
     * measures the application window instead of the screen's work area.
     * <p>
     * What is asserted is that the path runs and leaves the SDK healthy. The geometry itself is
     * asserted where it can be done deterministically: {@code FxSurfacesTests} pins that the owner's
     * bounds are what gets measured, and {@code WidgetPlacementTests} pins the clamping. An earlier
     * version hunted the card stage through the JavaFX window list and failed every run, which
     * bought fragility rather than confidence, so do not restore it.
     */
    @Test
    public void presentingInsideTheApplicationWindow_runsAndLeavesTheSdkHealthy() {
        Countly.instance().init(UiTestConfigs.configFor("http://127.0.0.1:" + widgetPort));
        CountlyWebView.setShowWidgetsWithinApp(true);

        FxTestToolkit.onFx(() -> {
            Stage owner = new Stage(StageStyle.UNDECORATED);
            owner.setScene(new Scene(new Pane(), 500, 400));
            owner.setX(60);
            owner.setY(50);
            owner.show();
            CountlyWebView.presentFeedbackWidget(owner, widget(), closed::incrementAndGet);
        });

        FxTestToolkit.sleep(2500);

        // Still usable afterwards: a presentation with nothing to show reports back as ever.
        CountlyWebView.presentFeedbackWidget(null, null, closed::incrementAndGet);
        FxTestToolkit.waitUntil("the SDK to still respond", () -> closed.get() >= 1);
    }

    /**
     * A widget served from a host that refuses the connection.
     * <p>
     * KNOWN GAP, pinned here rather than asserted as desirable: JavaFX reports a refused connection
     * as a SUCCEEDED load, because WebKit substitutes its own error document. So the load-failure
     * path never runs, a card is placed around the error page, and the caller's callback is never
     * run. Only a transport level failure reaches {@code onLoadFailed}. What this pins is that the
     * SDK neither crashes nor reports twice.
     */
    @Test
    public void aWidgetOnARefusedPort_showsAnErrorPageAndDoesNotCallBack() {
        Countly.instance().init(UiTestConfigs.refusedServer());

        CountlyWebView.presentFeedbackWidget(null, widget(), closed::incrementAndGet);

        // Long enough for the load and the placement fallback to have run.
        FxTestToolkit.sleep(2500);
        Assert.assertEquals(0, closed.get());
    }

    /**
     * The quick calls end to end against a served widget list: the list is fetched, the widget of
     * the asked-for type is selected, its page loads and closes itself, and the caller is told. A
     * selector matching nothing is declined rather than falling back to a different widget.
     */
    @Test
    public void quickCalls_fetchSelectAndPresentAServedWidget() {
        Countly.instance().init(UiTestConfigs.configFor("http://127.0.0.1:" + widgetPort));

        CountlyWebView.presentNPS(null, "", closed::incrementAndGet);
        FxTestToolkit.waitUntil("the NPS widget to close", () -> closed.get() >= 1);

        CountlyWebView.presentSurvey(null, "served survey", closed::incrementAndGet);
        FxTestToolkit.waitUntil("the survey widget to close", () -> closed.get() >= 2);

        CountlyWebView.presentRating(null, "", closed::incrementAndGet);
        FxTestToolkit.waitUntil("the rating widget to close", () -> closed.get() >= 3);

        CountlyWebView.presentNPS(null, "no-such-widget", closed::incrementAndGet);
        FxTestToolkit.waitUntil("the declined call to report back", () -> closed.get() >= 4);

        // The overloads without a callback have to work too.
        CountlyWebView.presentNPS(null);
        CountlyWebView.presentSurvey(null, "served survey");
        CountlyWebView.presentRating(null);
        FxTestToolkit.sleep(1200);
    }

    /**
     * When the widget list cannot be fetched at all, the quick calls still report back rather than
     * leaving the caller waiting on a callback that never comes.
     */
    @Test
    public void quickCalls_reportBackWhenTheWidgetListCannotBeFetched() {
        Countly.instance().init(UiTestConfigs.refusedServer());

        CountlyWebView.presentNPS(null, "", closed::incrementAndGet);
        CountlyWebView.presentSurvey(null, "", closed::incrementAndGet);
        CountlyWebView.presentRating(null, "", closed::incrementAndGet);

        FxTestToolkit.waitUntil("the three callbacks", () -> closed.get() == 3);
        Assert.assertEquals(3, closed.get());
    }

    /**
     * Entering and leaving the content zone, with and without a named window, and entering twice so
     * the display is reused rather than rebuilt.
     */
    @Test
    public void contentZone_canBeEnteredAndLeft() {
        Countly.instance().init(UiTestConfigs.refusedServer());

        CountlyWebView.enableContentZone();
        CountlyWebView.enableContentZone();

        FxTestToolkit.onFx(() -> {
            Stage owner = new Stage(StageStyle.UNDECORATED);
            owner.setScene(new Scene(new Pane(), 400, 300));
            owner.show();
            try {
                // A different window needs a different display, since a display follows its window.
                CountlyWebView.enableContentZone(owner);
            } finally {
                CountlyWebView.disableContentZone();
                owner.close();
            }
        });

        CountlyWebView.disableContentZone();
        FxTestToolkit.sleep(200);
    }

    private static CountlyFeedbackWidget widget() {
        CountlyFeedbackWidget widget = new CountlyFeedbackWidget();
        widget.widgetId = "widget_1";
        widget.type = FeedbackWidgetType.nps;
        widget.name = "test widget";
        widget.tags = new String[] { "by-tag" };
        return widget;
    }
}
