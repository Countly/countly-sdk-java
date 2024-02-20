package ly.count.sdk.java.internal;

import java.util.Map;
import ly.count.sdk.java.Config;

public class ConfigViews {
    private final Config config;
    protected Map<String, Object> globalViewSegmentation = null;

    public ConfigViews(Config config) {
        this.config = config;
    }

    /**
     * @param segmentation segmentation values that will be added for all recorded views (manual and automatic)
     * @return Returns the same config object for convenient linking
     */
    public Config setGlobalViewSegmentation(Map<String, Object> segmentation) {
        globalViewSegmentation = segmentation;
        return config;
    }
}
