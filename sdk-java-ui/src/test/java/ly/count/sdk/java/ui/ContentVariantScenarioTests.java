package ly.count.sdk.java.ui;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import ly.count.sdk.java.internal.ContentUrlHandler;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * The six content variants the Android SDK's harness drives, against a stub server.
 * <p>
 * On a live server a variant is nothing but the geometry the campaign was authored with, routed by
 * device ID; with no campaigns bound to an app key there is nothing to drive at all
 * ({@link ContentScenarioTests} records that). Serving the same payloads locally exercises every
 * step that is actually the SDK's: the queue response, the placement, the page, the links, the
 * events recorded while a block is up, and the close.
 */
@RunWith(JUnit4.class)
public class ContentVariantScenarioTests {

    /** Screen relative geometry per variant, in the shape the server sends. */
    private static final String[][] VARIANTS = {
        // name, x, y, w, h (as a fraction of the surface, resolved below)
        { "sticky_top", "0", "0", "1", "0.18" },
        { "sticky_bottom", "0", "0.82", "1", "0.18" },
        { "modal", "0.2", "0.25", "0.6", "0.5" },
        { "half_modal_top", "0", "0", "1", "0.5" },
        { "half_modal_bottom", "0", "0.5", "1", "0.5" },
        { "fullscreen", "0", "0", "1", "1" },
    };

    private static final long PAINT_TIMEOUT_MS = 25_000;

    private static HttpServer server;
    private static int port;
    private static Stage owner;

    /** The variant the stub is currently serving, and whether it has been taken. */
    private static volatile String servingVariant;
    private static volatile boolean served;
    private static volatile int surfaceWidth = 1470;
    private static volatile int surfaceHeight = 923;

    private ScenarioDriver.LogBuffer log;
    private JavaFxContentDisplay display;

    /**
     * Run again with {@code -Dcountly.ui.scenarioWithinApp=true} to drive the same variants laid out
     * inside the application window. The two modes cannot share a run: the setting is applied once
     * per process on purpose.
     */
    private static final boolean WITHIN_APP = Boolean.getBoolean("countly.ui.scenarioWithinApp");

    @BeforeClass
    public static void startEverything() throws IOException {
        ScenarioDriver.assumeEnabled();
        CountlyWebView.setShowWidgetsWithinApp(WITHIN_APP);
        owner = ScenarioDriver.newApplicationWindow(100, 80, 1000, 700);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();

        // The content queue: one block per variant, then nothing, so the zone does not loop.
        server.createContext("/o/sdk/content", exchange -> {
            String body;
            if (served || servingVariant == null) {
                body = "{\"result\":\"No content block found!\"}";
            } else {
                served = true;
                body = queueResponse(servingVariant);
            }
            respond(exchange, body);
        });

        // The block itself: a page with a link and a close button, like a real content block.
        server.createContext("/content-page", exchange -> respond(exchange,
            "<html><head><title>content</title></head><body style='margin:0'>"
                + "<div id='widget-body' style='background:#fff;height:100%'>"
                + "<h3>Scenario content</h3>"
                + "<a id='go' href='https://countly_action_event/?cly_x_action_event=1&action=link"
                + "&link=https%3A%2F%2Fexample.test%2Fgo&close=0'>Go</a>"
                + "<a id='x' href='https://countly_action_event/?cly_x_action_event=1&close=1'>X</a>"
                + "</div></body></html>"));

        server.createContext("/", exchange -> respond(exchange, "{\"result\":\"Success\"}"));
        server.setExecutor(null);
        server.start();
    }

    @AfterClass
    public static void stopEverything() {
        ScenarioDriver.writeReport("content-variant-scenarios" + (WITHIN_APP ? "-within-app" : ""));
        if (server != null) {
            server.stop(0);
        }
        if (owner != null) {
            FxTestToolkit.onFx(owner::close);
        }
    }

    @After
    public void stopSdk() {
        Countly.instance().halt();
    }

    @Test
    public void allVariants() {
        for (String[] variant : VARIANTS) {
            drive(variant);
        }
    }

    private void drive(String[] spec) {
        String variant = spec[0];
        log = new ScenarioDriver.LogBuffer();
        List<String> handledUrls = Collections.synchronizedList(new ArrayList<>());
        servingVariant = variant;
        served = false;

        Config config = ScenarioDriver.liveConfig(variant + "_stub", log, "http://127.0.0.1:" + port);
        config.content.setContentUrlHandler(url -> {
            handledUrls.add(url);
            return true;
        });
        Countly.instance().init(config);

        FxTestToolkit.onFx(() -> {
            display = new JavaFxContentDisplay(owner);
            WidgetSurface surface = FxSurfaces.surfaceFor(owner);
            surfaceWidth = surface.width;
            surfaceHeight = surface.height;
            Countly.instance().content().setContentDisplay(display);
            Countly.instance().content().enterContentZone();
        });

        boolean painted = log.waitFor("\\[JavaFxContentDisplay\\] show, content painted", PAINT_TIMEOUT_MS);
        if (!painted) {
            ScenarioDriver.record(variant, "the block was shown", ScenarioDriver.Verdict.FAIL,
                "no paint in " + (PAINT_TIMEOUT_MS / 1000) + "s, content logs "
                    + log.countOf("\\[ModuleContent\\]") + ", display logs "
                    + log.countOf("\\[JavaFxContentDisplay\\]"));
            teardown();
            return;
        }

        AtomicReference<WebEngine> engineRef = new AtomicReference<>();
        AtomicReference<int[]> rect = new AtomicReference<>();
        FxTestToolkit.onFx(() -> {
            Stage stage = display.activeStage();
            if (stage == null) {
                return;
            }
            engineRef.set(((WebView) stage.getScene().getRoot()).getEngine());
            rect.set(new int[] { (int) stage.getX(), (int) stage.getY(),
                (int) stage.getWidth(), (int) stage.getHeight() });
        });

        int[] expected = expectedRect(spec);
        int[] actual = rect.get();
        ScenarioDriver.check(variant, "the block is where the server asked",
            actual != null && Math.abs(actual[0] - expected[0]) <= 2 && Math.abs(actual[1] - expected[1]) <= 2
                && Math.abs(actual[2] - expected[2]) <= 2 && Math.abs(actual[3] - expected[3]) <= 2,
            "asked " + describe(expected) + ", got " + (actual == null ? "no window" : describe(actual)));

        if (engineRef.get() == null) {
            teardown();
            return;
        }
        WebEngine engine = engineRef.get();
        ScenarioDriver.check(variant, "the page rendered",
            ScenarioDriver.visibleText(engine).contains("Scenario content"),
            "text [" + ScenarioDriver.visibleText(engine).replace('\n', ' ').trim() + "]");

        // The desktop's answer to the harness's rotate: the window underneath it changes shape.
        FxTestToolkit.onFx(() -> {
            owner.setX(owner.getX() + 120);
            owner.setWidth(owner.getWidth() - 200);
        });
        ScenarioDriver.pause(600);
        AtomicReference<int[]> afterMove = new AtomicReference<>();
        FxTestToolkit.onFx(() -> {
            Stage stage = display.activeStage();
            if (stage != null) {
                afterMove.set(new int[] { (int) stage.getX(), (int) stage.getY(),
                    (int) stage.getWidth(), (int) stage.getHeight() });
            }
        });
        boolean moved = afterMove.get() != null && actual != null
            && (afterMove.get()[0] != actual[0] || afterMove.get()[2] != actual[2]);
        if (WITHIN_APP) {
            // The window is the surface, so the block has to have moved with it.
            ScenarioDriver.check(variant, "the block followed the window", moved,
                describe(actual) + " -> " + (afterMove.get() == null ? "gone" : describe(afterMove.get())));
        } else {
            // The screen is the surface, and it did not move, so the block must not either.
            ScenarioDriver.check(variant, "the block stayed put on the screen",
                afterMove.get() != null && !moved,
                describe(actual) + " -> " + (afterMove.get() == null ? "gone" : describe(afterMove.get())));
        }
        FxTestToolkit.onFx(() -> {
            owner.setX(owner.getX() - 120);
            owner.setWidth(owner.getWidth() + 200);
        });

        Countly.instance().events().recordEvent("scenario_poke_" + variant);
        ScenarioDriver.check(variant, "an app event recorded while the block is up",
            log.waitFor("recordEventInternal.*scenario_poke", 5000),
            String.valueOf(log.find("recordEventInternal.*scenario_poke")));

        // The link, which is what the harness checks by waiting for Chrome.
        ScenarioDriver.check(variant, "the Go link reached the SDK's URL handling",
            ScenarioDriver.click(engine, "#go") && waitUntilNotEmpty(handledUrls),
            handledUrls.toString());

        // The close signal, which has to release the zone as well as the window.
        ScenarioDriver.check(variant, "the X closed the block",
            ScenarioDriver.click(engine, "#x") && waitClosed(),
            "content status logs: " + log.countOf("\\[ModuleContent\\].*(closed|contentShown)"));

        ScenarioDriver.check(variant, "the zone is free to fetch again",
            log.waitFor("\\[ModuleContent\\]", 2000), "content module still logging");

        teardown();
    }

    // ----------------------------------------------------------------- helpers

    private boolean waitUntilNotEmpty(List<String> urls) {
        long until = System.currentTimeMillis() + 4000;
        while (System.currentTimeMillis() < until) {
            if (!urls.isEmpty()) {
                return true;
            }
            ScenarioDriver.pause(100);
        }
        return false;
    }

    private boolean waitClosed() {
        long until = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < until) {
            AtomicReference<Boolean> showing = new AtomicReference<>(false);
            FxTestToolkit.onFx(() -> {
                Stage stage = display.activeStage();
                showing.set(stage != null && stage.isShowing());
            });
            if (!showing.get()) {
                return true;
            }
            ScenarioDriver.pause(150);
        }
        return false;
    }

    private void teardown() {
        try {
            Countly.instance().content().exitContentZone();
        } catch (Throwable ignored) {
            // Nothing to release.
        }
        FxTestToolkit.onFx(() -> {
            Stage stage = display == null ? null : display.activeStage();
            if (stage != null && stage.isShowing()) {
                stage.close();
            }
        });
        Countly.instance().halt();
        ScenarioDriver.pause(200);
    }

    private static int[] expectedRect(String[] spec) {
        int x = (int) (Double.parseDouble(spec[1]) * surfaceWidth);
        int y = (int) (Double.parseDouble(spec[2]) * surfaceHeight);
        int w = (int) (Double.parseDouble(spec[3]) * surfaceWidth);
        int h = (int) (Double.parseDouble(spec[4]) * surfaceHeight);
        AtomicReference<WidgetSurface> surface = new AtomicReference<>();
        FxTestToolkit.onFx(() -> surface.set(FxSurfaces.surfaceFor(owner)));
        return new int[] { surface.get().x + x, surface.get().y + y, w, h };
    }

    private static String describe(int[] rect) {
        return rect[0] + "," + rect[1] + " " + rect[2] + "x" + rect[3];
    }

    private static String queueResponse(String variant) {
        for (String[] spec : VARIANTS) {
            if (!spec[0].equals(variant)) {
                continue;
            }
            int x = (int) (Double.parseDouble(spec[1]) * surfaceWidth);
            int y = (int) (Double.parseDouble(spec[2]) * surfaceHeight);
            int w = (int) (Double.parseDouble(spec[3]) * surfaceWidth);
            int h = (int) (Double.parseDouble(spec[4]) * surfaceHeight);
            String rect = "{\"x\":" + x + ",\"y\":" + y + ",\"w\":" + w + ",\"h\":" + h + "}";
            return "{\"html\":\"http://127.0.0.1:" + port + "/content-page\","
                + "\"geo\":{\"p\":" + rect + ",\"l\":" + rect + "}}";
        }
        return "{\"result\":\"No content block found!\"}";
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type",
            body.startsWith("<") ? "text/html; charset=utf-8" : "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
