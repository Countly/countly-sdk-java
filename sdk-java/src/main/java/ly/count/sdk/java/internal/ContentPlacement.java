package ly.count.sdk.java.internal;

/**
 * A rectangle the server asked a content block to occupy, in the same units the SDK reported the
 * screen resolution in (see {@link ContentScreen}).
 *
 * @apiNote This is an EXPERIMENTAL feature, and it can have breaking changes
 */
public class ContentPlacement {
    public final int x;
    public final int y;
    public final int width;
    public final int height;

    public ContentPlacement(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    @Override
    public String toString() {
        return "ContentPlacement{x=" + x + ", y=" + y + ", width=" + width + ", height=" + height + '}';
    }
}
