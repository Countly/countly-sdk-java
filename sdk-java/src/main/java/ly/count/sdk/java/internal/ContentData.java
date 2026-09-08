package ly.count.sdk.java.internal;

/**
 * One content block the server decided to show: the URL to load and where to put it.
 * <p>
 * Only one of {@link #portrait} / {@link #landscape} is guaranteed to be set. A display picks the
 * one matching its own surface and falls back to the other.
 *
 * @apiNote This is an EXPERIMENTAL feature, and it can have breaking changes
 */
public class ContentData {
    public final String url;
    public final ContentPlacement portrait;
    public final ContentPlacement landscape;

    public ContentData(String url, ContentPlacement portrait, ContentPlacement landscape) {
        this.url = url;
        this.portrait = portrait;
        this.landscape = landscape;
    }

    /**
     * The placement to use for a surface of the given shape, or {@code null} if neither
     * orientation carries one.
     *
     * @param landscapeSurface {@code true} when the surface is wider than it is tall
     * @return the placement to lay the content out with
     */
    public ContentPlacement placementFor(boolean landscapeSurface) {
        ContentPlacement preferred = landscapeSurface ? landscape : portrait;
        if (preferred != null) {
            return preferred;
        }
        return landscapeSurface ? portrait : landscape;
    }

    @Override
    public String toString() {
        return "ContentData{url=" + url + ", portrait=" + portrait + ", landscape=" + landscape + '}';
    }
}
