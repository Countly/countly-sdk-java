package ly.count.sdk.java.internal;

/**
 * Lets the application open links itself instead of the SDK handing them to the desktop.
 * <p>
 * Register one with {@link ConfigContent#setContentUrlHandler(ContentUrlHandler)}. It is consulted
 * for links from both content blocks and feedback widgets, which is how an application routes its
 * own deep links, custom scheme or https, to the right screen rather than having them opened in a
 * browser.
 *
 * @apiNote This is an EXPERIMENTAL feature, and it can have breaking changes
 */
public interface ContentUrlHandler {

    /**
     * @param url the URL the web content is trying to open
     * @return {@code true} if the application handled it; {@code false} to let the SDK open it as
     *     usual
     */
    boolean onContentUrl(String url);
}
