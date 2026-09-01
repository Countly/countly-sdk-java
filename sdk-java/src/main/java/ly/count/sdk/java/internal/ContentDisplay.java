package ly.count.sdk.java.internal;

/**
 * The bridge between the headless content module and whatever can actually draw a web view. The
 * SDK core never depends on a UI toolkit, so a display has to be registered with
 * {@link ModuleContent.Content#setContentDisplay(ContentDisplay)} before entering a content zone.
 * <p>
 * The "ly.count.sdk:java-ui" artifact ships a JavaFX implementation. Provide your own to render
 * content with another toolkit.
 *
 * @apiNote This is an EXPERIMENTAL feature, and it can have breaking changes
 */
public interface ContentDisplay {

    /**
     * Called on every fetch, so the SDK can tell the server how much room there is. Return the
     * dimensions in the same unit the content is laid out in, which is CSS pixels for a web view.
     *
     * @return the surface content can be placed on
     */
    ContentScreen getScreen();

    /**
     * Show the given content. Called off the UI thread, so hop onto your toolkit's thread before
     * touching any widget.
     *
     * @param content what to show and where to put it
     * @param onClosed must be called exactly once, when the content is gone
     */
    void present(ContentData content, ContentCloseCallback onClosed);
}
