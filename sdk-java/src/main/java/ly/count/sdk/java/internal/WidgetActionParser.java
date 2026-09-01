package ly.count.sdk.java.internal;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/**
 * Parses the URLs a feedback widget or a content block navigates to in order to talk back to the
 * SDK. Both use the {@code https://countly_action_event} host, plus an {@code cly_x_int=1} flag on
 * any URL that should be handed to an external browser instead.
 * <p>
 * Kept free of any UI toolkit so it can be unit tested and reused by every display implementation.
 */
public class WidgetActionParser {

    /**
     * The host a widget or content block navigates to in order to talk to the SDK. Public because a
     * display implementation outside this package has to recognise it, and because JavaFX's network
     * stack reports it as an error that has to be filtered by name.
     */
    public static final String ACTION_HOST = "countly_action_event";
    public static final String ACTION_URL_START = "https://" + ACTION_HOST;

    private WidgetActionParser() {
    }

    /**
     * @param url the URL the web view is about to navigate to
     * @param L logger
     * @return the parsed signal, never {@code null}; check {@link WidgetAction#isSdkSignal} to see
     *     whether the URL was one of the SDK's own
     */
    public static WidgetAction parse(String url, Log L) {
        WidgetAction action = new WidgetAction();

        if (Utils.isEmptyOrNull(url)) {
            return action;
        }

        Map<String, Object> query = parseQuery(url);
        action.queryParams = query;
        action.isExternalLink = "1".equals(query.get("cly_x_int"));
        action.isWidgetCommand = "1".equals(query.get("cly_widget_command"));
        action.isActionEvent = "1".equals(query.get("cly_x_action_event"));
        action.isSdkSignal = url.contains(ACTION_HOST) || action.isExternalLink || action.isWidgetCommand || action.isActionEvent;

        if (!action.isSdkSignal) {
            return action;
        }

        if (action.isExternalLink) {
            // The whole URL is the destination; there is nothing else to process.
            action.link = url;
            return action;
        }

        action.close = isTruthy(query.get("close"));

        Object resize = query.get("resize_me");
        if (resize != null) {
            readResize(action, resize.toString(), L);
        }

        Object link = query.get("link");
        if (link != null && !Utils.isEmptyOrNull(link.toString())) {
            // A close carried inside the destination's own query ("link=https://x?close=1") is a
            // signal to us, not part of the destination, so it is honoured and then stripped.
            Map<String, Object> linkQuery = parseQuery(link.toString());
            if (!action.close && isTruthy(linkQuery.get("close"))) {
                action.close = true;
            }
            action.link = stripParam(link.toString(), "close");
        }

        Object event = query.get("event");
        if (event != null && !Utils.isEmptyOrNull(event.toString())) {
            action.eventPayload = event.toString();
        }

        return action;
    }

    private static void readResize(WidgetAction action, String resize, Log L) {
        try {
            JSONObject rects = new JSONObject(resize);
            action.portrait = toPlacement(rects.optJSONObject("p"));
            action.landscape = toPlacement(rects.optJSONObject("l"));
            action.hasResize = action.portrait != null || action.landscape != null;
        } catch (Throwable t) {
            if (L != null) {
                L.w("[WidgetActionParser] readResize, malformed 'resize_me' payload, ignoring it, [" + t + "]");
            }
        }
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
        String asString = value.toString();
        return "1".equals(asString) || "true".equalsIgnoreCase(asString);
    }

    /**
     * @param url the URL to clean up
     * @param name the query parameter to drop
     * @return the URL without that parameter, and without the '?' if nothing else is left
     */
    static String stripParam(String url, String name) {
        int question = url.indexOf('?');
        if (question < 0) {
            return url;
        }

        StringBuilder kept = new StringBuilder();
        for (String pair : url.substring(question + 1).split("&")) {
            int equals = pair.indexOf('=');
            String key = equals > 0 ? pair.substring(0, equals) : pair;
            if (name.equals(key)) {
                continue;
            }
            if (kept.length() > 0) {
                kept.append('&');
            }
            kept.append(pair);
        }

        String base = url.substring(0, question);
        if (kept.length() == 0) {
            return base;
        }
        return base + "?" + kept;
    }

    /**
     * @param url URL to read the query of
     * @return the URL decoded query parameters, in the order they appeared
     */
    static Map<String, Object> parseQuery(String url) {
        Map<String, Object> result = new LinkedHashMap<>();
        int question = url.indexOf('?');
        if (question < 0 || question == url.length() - 1) {
            return result;
        }

        for (String pair : url.substring(question + 1).split("&")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            result.put(decode(pair.substring(0, equals)), decode(pair.substring(equals + 1)));
        }
        return result;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, Utils.UTF8);
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return value;
        }
    }
}
