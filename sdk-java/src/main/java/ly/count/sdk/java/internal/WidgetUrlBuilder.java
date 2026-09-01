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

    private WidgetUrlBuilder() {
    }

    /**
     * @param config to read the server URL, app key, device ID and SDK identity from
     * @param widget the widget to display
     * @param appVersion the application version to report
     * @return the URL to load in a web view
     */
    static String build(InternalConfig config, CountlyFeedbackWidget widget, String appVersion) {
        Params params = new Params()
            .add("widget_id", widget.widgetId)
            .add("device_id", config.getDeviceId().id)
            .add("app_key", config.getServerAppKey())
            .add("sdk_version", config.getSdkVersion())
            .add("sdk_name", config.getSdkName())
            .add("platform", config.getSdkPlatform());

        if (!Utils.isEmptyOrNull(appVersion)) {
            params.add("app_version", appVersion);
        }

        params.add("custom", CUSTOM_PARAMS);

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
