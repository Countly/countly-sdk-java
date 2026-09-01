package ly.count.sdk.java.ui;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
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
 * // Content (experimental)
 * CountlyWebView.enableContentZone();
 * CountlyWebView.disableContentZone();
 * </pre>
 */
public final class CountlyWebView {

    private static volatile boolean showWidgetsWithinApp = false;
    private static volatile JavaFxContentDisplay contentDisplay = null;
    private static volatile Window contentDisplayOwner = null;

    private CountlyWebView() {
    }

    /**
     * Place widget cards inside the application window instead of on the screen's work area.
     * Defaults to {@code false}, which is what a desktop widget expects.
     *
     * @param withinApp {@code true} to keep cards inside the owner window
     */
    public static void setShowWidgetsWithinApp(boolean withinApp) {
        showWidgetsWithinApp = withinApp;
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

        try {
            FxSurfaces.prewarm();
            WidgetSurface surface = resolveSurface(owner);
            WebView webView = new WebView();

            // Starts as a 1x1 window at the surface origin so the page can load before the widget
            // tells us how big its card has to be; the presenter shows it once it knows.
            Stage stage = new Stage(StageStyle.UNDECORATED);
            stage.setAlwaysOnTop(true);
            stage.setResizable(false);
            if (owner != null) {
                stage.initOwner(owner);
            }
            stage.setScene(new Scene(webView, 1, 1));
            stage.setX(surface.x);
            stage.setY(surface.y);

            JavaFxWidgetHost host = new JavaFxWidgetHost(stage, webView, surface);
            host.initialize();

            FeedbackWidgetPresenter presenter = new FeedbackWidgetPresenter(host, feedback, onClosed);
            presenter.start(widget);
        } catch (Throwable t) {
            UiLog.e("[CountlyWebView] presentFeedbackWidget, could not show the widget, [" + t + "]");
            run(onClosed);
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

    private static WidgetSurface resolveSurface(Window owner) {
        Window window = owner != null ? owner : FxSurfaces.primaryApplicationWindow();

        if (showWidgetsWithinApp) {
            return FxSurfaces.boundsOf(window);
        }

        // The work area of the screen the application is on, which is not necessarily the primary one.
        return FxSurfaces.screenOf(window);
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
