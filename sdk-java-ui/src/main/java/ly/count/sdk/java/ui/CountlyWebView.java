package ly.count.sdk.java.ui;

import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.web.WebView;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import ly.count.sdk.java.Countly;
import ly.count.sdk.java.internal.CountlyFeedbackWidget;
import ly.count.sdk.java.internal.ModuleContent;
import ly.count.sdk.java.internal.ModuleFeedback;

/**
 * Entry point for showing Countly feedback widgets and Countly content in a JavaFX application.
 * <p>
 * The core SDK stays headless: it fetches and reports, this package draws. Initialize the SDK as
 * usual, then present a widget or turn the content zone on.
 *
 * <pre>
 * // Feedback widgets
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
     * Register the JavaFX content display with the SDK and enter the content zone, limited to the
     * given categories. Must be called on the JavaFX application thread.
     *
     * @param categories the content categories to ask for, {@code null} or empty for all
     * @apiNote This is an EXPERIMENTAL feature, and it can have breaking changes
     */
    public static void enableContentZone(String[] categories) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> enableContentZone(categories));
            return;
        }

        ModuleContent.Content content = Countly.instance().content();
        if (content == null) {
            UiLog.w("[CountlyWebView] enableContentZone, the content interface is not available, ignoring the call");
            return;
        }

        if (contentDisplay == null) {
            contentDisplay = new JavaFxContentDisplay();
        }

        content.setContentDisplay(contentDisplay);
        content.enterContentZone(categories);
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
        if (showWidgetsWithinApp && owner != null) {
            return new WidgetSurface((int) owner.getX(), (int) owner.getY(), (int) owner.getWidth(), (int) owner.getHeight());
        }

        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        return new WidgetSurface((int) bounds.getMinX(), (int) bounds.getMinY(), (int) bounds.getWidth(), (int) bounds.getHeight());
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
