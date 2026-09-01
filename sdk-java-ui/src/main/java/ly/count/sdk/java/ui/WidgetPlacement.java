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

    private static int clamp(int value, int low, int high) {
        if (value < low) {
            return low;
        }
        return Math.min(value, high);
    }
}
