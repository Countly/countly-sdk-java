package ly.count.sdk.java.ui;

import ly.count.sdk.java.internal.ContentPlacement;
import ly.count.sdk.java.internal.WidgetAction;

/**
 * Maps a rectangle a widget or a content block asked for, which is relative to the surface the SDK
 * reported, onto a screen absolute rectangle that stays inside that surface.
 */
public class WidgetPlacement {

    private WidgetPlacement() {
    }

    /**
     * @param rect the requested rectangle, relative to the surface
     * @param surface the surface the rectangle has to fit into
     * @return a screen absolute rectangle, or {@code null} when there is nothing to place
     */
    public static ContentPlacement resolve(ContentPlacement rect, WidgetSurface surface) {
        if (rect == null || surface == null) {
            return null;
        }

        int width = Math.min(rect.width, surface.width);
        int height = Math.min(rect.height, surface.height);
        int x = surface.x + clamp(rect.x, 0, Math.max(0, surface.width - width));
        int y = surface.y + clamp(rect.y, 0, Math.max(0, surface.height - height));

        return new ContentPlacement(x, y, width, height);
    }

    /**
     * @param action the signal carrying the requested rectangle
     * @param surface the surface the rectangle has to fit into
     * @return a screen absolute rectangle, or {@code null} when the action carries none
     */
    public static ContentPlacement resolve(WidgetAction action, WidgetSurface surface) {
        if (action == null || surface == null) {
            return null;
        }
        return resolve(action.resizeFor(surface.isLandscape()), surface);
    }

    /**
     * The rectangle a block on screen should occupy now, given both parties that have an opinion.
     * <p>
     * The page outranks the server once it has spoken: only the page knows how tall its own content
     * turned out, which is what {@code resize_me} is for, and Android likewise replaces its stored
     * config when that arrives. Whichever rectangle is used is re-stated in the current surface's
     * proportions, because it was computed against the surface in force at the time.
     *
     * @param pageRect what the page last asked for, or {@code null} if it never did
     * @param pageSurface the surface the page asked against
     * @param serverRect what the server computed at fetch
     * @param serverSurface the surface reported to the server
     * @param current the surface the block sits on now
     * @return a screen absolute rectangle, or {@code null} when there is nothing to place
     */
    public static ContentPlacement following(ContentPlacement pageRect, WidgetSurface pageSurface,
        ContentPlacement serverRect, WidgetSurface serverSurface, WidgetSurface current) {
        if (pageRect != null) {
            // A page that asked for its own rectangle sized itself - a survey hosted by the content
            // queue asks for its 500 wide card whatever the window is. That size is not a proportion
            // of anything, so scaling it with the window is wrong: shrinking the application window
            // squeezed a hosted survey to a sliver, its title one letter per line, while the same
            // survey shown as a widget kept its width and was merely clamped. Keep the size, put it
            // against the edges it was against, clamp it onto the current surface; the page re-anchors
            // itself too once it is told the new room. The server's rectangle is what scales, because
            // it is a proportion of the area.
            return resolve(reanchor(pageRect, pageSurface, current), current);
        }
        return resolve(rescale(serverRect, serverSurface, current), current);
    }

    /**
     * Moves a page-sized rectangle to a new surface by keeping it against the edges it was against.
     * <p>
     * A page computes its position for the area it was told about: a bottom-left survey puts itself
     * at {@code y = height - h}. When the window then grows taller, that number is stale, and keeping
     * it left the card floating mid-window until the page happened to recompute. The edge a card
     * hugs is recoverable from its margins - the smaller margin on each axis is the one it was placed
     * by - so the card is put the same distance from the same edge of the new surface. Equal margins
     * mean centred, and stay centred.
     *
     * @param rect the page's rectangle, surface relative
     * @param from the surface it was computed against, {@code null} if unknown
     * @param to the surface it is being placed on now
     * @return the rectangle re-anchored on {@code to}, surface relative
     */
    static ContentPlacement reanchor(ContentPlacement rect, WidgetSurface from, WidgetSurface to) {
        if (rect == null || from == null || to == null || from.width <= 0 || from.height <= 0) {
            return rect;
        }
        if (from.width == to.width && from.height == to.height) {
            return rect;
        }
        return new ContentPlacement(
            along(rect.x, rect.width, from.width, to.width),
            along(rect.y, rect.height, from.height, to.height),
            rect.width, rect.height);
    }

    /** One axis of {@link #reanchor}: the offset that keeps the smaller margin, or the centre. */
    private static int along(int start, int size, int fromExtent, int toExtent) {
        int leading = start;
        int trailing = fromExtent - (start + size);
        if (Math.abs(leading - trailing) <= 2) {
            return Math.max(0, (toExtent - size) / 2);
        }
        if (leading <= trailing) {
            return leading;
        }
        return toExtent - trailing - size;
    }

    /**
     * Re-states a rectangle the server computed for one surface against another.
     * <p>
     * The server lays a block out as a proportion of the area it was told about - a large sticky
     * centre block is 60% of the width, a corner one 45%, each with a cap - so the only faithful way
     * to follow a window that changed size is to keep those proportions. Carrying the absolute
     * rectangle over and clamping it into the new area instead squeezes the card: a 768 wide block
     * became 487 wide as the window narrowed, rather than staying the same share of it.
     *
     * @param rect what the server asked for
     * @param from the surface the server was told about, {@code null} if unknown
     * @param to the surface the block is being placed on now
     * @return the rectangle in {@code to}'s proportions, surface relative like the server's own
     */
    public static ContentPlacement rescale(ContentPlacement rect, WidgetSurface from, WidgetSurface to) {
        if (rect == null || from == null || to == null || from.width <= 0 || from.height <= 0) {
            return rect;
        }
        if (from.width == to.width && from.height == to.height) {
            return rect;
        }

        double horizontal = (double) to.width / from.width;
        double vertical = (double) to.height / from.height;

        return new ContentPlacement(
            (int) Math.round(rect.x * horizontal),
            (int) Math.round(rect.y * vertical),
            (int) Math.round(rect.width * horizontal),
            (int) Math.round(rect.height * vertical));
    }

    private static int clamp(int value, int low, int high) {
        if (value < low) {
            return low;
        }
        return Math.min(value, high);
    }
}
