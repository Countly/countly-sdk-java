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

public class CrashesPane {

    private final VBox root = new VBox(12);
    private final TextField breadcrumb = new TextField("User opened settings");
    private final TextField segKey = new TextField("screen");
    private final TextField segVal = new TextField("settings");

    private final LogPanel log;

    public CrashesPane(LogPanel log) {
        this.log = log;
        root.setPadding(new Insets(16));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.addRow(0, new Label("Breadcrumb:"), breadcrumb);
        grid.addRow(1, new Label("Segment K/V:"), segKey);
        grid.add(segVal, 2, 1);

        Button addBC = new Button("addCrashBreadcrumb");
        addBC.setOnAction(e -> SdkUtil.run(log, "addCrashBreadcrumb",
            () -> Countly.instance().crashes().addCrashBreadcrumb(breadcrumb.getText())));

        Button handled = new Button("recordHandledException");
        handled.setOnAction(e -> SdkUtil.run(log, "recordHandledException",
            () -> Countly.instance().crashes().recordHandledException(new RuntimeException("Demo handled exception"))));

        Button handledSeg = new Button("recordHandledException(seg)");
        handledSeg.setOnAction(e -> SdkUtil.run(log, "recordHandledException(seg)", () -> {
            Map<String, Object> m = new HashMap<>();
            m.put(segKey.getText(), segVal.getText());
            Countly.instance().crashes().recordHandledException(new IllegalStateException("Demo handled w/ seg"), m);
        }));

        Button unhandled = new Button("recordUnhandledException");
        unhandled.setOnAction(e -> SdkUtil.run(log, "recordUnhandledException",
            () -> Countly.instance().crashes().recordUnhandledException(new RuntimeException("Demo unhandled exception"))));

        Button unhandledSeg = new Button("recordUnhandledException(seg)");
        unhandledSeg.setOnAction(e -> SdkUtil.run(log, "recordUnhandledException(seg)", () -> {
            Map<String, Object> m = new HashMap<>();
            m.put(segKey.getText(), segVal.getText());
            Countly.instance().crashes().recordUnhandledException(new OutOfMemoryError("Demo unhandled w/ seg"), m);
        }));

        FlowPane buttons = new FlowPane(8, 8, addBC, handled, handledSeg, unhandled, unhandledSeg);

        root.getChildren().addAll(title("Crashes & breadcrumbs"), grid, buttons);
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
