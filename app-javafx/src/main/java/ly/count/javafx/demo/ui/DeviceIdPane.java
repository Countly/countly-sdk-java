package ly.count.javafx.demo.ui;

import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import ly.count.sdk.java.Countly;

public class DeviceIdPane {

    private final VBox root = new VBox(12);
    private final TextField newId = new TextField("user_" + System.currentTimeMillis());
    private final TextField loginId = new TextField("authenticated_user_42");
    private final Label currentId = new Label("(SDK not init)");
    private final Label currentType = new Label("-");
    private final LogPanel log;
    private final Label statusLabel;

    public DeviceIdPane(LogPanel log, Label statusLabel) {
        this.log = log;
        this.statusLabel = statusLabel;
        root.setPadding(new Insets(16));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.addRow(0, new Label("Current device ID:"), currentId);
        grid.addRow(1, new Label("Current ID type:"), currentType);
        grid.addRow(2, new Label("New ID (for merge/noMerge):"), newId);
        grid.addRow(3, new Label("Login ID:"), loginId);

        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> refresh());

        Button withMerge = new Button("changeWithMerge(newId)");
        withMerge.setOnAction(e -> SdkUtil.run(log, "changeWithMerge", () -> {
            Countly.instance().deviceId().changeWithMerge(newId.getText());
            refresh();
        }));

        Button withoutMerge = new Button("changeWithoutMerge(newId)");
        withoutMerge.setOnAction(e -> SdkUtil.run(log, "changeWithoutMerge", () -> {
            Countly.instance().deviceId().changeWithoutMerge(newId.getText());
            refresh();
        }));

        Button login = new Button("login(id)");
        login.setOnAction(e -> SdkUtil.run(log, "login", () -> {
            Countly.instance().login(loginId.getText());
            refresh();
        }));

        Button logout = new Button("logout()");
        logout.setOnAction(e -> SdkUtil.run(log, "logout", () -> {
            Countly.instance().logout();
            refresh();
        }));

        FlowPane buttons = new FlowPane(8, 8, refresh, withMerge, withoutMerge, login, logout);

        root.getChildren().addAll(title("Device ID"), grid, buttons);
    }

    private void refresh() {
        if (!Countly.isInitialized()) {
            currentId.setText("(SDK not init)");
            currentType.setText("-");
            return;
        }
        currentId.setText(Countly.instance().deviceId().getID());
        currentType.setText(String.valueOf(Countly.instance().deviceId().getType()));
        if (statusLabel != null) {
            statusLabel.setText("SDK: initialized · device=" + currentId.getText());
            statusLabel.getStyleClass().setAll("status-pill", "status-ok");
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
