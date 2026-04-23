package ly.count.javafx.demo.ui;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Mirrors cpp_demo's fwFetchWidgets + fwConstructWebViewUrl: raw HTTP
 * access to the Countly feedback endpoint so we can read the widget's
 * {@code wv} (widget version) field — the SDK's typed
 * {@code CountlyFeedbackWidget} drops it, but the WebView close button
 * only renders when {@code custom.xb=1 & custom.rw=1} is present on
 * versioned widgets. See Countly_Feedback_Widget_Implementation_Guide.html.
 */
final class FeedbackHttp {

    static final String COMM_HOST = "countly_action_event";

    /** Raw feedback endpoint response, per widget. */
    static final class RawWidget {
        final String id;
        final String type;
        final String name;
        final String wv; // "" if legacy
        RawWidget(String id, String type, String name, String wv) {
            this.id = id; this.type = type; this.name = name; this.wv = wv;
        }
    }

    /** widgetId → widget version ("" if legacy / no wv). */
    static Map<String, String> fetchVersions(
            String serverUrl, String appKey, String deviceId,
            String sdkName, String sdkVersion) throws Exception {
        Map<String, String> out = new HashMap<>();
        String url = stripTrailingSlash(serverUrl) + "/o/sdk"
            + "?method=feedback"
            + "&app_key="    + enc(appKey)
            + "&device_id="  + enc(deviceId)
            + "&sdk_version=" + enc(sdkVersion)
            + "&sdk_name="   + enc(sdkName);

        String body = httpGet(url);
        JSONObject j = new JSONObject(body);
        JSONArray arr = j.optJSONArray("result");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject w = arr.optJSONObject(i);
            if (w == null) continue;
            String id = w.optString("_id", "");
            String wv = w.optString("wv", "");
            if (!id.isEmpty()) out.put(id, wv);
        }
        return out;
    }

    /**
     * Builds the widget WebView URL exactly like cpp_demo's
     * fwConstructWebViewUrl: tc=1 always, xb=1 &amp; rw=1 when widgetVersion
     * is present, plus app_version which the SDK's constructFeedbackWidgetUrl()
     * omits.
     */
    static String constructWebViewUrl(
            String serverUrl, String appKey, String deviceId,
            String sdkName, String sdkVersion, String appVersion, String platform,
            String widgetId, String widgetType, String widgetVersion) {

        JSONObject custom = new JSONObject();
        custom.put("tc", 1);
        if (widgetVersion != null && !widgetVersion.isEmpty()) {
            custom.put("xb", 1);
            custom.put("rw", 1);
        }

        return stripTrailingSlash(serverUrl) + "/feedback/" + widgetType
            + "?widget_id="   + enc(widgetId)
            + "&device_id="   + enc(deviceId)
            + "&app_key="     + enc(appKey)
            + "&sdk_version=" + enc(sdkVersion)
            + "&sdk_name="    + enc(sdkName)
            + "&app_version=" + enc(appVersion)
            + "&platform="    + enc(platform)
            + "&custom="      + enc(custom.toString());
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        conn.setInstanceFollowRedirects(true);
        int status = conn.getResponseCode();
        if (status != 200) {
            throw new RuntimeException("HTTP " + status + " for " + url);
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return s;
        }
    }

    private static String stripTrailingSlash(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private FeedbackHttp() {}
}
