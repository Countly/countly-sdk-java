package ly.count.sdk.java.internal;

import java.util.List;

/**
 * Picks one feedback widget out of a fetched list, by type and optionally by a name, ID or tag.
 * Kept free of any UI toolkit so the quick present calls of every display implementation select the
 * same way.
 */
public class FeedbackWidgetSelector {

    private FeedbackWidgetSelector() {
    }

    /**
     * @param widgets the widgets available for this device, may be {@code null}
     * @param type the type of widget to look for
     * @param nameIDorTag the widget ID, name or one of its tags. {@code null} or empty takes the
     *     first widget of that type.
     * @return the widget to show, or {@code null} when the list holds no match
     */
    public static CountlyFeedbackWidget select(List<CountlyFeedbackWidget> widgets, FeedbackWidgetType type, String nameIDorTag) {
        if (widgets == null || widgets.isEmpty() || type == null) {
            return null;
        }

        boolean matchAny = Utils.isEmptyOrNull(nameIDorTag);

        for (CountlyFeedbackWidget widget : widgets) {
            if (widget == null || widget.type != type) {
                continue;
            }

            if (matchAny || matches(widget, nameIDorTag)) {
                return widget;
            }
        }

        return null;
    }

    private static boolean matches(CountlyFeedbackWidget widget, String nameIDorTag) {
        if (nameIDorTag.equals(widget.widgetId) || nameIDorTag.equals(widget.name)) {
            return true;
        }

        if (widget.tags == null) {
            return false;
        }

        for (String tag : widget.tags) {
            if (nameIDorTag.equals(tag)) {
                return true;
            }
        }

        return false;
    }
}
