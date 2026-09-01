package ly.count.sdk.java.internal;

import java.util.Map;

/**
 * Handed to a {@link ContentDisplay} so it can tell the SDK that the content it was showing is
 * gone. Must be called exactly once per {@link ContentDisplay#present(ContentData, ContentCloseCallback)},
 * including when the display fails to show anything, otherwise the content zone never resumes
 * fetching.
 *
 * @apiNote This is an EXPERIMENTAL feature, and it can have breaking changes
 */
public interface ContentCloseCallback {

    /**
     * @param contentData the query parameters the content sent along with its close signal, may be empty
     */
    void onClosed(Map<String, Object> contentData);
}
