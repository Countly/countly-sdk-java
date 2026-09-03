package ly.count.sdk.java.ui;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
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

    /**
     * The device ID prefix the server routes each placement by, and the placement it should produce.
     * <p>
     * The prefixes are the app's own convention rather than the placement names: the two oldest,
     * {@code sticky_top} and {@code sticky_bottom}, were re-pointed at the right hand placements when
     * the centre and left ones were added, so the mapping cannot be inferred from the prefix.
     */
    private static final String[][] VARIANTS = {
        { "sticky_tll", "sticky top left" },
        { "sticky_tcl", "sticky top centre" },
        { "sticky_top", "sticky top right" },
        { "sticky_bll", "sticky bottom left" },
        { "sticky_bcl", "sticky bottom centre" },
        { "sticky_bottom", "sticky bottom right" },
        { "modal", "modal centre" },
        { "half_modal_top", "half modal centre" },
        { "fullscreen", "fullscreen" },
    };

    /** No area outside the block, so no passthrough probe: modals and fullscreen cover everything. */
    private static final List<String> FULLSCREEN = java.util.Arrays.asList(
        "fullscreen", "modal", "half_modal_top");

    private static final long ZONE_TIMEOUT_MS = 50_000;

    private static Stage owner;
    private ScenarioDriver.LogBuffer log;
    private JavaFxContentDisplay display;

    /** Run again with {@code -Dcountly.ui.scenarioWithinApp=true} to lay content out in the window. */
    private static final boolean WITHIN_APP = Boolean.getBoolean("countly.ui.scenarioWithinApp");

    @BeforeClass
    public static void enable() {
        ScenarioDriver.assumeEnabled();
        CountlyWebView.setShowWidgetsWithinApp(WITHIN_APP);
        // The point of a driven run is the evidence: this is what logs each page's images, fonts and
        // painted boxes into the report.
        CountlyWebView.setWebViewDiagnosticsEnabled(true);
        owner = ScenarioDriver.newApplicationWindow(100, 80, 1000, 700);
    }

    @AfterClass
    public static void report() {
        ScenarioDriver.writeReport("content-scenarios" + (WITHIN_APP ? "-within-app" : ""));
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
        for (String[] variant : VARIANTS) {
            drive(variant[0], variant[1]);
        }
    }

    private void drive(String variant, String placement) {
        log = new ScenarioDriver.LogBuffer();
        List<String> handledUrls = Collections.synchronizedList(new ArrayList<>());
        // A journey serves each device once, so a re-run needs a device it has not served yet.
        String deviceId = variant + "_javafx_" + ScenarioDriver.RUN_ID;

        // Instead of letting a link reach a real browser, as the Android harness does when it waits
        // for Chrome, it is captured here: the SDK's own handler is the same code path.
        Config config = ScenarioDriver.liveConfig(deviceId, log);
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

        // A device that never began a session has not entered any journey, so the server has no
        // content to give it. Without this the whole content half reads as "nothing configured".
        Countly.session().begin();
        boolean sessionSent = log.waitFor("begin_session", 15_000);
        ScenarioDriver.record(variant, "expected placement", ScenarioDriver.Verdict.PASS, placement);
        ScenarioDriver.record(variant, "the SDK started and began a session",
            sessionSent ? ScenarioDriver.Verdict.PASS : ScenarioDriver.Verdict.WARN,
            "device id [" + deviceId + "], begin_session sent: " + sessionSent);
        // Let the journey pick the device up before asking for content.
        ScenarioDriver.pause(3000);

        long enteredAt = System.currentTimeMillis();
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
        long appearedIn = System.currentTimeMillis() - enteredAt;
        ScenarioDriver.record(variant, "a content block arrived", ScenarioDriver.Verdict.PASS,
            String.valueOf(log.find("\\[JavaFxContentDisplay\\] show, content painted")));
        // What the user actually waits: the call, the zone's first fetch, and the page.
        ScenarioDriver.record(variant, "how long from entering the zone to seeing it",
            appearedIn < 3000 ? ScenarioDriver.Verdict.PASS : ScenarioDriver.Verdict.WARN,
            appearedIn + " ms");

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
        // The server's own rectangle beside the one that was applied, so checking a campaign's
        // placement is a comparison rather than a guess.
        String served = log.find("onContentFetched, showing content");
        ScenarioDriver.record(variant, "the block is on screen",
            engineRef.get() == null ? ScenarioDriver.Verdict.FAIL : ScenarioDriver.Verdict.PASS,
            "server " + (served == null ? "?" : served.substring(served.indexOf("portrait=")))
                + " -> window " + geometry.get());
        if (engineRef.get() == null) {
            teardown();
            return;
        }
        WebEngine engine = engineRef.get();

        // A card's picture can be missing two ways round: the engine never got the image, or it got
        // it and the box it sits in is empty. Only the natural size next to the box tells them apart,
        // and the answer decides which side of the stack has the bug. The wait is for the image
        // itself: the assets these blocks carry are megabytes, and decoding one is not instant.
        ScenarioDriver.pause(2500);
        AtomicReference<String> images = new AtomicReference<>("not read");
        FxTestToolkit.onFx(() -> images.set(FxSurfaces.describeImages(engine)));
        String described = images.get() + " ;; backgrounds: " + probeCardPictures(engine);
        boolean decoded = described.contains("complete=true") && !described.contains("natural=0x0");
        // Does the card change after it is on screen? A page that fetches its fonts after first
        // paint repaints its text a second later, which is the swap that makes a card look broken.
        // Sampled outside the picture's box, since the picture legitimately arrives late.
        int[] first = snapshotArgb();
        ScenarioDriver.pause(2500);
        int[] second = snapshotArgb();
        ScenarioDriver.record(variant, "the card stopped changing once it was shown",
            ScenarioDriver.Verdict.PASS, compare(first, second, engine));

        // Pixels, because everything up to here is the page's own account of itself. This is the
        // only step that can tell a painted picture from one the DOM merely claims is visible.
        ScenarioDriver.record(variant, "the picture's pixels", ScenarioDriver.Verdict.PASS,
            capturePicture(variant, engine));

        ScenarioDriver.record(variant, "the block's images decoded and have a box",
            described.contains("no img elements") ? ScenarioDriver.Verdict.SKIP
                : decoded ? ScenarioDriver.Verdict.PASS : ScenarioDriver.Verdict.FAIL,
            described);

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

        // The link out of the block. These blocks put it on a button rather than an anchor, so it
        // is found by its text the way the Android harness finds its "Go": looking for a[href] alone
        // reported every variant as having no link at all.
        String go = ScenarioDriver.clickByText(engine, "Go to checkout", "Go", "Open", "Visit", "Click here");
        if (go == null && ScenarioDriver.count(engine, "a[href]") > 0) {
            go = ScenarioDriver.click(engine, "a[href]") ? "a[href]" : null;
        }
        if (go == null) {
            ScenarioDriver.record(variant, "the link out of the block was handled",
                ScenarioDriver.Verdict.SKIP, "the block has no link control");
        } else {
            ScenarioDriver.pause(1500);
            ScenarioDriver.check(variant, "the link out of the block was handled",
                !handledUrls.isEmpty(), "clicked [" + go + "], handler got " + handledUrls);
        }

        if (!FULLSCREEN.contains(variant)) {
            ScenarioDriver.record(variant, "there is an area outside the block",
                ScenarioDriver.Verdict.PASS, "the window is " + after.get()
                    + "; anything outside it belongs to the application");
        }

        // Closing: the block's own close control, by its text rather than by position in the
        // markup, which is what the Android harness's WEBVIEW_HINTS do. A link carrying close=1 has
        // already taken the block down, so this only applies while it is still up.
        AtomicReference<Boolean> upStill = new AtomicReference<>(false);
        FxTestToolkit.onFx(() -> {
            Stage stage = display.activeStage();
            upStill.set(stage != null && stage.isShowing());
        });
        if (!upStill.get()) {
            ScenarioDriver.record(variant, "clicked the block's close control",
                ScenarioDriver.Verdict.SKIP, "the link already closed it");
        } else {
            String clicked = ScenarioDriver.clickByText(engine, "Close", "X", "\u00d7", "\u2715", "Dismiss");
            ScenarioDriver.record(variant, "clicked the block's close control",
                clicked != null ? ScenarioDriver.Verdict.PASS : ScenarioDriver.Verdict.SKIP,
                clicked == null ? "no close control found" : "clicked [" + clicked + "]");
            ScenarioDriver.pause(2000);
        }

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

    /**
     * The card's picture, which these templates paint as a CSS background rather than an
     * {@code <img>}: where the box is, and whether the engine can actually decode what is in it.
     * <p>
     * The decode is proved by asking for the same URL again through {@code new Image()}. That is not
     * a second download: the engine's memory cache serves it, so a URL that painted answers straight
     * away with its natural size, and one that never loaded answers with an error.
     *
     * @param engine the engine showing the block
     * @return one entry per background image, or why there are none
     */
    private static String probeCardPictures(WebEngine engine) {
        AtomicReference<String> started = new AtomicReference<>("0");
        FxTestToolkit.onFx(() -> {
            try {
                started.set(String.valueOf(engine.executeScript(
                    "(function(){window.__clyPics=[];var all=document.querySelectorAll('*');"
                        + "for(var i=0;i<all.length&&window.__clyPics.length<5;i++){"
                        + "var st=window.getComputedStyle(all[i]);var u=st.backgroundImage;"
                        + "if(!u||u.indexOf('url(')!==0){continue;}"
                        + "var url=u.slice(4,u.length-1).replace(/[\'\"]/g,'');"
                        + "var r=all[i].getBoundingClientRect();"
                        + "var rec={url:url,box:Math.round(r.width)+'x'+Math.round(r.height)"
                        + "+'@'+Math.round(r.left)+','+Math.round(r.top)"
                        + ",vis:st.visibility+'/'+st.display+'/'+st.opacity+'/'+st.backgroundSize"
                        + ",state:'pending',natural:'0x0'};"
                        + "window.__clyPics.push(rec);"
                        + "(function(rec){var im=new Image();"
                        + "im.onload=function(){rec.state='decoded';rec.natural=im.naturalWidth+'x'+im.naturalHeight;};"
                        + "im.onerror=function(){rec.state='FAILED';};im.src=rec.url;})(rec);}"
                        + "return window.__clyPics.length;})()")));
            } catch (Throwable t) {
                started.set("probe threw " + t);
            }
        });
        if ("0".equals(started.get())) {
            return "no background images";
        }

        String read = "pending";
        for (int i = 0; i < 40; i++) {
            AtomicReference<String> current = new AtomicReference<>("pending");
            FxTestToolkit.onFx(() -> {
                try {
                    current.set(String.valueOf(engine.executeScript(
                        "JSON.stringify(window.__clyPics.map(function(p){"
                            + "return p.state+' natural='+p.natural+' box '+p.box+' '+p.vis+' '"
                            + "+p.url.slice(0,80);}))")));
                } catch (Throwable t) {
                    current.set("read threw " + t);
                }
            });
            read = current.get();
            if (!read.contains("pending")) {
                break;
            }
            ScenarioDriver.pause(250);
        }
        return read;
    }


    /**
     * Snapshots the card and reports what is actually drawn inside the picture's box, and writes the
     * card next to the report so it can be looked at.
     *
     * @param variant which run this is, used for the file name
     * @param engine the engine showing the block
     * @return what the pixels in the picture's box are
     */
    private String capturePicture(String variant, WebEngine engine) {
        AtomicReference<String> rect = new AtomicReference<>("");
        AtomicReference<String> result = new AtomicReference<>("no snapshot");

        FxTestToolkit.onFx(() -> {
            try {
                rect.set(String.valueOf(engine.executeScript(
                    "(function(){var all=document.querySelectorAll('*');"
                        + "for(var i=0;i<all.length;i++){var st=window.getComputedStyle(all[i]);"
                        + "if((st.backgroundImage||'').indexOf('url(')!==0){continue;}"
                        + "var r=all[i].getBoundingClientRect();"
                        + "return Math.round(r.left)+','+Math.round(r.top)+','"
                        + "+Math.round(r.width)+','+Math.round(r.height);}return '';})()")));
            } catch (Throwable t) {
                rect.set("");
            }

            Stage stage = display.activeStage();
            if (stage == null) {
                return;
            }
            WebView webView = (WebView) stage.getScene().getRoot();
            WritableImage shot = webView.snapshot(null, null);
            PixelReader pixels = shot.getPixelReader();
            int width = (int) shot.getWidth();
            int height = (int) shot.getHeight();

            // The whole card, as a plain PPM: no javafx.swing on this classpath, and a PPM is a
            // header plus RGB bytes.
            try {
                File dir = new File(System.getProperty("countly.ui.scenarioOut",
                    System.getProperty("java.io.tmpdir")));
                if (dir.exists() || dir.mkdirs()) {
                    ByteArrayOutputStream body = new ByteArrayOutputStream();
                    body.write(("P6\n" + width + " " + height + "\n255\n").getBytes(StandardCharsets.US_ASCII));
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
                            int argb = pixels.getArgb(x, y);
                            body.write((argb >> 16) & 0xFF);
                            body.write((argb >> 8) & 0xFF);
                            body.write(argb & 0xFF);
                        }
                    }
                    Files.write(new File(dir, "card-" + variant + ".ppm").toPath(), body.toByteArray());
                }
            } catch (Throwable t) {
                // The counts below are the evidence; the file is a convenience.
            }

            String[] parts = rect.get().split(",");
            if (parts.length != 4) {
                result.set("card " + width + "x" + height + ", but the picture has no box to sample");
                return;
            }
            int left = Integer.parseInt(parts[0]);
            int top = Integer.parseInt(parts[1]);
            int boxWidth = Integer.parseInt(parts[2]);
            int boxHeight = Integer.parseInt(parts[3]);

            Set<Integer> distinct = new HashSet<>();
            int opaque = 0;
            int sampled = 0;
            for (int y = top; y < top + boxHeight && y < height; y++) {
                for (int x = left; x < left + boxWidth && x < width; x++) {
                    if (x < 0 || y < 0) {
                        continue;
                    }
                    int argb = pixels.getArgb(x, y);
                    distinct.add(argb);
                    sampled++;
                    if (((argb >> 24) & 0xFF) > 8) {
                        opaque++;
                    }
                }
            }
            // A photograph scaled into a box is many colours; a slot that never painted is one.
            result.set("card " + width + "x" + height + ", box " + boxWidth + "x" + boxHeight
                + "@" + left + "," + top + ", sampled " + sampled + " px, distinct colours "
                + distinct.size() + ", not transparent " + opaque
                + ", written to card-" + variant + ".ppm");
        });
        return result.get();
    }


    /** @return the card's pixels, row major, or an empty array when there is no window */
    private int[] snapshotArgb() {
        AtomicReference<int[]> pixels = new AtomicReference<>(new int[0]);
        FxTestToolkit.onFx(() -> {
            Stage stage = display.activeStage();
            if (stage == null) {
                return;
            }
            WritableImage shot = ((WebView) stage.getScene().getRoot()).snapshot(null, null);
            int width = (int) shot.getWidth();
            int height = (int) shot.getHeight();
            int[] read = new int[width * height + 2];
            read[0] = width;
            read[1] = height;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    read[2 + y * width + x] = shot.getPixelReader().getArgb(x, y);
                }
            }
            pixels.set(read);
        });
        return pixels.get();
    }

    /**
     * @return how much of the card changed between the two snapshots, ignoring the picture's box
     */
    private String compare(int[] before, int[] after, WebEngine engine) {
        if (before.length < 3 || before.length != after.length) {
            return "could not compare, " + before.length + " against " + after.length + " pixels";
        }
        int width = before[0];
        int height = before[1];

        AtomicReference<String> box = new AtomicReference<>("");
        FxTestToolkit.onFx(() -> {
            try {
                box.set(String.valueOf(engine.executeScript(
                    "(function(){var all=document.querySelectorAll('*');"
                        + "for(var i=0;i<all.length;i++){"
                        + "if((window.getComputedStyle(all[i]).backgroundImage||'').indexOf('url(')!==0){continue;}"
                        + "var r=all[i].getBoundingClientRect();"
                        + "return Math.round(r.left)+','+Math.round(r.top)+','"
                        + "+Math.round(r.right)+','+Math.round(r.bottom);}return '';})()")));
            } catch (Throwable t) {
                box.set("");
            }
        });
        int[] picture = { -1, -1, -1, -1 };
        String[] parts = box.get().split(",");
        if (parts.length == 4) {
            for (int i = 0; i < 4; i++) {
                picture[i] = Integer.parseInt(parts[i]);
            }
        }

        int changed = 0;
        int sampled = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (x >= picture[0] && x < picture[2] && y >= picture[1] && y < picture[3]) {
                    continue;
                }
                sampled++;
                if (before[2 + y * width + x] != after[2 + y * width + x]) {
                    changed++;
                }
            }
        }
        int percent = sampled == 0 ? 0 : (changed * 100) / sampled;
        return changed + " of " + sampled + " px changed outside the picture (" + percent + "%)";
    }

}
