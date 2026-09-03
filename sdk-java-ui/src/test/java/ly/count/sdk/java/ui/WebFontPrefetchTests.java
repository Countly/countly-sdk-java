package ly.count.sdk.java.ui;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import javafx.scene.web.WebView;
import javafx.util.Duration;
import ly.count.sdk.java.Countly;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * The web font head start: what a widget page's fonts are worth remembering, and that the warm-up
 * page actually fetches them.
 */
@RunWith(JUnit4.class)
public class WebFontPrefetchTests {

    private static HttpServer fontServer;
    private static String base;

    /** Every path the served page's fonts were asked for, in order. */
    private static final List<String> requested = new CopyOnWriteArrayList<>();

    @BeforeClass
    public static void startToolkitAndServer() throws IOException {
        FxTestToolkit.assumeToolkitAvailable();
        FxTestToolkit.start();

        fontServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + fontServer.getAddress().getPort();
        fontServer.createContext("/", exchange -> {
            requested.add(exchange.getRequestURI().getPath());
            // Not a real font: the engine only has to try to fetch it for the point to be made.
            byte[] body = new byte[64];
            exchange.getResponseHeaders().add("Content-Type", "font/woff");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        fontServer.start();
    }

    @AfterClass
    public static void stopServer() {
        if (fontServer != null) {
            fontServer.stop(0);
        }
    }

    @Before
    public void startSdk() {
        // The harvest waits for the page to settle before reading which faces were used; a short
        // wait here keeps each test's write inside its own test.
        WebFontPrefetch.settle = Duration.millis(150);
        Countly.instance().init(UiTestConfigs.refusedServer());
        WebFontPrefetch.forget();
        requested.clear();
    }

    @After
    public void stopSdk() {
        // Let a harvest already in flight land, so it cannot write into the next test.
        ScenarioDriver.pause(400);
        WebFontPrefetch.forget();
        Countly.instance().halt();
    }

    @Test
    public void aPagesFontsAreRememberedForTheNextRun() {
        rememberFontsOf("@font-face{font-family:'Inter';src:url('" + base + "/inter.woff2') format('woff2'),"
            + "url('" + base + "/inter.woff') format('woff'),"
            + "url('" + base + "/inter.ttf') format('truetype');}");

        String page = awaitWarmupPage();
        Assert.assertTrue(page, page.contains(base + "/inter.woff"));
        // The one this engine fetches and then fails to decode is not worth fetching, and neither is
        // the ttf twin of a face already covered by its woff.
        Assert.assertFalse(page, page.contains("inter.woff2"));
        Assert.assertFalse(page, page.contains("inter.ttf"));
        Assert.assertEquals(1, WebFontPrefetch.rememberedCount());
    }

    @Test
    public void theWarmupPageFetchesWhatWasRemembered() {
        rememberFontsOf("@font-face{font-family:'Inter';src:url('" + base + "/wanted.woff') format('woff');}");
        String page = awaitWarmupPage();
        requested.clear();

        AtomicReference<WebView> viewRef = new AtomicReference<>();
        FxTestToolkit.onFx(() -> {
            WebView webView = new WebView();
            viewRef.set(webView);
            FxSurfaces.configure(webView.getEngine());
            webView.getEngine().loadContent(page);
        });

        for (int i = 0; i < 40 && !requested.contains("/wanted.woff"); i++) {
            ScenarioDriver.pause(100);
        }
        // A view with no scene lays nothing out, so this only passes because the page asks for each
        // face itself. That is the whole mechanism.
        Assert.assertTrue("fetched " + requested, requested.contains("/wanted.woff"));
    }

    @Test
    public void harvestingAlsoWarmsTheFacesInThisRun() {
        // Nothing on this page uses the face, so the page itself never asks for it. A request
        // arriving anyway is the warm-up view fetching it, which is what makes the second card of a
        // run fast even when the first was closed mid-download.
        rememberFontsOf("@font-face{font-family:'Inter';src:url('" + base + "/thisrun.woff') format('woff');}");

        for (int i = 0; i < 60 && !requested.contains("/thisrun.woff"); i++) {
            ScenarioDriver.pause(100);
        }
        Assert.assertTrue("fetched " + requested, requested.contains("/thisrun.woff"));
    }

    @Test
    public void onlyAbsoluteHttpFontUrlsAreRemembered() {
        rememberFontsOf("@font-face{font-family:'A';src:url('data:font/woff;base64,AAAA') format('woff');}"
            + "@font-face{font-family:'B';src:url('/relative/only.woff') format('woff');}"
            + "@font-face{font-family:'C';src:url('" + base + "/styles.css') format('woff');}"
            + "@font-face{font-family:'D';src:url('" + base + "/kept.woff') format('woff');}");

        String page = awaitWarmupPage();
        // A page-relative URL is resolved against the document. This probe page has no base URI of
        // its own, so there is nothing to resolve against and it is dropped; a widget page served
        // over HTTP keeps it, as an absolute URL.
        Assert.assertFalse(page, page.contains("/relative/only.woff"));
        Assert.assertTrue(page, page.contains("/kept.woff"));
        Assert.assertFalse(page, page.contains("data:font"));
        Assert.assertFalse(page, page.contains("styles.css"));
    }

    @Test
    public void aPageWithNoWebFontsRemembersNothing() {
        rememberFontsOf("body{font-family:sans-serif}");
        ScenarioDriver.pause(300);
        Assert.assertEquals(0, WebFontPrefetch.rememberedCount());
        Assert.assertNull(WebFontPrefetch.warmupPage());
    }

    @Test
    public void withoutTheSdkNothingIsRememberedAndNothingThrows() {
        Countly.instance().halt();
        rememberFontsOf("@font-face{font-family:'Inter';src:url('" + base + "/inter.woff') format('woff');}");
        ScenarioDriver.pause(300);
        Assert.assertEquals(0, WebFontPrefetch.rememberedCount());
        Assert.assertNull(WebFontPrefetch.warmupPage());
    }

    @Test
    public void aPageWithTooManyFacesIsCapped() {
        StringBuilder css = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            css.append("@font-face{font-family:'F").append(i).append("';src:url('")
                .append(base).append("/face").append(i).append(".woff') format('woff');}");
        }
        rememberFontsOf(css.toString());

        awaitWarmupPage();
        Assert.assertEquals(8, WebFontPrefetch.rememberedCount());
    }

    /** Loads a page carrying the given stylesheet and harvests it, the way a widget page is harvested. */
    private void rememberFontsOf(String css) {
        AtomicReference<WebView> viewRef = new AtomicReference<>();
        AtomicReference<Boolean> loaded = new AtomicReference<>(false);
        FxTestToolkit.onFx(() -> {
            WebView webView = new WebView();
            viewRef.set(webView);
            FxSurfaces.configure(webView.getEngine());
            webView.getEngine().getLoadWorker().stateProperty().addListener((o, old, state) -> {
                if (state == javafx.concurrent.Worker.State.SUCCEEDED) {
                    loaded.set(true);
                }
            });
            webView.getEngine().loadContent("<html><head><style>" + css + "</style></head><body>x</body></html>");
        });

        for (int i = 0; i < 60 && !loaded.get(); i++) {
            ScenarioDriver.pause(100);
        }
        Assert.assertTrue("the probe page never loaded", loaded.get());
        FxTestToolkit.onFx(() -> WebFontPrefetch.remember(viewRef.get().getEngine()));
    }

    /** The list is written off the application thread, so give it a moment to land. */
    private String awaitWarmupPage() {
        for (int i = 0; i < 40; i++) {
            String page = WebFontPrefetch.warmupPage();
            if (page != null) {
                return page;
            }
            ScenarioDriver.pause(100);
        }
        Assert.fail("nothing was remembered");
        return Collections.<String>emptyList().toString();
    }
}
