package ly.count.sdk.java.internal;

import java.net.URL;

/**
 * Builds the URL that renders a feedback widget in a web view. Kept free of any UI toolkit so it
 * can be unit tested and reused by every display implementation.
 */
class WidgetUrlBuilder {

    /**
     * Desktop follows the web SDK model: the widget draws itself as a positioned card with its own
     * close button, rather than filling the whole viewport. {@code tc} lets it close itself,
     * {@code xb} makes it draw the close button.
     */
    static final String CUSTOM_PARAMS = "{\"tc\":1,\"xb\":1}";

    /**
     * What a feedback widget is told this platform is, and what its answers are segmented by.
     * <p>
     * One value for every desktop, the way the Android SDK sends {@code android} and the web SDK
     * sends {@code web}. The SDK's configurable platform defaults to the operating system's name,
     * which would split one integration's widget answers across "Mac OS X", "Windows 11" and
     * "Linux" in the dashboard while adding nothing: the operating system is already reported, as
     * {@code _os} and {@code _os_version} in the session metrics. It also has to agree with what the
     * widget page itself reports, since the page sends this value back with the answer.
     * <p>
     * The templates read it for one decision only - whether to draw their own close button, which
     * they do when it is {@code Web} - so any other value behaves identically. This is also the
     * value {@code ContentRequestBuilder} declares as {@code dt} for content.
     */
    static final String PLATFORM = ContentRequestBuilder.DEVICE_TYPE;

    /**
     * The area the widget has, in the {@code custom} parameter, which is where every template reads
     * it from: {@code custom.width} and {@code custom.height} seed the parent dimensions each one
     * computes its card against. Without them an NPS page falls through to its largest breakpoint
     * with undefined dimensions, so it caps its own height and anchors to zero instead of the bottom
     * edge. The web SDK sends the same two values.
     *
     * @param width the surface width in logical pixels, ignored when not positive
     * @param height the surface height in logical pixels, ignored when not positive
     * @return the custom parameter to send
     */
    static String customParams(int width, int height) {
        if (width <= 0 || height <= 0) {
            return CUSTOM_PARAMS;
        }
        return "{\"tc\":1,\"xb\":1,\"width\":" + width + ",\"height\":" + height + "}";
    }

    private WidgetUrlBuilder() {
    }

    /**
     * @param config to read the server URL, app key, device ID and SDK identity from
     * @param widget the widget to display
     * @param appVersion the application version to report
     * @return the URL to load in a web view
     */
    static String build(InternalConfig config, CountlyFeedbackWidget widget, String appVersion) {
        return build(config, widget, appVersion, 0, 0);
    }

    /**
     * @param config to read the server URL, app key, device ID and SDK identity from
     * @param widget the widget to display
     * @param appVersion the application version to report
     * @param surfaceWidth the width the widget has to lay itself out in
     * @param surfaceHeight the height the widget has to lay itself out in
     * @return the URL to load in a web view
     */
    static String build(InternalConfig config, CountlyFeedbackWidget widget, String appVersion,
        int surfaceWidth, int surfaceHeight) {
        Params params = new Params()
            .add("widget_id", widget.widgetId)
            .add("device_id", config.getDeviceId().id)
            .add("app_key", config.getServerAppKey())
            .add("sdk_version", config.getSdkVersion())
            .add("sdk_name", config.getSdkName())
            .add("platform", PLATFORM);

        if (!Utils.isEmptyOrNull(appVersion)) {
            params.add("app_version", appVersion);
        }

        params.add("custom", customParams(surfaceWidth, surfaceHeight));

        // The widget page only accepts the SDK's post-load {type:'resize'} message when 'origin'
        // matches the page's own origin. Without it the message is dropped and the widget has no
        // viewport to size itself against. Sent unencoded, the same way the web SDK sends it.
        String origin = originOf(config.getServerURL());
        if (origin != null) {
            params.add("&origin=" + origin);
        }

        return config.getServerURL() + "/feedback/" + widget.type.name() + "?" + params;
    }

    /**
     * @param serverUrl the configured server URL
     * @return scheme and authority of the server URL, or {@code null} when there is none
     */
    static String originOf(URL serverUrl) {
        if (serverUrl == null || serverUrl.getProtocol() == null || Utils.isEmptyOrNull(serverUrl.getHost())) {
            return null;
        }

        String origin = serverUrl.getProtocol() + "://" + serverUrl.getHost();
        if (serverUrl.getPort() > 0) {
            origin += ":" + serverUrl.getPort();
        }
        return origin;
    }
}
