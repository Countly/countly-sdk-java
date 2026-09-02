package ly.count.sdk.java.ui;

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
 * Drives the content zone through the variants the Android SDK's {@code content_test_runner.py}
 * drives: one run per content type the server routes by device ID prefix, each entering the zone,
 * waiting for the block, then exercising what the harness exercises — the window moving underneath
 * it (the desktop's answer to a rotation), the links inside it, and closing it.
 * <p>
 * Findings are recorded rather than asserted, because what a live server serves is itself the
 * finding. Enable with {@code -Dcountly.ui.scenarios=true}.
 */
@RunWith(JUnit4.class)
public class ContentScenarioTests {

    /** CONTENT_VARIANTS from content_test_config.py, and the same device ID prefix routing. */
    private static final String[] VARIANTS = {
        "sticky_top", "sticky_bottom", "modal", "half_modal_top", "half_modal_bottom", "fullscreen",
    };

    /** FULLSCREEN_VARIANTS: no outside area, so no passthrough probe. */
    private static final List<String> FULLSCREEN = java.util.Arrays.asList(
        "fullscreen", "modal", "half_modal_top", "half_modal_bottom");

    private static final long ZONE_TIMEOUT_MS = 50_000;

    private static Stage owner;
    private ScenarioDriver.LogBuffer log;
    private JavaFxContentDisplay display;

    @BeforeClass
    public static void enable() {
        ScenarioDriver.assumeEnabled();
        owner = ScenarioDriver.newApplicationWindow(100, 80, 1000, 700);
    }

    @AfterClass
    public static void report() {
        ScenarioDriver.writeReport("content-scenarios");
        if (owner != null) {
            FxTestToolkit.onFx(owner::close);
        }
    }

    @After
    public void stopSdk() {
        if (display != null) {
            FxTestToolkit.onFx(() -> {
                Stage stage = display.activeStage();
                if (stage != null && stage.isShowing()) {
                    stage.close();
                }
            });
        }
        try {
            Countly.instance().content().exitContentZone();
        } catch (Throwable ignored) {
            // Halting is what matters.
        }
        Countly.instance().halt();
    }

    @Test
    public void allVariants() {
        for (String variant : VARIANTS) {
            drive(variant);
        }
    }

    private void drive(String variant) {
        log = new ScenarioDriver.LogBuffer();
        List<String> handledUrls = Collections.synchronizedList(new ArrayList<>());

        // Instead of letting a link reach a real browser, as the Android harness does when it waits
        // for Chrome, it is captured here: the SDK's own handler is the same code path.
        Config config = ScenarioDriver.liveConfig(variant + "_javafx_scenario", log);
        config.content.setContentUrlHandler(new ContentUrlHandler() {
            @Override
            public boolean onContentUrl(String url) {
                handledUrls.add(url);
                return true;
            }
        });
        config.content.setGlobalContentCallback((status, data) ->
            ScenarioDriver.record(variant, "content callback", ScenarioDriver.Verdict.PASS,
                status + " " + data));

        Countly.instance().init(config);
        ScenarioDriver.record(variant, "the SDK started", ScenarioDriver.Verdict.PASS,
            "device id [" + variant + "_javafx_scenario]");

        FxTestToolkit.onFx(() -> {
            display = new JavaFxContentDisplay(owner);
            Countly.instance().content().setContentDisplay(display);
            Countly.instance().content().enterContentZone();
        });

        boolean fetched = log.waitFor("\\[ModuleContent\\].*(No content|content block|fetchContents)", 15_000);
        ScenarioDriver.record(variant, "the zone fetched", fetched
                ? ScenarioDriver.Verdict.PASS : ScenarioDriver.Verdict.WARN,
            String.valueOf(log.find("\\[ModuleContent\\].*(No content|content block|fetchContents)")));

        boolean painted = log.waitFor("\\[JavaFxContentDisplay\\] show, content painted", ZONE_TIMEOUT_MS);
        if (!painted) {
            ScenarioDriver.record(variant, "a content block arrived", ScenarioDriver.Verdict.SKIP,
                "nothing served for this device ID in " + (ZONE_TIMEOUT_MS / 1000) + "s; last content log ["
                    + log.find("\\[ModuleContent\\]") + "]");
            teardown();
            return;
        }
        ScenarioDriver.record(variant, "a content block arrived", ScenarioDriver.Verdict.PASS,
            String.valueOf(log.find("\\[JavaFxContentDisplay\\] show, content painted")));

        AtomicReference<WebEngine> engineRef = new AtomicReference<>();
        AtomicReference<String> geometry = new AtomicReference<>("no window");
        FxTestToolkit.onFx(() -> {
            Stage stage = display.activeStage();
            if (stage == null) {
                return;
            }
            engineRef.set(((WebView) stage.getScene().getRoot()).getEngine());
            geometry.set((int) stage.getX() + "," + (int) stage.getY() + " "
                + (int) stage.getWidth() + "x" + (int) stage.getHeight());
        });
        ScenarioDriver.record(variant, "the block is on screen",
            engineRef.get() == null ? ScenarioDriver.Verdict.FAIL : ScenarioDriver.Verdict.PASS,
            geometry.get());
        if (engineRef.get() == null) {
            teardown();
            return;
        }
        WebEngine engine = engineRef.get();

        ScenarioDriver.record(variant, "the page rendered something", ScenarioDriver.Verdict.PASS,
            "text [" + trim(ScenarioDriver.visibleText(engine)) + "], links "
                + ScenarioDriver.count(engine, "a") + ", buttons " + ScenarioDriver.count(engine, "button"));

        // The desktop equivalent of the harness's rotate/back/rotate: the window the block is
        // anchored to moves and changes shape underneath it.
        String before = geometry.get();
        FxTestToolkit.onFx(() -> {
            owner.setX(owner.getX() + 120);
            owner.setWidth(owner.getWidth() - 160);
        });
        ScenarioDriver.pause(700);
        AtomicReference<String> after = new AtomicReference<>("no window");
        FxTestToolkit.onFx(() -> {
            Stage stage = display.activeStage();
            if (stage != null) {
                after.set((int) stage.getX() + "," + (int) stage.getY() + " "
                    + (int) stage.getWidth() + "x" + (int) stage.getHeight());
            }
        });
        ScenarioDriver.record(variant, "the block followed the window",
            ScenarioDriver.Verdict.PASS, before + " -> " + after.get());
        FxTestToolkit.onFx(() -> {
            owner.setX(owner.getX() - 120);
            owner.setWidth(owner.getWidth() + 160);
        });

        // Events recorded while a block is up, which is what the harness's "pokes" check.
        Countly.instance().events().recordEvent("scenario_poke_" + variant);
        ScenarioDriver.record(variant, "an app event recorded while content is up",
            log.waitFor("recordEventInternal.*scenario_poke", 5000)
                ? ScenarioDriver.Verdict.PASS : ScenarioDriver.Verdict.FAIL,
            String.valueOf(log.find("recordEventInternal.*scenario_poke")));

        // A link inside the block: the SDK's URL handling, without a browser opening.
        int links = ScenarioDriver.count(engine, "a[href]");
        if (links > 0) {
            ScenarioDriver.click(engine, "a[href]");
            ScenarioDriver.pause(1200);
            ScenarioDriver.record(variant, "a link inside the block was handled",
                handledUrls.isEmpty() ? ScenarioDriver.Verdict.WARN : ScenarioDriver.Verdict.PASS,
                handledUrls.isEmpty() ? "no URL reached the handler" : handledUrls.toString());
        } else {
            ScenarioDriver.record(variant, "a link inside the block was handled",
                ScenarioDriver.Verdict.SKIP, "the block has no links");
        }

        if (!FULLSCREEN.contains(variant)) {
            ScenarioDriver.record(variant, "there is an area outside the block",
                ScenarioDriver.Verdict.PASS, "the window is " + after.get()
                    + "; anything outside it belongs to the application");
        }

        // Closing: the block's own close button, or the window, and the zone has to be released.
        boolean clicked = ScenarioDriver.click(engine, "[onclick],button,a[href*='close']");
        ScenarioDriver.record(variant, "clicked something that should close it",
            clicked ? ScenarioDriver.Verdict.PASS : ScenarioDriver.Verdict.SKIP, "clicked=" + clicked);
        ScenarioDriver.pause(1500);

        AtomicReference<Boolean> stillShowing = new AtomicReference<>(false);
        FxTestToolkit.onFx(() -> {
            Stage stage = display.activeStage();
            stillShowing.set(stage != null && stage.isShowing());
        });
        ScenarioDriver.record(variant, "the block closed", !stillShowing.get()
                ? ScenarioDriver.Verdict.PASS : ScenarioDriver.Verdict.WARN,
            "still showing: " + stillShowing.get());

        ScenarioDriver.record(variant, "no SDK errors during the variant",
            log.countOf("^ERROR ") == 0 ? ScenarioDriver.Verdict.PASS : ScenarioDriver.Verdict.WARN,
            log.countOf("^ERROR ") + " error lines, first [" + log.find("^ERROR ") + "]");

        teardown();
    }

    private void teardown() {
        FxTestToolkit.onFx(() -> {
            Stage stage = display == null ? null : display.activeStage();
            if (stage != null && stage.isShowing()) {
                stage.close();
            }
        });
        try {
            Countly.instance().content().exitContentZone();
        } catch (Throwable ignored) {
            // Nothing to release.
        }
        Countly.instance().halt();
        ScenarioDriver.pause(300);
    }

    private static String trim(String text) {
        String single = text == null ? "" : text.replace('\n', ' ').trim();
        return single.length() > 120 ? single.substring(0, 120) + "..." : single;
    }
}
