package ly.count.sdk.java.ui;

import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.Window;
import ly.count.sdk.java.Countly;
import ly.count.sdk.java.internal.CountlyFeedbackWidget;
import ly.count.sdk.java.internal.FeedbackWidgetSelector;
import ly.count.sdk.java.internal.FeedbackWidgetType;
import ly.count.sdk.java.internal.ModuleContent;
import ly.count.sdk.java.internal.ModuleFeedback;

/**
 * Entry point for showing Countly feedback widgets and Countly content in a JavaFX application.
 * <p>
 * The core SDK stays headless: it fetches and reports, this package draws. Initialize the SDK as
 * usual, then present a widget or turn the content zone on.
 *
 * <pre>
 * // Feedback widgets, the quick way: fetch, pick and show in one call
 * CountlyWebView.presentNPS(stage);
 * CountlyWebView.presentSurvey(stage, "onboarding");
 * CountlyWebView.presentRating(stage, "", () -&gt; System.out.println("dismissed"));
 *
 * // Feedback widgets, picking one yourself
 * Countly.instance().feedback().getAvailableFeedbackWidgets((widgets, error) -&gt;
 *     Platform.runLater(() -&gt; CountlyWebView.presentFeedbackWidget(stage, widgets.get(0), null)));
 *
 * // Configuration, set once up front: lay widgets and content out inside the application window
 * CountlyWebView.setShowWidgetsWithinApp(true);
 *
 * // Content (experimental)
 * CountlyWebView.enableContentZone();
 * CountlyWebView.disableContentZone();
 * </pre>
 */
public final class CountlyWebView {

    /** Inside the application window, matching the other desktop SDKs. */
    private static final boolean DEFAULT_WITHIN_APP = true;

    private static volatile boolean displayAreaSet = false;
    private static volatile JavaFxContentDisplay contentDisplay = null;
    private static volatile Window contentDisplayOwner = null;

    private CountlyWebView() {
    }

    /**
     * Lay feedback widget cards and content blocks out inside the application window instead of over
     * the work area of the screen the application is on. Applies to both.
     * <p>
     * Configuration, set once before the first widget or content block: a widget's own layout is
     * built around one of the two, so moving the goalposts underneath a running application is not
     * something it can be expected to cope with. Later calls are ignored.
     * <p>
     * Defaults to {@code true}, which is what the other desktop SDKs settled on: a card belongs to
     * the application that asked for it. Pass {@code false} to lay cards and content out against the
     * work area of the screen the application is on instead.
     *
     * @param withinApp {@code true} to keep cards and content inside the application window
     */
    public static void setShowWidgetsWithinApp(boolean withinApp) {
        if (displayAreaSet) {
            UiLog.w("[CountlyWebView] setShowWidgetsWithinApp, this is set once and it already is ["
                + FxSurfaces.isDisplayWithinApp() + "], ignoring [" + withinApp + "]");
            return;
        }
        displayAreaSet = true;
        FxSurfaces.setDisplayWithinApp(withinApp);
    }

    /**
     * @return whether cards and content are laid out inside the application window
     */
    public static boolean isShowingWidgetsWithinApp() {
        return FxSurfaces.isDisplayWithinApp();
    }

    /**
     * Forgets that the setting above was set, so a test can set it again. A one time setting is
     * one time per process, and tests share one.
     */
    static void forgetDisplayAreaForTests() {
        displayAreaSet = false;
        FxSurfaces.setDisplayWithinApp(DEFAULT_WITHIN_APP);
    }

    /**
     * The corner radius used for a widget or content block that fills the application window.
     * <p>
     * Only matters with {@link #setShowWidgetsWithinApp(boolean)}: an overlay is a rectangular window
     * laid over the application's, so a block that covers it would otherwise paint square corners
     * past the application's rounded ones. JavaFX cannot read the window's real radius, so the
     * default is a sensible one; set it to match your window, or to {@code 0} for square windows.
     *
     * @param radius the radius in logical pixels
     */
    public static void setOverlayCornerRadius(double radius) {
        FxSurfaces.setOverlayCornerRadius(radius);
    }

    /**
     * Log what each page the SDK loads manages to fetch: the bundled WebKit version, images that
     * failed, and the font loading status. Off by default, because it scripts the page. Switch it on
     * when a widget or a content block does not look the way it should.
     *
     * @param enabled whether to log page diagnostics
     */
    public static void setWebViewDiagnosticsEnabled(boolean enabled) {
        FxSurfaces.setDiagnosticsEnabled(enabled);
    }

    /**
     * Show a feedback widget as a borderless card, sized and positioned where the widget asks. Must
     * be called on the JavaFX application thread.
     *
     * @param owner the application window the card belongs to, may be {@code null}
     * @param widget the widget to show, obtained from
     *     {@link ModuleFeedback.Feedback#getAvailableFeedbackWidgets}
     * @param onClosed called once, when the card is gone, may be {@code null}
     */
    public static void presentFeedbackWidget(Window owner, CountlyFeedbackWidget widget, Runnable onClosed) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> presentFeedbackWidget(owner, widget, onClosed));
            return;
        }

        if (widget == null) {
            UiLog.w("[CountlyWebView] presentFeedbackWidget, no widget was given, ignoring the call");
            run(onClosed);
            return;
        }

        ModuleFeedback.Feedback feedback = Countly.instance().feedback();
        if (feedback == null) {
            UiLog.w("[CountlyWebView] presentFeedbackWidget, the feedback interface is not available, ignoring the call");
            run(onClosed);
            return;
        }

        // One card at a time, shared with content: see PresentationLock.
        String claim = "widget " + widget.widgetId;
        if (!PresentationLock.tryAcquire(claim)) {
            run(onClosed);
            return;
        }
        // Whatever ends this presentation - the page closing itself, the window manager, an error
        // below - frees the screen before the caller hears about it, so the caller may present again
        // from inside its own callback.
        Runnable onClosedAndReleased = () -> {
            PresentationLock.release(claim);
            run(onClosed);
        };

        try {
            FxSurfaces.prewarm();
            AtomicReference<FeedbackWidgetPresenter> presenterRef = new AtomicReference<>();
            WidgetSurface surface = resolveSurface(owner);
            WebView webView = new WebView();

            // Starts as a 1x1 window at the surface origin so the page can load before the widget
            // tells us how big its card has to be; the presenter shows it once it knows. Transparent
            // and owned or always on top, as FxSurfaces explains.
            Stage stage = FxSurfaces.newOverlayStage(owner, webView, 1, 1);
            stage.setX(surface.x);
            stage.setY(surface.y);

            JavaFxWidgetHost host = new JavaFxWidgetHost(stage, webView, surface);
            host.initialize();

            FeedbackWidgetPresenter presenter = new FeedbackWidgetPresenter(host, feedback,
                followWindow(owner, host, presenterRef, onClosedAndReleased));
            presenterRef.set(presenter);
            // A card hidden by anything other than the SDK - its owner window closing and taking it
            // along, the integrator calling hide() - must still free the screen and tell the caller,
            // or every later widget and block is refused as "already being shown". The presenter's
            // own finish() runs first on the SDK's paths and makes this a no-op there.
            stage.setOnHidden(event -> presenter.dismissedExternally());
            presenter.start(widget);
        } catch (Throwable t) {
            UiLog.e("[CountlyWebView] presentFeedbackWidget, could not show the widget, [" + t + "]");
            onClosedAndReleased.run();
        }
    }

    /**
     * Show the first available NPS widget.
     *
     * @param owner the application window the card belongs to, may be {@code null}
     */
    public static void presentNPS(Window owner) {
        presentNPS(owner, null, null);
    }

    /**
     * Show an NPS widget picked by its ID, name or one of its tags.
     *
     * @param owner the application window the card belongs to, may be {@code null}
     * @param nameIDorTag the widget ID, widget name or widget tag to look for. Leave it empty to take
     *     the first available NPS widget.
     */
    public static void presentNPS(Window owner, String nameIDorTag) {
        presentNPS(owner, nameIDorTag, null);
    }

    /**
     * Show an NPS widget picked by its ID, name or one of its tags.
     *
     * @param owner the application window the card belongs to, may be {@code null}
     * @param nameIDorTag the widget ID, widget name or widget tag to look for. Leave it empty to take
     *     the first available NPS widget.
     * @param onClosed called once, when the card is gone, may be {@code null}
     */
    public static void presentNPS(Window owner, String nameIDorTag, Runnable onClosed) {
        presentWidgetOfType(owner, FeedbackWidgetType.nps, nameIDorTag, onClosed);
    }

    /**
     * Show the first available survey widget.
     *
     * @param owner the application window the card belongs to, may be {@code null}
     */
    public static void presentSurvey(Window owner) {
        presentSurvey(owner, null, null);
    }

    /**
     * Show a survey widget picked by its ID, name or one of its tags.
     *
     * @param owner the application window the card belongs to, may be {@code null}
     * @param nameIDorTag the widget ID, widget name or widget tag to look for. Leave it empty to take
     *     the first available survey widget.
     */
    public static void presentSurvey(Window owner, String nameIDorTag) {
        presentSurvey(owner, nameIDorTag, null);
    }

    /**
     * Show a survey widget picked by its ID, name or one of its tags.
     *
     * @param owner the application window the card belongs to, may be {@code null}
     * @param nameIDorTag the widget ID, widget name or widget tag to look for. Leave it empty to take
     *     the first available survey widget.
     * @param onClosed called once, when the card is gone, may be {@code null}
     */
    public static void presentSurvey(Window owner, String nameIDorTag, Runnable onClosed) {
        presentWidgetOfType(owner, FeedbackWidgetType.survey, nameIDorTag, onClosed);
    }

    /**
     * Show the first available rating widget.
     *
     * @param owner the application window the card belongs to, may be {@code null}
     */
    public static void presentRating(Window owner) {
        presentRating(owner, null, null);
    }

    /**
     * Show a rating widget picked by its ID, name or one of its tags.
     *
     * @param owner the application window the card belongs to, may be {@code null}
     * @param nameIDorTag the widget ID, widget name or widget tag to look for. Leave it empty to take
     *     the first available rating widget.
     */
    public static void presentRating(Window owner, String nameIDorTag) {
        presentRating(owner, nameIDorTag, null);
    }

    /**
     * Show a rating widget picked by its ID, name or one of its tags.
     *
     * @param owner the application window the card belongs to, may be {@code null}
     * @param nameIDorTag the widget ID, widget name or widget tag to look for. Leave it empty to take
     *     the first available rating widget.
     * @param onClosed called once, when the card is gone, may be {@code null}
     */
    public static void presentRating(Window owner, String nameIDorTag, Runnable onClosed) {
        presentWidgetOfType(owner, FeedbackWidgetType.rating, nameIDorTag, onClosed);
    }

    /**
     * Fetches the widget list, picks the one asked for, and shows it. The fetch is a network call, so
     * this returns straight away and the card appears later.
     */
    private static void presentWidgetOfType(Window owner, FeedbackWidgetType type, String nameIDorTag, Runnable onClosed) {
        ModuleFeedback.Feedback feedback = Countly.instance().feedback();
        if (feedback == null) {
            UiLog.w("[CountlyWebView] present" + type.name() + ", the feedback interface is not available, ignoring the call");
            run(onClosed);
            return;
        }

        feedback.getAvailableFeedbackWidgets((widgets, error) -> {
            // This callback runs on the SDK's network thread. The happy path hands the callback back
            // on the JavaFX thread, so these bail outs do the same and the caller only ever sees one.
            if (error != null) {
                UiLog.e("[CountlyWebView] present" + type.name() + ", could not retrieve the widget list, [" + error + "]");
                runOnFxThread(onClosed);
                return;
            }

            CountlyFeedbackWidget widget = FeedbackWidgetSelector.select(widgets, type, nameIDorTag);
            if (widget == null) {
                UiLog.w("[CountlyWebView] present" + type.name() + ", no widget of that type matches [" + nameIDorTag + "]");
                runOnFxThread(onClosed);
                return;
            }

            // The fetch completed off the JavaFX thread; presentFeedbackWidget hops back on its own.
            presentFeedbackWidget(owner, widget, onClosed);
        });
    }

    /**
     * Register the JavaFX content display with the SDK and enter the content zone. Must be called on
     * the JavaFX application thread, after the SDK was initialized with
     * {@code Config.Feature.Content} enabled.
     *
     * @apiNote This is an EXPERIMENTAL feature, and it can have breaking changes
     */
    public static void enableContentZone() {
        enableContentZone(null);
    }

    /**
     * Register the JavaFX content display with the SDK and enter the content zone, placing content on
     * the screen the given window is on. Must be called on the JavaFX application thread, after the
     * SDK was initialized with {@code Config.Feature.Content} enabled.
     *
     * @param owner the application window content should follow. Pass {@code null} to follow the
     *     application's focused window, which is what most applications want.
     * @apiNote This is an EXPERIMENTAL feature, and it can have breaking changes
     */
    public static void enableContentZone(Window owner) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> enableContentZone(owner));
            return;
        }

        ModuleContent.Content content = Countly.instance().content();
        if (content == null) {
            UiLog.w("[CountlyWebView] enableContentZone, the content interface is not available, ignoring the call");
            return;
        }

        Window window = owner != null ? owner : FxSurfaces.primaryApplicationWindow();

        // The display listens to its window to follow it across monitors, so a different window
        // needs a different display.
        if (contentDisplay == null || contentDisplayOwner != window) {
            contentDisplay = new JavaFxContentDisplay(window);
            contentDisplayOwner = window;
        }

        content.setContentDisplay(contentDisplay);
        content.enterContentZone();
    }

    /**
     * Leave the content zone. A content block that is already on screen stays there, so the user
     * can finish with it.
     *
     * @apiNote This is an EXPERIMENTAL feature, and it can have breaking changes
     */
    public static void disableContentZone() {
        ModuleContent.Content content = Countly.instance().content();
        if (content == null) {
            UiLog.w("[CountlyWebView] disableContentZone, the content interface is not available, ignoring the call");
            return;
        }
        content.exitContentZone();
    }

    /**
     * Keeps a card with the window it belongs to: a window that is moved, resized, or dragged to
     * another monitor takes its card with it, rather than leaving it stranded where the window used
     * to be. The listeners are removed when the card closes, so a long lived application window does
     * not collect one set per widget it ever showed.
     *
     * @param owner the window to follow, may be {@code null}
     * @param host the host whose surface to keep current
     * @param presenterRef where the presenter will be, once it exists
     * @param onClosed the caller's own callback, may be {@code null}
     * @return the callback to hand the presenter
     */
    private static Runnable followWindow(Window owner, JavaFxWidgetHost host,
        AtomicReference<FeedbackWidgetPresenter> presenterRef, Runnable onClosed) {
        if (owner == null) {
            return onClosed;
        }

        ChangeListener<Number> moved = (observable, was, now) -> {
            host.setSurface(resolveSurface(owner));
            FeedbackWidgetPresenter presenter = presenterRef.get();
            if (presenter != null) {
                presenter.refreshPlacement();
            }
        };
        owner.xProperty().addListener(moved);
        owner.yProperty().addListener(moved);
        owner.widthProperty().addListener(moved);
        owner.heightProperty().addListener(moved);

        return () -> {
            owner.xProperty().removeListener(moved);
            owner.yProperty().removeListener(moved);
            owner.widthProperty().removeListener(moved);
            owner.heightProperty().removeListener(moved);
            run(onClosed);
        };
    }

    private static WidgetSurface resolveSurface(Window owner) {
        Window window = owner != null ? owner : FxSurfaces.primaryApplicationWindow();
        WidgetSurface surface = FxSurfaces.surfaceFor(window);

        // Which monitor a card lands on is the application window's, so when a card is reported as
        // not showing up, the first thing worth knowing is where the SDK thought that window was.
        UiLog.d("[CountlyWebView] resolveSurface, withinApp=" + FxSurfaces.isDisplayWithinApp() + " " + surface
            + ", owner " + describe(window));
        return surface;
    }

    private static String describe(Window window) {
        if (window == null) {
            return "none, so the primary screen";
        }
        return (window.isShowing() ? "showing" : "not on screen") + " at " + (int) window.getX()
            + "," + (int) window.getY() + " " + (int) window.getWidth() + "x" + (int) window.getHeight();
    }

    private static void runOnFxThread(Runnable runnable) {
        if (runnable == null) {
            return;
        }
        try {
            Platform.runLater(() -> run(runnable));
        } catch (Throwable t) {
            // No toolkit running: better an off thread callback than none at all.
            UiLog.w("[CountlyWebView] runOnFxThread, the JavaFX toolkit is not running, [" + t + "]");
            run(runnable);
        }
    }

    private static void run(Runnable runnable) {
        if (runnable == null) {
            return;
        }
        try {
            runnable.run();
        } catch (Throwable t) {
            UiLog.e("[CountlyWebView] run, a callback threw, [" + t + "]");
        }
    }
}
