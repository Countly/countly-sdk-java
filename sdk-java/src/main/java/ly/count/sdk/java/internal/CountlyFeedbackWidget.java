package ly.count.sdk.java.internal;

import java.util.Arrays;
import java.util.Objects;

public class CountlyFeedbackWidget {
    public String widgetId;
    public FeedbackWidgetType type;
    public String name;
    public String[] tags;

    /**
     * The widget's {@code appearance.position}, for example "bLeft" or "bRight", or {@code null}
     * when the server did not send one. A display uses it to anchor the card the way the web SDK's
     * stylesheet does.
     */
    public String position;

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

    @Override
    public int hashCode() {
        return Objects.hash(widgetId, type, name);
    }
}
