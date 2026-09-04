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
    private ContentPlacement lastRequested;

    /** The card as the page last drew it, which outranks a request that only differs by a border. */
    private ContentPlacement lastDrawn;

    /**
     * How far a page's request may differ from the card it draws and still mean the same card. Wider
     * than the host's fit tolerance on purpose: this is about a template's own arithmetic being off
     * by a border or a shadow, not about a card that changed.
     */
    private static final int NEAR_ENOUGH = 24;

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

        // With the surface, so the page can lay its card out against the real area and report the
        // rectangle it wants. Every template reads these two numbers out of the custom parameter.
        WidgetSurface surface = host.getSurface();
        String url = feedback.constructFeedbackWidgetUrl(widgetToShow,
            surface == null ? 0 : surface.width, surface == null ? 0 : surface.height);
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

    @Override
    public void onSizeNotReported(int paintedWidth, int paintedHeight) {
        // What a widget page paints is not the card: a rating paints only its little sticky tab, and
        // placing that measurement gave a 50px sliver of a window. For a type we know, the type's
        // own card wins; the measurement is only worth anything when the type is not known at all.
        boolean known = widget != null && widget.type != null;
        place(!known && paintedWidth > 0 && paintedHeight > 0
            ? new ContentPlacement(0, 0, paintedWidth, paintedHeight) : null);
    }

    private void handle(WidgetAction action) {
        if (action.hasResize) {
            UiLog.d("[FeedbackWidgetPresenter] handle, the page asked to be "
                + action.resizeFor(host.getSurface().isLandscape()) + " on surface " + host.getSurface());
            place(reconciledWithDrawn(WidgetPlacement.resolve(action, host.getSurface())));
        }

        if (action.link != null) {
            ExternalBrowser.open(action.link);
        }

        if (action.close) {
            UiLog.i("[FeedbackWidgetPresenter] handle, the widget asked to be closed");
            finish();
        }
    }

    @Override
    public void onCardMeasured(int width, int height) {
        if (widget != null && !WidgetLayout.usesReportedSize(widget.type)) {
            // A rating's card is a fixed size, so re-placing it from a measurement lands on the same
            // rectangle. Answering anyway just made the host measure again, three times over, and a
            // card is not shown until the fitting settles.
            return;
        }

        // What the page drew wins over what it asked for: the card is what the user sees, and a
        // window taller than the card leaves it floating above the edge it is anchored to. The
        // measurement has to be taken after the page's own step transition, though, or it describes
        // a card mid fade rather than the step it is becoming - that timing lives in the host.
        lastDrawn = new ContentPlacement(0, 0, width, height);
        place(lastDrawn);
    }

    @Override
    public void onCardFollowing(int width, int height) {
        if (finished || widget == null || !WidgetLayout.usesReportedSize(widget.type)) {
            return;
        }

        // The same anchor rule as a placement, without any of its machinery: this runs for every
        // frame the page animates, so the card grows with the animation rather than after it.
        ContentPlacement following = WidgetLayout.resolve(widget.type, widget.position,
            host.getSurface(), new ContentPlacement(0, 0, width, height));
        if (following == null) {
            return;
        }
        lastRequested = new ContentPlacement(0, 0, width, height);
        lastDrawn = lastRequested;
        host.followGeometry(following);
    }

    @Override
    public void onContentOverflow(int extraHeight) {
        ContentPlacement current = lastRequested;
        if (finished || extraHeight <= 0 || current == null) {
            return;
        }

        // Taller by exactly what the content could not fit, keeping the width the page chose. The
        // host bounds this by the surface before it asks.
        UiLog.d("[FeedbackWidgetPresenter] onContentOverflow, growing the card by [" + extraHeight + "] px");
        place(new ContentPlacement(current.x, current.y, current.width, current.height + extraHeight));
    }

    /**
     * Puts the card back where it belongs after the window or screen it is anchored to moved or was
     * resized. Nothing happens until the widget has asked to be placed at least once.
     */
    public void refreshPlacement() {
        if (finished || (lastRequested == null && !surfaceReported)) {
            return;
        }

        // The widget sizes its own card against the room it was told it has, so a resized window is
        // news it has to act on: it answers with a fresh rect, which places the card again.
        WidgetSurface surface = host.getSurface();
        if (surfaceReported) {
            host.reportSurfaceSize(surface.width, surface.height);
        }
        place(lastRequested);
    }

    /**
     * Where a page's own number and the card it actually paints disagree by a few pixels, the paint
     * wins, and this stops the two from correcting each other forever.
     * <p>
     * A survey template asked for 574 and drew 580 - a border its own height calculation does not
     * count. Honouring the request left six pixels of card clipped, measuring the card then grew
     * the window to 580, the page's next message asked for 574 again, and so on: a visible loop,
     * restarted by every window resize. A request this close to the drawn card now resolves to the
     * drawn card, which is what is already on screen, so nothing moves. A request that differs by
     * more than {@link #NEAR_ENOUGH} is a genuinely new step and is honoured as asked.
     *
     * @param requested what the page asked for, surface absolute
     * @return the same rectangle, or one carrying the drawn card's size when that is what it means
     */
    private ContentPlacement reconciledWithDrawn(ContentPlacement requested) {
        ContentPlacement drawn = lastDrawn;
        if (requested == null || drawn == null) {
            return requested;
        }
        if (Math.abs(requested.width - drawn.width) > NEAR_ENOUGH
            || Math.abs(requested.height - drawn.height) > NEAR_ENOUGH) {
            return requested;
        }
        if (requested.width == drawn.width && requested.height == drawn.height) {
            return requested;
        }
        UiLog.d("[FeedbackWidgetPresenter] reconciledWithDrawn, the page asked for " + requested.width
            + "x" + requested.height + " but draws " + drawn.width + "x" + drawn.height + ", keeping the drawn size");
        return new ContentPlacement(requested.x, requested.y, drawn.width, drawn.height);
    }

    /**
     * Places the card the widget asked for. The size is the widget's own, since its page measured
     * its content to get it; the anchor is the widget type's, since on the web that comes from the
     * SDK's stylesheet and the page's numbers assume it was applied.
     *
     * @param requested the rect the widget asked for, {@code null} when it asked for nothing
     */
    private void place(ContentPlacement requested) {
        UiLog.d("[FeedbackWidgetPresenter] place, requested " + requested);
        lastRequested = requested;
        WidgetSurface surface = host.getSurface();
        ContentPlacement rect = WidgetLayout.resolve(
            widget == null ? null : widget.type,
            widget == null ? null : widget.position,
            surface, requested);

        UiLog.d("[FeedbackWidgetPresenter] place, putting the " + (widget == null ? "widget" : widget.type)
            + " at " + rect + " (it asked for " + requested + ")");
        host.placeAndShow(rect);
    }

    /**
     * The card went away without the SDK closing it. Reported and torn down like any other dismissal,
     * once; a no-op after finish() has already run.
     */
    void dismissedExternally() {
        if (finished) {
            return;
        }
        UiLog.i("[FeedbackWidgetPresenter] dismissedExternally, the card was hidden from outside the SDK");
        finish();
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
