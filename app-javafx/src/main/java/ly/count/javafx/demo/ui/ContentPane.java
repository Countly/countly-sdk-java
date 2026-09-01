package ly.count.javafx.demo.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import ly.count.sdk.java.ui.CountlyWebView;

/**
 * Drives the Content feature through the JavaFX UI artifact: enter and leave the content zone, force
 * a refresh, preview one specific content block by ID, and switch users to see how the zone reacts.
 *
 * <p>Content is fetched by the core SDK and drawn by {@code sdk-java-ui} as a borderless, always on
 * top window placed where the server asked for it. The area around it stays usable.
 */
public class ContentPane {

    private final VBox root = new VBox(12);
    private final LogPanel log;
    private final TextField previewIdField = new TextField();
    private final TextField deviceIdField = new TextField();

    public ContentPane(LogPanel log) {
        this.log = log;

        root.setPadding(new Insets(14));

        Label title = new Label("Content zone");
        title.getStyleClass().add("section-title");

        Button enter = new Button("Enter content zone");
        enter.setOnAction(event -> SdkUtil.run(log, "[Content] enterContentZone", CountlyWebView::enableContentZone));

        Button exit = new Button("Exit content zone");
        exit.setOnAction(event -> SdkUtil.run(log, "[Content] exitContentZone", CountlyWebView::disableContentZone));

        Button refresh = new Button("Refresh content zone");
        refresh.setOnAction(event -> SdkUtil.run(log, "[Content] refreshContentZone",
            () -> Countly.instance().content().refreshContentZone()));

        previewIdField.setPromptText("content block ID");
        previewIdField.setPrefColumnCount(24);

        Button preview = new Button("Preview by ID");
        preview.setOnAction(event -> previewContent());

        HBox zoneRow = new HBox(8, enter, exit, refresh);
        zoneRow.setAlignment(Pos.CENTER_LEFT);

        HBox previewRow = new HBox(8, new Label("Preview:"), previewIdField, preview);
        previewRow.setAlignment(Pos.CENTER_LEFT);

        Label hint = new Label(
            "Enable the Content feature on the Init tab before entering a zone. "
                + "The first fetch waits about 4 seconds, then the SDK polls on the configured interval.");
        hint.setWrapText(true);
        hint.getStyleClass().add("placeholder");

        root.getChildren().addAll(title, zoneRow, previewRow, hint, buildDeviceIdSection());
    }

    /**
     * Changing the user while a content zone is running. "setID" changes the device ID without merge
     * when the previous ID was developer supplied, which is a different user, so the SDK leaves the
     * content zone. Consents are then granted again for the new user and the zone is entered again,
     * which is the flow an application would run on a login.
     */
    private Parent buildDeviceIdSection() {
        Label title = new Label("Device ID");
        title.getStyleClass().add("section-title");

        deviceIdField.setPromptText("new device ID");
        deviceIdField.setPrefColumnCount(24);

        Button setId = new Button("setID + grant all consents");
        setId.setOnAction(event -> changeDeviceId(true));

        Button setIdOnly = new Button("setID only");
        setIdOnly.setOnAction(event -> changeDeviceId(false));

        Label hint = new Label(
            "A device ID change without merge is a different user, so the SDK leaves the content zone. "
                + "Grant the consents again and enter the zone again to keep serving content.");
        hint.setWrapText(true);
        hint.getStyleClass().add("placeholder");

        HBox row = new HBox(8, deviceIdField, setId, setIdOnly);
        row.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(8, title, row, hint);
        box.setPadding(new Insets(10, 0, 0, 0));
        return box;
    }

    private void changeDeviceId(boolean grantConsentsAndReEnter) {
        String newId = deviceIdField.getText().trim();
        if (newId.isEmpty()) {
            log.warn("[Content] Enter a device ID first.");
            return;
        }

        SdkUtil.run(log, "[Content] setID " + newId, () -> {
            Countly.instance().deviceId().setID(newId);
            log.info("[Content] device ID is now " + Countly.instance().deviceId().getID());

            if (!grantConsentsAndReEnter) {
                return;
            }

            Countly.onConsent(Config.Feature.values());
            log.info("[Content] granted all consents for the new user");

            // The device ID change tore the zone down, so it has to be entered again. This also
            // re-registers the display, because a consent change rebuilds the content module.
            CountlyWebView.enableContentZone();
            log.info("[Content] re-entered the content zone for the new user");
        });
    }

    private void previewContent() {
        String id = previewIdField.getText().trim();
        if (id.isEmpty()) {
            log.warn("[Content] Enter a content block ID to preview.");
            return;
        }
        SdkUtil.run(log, "[Content] previewContent " + id, () -> {
            // Previewing still needs a display registered, which entering the zone does for us.
            CountlyWebView.enableContentZone();
            Countly.instance().content().previewContent(id);
        });
    }

    public Parent getRoot() {
        return root;
    }
}
