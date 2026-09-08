package ly.count.sdk.java.internal;

import java.util.Locale;

/**
 * Builds the content specific query parameters of a {@code /o/sdk/content} fetch. The common
 * parameters (app key, device ID, timestamp, SDK name and version) are added by
 * {@link ModuleRequests#prepareRequiredParamsAsString(InternalConfig, Object...)}.
 *
 * @apiNote This is an EXPERIMENTAL feature, and it can have breaking changes
 */
class ContentRequestBuilder {

    static final String DEVICE_TYPE = "desktop";

    private ContentRequestBuilder() {
    }

    /**
     * @param screen the surface the content has to fit into
     * @param contentId set to fetch one specific content block as a preview, {@code null} otherwise
     * @param L logger
     * @return the content specific parameters, ready to be appended to a request
     */
    static Params build(ContentScreen screen, String contentId, Log L) {
        int width = screen == null ? 0 : screen.width;
        int height = screen == null ? 0 : screen.height;

        // A desktop surface does not rotate, so both orientations report the same rectangle.
        String resolution = "{\"l\":{\"w\":" + width + ",\"h\":" + height + "},\"p\":{\"w\":" + width + ",\"h\":" + height + "}}";

        Params params = new Params()
            .add("method", "queue")
            .add("resolution", resolution)
            .add("la", language())
            .add("dt", DEVICE_TYPE);

        if (!Utils.isEmptyOrNull(contentId)) {
            params.add("content_id", contentId).add("preview", "true");
        }

        L.v("[ContentRequestBuilder] build, resolution:[" + resolution + "] preview:[" + !Utils.isEmptyOrNull(contentId) + "]");
        return params;
    }

    private static String language() {
        String language = Locale.getDefault().getLanguage();
        return language == null ? "" : language;
    }
}
