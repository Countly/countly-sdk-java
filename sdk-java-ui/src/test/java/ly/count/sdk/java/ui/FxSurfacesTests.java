package ly.count.sdk.java.ui;

import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Screen geometry and web view defaults, exercised against the headless toolkit.
 */
@RunWith(JUnit4.class)
public class FxSurfacesTests {

    @BeforeClass
    public static void startToolkit() {
        // Needs a toolkit, and the screen assertions below only hold on a predictable single screen.
        FxTestToolkit.assumeRealPageLoadsAreSafe();
    }

    @AfterClass
    public static void resetDiagnostics() {
        FxSurfaces.setDiagnosticsEnabled(false);
    }

    /**
     * With no window to follow, and with a window that is not on screen yet, the primary screen's
     * work area is the answer. A window that IS on screen selects the screen it sits on, and reports
     * its own bounds separately.
     */
    @Test
    public void surfaces_fallBackToThePrimaryScreenUntilAWindowIsOnScreen() {
        WidgetSurface primary = FxSurfaces.screenOf(null);
        Assert.assertTrue("the headless screen should have a size", primary.width > 0 && primary.height > 0);
        Assert.assertTrue(primary.isLandscape());

        // Not shown yet, so there is nothing to follow.
        FxTestToolkit.onFx(() -> {
            Stage hidden = new Stage(StageStyle.UNDECORATED);
            Assert.assertEquals(primary.width, FxSurfaces.screenOf(hidden).width);
            Assert.assertEquals(primary.width, FxSurfaces.boundsOf(hidden).width);
        });

        FxTestToolkit.onFx(() -> {
            Stage shown = new Stage(StageStyle.UNDECORATED);
            shown.setScene(new Scene(new Pane(), 300, 200));
            shown.setX(40);
            shown.setY(30);
            shown.setWidth(300);
            shown.setHeight(200);
            shown.show();
            try {
                // Not compared against the primary screen: on a multiple monitor machine the window
                // may legitimately sit on another one, which is the whole point of screenOf.
                WidgetSurface screen = FxSurfaces.screenOf(shown);
                Assert.assertTrue(screen.width > 0 && screen.height > 0);

                WidgetSurface own = FxSurfaces.boundsOf(shown);
                Assert.assertEquals(40, own.x);
                Assert.assertEquals(30, own.y);
                Assert.assertEquals(300, own.width);
                Assert.assertEquals(200, own.height);
                Assert.assertTrue(own.isLandscape());
                Assert.assertTrue(own.toString().contains("width=300"));
            } finally {
                shown.close();
            }
        });
    }

    /**
     * The window a display follows when the integrator names none: a showing window, preferring the
     * focused one, and nothing at all when the application has no window up.
     */
    @Test
    public void primaryApplicationWindow_prefersAShowingWindow() {
        FxTestToolkit.onFx(() -> {
            Stage stage = new Stage(StageStyle.UNDECORATED);
            stage.setScene(new Scene(new Pane(), 200, 100));
            stage.show();
            try {
                Window found = FxSurfaces.primaryApplicationWindow();
                Assert.assertNotNull(found);
                Assert.assertTrue(found.isShowing());
            } finally {
                stage.close();
            }
        });
    }

    /**
     * Configuring an engine installs the default typography and a load-failure listener, and must
     * survive being handed an engine twice. Diagnostics stay off unless asked for, and asking for
     * them makes the probe run without throwing.
     */
    @Test
    public void configure_andDiagnostics_areSafeToApply() {
        Assert.assertFalse(FxSurfaces.isDiagnosticsEnabled());

        FxTestToolkit.onFx(() -> {
            WebView webView = new WebView();
            FxSurfaces.configure(webView.getEngine());
            FxSurfaces.configure(webView.getEngine());
            Assert.assertTrue(webView.getEngine().isJavaScriptEnabled());
            // A fallback font IS installed, as a user stylesheet so the page still wins when it
            // states a font. Without it, a page with no usable font declaration renders in serif.
            Assert.assertNotNull(webView.getEngine().getUserStyleSheetLocation());

            // Off by default, so a customer's page is never scripted.
            FxSurfaces.logPageDiagnostics(webView.getEngine(), "test");

            FxSurfaces.setDiagnosticsEnabled(true);
            Assert.assertTrue(FxSurfaces.isDiagnosticsEnabled());
            FxSurfaces.logPageDiagnostics(webView.getEngine(), "test");
            FxSurfaces.setDiagnosticsEnabled(false);
        });
    }

    /**
     * Clearing the web view's page backdrop, which is what lets the application show through
     * everywhere the page does not paint. Public JavaFX API since version 20; this module requires
     * JavaFX 21 precisely so it does not have to reach an internal reflectively.
     */
    @Test
    public void makePageBackgroundTransparent_clearsTheBackdrop() {
        FxTestToolkit.onFx(() -> {
            WebView webView = new WebView();
            FxSurfaces.makePageBackgroundTransparent(webView);
            Assert.assertEquals(javafx.scene.paint.Color.TRANSPARENT, webView.getPageFill());

            // Applying it twice is harmless.
            FxSurfaces.makePageBackgroundTransparent(webView);
            Assert.assertEquals(javafx.scene.paint.Color.TRANSPARENT, webView.getPageFill());
        });
    }

    /**
     * Starting WebKit early is idempotent and does not throw.
     */
    @Test
    public void prewarm_isIdempotent() {
        FxTestToolkit.onFx(() -> {
            FxSurfaces.prewarm();
            FxSurfaces.prewarm();
        });
    }
}
