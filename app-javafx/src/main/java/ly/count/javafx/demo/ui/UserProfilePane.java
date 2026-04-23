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
import ly.count.sdk.java.PredefinedUserPropertyKeys;

public class UserProfilePane {

    private final VBox root = new VBox(12);
    private final TextField name = new TextField("Jane Doe");
    private final TextField username = new TextField("jane");
    private final TextField email = new TextField("jane@count.ly");
    private final TextField organization = new TextField("Countly");
    private final TextField phone = new TextField("+1 555 0100");
    private final TextField picturePath = new TextField("https://example.com/avatar.png");
    private final TextField gender = new TextField("F");
    private final TextField byear = new TextField("1990");

    private final TextField customKey = new TextField("favouritePet");
    private final TextField customValue = new TextField("cat");
    private final TextField incKey = new TextField("logins");
    private final TextField incBy = new TextField("5");
    private final TextField mulKey = new TextField("score");
    private final TextField mulVal = new TextField("1.5");

    private final LogPanel log;

    public UserProfilePane(LogPanel log) {
        this.log = log;
        root.setPadding(new Insets(16));

        GridPane predef = new GridPane();
        predef.setHgap(10);
        predef.setVgap(6);
        int r = 0;
        predef.addRow(r++, new Label("Name:"), name);
        predef.addRow(r++, new Label("Username:"), username);
        predef.addRow(r++, new Label("Email:"), email);
        predef.addRow(r++, new Label("Organization:"), organization);
        predef.addRow(r++, new Label("Phone:"), phone);
        predef.addRow(r++, new Label("Picture URL:"), picturePath);
        predef.addRow(r++, new Label("Gender:"), gender);
        predef.addRow(r,   new Label("Birth year:"), byear);

        Button setPredefined = new Button("Set predefined profile + save()");
        setPredefined.setOnAction(e -> SdkUtil.run(log, "predefined profile saved", () -> {
            Countly.instance().userProfile().setProperty(PredefinedUserPropertyKeys.NAME, name.getText());
            Countly.instance().userProfile().setProperty(PredefinedUserPropertyKeys.USERNAME, username.getText());
            Countly.instance().userProfile().setProperty(PredefinedUserPropertyKeys.EMAIL, email.getText());
            Countly.instance().userProfile().setProperty(PredefinedUserPropertyKeys.ORGANIZATION, organization.getText());
            Countly.instance().userProfile().setProperty(PredefinedUserPropertyKeys.PHONE, phone.getText());
            Countly.instance().userProfile().setProperty(PredefinedUserPropertyKeys.PICTURE_PATH, picturePath.getText());
            Countly.instance().userProfile().setProperty(PredefinedUserPropertyKeys.GENDER, gender.getText());
            try {
                Countly.instance().userProfile().setProperty(PredefinedUserPropertyKeys.BIRTH_YEAR, Integer.parseInt(byear.getText()));
            } catch (NumberFormatException ignore) { /* tolerate empty/invalid */ }
            Countly.instance().userProfile().save();
        }));

        GridPane customGrid = new GridPane();
        customGrid.setHgap(10);
        customGrid.setVgap(6);
        customGrid.addRow(0, new Label("Custom key:"), customKey, new Label("value:"), customValue);
        customGrid.addRow(1, new Label("Increment key:"), incKey, new Label("by:"), incBy);
        customGrid.addRow(2, new Label("Multiply key:"), mulKey, new Label("factor:"), mulVal);

        Button setCustom = new Button("setProperty(key, value)");
        setCustom.setOnAction(e -> SdkUtil.run(log, "setProperty",
            () -> Countly.instance().userProfile().setProperty(customKey.getText(), customValue.getText())));

        Button setMany = new Button("setProperties(map)");
        setMany.setOnAction(e -> SdkUtil.run(log, "setProperties", () -> {
            Map<String, Object> m = new HashMap<>();
            m.put(customKey.getText(), customValue.getText());
            Countly.instance().userProfile().setProperties(m);
        }));

        Button setOnce = new Button("setOnce(key, value)");
        setOnce.setOnAction(e -> SdkUtil.run(log, "setOnce",
            () -> Countly.instance().userProfile().setOnce(customKey.getText(), customValue.getText())));

        Button push = new Button("push(key, value)");
        push.setOnAction(e -> SdkUtil.run(log, "push",
            () -> Countly.instance().userProfile().push(customKey.getText(), customValue.getText())));

        Button pushUnique = new Button("pushUnique(key, value)");
        pushUnique.setOnAction(e -> SdkUtil.run(log, "pushUnique",
            () -> Countly.instance().userProfile().pushUnique(customKey.getText(), customValue.getText())));

        Button pull = new Button("pull(key, value)");
        pull.setOnAction(e -> SdkUtil.run(log, "pull",
            () -> Countly.instance().userProfile().pull(customKey.getText(), customValue.getText())));

        Button inc = new Button("increment(key)");
        inc.setOnAction(e -> SdkUtil.run(log, "increment",
            () -> Countly.instance().userProfile().increment(incKey.getText())));

        Button incByBtn = new Button("incrementBy(key, n)");
        incByBtn.setOnAction(e -> SdkUtil.run(log, "incrementBy",
            () -> Countly.instance().userProfile().incrementBy(incKey.getText(), parseInt(incBy.getText()))));

        Button mul = new Button("multiply(key, factor)");
        mul.setOnAction(e -> SdkUtil.run(log, "multiply",
            () -> Countly.instance().userProfile().multiply(mulKey.getText(), parseDouble(mulVal.getText()))));

        Button saveMax = new Button("saveMax(key, value)");
        saveMax.setOnAction(e -> SdkUtil.run(log, "saveMax",
            () -> Countly.instance().userProfile().saveMax(mulKey.getText(), parseDouble(mulVal.getText()))));

        Button saveMin = new Button("saveMin(key, value)");
        saveMin.setOnAction(e -> SdkUtil.run(log, "saveMin",
            () -> Countly.instance().userProfile().saveMin(mulKey.getText(), parseDouble(mulVal.getText()))));

        Button save = new Button("save()");
        save.setOnAction(e -> SdkUtil.run(log, "save",
            () -> Countly.instance().userProfile().save()));

        Button clear = new Button("clear()");
        clear.setOnAction(e -> SdkUtil.run(log, "clear",
            () -> Countly.instance().userProfile().clear()));

        FlowPane buttons = new FlowPane(8, 8,
            setCustom, setMany, setOnce, push, pushUnique, pull,
            inc, incByBtn, mul, saveMax, saveMin, save, clear);

        root.getChildren().addAll(
            title("Predefined user properties"),
            predef,
            setPredefined,
            title("Custom properties"),
            customGrid,
            buttons
        );
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 1; }
    }

    private static double parseDouble(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 1.0; }
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
