package ly.count.javafx.demo.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import ly.count.sdk.java.Countly;
import ly.count.sdk.java.ui.CountlyWebView;

/**
 * Drives the Content feature through the JavaFX UI artifact: enter and leave the content zone, force
 * a refresh, and preview one specific content block by ID.
 *
 * <p>Content is fetched by the core SDK and drawn by {@code sdk-java-ui} as a borderless, always on
 * top window placed where the server asked for it. The area around it stays usable.
 */
public class ContentPane {

    private final VBox root = new VBox(12);
    private final LogPanel log;
    private final TextField categoriesField = new TextField();
    private final TextField previewIdField = new TextField();
    private final CheckBox useCategories = new CheckBox("Filter by categories");

    public ContentPane(LogPanel log) {
        this.log = log;

        root.setPadding(new Insets(14));

        Label title = new Label("Content zone");
        title.getStyleClass().add("section-title");

        categoriesField.setPromptText("promo, onboarding");
        categoriesField.setPrefColumnCount(24);
        categoriesField.disableProperty().bind(useCategories.selectedProperty().not());

        Button enter = new Button("Enter content zone");
        enter.setOnAction(event -> enterZone());

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

        HBox categoryRow = new HBox(8, useCategories, categoriesField);
        categoryRow.setAlignment(Pos.CENTER_LEFT);

        HBox previewRow = new HBox(8, new Label("Preview:"), previewIdField, preview);
        previewRow.setAlignment(Pos.CENTER_LEFT);

        Label hint = new Label(
            "Enable the Content feature on the Init tab before entering a zone. "
                + "The first fetch waits about 4 seconds, then the SDK polls on the configured interval.");
        hint.setWrapText(true);
        hint.getStyleClass().add("placeholder");

        root.getChildren().addAll(title, categoryRow, zoneRow, previewRow, hint);
    }

    private void enterZone() {
        SdkUtil.run(log, "[Content] enterContentZone", () -> CountlyWebView.enableContentZone(parseCategories()));
    }

    private void previewContent() {
        String id = previewIdField.getText().trim();
        if (id.isEmpty()) {
            log.warn("[Content] Enter a content block ID to preview.");
            return;
        }
        SdkUtil.run(log, "[Content] previewContent " + id, () -> {
            // Previewing still needs a display registered, which entering the zone does for us.
            CountlyWebView.enableContentZone(parseCategories());
            Countly.instance().content().previewContent(id);
        });
    }

    private String[] parseCategories() {
        if (!useCategories.isSelected()) {
            return null;
        }
        String raw = categoriesField.getText().trim();
        if (raw.isEmpty()) {
            return null;
        }
        String[] parts = raw.split(",");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }

    public Parent getRoot() {
        return root;
    }
}
