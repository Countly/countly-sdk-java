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
