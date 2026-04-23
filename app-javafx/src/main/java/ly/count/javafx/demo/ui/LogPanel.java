package ly.count.javafx.demo.ui;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

public class LogPanel {

    private static final DateTimeFormatter TIME =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final BorderPane root = new BorderPane();
    private final TextArea area = new TextArea();

    public LogPanel() {
        area.setEditable(false);
        area.getStyleClass().add("log-area");
        area.setWrapText(false);

        Label header = new Label("Log");
        header.getStyleClass().add("panel-title");

        Button clear = new Button("Clear");
        clear.setOnAction(e -> area.clear());

        HBox top = new HBox(8, header, spacer(), clear);
        top.setPadding(new Insets(6, 10, 6, 10));
        top.getStyleClass().add("panel-header");

        root.setTop(top);
        root.setCenter(area);
        BorderPane.setMargin(area, new Insets(0, 8, 8, 8));
    }

    public void info(String msg) {
        append("INFO ", msg);
    }

    public void warn(String msg) {
        append("WARN ", msg);
    }

    public void error(String msg) {
        append("ERROR", msg);
    }

    public void sdk(String msg) {
        append("SDK  ", msg);
    }

    private void append(String level, String msg) {
        String line = "[" + LocalTime.now().format(TIME) + "] " + level + " | " + msg + "\n";
        if (Platform.isFxApplicationThread()) {
            area.appendText(line);
        } else {
            Platform.runLater(() -> area.appendText(line));
        }
    }

    public Parent getRoot() {
        return root;
    }

    private static javafx.scene.Node spacer() {
        javafx.scene.layout.Region r = new javafx.scene.layout.Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }
}
