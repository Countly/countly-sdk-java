package ly.count.sdk.java.ui;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
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

    /** How long a step transition is given to finish before the card is measured or checked. */
    private static final Duration SETTLE_DELAY = Duration.millis(600);

    /**
     * How often the card is re-checked from this side. Only a backstop now that the page reports its
     * own changes: it covers a page that cannot be scripted, where nothing else would notice.
     */
    private static final Duration WATCH_INTERVAL = Duration.millis(3000);

    /**
     * How many corrections the watcher may make for one page. A widget has a handful of steps, so
     * this is generous; it exists so a page that changes size every time it is looked at cannot keep
     * the card moving forever.
     */
    private static final int MAX_WATCH_CORRECTIONS = 12;

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
    private int corrections = 0;
    private int followedFrames = 0;
    private Timeline watch;

    /** The object the page calls into. See installBridge for why it has to be a field. */
    private WidgetJsBridge bridge;
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
        // As soon as there is a document, rather than only once the load has succeeded.
        //
        // A widget template asks for its size while it is still initialising, which happens as the
        // document parses. Installing the bridge on SUCCEEDED is later than that: on a fast machine
        // the page's first resize_me was posted before anything was listening and was lost, and the
        // card was left at a guessed height with the page never asked again. Installing here catches
        // it. Both installs are guarded, so whichever runs first wins and the other does nothing.
        engine.documentProperty().addListener((observable, oldDocument, document) -> {
            if (document != null) {
                installBridge();
            }
        });
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
        // A page that sizes itself against the surface has to be told when the surface changes, the
        // same way the content display tells its page.
        if (pageLoaded) {
            FxSurfaces.notifyPageOfSurface(engine, updated);
        }
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

    /** @return how many placements were applied, for tests */
    long placementRequestsForTests() {
        return placementRequests;
    }

    @Override
    public void followGeometry(ContentPlacement rect) {
        if (!stage.isShowing()) {
            return;
        }
        applyGeometry(rect);
    }

    @Override
    public void placeAndShow(ContentPlacement rect) {
        if (placed && stage.isShowing() && rect.x == (int) stage.getX() && rect.y == (int) stage.getY()
            && rect.width == (int) stage.getWidth() && rect.height == (int) stage.getHeight()) {
            // Already exactly there. A page re-announcing the same size, or a window drag that
            // changed nothing for this card, used to start a fresh round of fitting and timers each
            // time - seven identical placements in a row in one log.
            return;
        }
        placed = true;
        placementRequests++;
        UiLog.d("[JavaFxWidgetHost] placeAndShow, request [" + placementRequests + "] for " + rect
            + ", the card is " + (int) stage.getWidth() + "x" + (int) stage.getHeight()
            + (followedFrames > 0 ? ", after following [" + followedFrames + "] animation frames" : ""));
        followedFrames = 0;
        // A rectangle materially different from the one on screen is a new request from the page - a
        // widget moving to its next step - and each of those gets its own fit budget. Without this a
        // three step widget spends the whole budget on its first step and the rest are left at
        // whatever height that one settled on.
        if (Math.abs(rect.height - (int) stage.getHeight()) > FIT_TOLERANCE
            || Math.abs(rect.width - (int) stage.getWidth()) > FIT_TOLERANCE) {
            fits = 0;
        }
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

        // The first placement is fitted quickly, because the card is invisible until it is: the
        // widget must not be held back. Every later one is a step the page moved to, and the page
        // fades that in over a few hundred milliseconds - measuring during the fade describes the
        // step it is leaving, which is what left later steps at the wrong height.
        long request = placementRequests;
        PauseTransition fit = new PauseTransition(request <= 1 ? FIT_DELAY : SETTLE_DELAY);
        fit.setOnFinished(event -> fitThenReveal(rect, request));
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
        // A fullscreen widget covers the application window, so it follows its rounded corners too.
        FxSurfaces.applyOverlayCorners(webView, rect, surface);
    }

    /**
     * Fits the card to what the page drew, then reveals it, unless the fit asked for a different
     * rectangle: the placement that follows reveals it instead, so the card is only ever seen at the
     * size it keeps.
     *
     * @param rect the rectangle this round was asked for
     */
    private void fitThenReveal(ContentPlacement rect, long requestWhenScheduled) {
        if (placementRequests != requestWhenScheduled) {
            // A newer placement was applied while this fit waited, so it describes a card that no
            // longer exists. Reporting that as a window manager surprise was crying wolf, which is
            // what filled the log with warnings about rectangles nobody had asked for any more.
            return;
        }

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
            UiLog.d("[JavaFxWidgetHost] reportMeasuredCard, declining " + width + "x" + height
                + " against a " + (int) stage.getWidth() + "x" + (int) stage.getHeight() + " card");
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
        if (followedFrames > 0) {
            UiLog.d("[JavaFxWidgetHost] closeHost, followed [" + followedFrames
                + "] animation frames since the last placement");
        }
        try {
            if (watch != null) {
                // Before the page goes: a tick against a document being torn down measures nothing
                // useful and would keep a timer alive after the card is gone.
                watch.stop();
                watch = null;
            }
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
            // The widget's own half of the resize protocol. An NPS page keeps its parent dimensions
            // null until this message arrives and computes every size from them, so without it
            // getDeviceSize throws on null and the page reports no size at all: every step is then
            // drawn at the host's fallback height, which is a scrollbar on the comment step and dead
            // space on the thanks step. A survey does not need it, because it seeds the same
            // dimensions from the 'custom' URL parameter itself. The page only accepts the message
            // when its 'origin' parameter matches, which WidgetUrlBuilder sends.
            FxSurfaces.notifyPageOfSurface(engine, surface);
            startWatchingThePage();
            FxSurfaces.logPageDiagnostics(engine, "JavaFxWidgetHost");
            WebFontPrefetch.remember(engine);
            FxSurfaces.repaintBackgroundImagesWhenTheyArrive(engine);
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

    /**
     * The page's own report about its card: it changed size, or its content stopped fitting.
     * <p>
     * This is the fast path and the only one that needs to be. It arrives as the page lays the step
     * out, rather than a fifth of a second to a second later, which is what a timer on this side
     * could manage.
     *
     * @param width the card the page has drawn
     * @param height its height
     * @param overflow how much taller its content is than the room it has
     */
    private void onObservedCard(int width, int height, int overflow) {
        if (!pageLoaded || !placed || listener == null) {
            return;
        }
        if (width < MIN_CREDIBLE_CARD || height < MIN_CREDIBLE_CARD) {
            return;
        }

        // Overflow means the page has capped its own card and the content does not fit, so the card
        // has to be told to grow beyond what the page drew. Budgeted, because growing changes what
        // the page then reports and could otherwise chase itself.
        if (overflow > FIT_TOLERANCE && (int) stage.getHeight() < surface.height) {
            if (corrections >= MAX_WATCH_CORRECTIONS) {
                return;
            }
            corrections++;
            UiLog.d("[JavaFxWidgetHost] onObservedCard, the content needs [" + overflow
                + "] more pixels than its " + (int) stage.getHeight() + " tall card");
            listener.onContentOverflow(overflow);
            return;
        }

        if (width == (int) stage.getWidth() && height == (int) stage.getHeight()) {
            return;
        }

        // Every other report is a frame of the page's own animation, and following it is the whole
        // point: no per-frame logging, no budget, no fitting. A pixel of difference is worth
        // following, which is why this does not use FIT_TOLERANCE. Counted, so a log can show the
        // animation was followed at all, in one line rather than one per frame.
        followedFrames++;
        listener.onCardFollowing(width, height);
    }

    /**
     * Follows the page while the widget is on screen, correcting the card when it stops matching.
     * <p>
     * Everything else here reacts to something: a load finishing, or the page asking for a size
     * through {@code resize_me}. Both proved unreliable for a widget that moves between steps - the
     * page announces some steps and not others, and a step that is not announced produces no
     * placement, so nothing was scheduled to notice that the card no longer fits. Driving the live
     * server, an NPS comment step sat 55 pixels short with a scrollbar for exactly that reason.
     * <p>
     * So the card is also checked on a timer, which needs nothing from the page. A check that finds
     * the card already correct does nothing and says nothing.
     */
    private void startWatchingThePage() {
        if (watch != null) {
            return;
        }
        watch = new Timeline(new KeyFrame(WATCH_INTERVAL, event -> {
            if (!pageLoaded || !placed || listener == null || corrections >= MAX_WATCH_CORRECTIONS) {
                return;
            }

            int[] painted = FxSurfaces.measurePaintedContent(engine);
            int overflow = FxSurfaces.measureOverflow(engine);
            int cardHeight = (int) stage.getHeight();
            int cardWidth = (int) stage.getWidth();

            if (overflow > FIT_TOLERANCE && cardHeight < surface.height) {
                corrections++;
                UiLog.d("[JavaFxWidgetHost] watch, the content needs [" + overflow
                    + "] more pixels than its " + cardHeight + " tall card");
                listener.onContentOverflow(overflow);
                return;
            }

            // The other direction: a step shorter than the one before it leaves the card too tall,
            // and a card taller than what it draws floats above the edge it is anchored to.
            if (painted == null || painted[2] < MIN_CREDIBLE_CARD || painted[3] < MIN_CREDIBLE_CARD) {
                return;
            }
            if (Math.abs(painted[2] - cardWidth) <= FIT_TOLERANCE
                && Math.abs(painted[3] - cardHeight) <= FIT_TOLERANCE) {
                return;
            }

            corrections++;
            UiLog.d("[JavaFxWidgetHost] watch, the page now draws " + painted[2] + "x" + painted[3]
                + " in a " + cardWidth + "x" + cardHeight + " card, fitting it");
            listener.onCardMeasured(painted[2], painted[3]);
        }));
        watch.setCycleCount(Animation.INDEFINITE);
        watch.play();
    }

    private void installBridge() {
        try {
            JSObject window = (JSObject) engine.executeScript("window");
            // Held in a field on purpose. The engine keeps only a weak reference to an object handed
            // to setMember, so a bridge created inline is garbage collected the first time the
            // collector runs, and from then on every call the page makes into it is dropped without
            // a trace. That is exactly how it failed on a customer's machine: the page's first
            // message arrived, and nothing after it ever did. A test JVM rarely collects in time,
            // which is why the tests kept passing.
            if (bridge == null) {
                bridge = new WidgetJsBridge(listener, this::onObservedCard);
            }
            window.setMember(WidgetJsBridge.MEMBER_NAME, bridge);
            Object result = engine.executeScript(WidgetJsBridge.INSTALL_SCRIPT);
            UiLog.d("[JavaFxWidgetHost] installBridge, " + result);
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
