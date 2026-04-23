package ly.count.javafx.demo;

import java.io.File;

public final class AppContext {

    public static final String DEFAULT_SERVER_URL = "https://your.server.ly";
    public static final String DEFAULT_APP_KEY = "YOUR_APP_KEY";
    public static final String DEFAULT_DEVICE_ID = "JAVA_FX_DEMO_DEVICE";
    public static final String APP_VERSION = "1.0.0";

    // Must match the values the Countly Java SDK sends for the SDK-driven
    // flows, so our raw feedback HTTP call (used to recover the wv field
    // for URL construction) identifies the same client.
    public static final String SDK_NAME = "java-native";
    public static final String SDK_VERSION = "24.1.5";
    public static final String PLATFORM = "desktop";

    // Populated from the Init tab once the user initializes the SDK; the
    // feedback-widgets pane needs these to reach the server directly for
    // the raw /o/sdk?method=feedback call (the SDK's Config does not expose
    // them publicly to code outside its package).
    public static volatile String liveServerUrl = DEFAULT_SERVER_URL;
    public static volatile String liveAppKey    = DEFAULT_APP_KEY;

    public static File storageDir() {
        File dir = new File(
            System.getProperty("user.home")
                + File.separator + "__COUNTLY"
                + File.separator + "java_fx_demo"
        );
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private AppContext() {}
}
