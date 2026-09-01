package ly.count.sdk.java.internal;

import java.util.Map;

/**
 * Called when a content block reaches an end state. Register one with
 * {@link ConfigContent#setGlobalContentCallback(ContentCallback)}.
 *
 * @apiNote This is an EXPERIMENTAL feature, and it can have breaking changes
 */
public interface ContentCallback {

    /**
     * @param contentStatus the state the content ended up in
     * @param contentData the query parameters the content sent along with its close signal
     */
    void onContentCallback(ContentStatus contentStatus, Map<String, Object> contentData);
}
