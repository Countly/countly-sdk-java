package ly.count.javafx.demo.ui;

import java.util.Arrays;
import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import ly.count.sdk.java.internal.CountlyFeedbackWidget;
import ly.count.sdk.java.internal.FeedbackWidgetType;

public class WidgetCard extends VBox {

    public WidgetCard(CountlyFeedbackWidget widget,
                      String widgetVersion,
                      Consumer<CountlyFeedbackWidget> onOpen,
                      Consumer<CountlyFeedbackWidget> onInspect,
                      Consumer<CountlyFeedbackWidget> onManualReport) {
        getStyleClass().add("widget-card");
        setPadding(new Insets(10));
        setSpacing(4);
        setCursor(Cursor.HAND);

        Label badge = new Label(widget.type.name().toUpperCase());
        badge.getStyleClass().addAll("badge", typeClass(widget.type));

        Label versionBadge = new Label(
            widgetVersion == null || widgetVersion.isEmpty()
                ? "legacy"
                : "v" + widgetVersion);
        versionBadge.getStyleClass().addAll("badge", "badge-version");

        HBox badgeRow = new HBox(6, badge, versionBadge);

        String name = widget.name == null || widget.name.isEmpty() ? "(unnamed)" : widget.name;
        Label nameLabel = new Label(name);
        nameLabel.getStyleClass().add("widget-name");
        nameLabel.setWrapText(true);

        String id = widget.widgetId == null ? "" : widget.widgetId;
        Label idLabel = new Label("ID: " + (id.length() > 14 ? id.substring(0, 14) + "..." : id));
        idLabel.getStyleClass().add("widget-sub");

        Label tagsLabel = new Label("Tags: " +
            (widget.tags == null || widget.tags.length == 0 ? "—" : Arrays.toString(widget.tags)));
        tagsLabel.getStyleClass().add("widget-sub");

        javafx.scene.control.Button open = new javafx.scene.control.Button("Open");
        open.setOnAction(e -> onOpen.accept(widget));
        javafx.scene.control.Button inspect = new javafx.scene.control.Button("Inspect data");
        inspect.setOnAction(e -> onInspect.accept(widget));
        javafx.scene.control.Button manual = new javafx.scene.control.Button("Report manually");
        manual.setOnAction(e -> onManualReport.accept(widget));

        HBox actions = new HBox(6, open, inspect, manual);
        actions.setPadding(new Insets(6, 0, 0, 0));

        getChildren().addAll(badgeRow, nameLabel, idLabel, tagsLabel, actions);

        setOnMouseClicked(e -> {
            if (e.getTarget() == this || e.getTarget() instanceof Label) {
                onOpen.accept(widget);
            }
        });
    }

    private static String typeClass(FeedbackWidgetType type) {
        switch (type) {
            case nps:    return "badge-nps";
            case survey: return "badge-survey";
            case rating: return "badge-rating";
            default:     return "badge-other";
        }
    }
}
