package ly.count.javafx.demo.ui;

import java.util.Map;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import ly.count.sdk.java.Countly;
import ly.count.sdk.java.internal.RCData;

public class RemoteConfigPane {

    private final VBox root = new VBox(12);
    private final TextField keyField = new TextField("welcome_message");
    private final TextField keysField = new TextField("welcome_message,banner,enable_ab");
    private final TextArea  output    = new TextArea();
    private final LogPanel log;

    public RemoteConfigPane(LogPanel log) {
        this.log = log;
        root.setPadding(new Insets(16));
        output.setEditable(false);
        output.setPrefRowCount(10);
        VBox.setVgrow(output, Priority.ALWAYS);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.addRow(0, new Label("Key:"), keyField);
        grid.addRow(1, new Label("Keys (csv):"), keysField);

        Button downloadAll = new Button("downloadAllKeys");
        downloadAll.setOnAction(e -> SdkUtil.run(log, "downloadAllKeys",
            () -> Countly.instance().remoteConfig().downloadAllKeys(this::onDownload)));

        Button downloadSpecific = new Button("downloadSpecificKeys");
        downloadSpecific.setOnAction(e -> SdkUtil.run(log, "downloadSpecificKeys",
            () -> Countly.instance().remoteConfig().downloadSpecificKeys(
                split(keysField.getText()), this::onDownload)));

        Button downloadOmit = new Button("downloadOmittingKeys");
        downloadOmit.setOnAction(e -> SdkUtil.run(log, "downloadOmittingKeys",
            () -> Countly.instance().remoteConfig().downloadOmittingKeys(
                split(keysField.getText()), this::onDownload)));

        Button getAll = new Button("getValues");
        getAll.setOnAction(e -> SdkUtil.run(log, "getValues",
            () -> setOutput(formatValues(Countly.instance().remoteConfig().getValues()))));

        Button getAndEnroll = new Button("getAllValuesAndEnroll");
        getAndEnroll.setOnAction(e -> SdkUtil.run(log, "getAllValuesAndEnroll",
            () -> setOutput(formatValues(Countly.instance().remoteConfig().getAllValuesAndEnroll()))));

        Button getOne = new Button("getValue(key)");
        getOne.setOnAction(e -> SdkUtil.run(log, "getValue", () -> {
            RCData v = Countly.instance().remoteConfig().getValue(keyField.getText());
            setOutput(keyField.getText() + " = " + fmt(v));
        }));

        Button getOneEnroll = new Button("getValueAndEnroll(key)");
        getOneEnroll.setOnAction(e -> SdkUtil.run(log, "getValueAndEnroll", () -> {
            RCData v = Countly.instance().remoteConfig().getValueAndEnroll(keyField.getText());
            setOutput(keyField.getText() + " = " + fmt(v));
        }));

        Button enrollKeys = new Button("enrollIntoABTestsForKeys");
        enrollKeys.setOnAction(e -> SdkUtil.run(log, "enrollIntoABTestsForKeys",
            () -> Countly.instance().remoteConfig().enrollIntoABTestsForKeys(split(keysField.getText()))));

        Button exitKeys = new Button("exitABTestsForKeys");
        exitKeys.setOnAction(e -> SdkUtil.run(log, "exitABTestsForKeys",
            () -> Countly.instance().remoteConfig().exitABTestsForKeys(split(keysField.getText()))));

        Button clearAll = new Button("clearAll");
        clearAll.setOnAction(e -> SdkUtil.run(log, "clearAll",
            () -> Countly.instance().remoteConfig().clearAll()));

        FlowPane buttons = new FlowPane(8, 8,
            downloadAll, downloadSpecific, downloadOmit,
            getAll, getAndEnroll, getOne, getOneEnroll,
            enrollKeys, exitKeys, clearAll);

        root.getChildren().addAll(title("Remote Config"), grid, buttons, new Label("Values:"), output);
    }

    private void onDownload(ly.count.sdk.java.internal.RequestResult result, String error,
                            boolean fullValueUpdate, Map<String, RCData> values) {
        String msg = "[RC] result=" + result + ", error=" + error
            + ", full=" + fullValueUpdate + ", count=" + (values == null ? 0 : values.size());
        log.info(msg);
        Platform.runLater(() -> setOutput(msg + "\n" + formatValues(values)));
    }

    private String formatValues(Map<String, RCData> values) {
        if (values == null || values.isEmpty()) return "(no values)";
        StringBuilder sb = new StringBuilder();
        values.forEach((k, v) -> sb.append(k).append(" = ").append(fmt(v)).append('\n'));
        return sb.toString();
    }

    private static String fmt(RCData v) {
        if (v == null) return "null";
        return String.valueOf(v.value) + " (currentUser=" + v.isCurrentUsersData + ")";
    }

    private static String[] split(String csv) {
        if (csv == null || csv.trim().isEmpty()) return new String[0];
        String[] parts = csv.split(",");
        for (int i = 0; i < parts.length; i++) parts[i] = parts[i].trim();
        return parts;
    }

    private void setOutput(String s) {
        if (Platform.isFxApplicationThread()) {
            output.setText(s);
        } else {
            Platform.runLater(() -> output.setText(s));
        }
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
