package ly.count.sdk.java.ui;

import ly.count.sdk.java.internal.ContentPlacement;
import ly.count.sdk.java.internal.FeedbackWidgetType;

/**
 * Where a feedback widget card belongs on screen, per widget type.
 * <p>
 * A widget page works out its own card size and posts it as a {@code resize_me} rect, and that size
 * is authoritative: it comes from the page measuring its own content against its own maximums. What
 * the rect does not carry is the anchor, because on the web the SDK's stylesheet supplies it, and
 * the page's numbers assume those rules are applied:
 * <ul>
 *     <li>NPS reports {@code x = parentWidth / 2} and relies on
 *     {@code transform:translateX(-50%)} to pull the card back by half its width. Applied to a
 *     window as it stands, that puts the card half a card right of centre.</li>
 *     <li>A survey reports {@code x = 0} and carries its {@code appearance.position} as a class
 *     instead: {@code .bLeft} sits {@code 50px} from the left, {@code .bRight} {@code 50px} from the
 *     right. Both are {@code bottom:0}, which the reported {@code y} already accounts for.</li>
 *     <li>A rating reports no rect at all. Its page only paints the little sticky tab; on the web the
 *     popup lives in a {@code 400x800} iframe centred by a flex wrapper
 *     ({@code star-rating/stylesheets/countly-feedback-web.css}). Measuring what the page painted
 *     gives the tab, which is a sliver.</li>
 * </ul>
 * Toolkit free, so all of this is testable without a JavaFX toolkit.
 */
final class WidgetLayout {

    /** The fixed size of the web SDK's {@code #countly-ratings-iframe}. */
    static final int RATING_WIDTH = 400;
    static final int RATING_HEIGHT = 800;

    /** The 50px gap .bLeft and .bRight put between a survey card and the screen edge. */
    static final int SIDE_MARGIN = 50;

    /**
     * Used only when a widget reported no size of its own and painted nothing worth measuring:
     * {@code max-width:480px} from the surveys stylesheet, and its per type max height.
     */
    static final int DEFAULT_WIDTH = 480;
    static final int NPS_DEFAULT_HEIGHT = 450;
    static final int SURVEY_DEFAULT_HEIGHT = 650;

    private WidgetLayout() {
    }

    /**
     * Whether a widget of this type is laid out from the size it reports.
     *
     * @param type the widget type, may be {@code null}
     * @return {@code false} for a rating, whose card is a fixed size, so measuring the page and
     *     re-placing it can only ever produce the same rectangle
     */
    static boolean usesReportedSize(FeedbackWidgetType type) {
        return type != FeedbackWidgetType.rating;
    }

    /**
     * @param type the widget being shown, may be {@code null} when it is not known
     * @param position the widget's {@code appearance.position} ("bLeft"/"bRight"), may be
     *     {@code null} when the server did not send one
     * @param surface the area the card may occupy, screen absolute
     * @param requested the rect the widget asked for, or {@code null} when it asked for nothing
     * @return where to put the card
     */
    static ContentPlacement resolve(FeedbackWidgetType type, String position, WidgetSurface surface, ContentPlacement requested) {
        if (type == FeedbackWidgetType.rating) {
            // Deliberately ignores anything the page reported: the tab it paints is not the card.
            return centred(surface, RATING_WIDTH, RATING_HEIGHT);
        }

        int width = clamp(requested == null || requested.width <= 0 ? DEFAULT_WIDTH : requested.width, surface.width);
        int defaultHeight = type == FeedbackWidgetType.nps ? NPS_DEFAULT_HEIGHT : SURVEY_DEFAULT_HEIGHT;
        int height = clamp(requested == null || requested.height <= 0 ? defaultHeight : requested.height, surface.height);

        if (type == null) {
            // An unknown type has no anchor to apply, so it goes in the middle.
            return centred(surface, width, height);
        }

        // bottom:0 for both NPS and surveys, which is also what the reported y works out to.
        int y = surface.y + Math.max(0, surface.height - height);
        return new ContentPlacement(surface.x + horizontalOffset(type, position, surface, width), y, width, height);
    }

    private static int horizontalOffset(FeedbackWidgetType type, String position, WidgetSurface surface, int width) {
        if (type == FeedbackWidgetType.nps) {
            // left:50% plus translateX(-50%) is the centre, whatever the reported x looks like. The
            // page itself already subtracts half the width below 1025px wide, so both branches of
            // its own breakpoint end up here.
            return Math.max(0, (surface.width - width) / 2);
        }

        if (position != null && position.trim().equalsIgnoreCase("bRight")) {
            return Math.max(0, surface.width - width - SIDE_MARGIN);
        }
        // bLeft is the default: the web SDK always adds a position class for surveys, and every
        // template ships one of the two.
        return Math.min(SIDE_MARGIN, Math.max(0, surface.width - width));
    }

    private static ContentPlacement centred(WidgetSurface surface, int requestedWidth, int requestedHeight) {
        int width = clamp(requestedWidth, surface.width);
        int height = clamp(requestedHeight, surface.height);
        return new ContentPlacement(
            surface.x + Math.max(0, (surface.width - width) / 2),
            surface.y + Math.max(0, (surface.height - height) / 2),
            width, height);
    }

    private static int clamp(int value, int available) {
        return Math.max(1, Math.min(value, available));
    }
}
