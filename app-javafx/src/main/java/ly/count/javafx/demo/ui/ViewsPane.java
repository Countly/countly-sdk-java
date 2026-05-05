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

public class ViewsPane {

    private final VBox root = new VBox(12);
    private final TextField viewName = new TextField("home");
    private final TextField viewId = new TextField();
    private final TextField segKey = new TextField("section");
    private final TextField segVal = new TextField("top");
    private final LogPanel log;

    public ViewsPane(LogPanel log) {
        this.log = log;
        root.setPadding(new Insets(16));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.addRow(0, new Label("View name:"), viewName);
        grid.addRow(1, new Label("View ID:"), viewId);
        grid.addRow(2, new Label("Segment K/V:"), segKey);
        grid.add(segVal, 2, 2);

        Button startView = new Button("startView(name)");
        startView.setOnAction(e -> SdkUtil.run(log, "startView " + viewName.getText(), () -> {
            String id = Countly.instance().views().startView(viewName.getText());
            viewId.setText(id == null ? "" : id);
        }));

        Button startAuto = new Button("startAutoStoppedView(name)");
        startAuto.setOnAction(e -> SdkUtil.run(log, "startAutoStoppedView " + viewName.getText(), () -> {
            String id = Countly.instance().views().startAutoStoppedView(viewName.getText());
            viewId.setText(id == null ? "" : id);
        }));

        Button startSeg = new Button("startView(name, seg)");
        startSeg.setOnAction(e -> SdkUtil.run(log, "startView w/ seg", () -> {
            String id = Countly.instance().views().startView(viewName.getText(), seg());
            viewId.setText(id == null ? "" : id);
        }));

        Button stopName = new Button("stopViewWithName(name)");
        stopName.setOnAction(e -> SdkUtil.run(log, "stopViewWithName " + viewName.getText(),
            () -> Countly.instance().views().stopViewWithName(viewName.getText())));

        Button stopNameSeg = new Button("stopViewWithName(name, seg)");
        stopNameSeg.setOnAction(e -> SdkUtil.run(log, "stopViewWithName w/ seg",
            () -> Countly.instance().views().stopViewWithName(viewName.getText(), seg())));

        Button stopId = new Button("stopViewWithID(id)");
        stopId.setOnAction(e -> SdkUtil.run(log, "stopViewWithID " + viewId.getText(),
            () -> Countly.instance().views().stopViewWithID(viewId.getText())));

        Button pauseId = new Button("pauseViewWithID(id)");
        pauseId.setOnAction(e -> SdkUtil.run(log, "pauseViewWithID " + viewId.getText(),
            () -> Countly.instance().views().pauseViewWithID(viewId.getText())));

        Button resumeId = new Button("resumeViewWithID(id)");
        resumeId.setOnAction(e -> SdkUtil.run(log, "resumeViewWithID " + viewId.getText(),
            () -> Countly.instance().views().resumeViewWithID(viewId.getText())));

        Button stopAll = new Button("stopAllViews(seg)");
        stopAll.setOnAction(e -> SdkUtil.run(log, "stopAllViews",
            () -> Countly.instance().views().stopAllViews(seg())));

        Button addSegName = new Button("addSegmentationToViewWithName");
        addSegName.setOnAction(e -> SdkUtil.run(log, "addSegmentationToViewWithName",
            () -> Countly.instance().views().addSegmentationToViewWithName(viewName.getText(), seg())));

        Button addSegId = new Button("addSegmentationToViewWithID");
        addSegId.setOnAction(e -> SdkUtil.run(log, "addSegmentationToViewWithID",
            () -> Countly.instance().views().addSegmentationToViewWithID(viewId.getText(), seg())));

        Button setGlobal = new Button("setGlobalViewSegmentation");
        setGlobal.setOnAction(e -> SdkUtil.run(log, "setGlobalViewSegmentation",
            () -> Countly.instance().views().setGlobalViewSegmentation(seg())));

        Button updateGlobal = new Button("updateGlobalViewSegmentation");
        updateGlobal.setOnAction(e -> SdkUtil.run(log, "updateGlobalViewSegmentation",
            () -> Countly.instance().views().updateGlobalViewSegmentation(seg())));

        FlowPane buttons = new FlowPane(8, 8,
            startView, startAuto, startSeg, stopName, stopNameSeg, stopId, pauseId, resumeId, stopAll,
            addSegName, addSegId, setGlobal, updateGlobal);

        root.getChildren().addAll(title("Views"), grid, buttons);
    }

    private Map<String, Object> seg() {
        Map<String, Object> m = new HashMap<>();
        if (!segKey.getText().isEmpty()) m.put(segKey.getText(), segVal.getText());
        return m;
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
