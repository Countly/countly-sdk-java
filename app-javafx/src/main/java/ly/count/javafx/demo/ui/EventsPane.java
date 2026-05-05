package ly.count.javafx.demo.ui;

import java.util.HashMap;
import java.util.Map;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import ly.count.sdk.java.Countly;

public class EventsPane {

    private final VBox root = new VBox(12);
    private final TextField key = new TextField("Basic Event");
    private final TextField count = new TextField("1");
    private final TextField sum = new TextField("0.0");
    private final TextField duration = new TextField("");
    private final TextField segKey = new TextField("source");
    private final TextField segValue = new TextField("demo");
    private final TextField timedKey = new TextField("timed_event");
    private final LogPanel log;

    public EventsPane(LogPanel log) {
        this.log = log;
        root.setPadding(new Insets(16));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.addRow(0, new Label("Key:"),      key);
        grid.addRow(1, new Label("Count:"),    count);
        grid.addRow(2, new Label("Sum:"),      sum);
        grid.addRow(3, new Label("Duration:"), duration);
        grid.addRow(4, new Label("Segment K/V:"), segKey);
        grid.add(segValue, 2, 4);

        Button basic = new Button("recordEvent(key)");
        basic.setOnAction(e -> SdkUtil.run(log, "recordEvent " + key.getText(),
            () -> Countly.instance().events().recordEvent(key.getText())));

        Button withCount = new Button("recordEvent(key, count)");
        withCount.setOnAction(e -> SdkUtil.run(log, "recordEvent w/ count",
            () -> Countly.instance().events().recordEvent(key.getText(), parseInt(count.getText(), 1))));

        Button withSum = new Button("recordEvent(key, count, sum)");
        withSum.setOnAction(e -> SdkUtil.run(log, "recordEvent w/ sum",
            () -> Countly.instance().events().recordEvent(
                key.getText(), parseInt(count.getText(), 1), parseDouble(sum.getText(), 0.0))));

        Button withSeg = new Button("recordEvent(key, seg)");
        withSeg.setOnAction(e -> SdkUtil.run(log, "recordEvent w/ segmentation",
            () -> Countly.instance().events().recordEvent(key.getText(), singleSegment())));

        Button fullEvent = new Button("recordEvent(key, seg, count, sum, duration)");
        fullEvent.setOnAction(e -> SdkUtil.run(log, "recordEvent w/ seg,count,sum,duration",
            () -> Countly.instance().events().recordEvent(
                key.getText(),
                singleSegment(),
                parseInt(count.getText(), 1),
                parseDouble(sum.getText(), 0.0),
                parseOptionalDouble(duration.getText()))));

        Button startTimed = new Button("startEvent(timedKey)");
        startTimed.setOnAction(e -> SdkUtil.run(log, "startEvent " + timedKey.getText(),
            () -> Countly.instance().events().startEvent(timedKey.getText())));

        Button endTimed = new Button("endEvent(timedKey)");
        endTimed.setOnAction(e -> SdkUtil.run(log, "endEvent " + timedKey.getText(),
            () -> Countly.instance().events().endEvent(timedKey.getText())));

        Button endTimedFull = new Button("endEvent(timedKey, seg, count, sum)");
        endTimedFull.setOnAction(e -> SdkUtil.run(log, "endEvent w/ seg,count,sum",
            () -> Countly.instance().events().endEvent(
                timedKey.getText(), singleSegment(),
                parseInt(count.getText(), 1),
                parseDouble(sum.getText(), 0.0))));

        Button cancelTimed = new Button("cancelEvent(timedKey)");
        cancelTimed.setOnAction(e -> SdkUtil.run(log, "cancelEvent " + timedKey.getText(),
            () -> Countly.instance().events().cancelEvent(timedKey.getText())));

        FlowPane basicButtons = new FlowPane(8, 8, basic, withCount, withSum, withSeg, fullEvent);
        FlowPane timedRow = new FlowPane(8, 8, new Label("Timed key:"), timedKey, startTimed, endTimed, endTimedFull, cancelTimed);

        root.getChildren().addAll(
            title("Events"),
            grid,
            basicButtons,
            title("Timed events"),
            timedRow
        );
    }

    private Map<String, Object> singleSegment() {
        Map<String, Object> m = new HashMap<>();
        if (!segKey.getText().isEmpty()) {
            m.put(segKey.getText(), segValue.getText());
        }
        return m;
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception ignored) { return def; }
    }

    private static double parseDouble(String s, double def) {
        try { return Double.parseDouble(s.trim()); } catch (Exception ignored) { return def; }
    }

    private static Double parseOptionalDouble(String s) {
        if (s == null || s.trim().isEmpty()) return 0.0;
        try { return Double.parseDouble(s.trim()); } catch (Exception ignored) { return 0.0; }
    }

    private static Label title(String s) {
        Label l = new Label(s);
        l.getStyleClass().add("section-title");
        return l;
    }

    public Parent getRoot() {
        return root;
    }
}
