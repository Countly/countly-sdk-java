package ly.count.sdk.java.ui;

/**
 * The area a feedback widget or a content block may be placed on, in screen absolute JavaFX
 * coordinates. JavaFX already works in logical pixels, so these are the same units a web page lays
 * itself out in and no density conversion is needed.
 */
public class WidgetSurface {

    public final int x;
    public final int y;
    public final int width;
    public final int height;

    public WidgetSurface(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    /**
     * @return {@code true} when the surface is wider than it is tall
     */
    public boolean isLandscape() {
        return width >= height;
    }

    /**
     * @param other the surface to compare against, may be {@code null}
     * @return whether the two describe the same area
     */
    public boolean sameAs(WidgetSurface other) {
        return other != null && x == other.x && y == other.y && width == other.width && height == other.height;
    }

    /**
     * @param other the surface to compare against, may be {@code null}
     * @return whether the two are the same size, wherever they sit
     */
    public boolean sameSizeAs(WidgetSurface other) {
        return other != null && width == other.width && height == other.height;
    }

    @Override
    public String toString() {
        return "WidgetSurface{x=" + x + ", y=" + y + ", width=" + width + ", height=" + height + '}';
    }
}
