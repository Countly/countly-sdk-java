package ly.count.sdk.java.internal;

import java.util.Collections;
import java.util.Map;

/**
 * A signal a feedback widget or a content block sent to the SDK by navigating to a
 * {@code https://countly_action_event} URL, already parsed. See {@link WidgetActionParser}.
 */
public class WidgetAction {

    /** {@code true} when the URL is one of the SDK's own signalling URLs and not a real navigation. */
    public boolean isSdkSignal;
    /** {@code true} for a feedback widget command ({@code cly_widget_command=1}). */
    public boolean isWidgetCommand;
    /** {@code true} for a content action event ({@code cly_x_action_event=1}). */
    public boolean isActionEvent;
    /** {@code true} when the whole URL should be opened in an external browser ({@code cly_x_int=1}). */
    public boolean isExternalLink;
    /** {@code true} when whatever is on screen should be dismissed after the action was processed. */
    public boolean close;
    /** {@code true} when {@link #portrait} or {@link #landscape} carries a new rectangle. */
    public boolean hasResize;
    public ContentPlacement portrait;
    public ContentPlacement landscape;
    /** {@code action=link}: the destination to open in an external browser. */
    public String link;
    /** {@code action=event}: the raw JSON array of {@code {key, sg|segmentation}} objects. */
    public String eventPayload;
    /** Every query parameter of the URL, as sent. */
    public Map<String, Object> queryParams = Collections.emptyMap();

    /**
     * The rectangle to apply on a surface of the given shape, or {@code null} when the action
     * carries none.
     *
     * @param landscapeSurface {@code true} when the surface is wider than it is tall
     * @return the requested rectangle
     */
    public ContentPlacement resizeFor(boolean landscapeSurface) {
        if (!hasResize) {
            return null;
        }
        ContentPlacement preferred = landscapeSurface ? landscape : portrait;
        if (preferred != null) {
            return preferred;
        }
        return landscapeSurface ? portrait : landscape;
    }
}
