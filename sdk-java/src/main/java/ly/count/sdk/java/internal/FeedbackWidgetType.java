package ly.count.sdk.java.internal;

public enum FeedbackWidgetType {
    survey("[CLY]_survey"),
    nps("[CLY]_nps"),
    rating("[CLY]_star_rating");

    final String eventKey;

    FeedbackWidgetType(String eventKey) {
        this.eventKey = eventKey;
    }
}
