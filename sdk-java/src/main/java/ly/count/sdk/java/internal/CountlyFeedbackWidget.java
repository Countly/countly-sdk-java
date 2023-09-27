package ly.count.sdk.java.internal;

import java.util.Arrays;

public class CountlyFeedbackWidget {
    public String widgetId;
    public FeedbackWidgetType type;
    public String name;
    public String[] tags;

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof CountlyFeedbackWidget)) {
            return false;
        }
        CountlyFeedbackWidget gonnaCompare = (CountlyFeedbackWidget) o;

        String str = widgetId + type + name + Arrays.toString(tags);
        String str2 = gonnaCompare.widgetId + gonnaCompare.type + gonnaCompare.name + Arrays.toString(gonnaCompare.tags);
        return str.equals(str2);
    }
}
