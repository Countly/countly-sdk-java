package ly.count.sdk.java.ui;

import javafx.animation.PauseTransition;
import javafx.concurrent.Worker;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.Window;
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

    /**
     * How long to wait for the page itself. A load that never resolves reports neither success nor
     * failure, so without a deadline the caller is left with no card and no callback at all.
     */
    static Duration loadTimeout = Duration.seconds(20);

    /** Long enough for the page to lay itself out at a newly applied size. */
    private static final Duration FIT_DELAY = Duration.millis(160);

    /** A page and a fit that disagreed forever would resize each other forever. */
    private static final int MAX_FITS_PER_PAGE = 3;

    /** Below this, a measurement is a wrapper caught mid layout rather than a card. */
    private static final int MIN_CREDIBLE_CARD = 120;

    /** A difference this small is not worth moving a visible window for. */
    private static final int FIT_TOLERANCE = 4;

    private final Stage stage;
    private final WebView webView;
    private final WebEngine engine;
    private WidgetSurface surface;

    private Listener listener;
    private boolean pageLoaded = false;
    private boolean placed = false;
    private int fits = 0;
    private long placementRequests = 0;

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
    public void setSurface(WidgetSurface updated) {
        if (updated == null) {
            return;
        }
        surface = updated;
    }

    @Override
    public void navigate(String url) {
        engine.load(url);
        scheduleLoadDeadline();
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
        placementRequests++;
        applyGeometry(rect);

        // The size a widget asks for is not the size it always draws, and a card drawn shorter than
        // its window floats above whatever edge it is anchored to, so the card is fitted to what the
        // page actually draws before anyone sees it. That needs a real layout pass, which a window
        // that has never been shown does not get: the page would still be measuring itself against
        // the 1x1 scene it started in. So the window is shown fully transparent, laid out, fitted,
        // and only then revealed. Showing it first and correcting afterwards is a card that jumps.
        if (!stage.isShowing()) {
            stage.setOpacity(0);
            stage.show();
            // Always on top settles the card against other applications, not against the owner's own
            // stacking.
            stage.toFront();
        }

        PauseTransition fit = new PauseTransition(FIT_DELAY);
        fit.setOnFinished(event -> fitThenReveal(rect));
        fit.play();
    }

    private void applyGeometry(ContentPlacement rect) {
        stage.setX(rect.x);
        stage.setY(rect.y);
        stage.setWidth(rect.width);
        stage.setHeight(rect.height);
        // The web view is the scene root, so the scene resizes it, but its own 800x600 preferred
        // size is what it reports meanwhile. Stating the size outright keeps the page's viewport and
        // the window the same size on every resize, not just the first.
        webView.setPrefSize(rect.width, rect.height);
        // Resized outright rather than only asked to lay out: a window that has not been shown yet
        // does not run a layout pass, so the page would still be measuring itself against the 1x1
        // scene it started in, and the fit below would have nothing to work with.
        webView.resize(rect.width, rect.height);
        webView.requestLayout();
    }

    /**
     * Fits the card to what the page drew, then reveals it, unless the fit asked for a different
     * rectangle: the placement that follows reveals it instead, so the card is only ever seen at the
     * size it keeps.
     *
     * @param rect the rectangle this round was asked for
     */
    private void fitThenReveal(ContentPlacement rect) {
        long requestsBefore = placementRequests;
        reportMeasuredCard(FxSurfaces.measurePaintedContent(engine));
        if (placementRequests != requestsBefore) {
            // The fit re-placed the card, and that round owns revealing it.
            return;
        }

        stage.setOpacity(1);

        // Asking is not landing: the window manager can move a card, size it differently, or refuse
        // to show it at all, which is what an owner window on another desktop or minimized does to
        // its children. Reported so a card the user never saw can be told apart from a card that was
        // never placed.
        if (!stage.isShowing() || (int) stage.getX() != rect.x || (int) stage.getY() != rect.y) {
            UiLog.w("[JavaFxWidgetHost] fitThenReveal, the card was placed at " + rect + " but the"
                + " window manager put it at " + describeStage() + ", owner=" + describeOwner());
            return;
        }

        UiLog.d("[JavaFxWidgetHost] fitThenReveal, the card is at " + describeStage()
            + ", web view " + (int) webView.getWidth() + "x" + (int) webView.getHeight()
            + ", owner " + describeOwner());
    }


    /**
     * Hands the size of the card the page actually drew to the listener.
     * <p>
     * The size a widget asks for is not always the size it draws: a page lays its card out at the
     * top of the viewport, so a window even slightly taller leaves transparent space below the card,
     * and a card that is supposed to sit on the bottom edge visibly floats above it.
     *
     * @param painted what the page drew, {@code null} when it could not be measured
     */
    private void reportMeasuredCard(int[] painted) {
        if (listener == null || painted == null || fits >= MAX_FITS_PER_PAGE) {
            return;
        }

        int width = painted[2];
        int height = painted[3];
        // A measurement is only worth acting on when it looks like a card: mid layout the wrapper
        // measures as a sliver, and the page's own numbers are the better guess in that case.
        if (width < MIN_CREDIBLE_CARD || height < MIN_CREDIBLE_CARD
            || (Math.abs(width - (int) stage.getWidth()) <= FIT_TOLERANCE
            && Math.abs(height - (int) stage.getHeight()) <= FIT_TOLERANCE)) {
            return;
        }

        fits++;
        UiLog.d("[JavaFxWidgetHost] reportMeasuredCard, the page drew " + width + "x" + height
            + " in a " + (int) stage.getWidth() + "x" + (int) stage.getHeight() + " card, fitting it");
        listener.onCardMeasured(width, height);
    }

    private String describeStage() {
        return (int) stage.getX() + "," + (int) stage.getY() + " "
            + (int) stage.getWidth() + "x" + (int) stage.getHeight()
            + ", showing=" + stage.isShowing() + ", focused=" + stage.isFocused();
    }

    private String describeOwner() {
        Window owner = stage.getOwner();
        if (owner == null) {
            return "none";
        }
        String state = owner.isShowing() ? "showing" : "not on screen";
        if (owner instanceof Stage && ((Stage) owner).isIconified()) {
            // A child stage of a minimized owner is not drawn, however well it was placed.
            state = "minimized";
        }
        return state + " at " + (int) owner.getX() + "," + (int) owner.getY();
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
            fits = 0;
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

    private void scheduleLoadDeadline() {
        PauseTransition deadline = new PauseTransition(loadTimeout);
        deadline.setOnFinished(event -> {
            if (pageLoaded) {
                return;
            }
            UiLog.w("[JavaFxWidgetHost] the widget page did not load within " + loadTimeout.toSeconds()
                + "s, giving up on it");
            if (listener != null) {
                listener.onLoadFailed();
            }
        });
        deadline.play();
    }

    private void schedulePlacementFallback() {
        PauseTransition wait = new PauseTransition(PLACEMENT_FALLBACK_DELAY);
        wait.setOnFinished(event -> {
            if (!placed) {
                reportMissingSize();
            }
        });
        wait.play();
    }

    /**
     * Reports a widget that never told us how big its card is, along with the size of whatever it
     * did paint. Only the listener knows the widget type, and the type is what decides the card, so
     * the decision belongs there: a rating never reports a size and paints only its sticky tab, so
     * placing the measurement gave a sliver of a window.
     * <p>
     * A widget page that could not be measured is still shown. Being unmeasurable used to be read as
     * "not a widget page" and the card was closed, which cost exactly what it was meant to prevent:
     * these templates wrap their card in a transparent wrapper, so a perfectly good page measured as
     * nothing, and any widget whose own size message arrived later than this deadline was killed
     * before it appeared. What the server actually served is asked separately, by looking for the
     * templates' own elements.
     */
    private void reportMissingSize() {
        if (listener == null) {
            return;
        }

        if (!FxSurfaces.looksLikeWidgetPage(engine)) {
            UiLog.w("[JavaFxWidgetHost] reportMissingSize, the document is not a widget page, so the"
                + " server served something else: treating it as a failed load");
            listener.onLoadFailed();
            return;
        }

        int[] card = FxSurfaces.measurePaintedContent(engine);
        UiLog.d("[JavaFxWidgetHost] reportMissingSize, the widget has reported no size; it painted "
            + (card == null ? "nothing measurable" : card[2] + "x" + card[3]));
        listener.onSizeNotReported(card == null ? 0 : card[2], card == null ? 0 : card[3]);
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
