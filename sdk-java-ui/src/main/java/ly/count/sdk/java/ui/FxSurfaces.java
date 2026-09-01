package ly.count.sdk.java.ui;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import javafx.geometry.Rectangle2D;
import javafx.scene.web.WebEngine;
import javafx.stage.Screen;
import javafx.stage.Window;

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

    private FxSurfaces() {
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
