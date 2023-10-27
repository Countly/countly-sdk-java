package ly.count.sdk.java.internal;

public interface RCVariantCallback {

    /**
     * The callback is called when AB variants are downloaded
     *
     * @param rResult see {@link RequestResult}
     * @param error null if there is no error
     */
    void callback(RequestResult rResult, String error);
}


