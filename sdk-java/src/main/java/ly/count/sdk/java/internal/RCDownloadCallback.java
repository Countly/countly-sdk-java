package ly.count.sdk.java.internal;

import java.util.Map;

public interface RCDownloadCallback {
    /**
     * Called when RC values are downloaded
     *
     * @param rResult see {@link RequestResult}
     * @param error null if there is no error
     * @param fullValueUpdate "true" - all values updated, "false" - a subset of values updated
     * @param downloadedValues the whole downloaded RC set, the delta
     */
    void callback(RequestResult rResult, String error, boolean fullValueUpdate, Map<String, RCData> downloadedValues);
}

