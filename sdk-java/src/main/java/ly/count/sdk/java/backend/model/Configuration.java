package ly.count.sdk.java.backend.model;

import java.net.MalformedURLException;
import java.net.URL;

public class Configuration {
    private String appKey;
    private String serverURL;

    public Configuration(String serverURL, String appKey) {
        this.appKey = appKey;
        this.serverURL = serverURL;
    }

    public String getAppKey() {
        return appKey;
    }

    public void setAppKey(String appKey) {
        this.appKey = appKey;
    }

    public String getServerURL() {
        return serverURL;
    }

    public void setServerURL(String serverURL) {
        this.serverURL = serverURL;
    }
}
