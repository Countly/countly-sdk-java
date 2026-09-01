package ly.count.sdk.java.ui;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import javafx.animation.PauseTransition;
import javafx.geometry.Rectangle2D;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Screen;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * Screen geometry and web view defaults shared by the widget and content displays.
 */
final class FxSurfaces {

    /**
     * Default typography for the pages the SDK loads. This is a <em>user</em> stylesheet, which the
     * CSS cascade ranks below the page's own rules, so it only fills in where the page left the font
     * unstated or ended its stack in a generic family. JavaFX bundles an old WebKit that cannot
     * resolve the "system-ui" keyword, and a page whose stack is only "system-ui" would otherwise
     * fall back to the engine's serif default.
     */
    private static final String USER_CSS =
        "html,body,button,input,select,textarea{"
            + "font-family:-apple-system,BlinkMacSystemFont,\"Segoe UI\",Roboto,\"Helvetica Neue\",Helvetica,Arial,sans-serif;"
            + "-webkit-font-smoothing:antialiased;}";

    private static final String USER_STYLESHEET =
        "data:text/css;charset=utf-8;base64," + Base64.getEncoder().encodeToString(USER_CSS.getBytes(StandardCharsets.UTF_8));

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
     * Starts WebKit ahead of time, so the first widget or content block does not pay for it. Cheap
     * to call more than once. Must be called on the JavaFX application thread.
     */
    static void prewarm() {
        if (warmView != null) {
            return;
        }
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
        if (!diagnosticsEnabled) {
            return;
        }

        UiLog.d("[" + label + "] user agent: " + engine.getUserAgent());

        PauseTransition wait = new PauseTransition(DIAGNOSTICS_DELAY);
        wait.setOnFinished(event -> {
            try {
                Object result = engine.executeScript(DIAGNOSTICS_SCRIPT);
                UiLog.d("[" + label + "] page resources: " + result);
            } catch (Throwable t) {
                UiLog.w("[" + label + "] could not read the page diagnostics, [" + t + "]");
            }
        });
        wait.play();
    }

    /**
     * Applies the SDK's web view defaults. Called before navigating.
     *
     * @param engine the engine to configure
     */
    static void configure(WebEngine engine) {
        engine.setJavaScriptEnabled(true);
        try {
            engine.setUserStyleSheetLocation(USER_STYLESHEET);
        } catch (Throwable t) {
            // Cosmetic only: the page still renders with the engine's own defaults.
            UiLog.w("[FxSurfaces] configure, could not apply the default stylesheet, [" + t + "]");
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
