package ly.count.sdk.java.internal;

import org.json.JSONObject;

/**
 * Turns a {@code /o/sdk/content} response into a {@link ContentData}.
 * <p>
 * The server answers with a JSON array (for example {@code [{"result":"No content block found!"}]})
 * when it has nothing to show. {@link ImmediateRequestMaker} wraps such an array into a
 * {@code {"jsonArray":[...]}} object, so an array response simply has no {@code html} key here and
 * is reported as "nothing to show".
 *
 * @apiNote This is an EXPERIMENTAL feature, and it can have breaking changes
 */
class ContentParser {

    private ContentParser() {
    }

    /**
     * @param response the parsed server response, may be {@code null}
     * @param L logger
     * @return the content to show, or {@code null} when the response carries none
     */
    static ContentData parse(JSONObject response, Log L) {
        if (response == null) {
            L.d("[ContentParser] parse, no response to parse");
            return null;
        }

        try {
            String url = response.optString("html", "");
            JSONObject geo = response.optJSONObject("geo");

            if (Utils.isEmptyOrNull(url) || geo == null) {
                L.d("[ContentParser] parse, response does not contain a content block");
                return null;
            }

            ContentPlacement portrait = toPlacement(geo.optJSONObject("p"));
            ContentPlacement landscape = toPlacement(geo.optJSONObject("l"));

            if (portrait == null && landscape == null) {
                L.w("[ContentParser] parse, content block has no usable placement, ignoring it");
                return null;
            }

            return new ContentData(url, portrait, landscape);
        } catch (Throwable t) {
            L.e("[ContentParser] parse, failed to parse the content response, [" + t + "]");
            return null;
        }
    }

    private static ContentPlacement toPlacement(JSONObject rect) {
        if (rect == null) {
            return null;
        }
        int width = rect.optInt("w", 0);
        int height = rect.optInt("h", 0);
        if (width <= 0 || height <= 0) {
            // Like the widget parsers: a degenerate rectangle must fall through to the other
            // orientation, or to "no usable placement", rather than become an invisible 0x0 window
            // that holds the zone open with nothing the user can close.
            return null;
        }
        return new ContentPlacement(rect.optInt("x", 0), rect.optInt("y", 0), width, height);
    }
}
