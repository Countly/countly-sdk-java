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

public class LocationPane {

    private final VBox root = new VBox(12);
    private final TextField country = new TextField("TR");
    private final TextField city    = new TextField("Istanbul");
    private final TextField gps     = new TextField("41.0082,28.9784");
    private final TextField ip      = new TextField("");

    private final LogPanel log;

    public LocationPane(LogPanel log) {
        this.log = log;
        root.setPadding(new Insets(16));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.addRow(0, new Label("Country code:"), country);
        grid.addRow(1, new Label("City:"), city);
        grid.addRow(2, new Label("GPS (lat,lon):"), gps);
        grid.addRow(3, new Label("IP address:"), ip);

        Button set = new Button("setLocation(...)");
        set.setOnAction(e -> SdkUtil.run(log, "setLocation",
            () -> Countly.instance().location().setLocation(
                country.getText(), city.getText(), gps.getText(),
                ip.getText().isEmpty() ? null : ip.getText())));

        Button disable = new Button("disableLocation()");
        disable.setOnAction(e -> SdkUtil.run(log, "disableLocation",
            () -> Countly.instance().location().disableLocation()));

        FlowPane buttons = new FlowPane(8, 8, set, disable);

        root.getChildren().addAll(title("Location"), grid, buttons);
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
