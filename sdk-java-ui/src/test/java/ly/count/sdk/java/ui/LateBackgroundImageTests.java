package ly.count.sdk.java.ui;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import javafx.concurrent.Worker;
import javafx.scene.web.WebView;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * A content card paints its picture as a CSS background, and this engine does not repaint the box
 * when the image arrives after it. The SDK closes that gap, and this is the page that proves it: the
 * image is deliberately served late, which is the case that leaves a card with an empty slot.
 */
@RunWith(JUnit4.class)
public class LateBackgroundImageTests {

    /** A 1x1 red PNG, so the served bytes are a real image the engine will decode. */
    private static final byte[] PNG = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==");

    private static final long SERVER_DELAY_MS = 900;

    private static HttpServer server;
    private static String base;

    @BeforeClass
    public static void startToolkitAndServer() throws IOException {
        FxTestToolkit.assumeToolkitAvailable();
        FxTestToolkit.start();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/late.png", exchange -> {
            try {
                Thread.sleep(SERVER_DELAY_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, PNG.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(PNG);
            }
        });
        server.start();
    }

    @AfterClass
    public static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    public void aBackgroundImageThatArrivesLateIsGivenBackToTheStyleSystem() {
        AtomicReference<WebView> viewRef = new AtomicReference<>();
        AtomicReference<Boolean> loaded = new AtomicReference<>(false);

        FxTestToolkit.onFx(() -> {
            WebView webView = new WebView();
            viewRef.set(webView);
            FxSurfaces.configure(webView.getEngine());
            webView.getEngine().getLoadWorker().stateProperty().addListener((o, old, state) -> {
                if (state == Worker.State.SUCCEEDED) {
                    loaded.set(true);
                    FxSurfaces.repaintBackgroundImagesWhenTheyArrive(webView.getEngine());
                }
            });
            // The slot a card's picture sits in: a sized box whose only content is the background.
            webView.getEngine().loadContent("<html><head><style>"
                + "#slot{width:58px;height:58px;background-image:url('" + base + "/late.png');"
                + "background-size:contain;}</style></head><body><div id='slot'></div></body></html>");
        });

        for (int i = 0; i < 60 && !loaded.get(); i++) {
            ScenarioDriver.pause(100);
        }
        Assert.assertTrue("the page never loaded", loaded.get());

        // The fix re-applies the URL through the element's own style once the image has loaded, so
        // that inline value appearing is the engine having been told to paint the box again.
        String inline = "";
        for (int i = 0; i < 60; i++) {
            AtomicReference<String> read = new AtomicReference<>("");
            FxTestToolkit.onFx(() -> {
                try {
                    read.set(String.valueOf(viewRef.get().getEngine().executeScript(
                        "document.getElementById('slot').style.backgroundImage")));
                } catch (Throwable t) {
                    read.set("");
                }
            });
            inline = read.get();
            if (inline.contains("late.png")) {
                break;
            }
            ScenarioDriver.pause(100);
        }
        Assert.assertTrue("the late image was never given back to the style system, was [" + inline + "]",
            inline.contains("late.png"));
    }

    @Test
    public void installingItTwiceOnOnePageDoesNothingTheSecondTime() {
        AtomicReference<String> first = new AtomicReference<>("");
        AtomicReference<String> second = new AtomicReference<>("");

        FxTestToolkit.onFx(() -> {
            WebView webView = new WebView();
            FxSurfaces.configure(webView.getEngine());
            webView.getEngine().loadContent("<html><body>x</body></html>");
        });
        ScenarioDriver.pause(500);

        AtomicReference<WebView> viewRef = new AtomicReference<>();
        FxTestToolkit.onFx(() -> {
            WebView webView = new WebView();
            viewRef.set(webView);
            FxSurfaces.configure(webView.getEngine());
            webView.getEngine().loadContent("<html><body><div id='slot'></div></body></html>");
        });
        ScenarioDriver.pause(800);

        FxTestToolkit.onFx(() -> {
            first.set(String.valueOf(viewRef.get().getEngine().executeScript(
                "window.__clyBackgroundFix ? 'was installed' : 'not installed'")));
            FxSurfaces.repaintBackgroundImagesWhenTheyArrive(viewRef.get().getEngine());
            FxSurfaces.repaintBackgroundImagesWhenTheyArrive(viewRef.get().getEngine());
            second.set(String.valueOf(viewRef.get().getEngine().executeScript(
                "window.__clyBackgroundFix ? 'was installed' : 'not installed'")));
        });

        Assert.assertEquals("not installed", first.get());
        Assert.assertEquals("was installed", second.get());
    }
}
