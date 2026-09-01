package ly.count.sdk.java.ui;

import ly.count.sdk.java.internal.ContentPlacement;
import ly.count.sdk.java.internal.CountlyFeedbackWidget;
import ly.count.sdk.java.internal.ModuleFeedback;
import ly.count.sdk.java.internal.SDKCore;
import ly.count.sdk.java.internal.WidgetAction;
import ly.count.sdk.java.internal.WidgetActionParser;

/**
 * Drives one feedback widget through a {@link WidgetWebHost}: loads the widget URL, tells the page
 * how much room it has once it is up, places the card where the widget asks for it, and reports the
 * result back to the SDK when the widget closes.
 * <p>
 * Free of any UI toolkit, so it can be tested against a fake host.
 */
public class FeedbackWidgetPresenter implements WidgetWebHost.Listener {

    private final WidgetWebHost host;
    private final ModuleFeedback.Feedback feedback;
    private final Runnable onClosed;

    private CountlyFeedbackWidget widget;
    private boolean surfaceReported = false;
    private boolean finished = false;

    /**
     * @param host the browser to drive
     * @param feedback the SDK's feedback interface, used to build the URL and report the result
     * @param onClosed called once, when the widget is gone, may be {@code null}
     */
    public FeedbackWidgetPresenter(WidgetWebHost host, ModuleFeedback.Feedback feedback, Runnable onClosed) {
        this.host = host;
        this.feedback = feedback;
        this.onClosed = onClosed;
        host.setListener(this);
    }

    /**
     * Load the given widget.
     *
     * @param widgetToShow the widget to present
     */
    public void start(CountlyFeedbackWidget widgetToShow) {
        widget = widgetToShow;

        if (widgetToShow == null) {
            UiLog.w("[FeedbackWidgetPresenter] start, no widget was given, nothing to present");
            finish();
            return;
        }

        if (feedback == null) {
            // The SDK is not initialized, or feedback consent was not given.
            UiLog.w("[FeedbackWidgetPresenter] start, the feedback interface is not available, nothing to present");
            finish();
            return;
        }

        String url = feedback.constructFeedbackWidgetUrl(widgetToShow);
        if (url == null || url.trim().isEmpty()) {
            UiLog.w("[FeedbackWidgetPresenter] start, could not build a URL for widget [" + widgetToShow.widgetId + "]");
            finish();
            return;
        }

        UiLog.i("[FeedbackWidgetPresenter] start, presenting widget [" + widgetToShow.widgetId + "]");
        host.navigate(url);
    }

    @Override
    public void onPageLoaded() {
        if (surfaceReported) {
            return;
        }
        surfaceReported = true;

        WidgetSurface surface = host.getSurface();
        UiLog.d("[FeedbackWidgetPresenter] onPageLoaded, reporting surface " + surface);
        // The widget can only work out its own card size once it knows the viewport, and it only
        // listens for that after its own page has loaded.
        host.reportSurfaceSize(surface.width, surface.height);
    }

    @Override
    public void onLoadFailed() {
        UiLog.w("[FeedbackWidgetPresenter] onLoadFailed, the widget page could not be loaded");
        // Without this the caller would be left with an invisible card and no callback.
        finish();
    }

    @Override
    public void onNavigationStarting(String url) {
        WidgetAction action = WidgetActionParser.parse(url, SDKCore.logger());
        if (!action.isSdkSignal) {
            return;
        }

        if (action.isExternalLink) {
            UiLog.d("[FeedbackWidgetPresenter] onNavigationStarting, opening an external link");
            ExternalBrowser.open(action.link);
            return;
        }

        handle(action);
    }

    @Override
    public void onWidgetMessage(String json) {
        WidgetAction action = WidgetMessageParser.parse(json);
        if (action == null) {
            return;
        }
        handle(action);
    }

    private void handle(WidgetAction action) {
        if (action.hasResize) {
            ContentPlacement rect = WidgetPlacement.resolve(action, host.getSurface());
            if (rect != null) {
                UiLog.d("[FeedbackWidgetPresenter] handle, placing the widget at " + rect);
                host.placeAndShow(rect);
            }
        }

        if (action.link != null) {
            ExternalBrowser.open(action.link);
        }

        if (action.close) {
            UiLog.i("[FeedbackWidgetPresenter] handle, the widget asked to be closed");
            finish();
        }
    }

    /**
     * Tears the presentation down exactly once: reports the dismissal to the SDK, closes the host
     * and tells the caller.
     */
    private void finish() {
        if (finished) {
            return;
        }
        finished = true;

        if (widget != null && feedback != null) {
            try {
                // The widget itself reports a completed result; this marks the dismissal.
                feedback.reportFeedbackWidgetManually(widget, null, null);
            } catch (Throwable t) {
                // Reporting is the least important part of tearing down. If it throws, the card
                // still has to close and the caller still has to be told, or the presentation
                // wedges with a stuck window and no callback.
                UiLog.e("[FeedbackWidgetPresenter] finish, reporting the dismissal failed, [" + t + "]");
            }
        }

        try {
            host.closeHost();
        } catch (Throwable t) {
            UiLog.e("[FeedbackWidgetPresenter] finish, the host failed to close, [" + t + "]");
        }

        if (onClosed != null) {
            try {
                onClosed.run();
            } catch (Throwable t) {
                UiLog.e("[FeedbackWidgetPresenter] finish, the close callback threw, [" + t + "]");
            }
        }
    }
}
