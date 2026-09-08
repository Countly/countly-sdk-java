package ly.count.sdk.java.internal;

/**
 * The surface a {@link ContentDisplay} can place content on. The SDK reports these dimensions to
 * the server, and the server answers with a {@link ContentPlacement} expressed in the same units,
 * so a display must report and place in one consistent unit (CSS pixels on a desktop web view).
 *
 * @apiNote This is an EXPERIMENTAL feature, and it can have breaking changes
 */
public class ContentScreen {
    public final int width;
    public final int height;

    public ContentScreen(int width, int height) {
        this.width = width;
        this.height = height;
    }

    @Override
    public String toString() {
        return "ContentScreen{width=" + width + ", height=" + height + '}';
    }
}
