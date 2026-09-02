package ly.count.sdk.java.ui;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.function.BooleanSupplier;
import javafx.animation.PauseTransition;
import javafx.geometry.Rectangle2D;
import javafx.scene.web.WebEngine;
import javafx.scene.paint.Color;
import javafx.scene.web.WebView;
import javafx.stage.Screen;
import javafx.stage.Window;
import ly.count.sdk.java.internal.WidgetActionParser;
import javafx.util.Duration;

/**
 * Screen geometry and web view defaults shared by the widget and content displays.
 */
final class FxSurfaces {

    /**
     * A fallback font for the pages the SDK loads, plus emoji coverage.
     * <p>
     * Scoped to {@code html} and {@code body} only, and therefore <em>inherited</em>. That is the
     * whole trick: an inherited declaration is weaker than any direct one, so an element the page
     * styles itself keeps the page's font (the templates load Inter and Lato through @font-face and
     * those do load here), while text the page leaves unstyled inherits this instead of falling all
     * the way back to the engine's serif default. An earlier version forced this on every element,
     * which replaced the templates' own typography with Helvetica.
     * <p>
     * {@code !important} is still needed, because losing to an inherited author rule on {@code body}
     * is exactly the case that produced serif text.
     * <p>
     * The emoji families come <em>before</em> the generic {@code sans-serif}: a generic always
     * matches, so anything after it is never consulted, which is why the templates' emoji rendered
     * as nothing.
     */
    private static final String USER_CSS =
        "html,body{"
            + "font-family:\"Helvetica Neue\",Helvetica,\"Segoe UI\",Roboto,Arial,"
            + "\"Apple Color Emoji\",\"Segoe UI Emoji\",\"Noto Color Emoji\",sans-serif !important;"
            + "-webkit-font-smoothing:antialiased;}";

    private static final String USER_STYLESHEET =
        "data:text/css;charset=utf-8;base64,"
            + Base64.getEncoder().encodeToString(USER_CSS.getBytes(StandardCharsets.UTF_8));

    /**
     * How long after a page loads to look at what it actually managed to fetch. Images are still
     * arriving when the document reports itself loaded, so an immediate probe would report false
     * failures.
     */
    private static final Duration DIAGNOSTICS_DELAY = Duration.millis(1500);

    /**
     * Reports what the page could and could not fetch. Written defensively because the WebKit build
     * JavaFX bundles is old: the CSS Font Loading API may be missing entirely, and a throwing probe
     * must never take a content block down with it.
     */
    private static final String DIAGNOSTICS_SCRIPT =
        "(function(){try{"
            + "var out=[],imgs=document.getElementsByTagName('img'),failed=0,pending=0;"
            + "for(var i=0;i<imgs.length;i++){var im=imgs[i],src=(im.currentSrc||im.src||'?');"
            + "if(im.complete&&im.naturalWidth===0){failed++;out.push('IMAGE FAILED '+src);}"
            + "else if(!im.complete){pending++;out.push('image still loading '+src);}}"
            + "out.unshift('images total='+imgs.length+' failed='+failed+' pending='+pending);"
            + "out.push('stylesheets='+(document.styleSheets?document.styleSheets.length:-1));"
            + "if(document.fonts&&document.fonts.status){out.push('fonts status='+document.fonts.status+"
            + "' loaded='+(document.fonts.size!==undefined?document.fonts.size:'?'));}"
            + "else{out.push('fonts: CSS Font Loading API not available in this WebKit');}"
            + "var b=document.body;if(b){var cs=window.getComputedStyle(b);"
            + "out.push('body font='+cs.fontFamily+' size='+cs.fontSize);"
            + "out.push('body background='+cs.backgroundColor);}"
            + "var h=document.documentElement;if(h){out.push('html background='"
            + "+window.getComputedStyle(h).backgroundColor);}"
            + "try{var faces=[];for(var s=0;s<document.styleSheets.length;s++){var rs=document.styleSheets[s].cssRules;"
            + "if(!rs){continue;}for(var r=0;r<rs.length;r++){if(rs[r].type===5){faces.push(rs[r].style.fontFamily+' <- '+rs[r].style.src);}}}"
            + "out.push('@font-face: '+(faces.length?faces.join(' ;; '):'none'));}"
            + "catch(e){out.push('@font-face: unreadable ('+e+')');}"
            + "return out.join(' | ');"
            + "}catch(e){return 'diagnostics failed: '+e;}})()";

    /**
     * Keeps the WebKit stack alive once it has been started. The very first WebView in a process
     * pays for initialising all of WebKit, which is the bulk of the delay between the server saying
     * "show this" and something appearing.
     */
    private static WebView warmView;

    private static volatile boolean diagnosticsEnabled = false;
    private static boolean signalFilterInstalled = false;

    private FxSurfaces() {
    }

    /**
     * @param enabled whether to log what each page managed to fetch
     */
    static void setDiagnosticsEnabled(boolean enabled) {
        diagnosticsEnabled = enabled;
    }

    static boolean isDiagnosticsEnabled() {
        return diagnosticsEnabled;
    }

    /**
     * Measures the rectangle the page actually painted, in CSS pixels.
     * <p>
     * Used to tell a real widget page from whatever the browser substituted when the real one could
     * not be fetched: JavaFX reports an HTTP level failure as a SUCCEEDED load carrying an error
     * document, and an error document paints no card.
     *
     * @param engine the engine to measure
     * @return {@code left,top,width,height}, or {@code null} when the page has no card element at
     *     all. Width and height may be zero when the window has not been sized yet.
     */
    static int[] measurePaintedContent(WebEngine engine) {
        try {
            Object result = engine.executeScript(
                "(function(){try{"
                    + "var e=document.getElementById('widget-body');"
                    + "if(!e){var kids=document.body?document.body.children:[];"
                    + "for(var i=0;i<kids.length;i++){var s=window.getComputedStyle(kids[i]);"
                    + "var bg=s.backgroundColor;"
                    + "if(bg&&bg!=='transparent'&&bg.indexOf('rgba(0, 0, 0, 0)')<0){e=kids[i];break;}}}"
                    + "if(!e){return '';}"
                    // Zero sizes are reported rather than rejected: the window is still 1x1 before
                    // it has been placed, so a real card measures zero here. The absence of the
                    // element is what says "this is not a widget page", not its size.
                    + "var b=e.getBoundingClientRect();"
                    + "return [Math.round(b.left),Math.round(b.top),Math.round(b.width),Math.round(b.height)].join(',');"
                    + "}catch(err){return '';}})()");
            if (!(result instanceof String) || ((String) result).isEmpty()) {
                return null;
            }
            String[] parts = ((String) result).split(",");
            if (parts.length != 4) {
                return null;
            }
            return new int[] {
                Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]), Integer.parseInt(parts[3])
            };
        } catch (Throwable t) {
            UiLog.w("[FxSurfaces] measurePaintedContent, could not measure the page, [" + t + "]");
            return null;
        }
    }

    /**
     * Makes the web view's own page backdrop fully transparent, so only what the page paints is
     * visible and the application shows through everywhere else.
     * <p>
     * This is public JavaFX API as of version 20 ({@code WebView.setPageFill}). Before that the only
     * lever was a {@code com.sun.webkit} internal reached by reflection, which needed
     * {@code --add-exports} from every integrator and, on this pipeline, rendered the empty area
     * opaque black instead of see-through. That is why this module requires JavaFX 21.
     * <p>
     * The page still wins: if the document sets its own background colour, that is what shows.
     *
     * @param webView the view whose backdrop to clear
     */
    static void makePageBackgroundTransparent(WebView webView) {
        try {
            webView.setPageFill(Color.TRANSPARENT);
        } catch (Throwable t) {
            // Cosmetic only: the overlay keeps an opaque backdrop.
            UiLog.w("[FxSurfaces] makePageBackgroundTransparent, could not clear the backdrop, [" + t + "]");
        }
    }

    /**
     * The JavaFX property that chooses between the HTTP/2 loader and the older one.
     * Read once, in {@code NetworkContext}'s static initialiser, so it has to be set before the
     * first web view exists. Found by reading {@code NetworkContext}'s bytecode; it is not documented.
     */
    private static final String USE_HTTP2_LOADER = "com.sun.webkit.useHTTP2Loader";

    /**
     * Opts out of JavaFX's HTTP/2 loader before any page is loaded.
     * <p>
     * A widget or content block talks to the SDK by navigating to {@code https://countly_action_event/…},
     * a host that deliberately does not resolve: the navigation is a message, not a destination. The
     * HTTP/2 loader routes through {@code java.net.http}, whose request builder rejects that URI and
     * throws {@code IllegalArgumentException} on the application thread, before the SDK's cancel can
     * take effect. The older loader fails such a load quietly, which is what the protocol relies on
     * and what JavaFX 17 did.
     * <p>
     * Only set when the integrator has not chosen for themselves. HTTP/1.1 versus HTTP/2 makes no
     * practical difference for loading a single widget page.
     */
    private static void preferTolerantNetworkLoader() {
        if (System.getProperty(USE_HTTP2_LOADER) != null) {
            return;
        }
        try {
            System.setProperty(USE_HTTP2_LOADER, "false");
        } catch (Throwable t) {
            // A restrictive security manager: the signalling URL will then log a stack trace.
            UiLog.w("[FxSurfaces] preferTolerantNetworkLoader, could not set " + USE_HTTP2_LOADER
                + ", signalling URLs may log a stack trace, [" + t + "]");
        }
    }

    /**
     * Stops JavaFX's network stack from reporting the SDK's own signalling URLs as fatal.
     * <p>
     * A widget or content block talks to the SDK by navigating to {@code https://countly_action_event/…}.
     * That host does not resolve, which is the point: the navigation is a message, not a destination,
     * and it is cancelled as soon as it is seen. JavaFX 21 routes loads through {@code java.net.http},
     * whose builder rejects the URI outright and throws {@code IllegalArgumentException} on the
     * application thread before the cancel can take effect. JavaFX 17's older loader simply failed
     * the load quietly.
     * <p>
     * There is no navigation veto in the {@code WebEngine} API to prevent the dispatch, so the
     * exception is filtered instead: only this exact case, only on the JavaFX thread, and any other
     * uncaught exception is passed to the handler that was already installed.
     */
    static void installSignalUrlExceptionFilter() {
        if (signalFilterInstalled) {
            return;
        }
        signalFilterInstalled = true;

        Thread fxThread = Thread.currentThread();
        Thread.UncaughtExceptionHandler previous = fxThread.getUncaughtExceptionHandler();
        fxThread.setUncaughtExceptionHandler((thread, thrown) -> {
            if (isSignalUrlRejection(thrown)) {
                UiLog.d("[FxSurfaces] the network stack refused a signalling URL, which is expected: "
                    + thrown.getMessage());
                return;
            }
            if (previous != null) {
                previous.uncaughtException(thread, thrown);
            }
        });
    }

    private static boolean isSignalUrlRejection(Throwable thrown) {
        return thrown instanceof IllegalArgumentException
            && thrown.getMessage() != null
            && thrown.getMessage().contains(WidgetActionParser.ACTION_HOST);
    }

    /**
     * Starts WebKit ahead of time, so the first widget or content block does not pay for it. Cheap
     * to call more than once. Must be called on the JavaFX application thread.
     */
    static void prewarm() {
        if (warmView != null) {
            return;
        }
        // Before the first WebView: NetworkContext reads the loader choice in its static initialiser.
        preferTolerantNetworkLoader();
        try {
            long started = System.currentTimeMillis();
            warmView = new WebView();
            warmView.getEngine().load("about:blank");
            UiLog.d("[FxSurfaces] prewarm, started WebKit in [" + (System.currentTimeMillis() - started) + "] ms");
        } catch (Throwable t) {
            warmView = null;
            UiLog.w("[FxSurfaces] prewarm, could not start WebKit ahead of time, [" + t + "]");
        }
    }

    /**
     * Logs the user agent, which carries the bundled WebKit version, and what the page fetched.
     * Only runs when diagnostics are switched on, so a customer's page is never scripted by default.
     *
     * @param engine the engine whose page to inspect
     * @param label which display is asking
     */
    static void logPageDiagnostics(WebEngine engine, String label) {
        logPageDiagnostics(engine, label, () -> true);
    }

    /**
     * @param engine the engine whose page to inspect
     * @param label which display is asking
     * @param stillShowing whether the page is still the one that was loaded. The delayed sample is
     *     skipped when it is not: a content block the user closed quickly has already been navigated
     *     away to a blank page, and probing that reports an empty document rather than the content.
     */
    static void logPageDiagnostics(WebEngine engine, String label, BooleanSupplier stillShowing) {
        if (!diagnosticsEnabled) {
            return;
        }

        UiLog.d("[" + label + "] user agent: " + engine.getUserAgent());
        // Sampled straight away, so a page that gets dismissed a moment later is still described.
        // Images may legitimately still be arriving here; the probe reports those as pending.
        sample(engine, label, "on load");

        PauseTransition wait = new PauseTransition(DIAGNOSTICS_DELAY);
        wait.setOnFinished(event -> {
            if (!stillShowing.getAsBoolean()) {
                UiLog.d("[" + label + "] page went away before the second diagnostics sample");
                return;
            }
            sample(engine, label, "settled");
        });
        wait.play();
    }

    private static void sample(WebEngine engine, String label, String when) {
        try {
            Object result = engine.executeScript(DIAGNOSTICS_SCRIPT);
            UiLog.d("[" + label + "] page resources (" + when + "): " + result);
        } catch (Throwable t) {
            UiLog.w("[" + label + "] could not read the page diagnostics (" + when + "), [" + t + "]");
        }
    }

    /**
     * Applies the SDK's web view defaults. Called before navigating.
     *
     * @param engine the engine to configure
     */
    static void configure(WebEngine engine) {
        preferTolerantNetworkLoader();
        installSignalUrlExceptionFilter();
        engine.setJavaScriptEnabled(true);
        try {
            engine.setUserStyleSheetLocation(USER_STYLESHEET);
        } catch (Throwable t) {
            // Cosmetic only: the page still renders with the engine's own defaults.
            UiLog.w("[FxSurfaces] configure, could not apply the fallback font, [" + t + "]");
        }

        engine.getLoadWorker().exceptionProperty().addListener((observable, old, thrown) -> {
            if (thrown != null) {
                UiLog.w("[FxSurfaces] load failed, [" + thrown + "]");
            }
        });
    }

    /**
     * The work area of the screen the given window currently sits on, which is where a widget or a
     * content block belongs. Following the window matters on a multiple monitor desktop: the primary
     * screen is not necessarily the one the application is on, and the application can be dragged to
     * another monitor while a content zone is running.
     *
     * @param owner the application window to follow, may be {@code null} or not yet on screen
     * @return the surface to place on, falling back to the primary screen's work area
     */
    static WidgetSurface screenOf(Window owner) {
        Rectangle2D bounds = screenBoundsOf(owner);
        return new WidgetSurface((int) bounds.getMinX(), (int) bounds.getMinY(), (int) bounds.getWidth(), (int) bounds.getHeight());
    }

    /**
     * @param owner the window to measure
     * @return the window's own bounds, or the primary screen's work area when it is not on screen yet
     */
    static WidgetSurface boundsOf(Window owner) {
        if (!isOnScreen(owner)) {
            return screenOf(null);
        }
        return new WidgetSurface((int) owner.getX(), (int) owner.getY(), (int) owner.getWidth(), (int) owner.getHeight());
    }

    private static Rectangle2D screenBoundsOf(Window owner) {
        try {
            if (isOnScreen(owner)) {
                // Match on the window's centre rather than its origin, so a window straddling two
                // monitors lands on the one it is mostly on.
                double centreX = owner.getX() + owner.getWidth() / 2;
                double centreY = owner.getY() + owner.getHeight() / 2;
                List<Screen> screens = Screen.getScreensForRectangle(centreX, centreY, 1, 1);
                if (!screens.isEmpty()) {
                    return screens.get(0).getVisualBounds();
                }
            }
            return Screen.getPrimary().getVisualBounds();
        } catch (Throwable t) {
            UiLog.w("[FxSurfaces] screenBoundsOf, could not measure the screen, [" + t + "]");
            return new Rectangle2D(0, 0, 0, 0);
        }
    }

    private static boolean isOnScreen(Window owner) {
        if (owner == null || !owner.isShowing()) {
            return false;
        }
        return !Double.isNaN(owner.getX()) && !Double.isNaN(owner.getY())
            && owner.getWidth() > 0 && owner.getHeight() > 0;
    }

    /**
     * The application window a display should follow when the integrator did not name one. Prefers
     * the focused window, then any showing one. Must be called on the JavaFX application thread.
     *
     * @return the window to follow, or {@code null} when the application has none on screen
     */
    static Window primaryApplicationWindow() {
        try {
            Window focused = null;
            Window showing = null;
            for (Window window : Window.getWindows()) {
                if (!window.isShowing()) {
                    continue;
                }
                if (showing == null) {
                    showing = window;
                }
                if (window.isFocused()) {
                    focused = window;
                }
            }
            return focused != null ? focused : showing;
        } catch (Throwable t) {
            UiLog.w("[FxSurfaces] primaryApplicationWindow, could not read the window list, [" + t + "]");
            return null;
        }
    }
}
