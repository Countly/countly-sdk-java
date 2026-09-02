package ly.count.sdk.java.ui;

import ly.count.sdk.java.internal.ContentPlacement;

/**
 * What {@link FeedbackWidgetPresenter} needs from an embedded browser. Keeping the presentation
 * logic behind this interface is what makes it testable without starting a JavaFX toolkit.
 */
public interface WidgetWebHost {

    /**
     * Events a host reports back to whoever drives it.
     */
    interface Listener {

        /**
         * @param url the URL the host is about to navigate to, including the SDK's own signalling URLs
         */
        void onNavigationStarting(String url);

        /**
         * A postMessage payload the widget sent, bridged out of the page as raw JSON.
         *
         * @param json the payload
         */
        void onWidgetMessage(String json);

        /**
         * The widget page finished loading, so it is safe to tell it how much room it has.
         */
        void onPageLoaded();

        /**
         * The widget page could not be loaded at all.
         */
        void onLoadFailed();

        /**
         * The page loaded but never reported a card size, so the placement has to be decided from
         * the widget type instead. Rating widgets never report one.
         *
         * @param paintedWidth the width of the card the page painted, {@code 0} when it painted none
         * @param paintedHeight the height of the card the page painted, {@code 0} when it painted none
         */
        void onSizeNotReported(int paintedWidth, int paintedHeight);

        /**
         * The size of the card the page actually drew, once it has settled and when it differs from
         * the size the host was given. A page draws its card at the top of the viewport, so a window
         * any taller than the card shows transparent space underneath it, and a card meant to sit on
         * the bottom edge floats above it instead.
         *
         * @param width the drawn card's width
         * @param height the drawn card's height
         */
        void onCardMeasured(int width, int height);
    }

    /**
     * @param listener who to report events to
     */
    void setListener(Listener listener);

    /**
     * @return the area the widget may place itself on
     */
    WidgetSurface getSurface();

    /**
     * The area moved, because the window or the screen the card belongs to did.
     *
     * @param surface the new area, {@code null} is ignored
     */
    void setSurface(WidgetSurface surface);

    /**
     * @param url the URL to load
     */
    void navigate(String url);

    /**
     * Tell the page how much room it has, by posting a {@code {type:'resize',width,height}} message.
     *
     * @param width available width
     * @param height available height
     */
    void reportSurfaceSize(int width, int height);

    /**
     * Move and size the host to the given screen absolute rectangle, and show it.
     *
     * @param rect where to put the host
     */
    void placeAndShow(ContentPlacement rect);

    /**
     * Dismiss the host.
     */
    void closeHost();
}
