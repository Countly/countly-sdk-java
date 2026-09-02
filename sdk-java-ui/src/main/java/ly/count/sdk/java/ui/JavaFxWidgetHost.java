package ly.count.sdk.java.ui;

import javafx.animation.PauseTransition;
import javafx.concurrent.Worker;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.util.Duration;
import ly.count.sdk.java.internal.ContentPlacement;
import ly.count.sdk.java.internal.SDKCore;
import ly.count.sdk.java.internal.WidgetAction;
import ly.count.sdk.java.internal.WidgetActionParser;
import netscape.javascript.JSObject;

/**
 * Maps {@link WidgetWebHost} onto a JavaFX {@link WebView} inside a borderless card {@link Stage}.
 * <p>
 * JavaFX lays a web page out in logical pixels and handles high density displays itself, so the
 * rectangles a widget asks for are applied to the stage as they are, with no density conversion.
 * <p>
 * Every method must be called on the JavaFX application thread.
 */
class JavaFxWidgetHost implements WidgetWebHost {

    /**
     * How long to wait for a widget to report its own size before falling back to measuring the
     * rendered page. Widgets built from the rating template never report one.
     */
    private static final Duration PLACEMENT_FALLBACK_DELAY = Duration.millis(900);

    private static final int FALLBACK_WIDTH = 400;
    private static final int FALLBACK_HEIGHT = 500;

    private final Stage stage;
    private final WebView webView;
    private final WebEngine engine;
    private final WidgetSurface surface;

    private Listener listener;
    private boolean pageLoaded = false;
    private boolean placed = false;

    JavaFxWidgetHost(Stage stage, WebView webView, WidgetSurface surface) {
        this.stage = stage;
        this.webView = webView;
        this.engine = webView.getEngine();
        this.surface = surface;
    }

    /**
     * Wires up the engine. Call once, before navigating.
     */
    void initialize() {
        FxSurfaces.configure(engine);
        // Everything the widget's own card does not paint shows the application through it, rather
        // than sitting in an opaque box.
        FxSurfaces.makePageBackgroundTransparent(webView);
        engine.locationProperty().addListener((observable, oldUrl, newUrl) -> onLocationChanged(newUrl));
        engine.setCreatePopupHandler(features -> openPopupExternally());
        engine.getLoadWorker().stateProperty().addListener((observable, oldState, newState) -> onLoadStateChanged(newState));
    }

    @Override
    public void setListener(Listener widgetListener) {
        listener = widgetListener;
    }

    @Override
    public WidgetSurface getSurface() {
        return surface;
    }

    @Override
    public void navigate(String url) {
        engine.load(url);
    }

    @Override
    public void reportSurfaceSize(int width, int height) {
        try {
            engine.executeScript("window.postMessage({type:'resize',width:" + width + ",height:" + height + "},'*');");
        } catch (Throwable t) {
            UiLog.w("[JavaFxWidgetHost] reportSurfaceSize, could not report the surface size, [" + t + "]");
        }
    }

    @Override
    public void placeAndShow(ContentPlacement rect) {
        placed = true;
        stage.setX(rect.x);
        stage.setY(rect.y);
        stage.setWidth(rect.width);
        stage.setHeight(rect.height);
        if (!stage.isShowing()) {
            stage.show();
        }
    }

    @Override
    public void closeHost() {
        try {
            engine.load("about:blank");
            stage.close();
        } catch (Throwable t) {
            UiLog.e("[JavaFxWidgetHost] closeHost, could not close the widget card, [" + t + "]");
        }
    }

    private void onLocationChanged(String url) {
        WidgetAction action = WidgetActionParser.parse(url, SDKCore.logger());
        if (!action.isSdkSignal) {
            return;
        }

        // Signalling URLs are not real destinations: navigating to them would replace the widget
        // with a browser error page.
        engine.getLoadWorker().cancel();

        if (listener != null) {
            listener.onNavigationStarting(url);
        }
    }

    private void onLoadStateChanged(Worker.State state) {
        if (state == Worker.State.SUCCEEDED) {
            pageLoaded = true;
            installBridge();
            FxSurfaces.logPageDiagnostics(engine, "JavaFxWidgetHost");
            if (listener != null) {
                listener.onPageLoaded();
            }
            schedulePlacementFallback();
        } else if (state == Worker.State.FAILED && !pageLoaded) {
            // Only the very first load matters here: later failures are the cancelled signalling
            // navigations, which are expected.
            if (listener != null) {
                listener.onLoadFailed();
            }
        }
    }

    private void installBridge() {
        try {
            JSObject window = (JSObject) engine.executeScript("window");
            window.setMember(WidgetJsBridge.MEMBER_NAME, new WidgetJsBridge(listener));
            engine.executeScript(WidgetJsBridge.INSTALL_SCRIPT);
        } catch (Throwable t) {
            // Without the bridge a widget can still close itself through a signalling URL, it just
            // cannot report its own card size, so the fallback placement takes over.
            UiLog.w("[JavaFxWidgetHost] installBridge, could not install the JavaScript bridge, [" + t + "]");
        }
    }

    private void schedulePlacementFallback() {
        PauseTransition wait = new PauseTransition(PLACEMENT_FALLBACK_DELAY);
        wait.setOnFinished(event -> {
            if (!placed) {
                placeByMeasuredContent();
            }
        });
        wait.play();
    }

    /**
     * Places a widget that never reported its own size, by measuring the card the page painted.
     * <p>
     * A page that painted no card at all is not a widget: it is whatever the browser substituted
     * when the real page could not be fetched. JavaFX reports an HTTP level failure (a refused
     * connection, a 404) as a SUCCEEDED load carrying an error document, so this is the only point
     * at which that can be noticed. Without the check a card was shown around a browser error page
     * and the caller's callback never ran, which is the exact wedge {@code onLoadFailed} exists to
     * prevent.
     */
    private void placeByMeasuredContent() {
        int[] card = FxSurfaces.measurePaintedContent(engine);
        if (card == null) {
            UiLog.w("[JavaFxWidgetHost] placeByMeasuredContent, the page painted no widget card, so"
                + " it is not a widget page: treating it as a failed load");
            if (listener != null) {
                listener.onLoadFailed();
            }
            return;
        }

        int width = Math.min(card[2] > 0 ? card[2] : FALLBACK_WIDTH, surface.width);
        int height = Math.min(card[3] > 0 ? card[3] : FALLBACK_HEIGHT, surface.height);
        int x = Math.max(0, (surface.width - width) / 2);
        int y = Math.max(0, (surface.height - height) / 2);

        UiLog.d("[JavaFxWidgetHost] placeByMeasuredContent, the widget reported no size, centring the"
            + " card it painted (" + width + "x" + height + ")");
        placeAndShow(new ContentPlacement(surface.x + x, surface.y + y, width, height));
    }

    /**
     * Sends {@code target="_blank"} links to the system browser instead of rendering them in the
     * card.
     *
     * @return a throwaway engine that only reports where it was asked to go
     */
    private WebEngine openPopupExternally() {
        WebEngine popup = new WebEngine();
        popup.locationProperty().addListener((observable, oldUrl, newUrl) -> {
            if (newUrl != null && !newUrl.isEmpty()) {
                ExternalBrowser.open(newUrl);
            }
        });
        return popup;
    }

    /**
     * @return the web view this host drives, so a caller can put it in a scene
     */
    WebView getWebView() {
        return webView;
    }
}
