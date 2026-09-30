package ly.count.sdk.java.ui;

import ly.count.sdk.java.internal.ContentPlacement;
import ly.count.sdk.java.internal.WidgetAction;
import org.json.JSONObject;

/**
 * Parses the {@code postMessage} payloads a feedback widget sends, the
 * {@code {cly_widget_command, action:'resize_me', resize_me:{p,l}, close}} shape. This is a
 * different channel from the URL based signals that
 * {@link ly.count.sdk.java.internal.WidgetActionParser} handles: a widget rendered with the web
 * model reports its own card size this way rather than by navigating.
 */
public class WidgetMessageParser {

    private WidgetMessageParser() {
    }

    /**
     * @param json the bridged payload
     * @return the parsed signal, or {@code null} when the payload is not a widget command
     */
    public static WidgetAction parse(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }

        JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (Throwable t) {
            return null;
        }

        if (!root.has("cly_widget_command")) {
            return null;
        }

        WidgetAction action = new WidgetAction();
        action.isSdkSignal = true;
        action.isWidgetCommand = true;
        action.close = isTruthy(root.opt("close"));

        JSONObject resize = root.optJSONObject("resize_me");
        if (resize != null) {
            action.portrait = toPlacement(resize.optJSONObject("p"));
            action.landscape = toPlacement(resize.optJSONObject("l"));
            action.hasResize = action.portrait != null || action.landscape != null;
        }

        return action;
    }

    private static ContentPlacement toPlacement(JSONObject rect) {
        if (rect == null) {
            return null;
        }
        int width = rect.optInt("w", 0);
        int height = rect.optInt("h", 0);
        if (width <= 0 || height <= 0) {
            return null;
        }
        return new ContentPlacement(rect.optInt("x", 0), rect.optInt("y", 0), width, height);
    }

    private static boolean isTruthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue() != 0;
        }
        String asString = value.toString();
        return "1".equals(asString) || "true".equalsIgnoreCase(asString);
    }
}
