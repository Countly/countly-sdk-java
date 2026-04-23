package ly.count.javafx.demo.ui;

import java.awt.Desktop;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import ly.count.javafx.demo.AppContext;
import ly.count.sdk.java.Countly;
import ly.count.sdk.java.internal.CountlyFeedbackWidget;
import ly.count.sdk.java.internal.FeedbackWidgetType;

/**
 * Mirrors cpp_demo/main.cpp + Countly_Feedback_Widget_Implementation_Guide.html:
 *
 *   1. list: SDK's getAvailableFeedbackWidgets() + raw HTTP fetch to capture
 *      the {@code wv} (widget version) field the SDK drops
 *   2. URL: built manually with {@code custom={"tc":1,"xb":1,"rw":1}} on
 *      versioned widgets so the WebView renders its own X close button
 *   3. URL interception: comm host {@code https://countly_action_event} +
 *      external link flag {@code cly_x_int=1}
 */
public class FeedbackWidgetsPane {

    private final BorderPane root = new BorderPane();
    private final VBox cardList = new VBox(8);
    private final Label headerLabel = new Label("Widgets (0)");
    private final Label placeholder = new Label("Select a widget to present it here");
    private final StackPane rightStack = new StackPane();
    private final WebView webView = new WebView();
    private final WebEngine engine = webView.getEngine();
    private final LogPanel log;

    private CountlyFeedbackWidget activeWidget;
    private String activeWidgetVersion = "";
    private final List<CountlyFeedbackWidget> fetched = new ArrayList<>();
    // widgetId → wv ("" for legacy). Populated by the raw HTTP fetch.
    private final Map<String, String> widgetVersions = new ConcurrentHashMap<>();

    public FeedbackWidgetsPane(LogPanel log) {
        this.log = log;

        // ----- left panel -----
        Button refresh = new Button("Fetch widgets");
        refresh.setOnAction(e -> fetchWidgets());

        VBox leftBox = new VBox(10);
        leftBox.setPadding(new Insets(10));
        headerLabel.getStyleClass().add("section-title");
        HBox headerRow = new HBox(8, headerLabel, refresh);
        headerRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        leftBox.getChildren().addAll(headerRow, cardList);

        ScrollPane leftScroll = new ScrollPane(leftBox);
        leftScroll.setFitToWidth(true);
        leftScroll.setPrefViewportWidth(340);

        // ----- right panel: stack that shows placeholder OR webview -----
        placeholder.getStyleClass().add("placeholder");
        placeholder.setMaxWidth(Double.MAX_VALUE);
        placeholder.setMaxHeight(Double.MAX_VALUE);
        StackPane.setAlignment(placeholder, javafx.geometry.Pos.CENTER);

        rightStack.getChildren().addAll(placeholder, webView);
        webView.setVisible(false);

        // WebView setup
        engine.setJavaScriptEnabled(true);
        engine.setUserAgent(engine.getUserAgent() + " CountlyJavaFXDemo/1.0");
        engine.locationProperty().addListener((obs, oldUrl, newUrl) -> interceptUrl(newUrl));
        engine.setCreatePopupHandler(popupFeatures -> handlePopup());
        engine.getLoadWorker().stateProperty().addListener((obs, oldS, newS) -> {
            if (newS == Worker.State.SUCCEEDED) {
                webView.setVisible(true);
                placeholder.setVisible(false);
                log.info("[Widget] Page loaded");
            } else if (newS == Worker.State.FAILED) {
                showPlaceholder("Page load failed");
                log.error("[Widget] Page load failed: " + engine.getLoadWorker().getException());
            } else if (newS == Worker.State.CANCELLED) {
                log.info("[Widget] Page load cancelled (handled as comm URL)");
            }
        });

        SplitPane split = new SplitPane(leftScroll, rightStack);
        split.setOrientation(Orientation.HORIZONTAL);
        split.setDividerPositions(0.28);
        root.setCenter(split);
    }

    // ------------------- Widget fetch -------------------
    private void fetchWidgets() {
        if (!SdkUtil.requireSdk(log)) return;
        log.info("[Widget] Fetching available feedback widgets...");
        cardList.getChildren().clear();
        fetched.clear();
        widgetVersions.clear();
        headerLabel.setText("Widgets (...)");

        // 1) Raw HTTP fetch in parallel so we can populate wv per widget.
        //    We do this on a background thread because HttpURLConnection blocks.
        String serverUrl = AppContext.liveServerUrl;
        String appKey = AppContext.liveAppKey;
        String deviceId = Countly.instance().deviceId().getID();
        new Thread(() -> {
            try {
                Map<String, String> versions = FeedbackHttp.fetchVersions(
                    serverUrl, appKey, deviceId,
                    AppContext.SDK_NAME, AppContext.SDK_VERSION);
                widgetVersions.putAll(versions);
                log.info("[Widget] Raw fetch captured wv for " + versions.size() + " widgets");
                Platform.runLater(this::refreshCards);
            } catch (Exception ex) {
                log.warn("[Widget] Raw fetch failed (" + ex.getMessage()
                    + "). Close button may not render for versioned widgets.");
            }
        }, "countly-feedback-raw").start();

        // 2) SDK fetch — still the canonical list for the demo.
        Countly.instance().feedback().getAvailableFeedbackWidgets((widgets, error) -> Platform.runLater(() -> {
            if (error != null || widgets == null) {
                log.error("[Widget] SDK fetch error: " + error);
                headerLabel.setText("Widgets (error)");
                return;
            }
            fetched.clear();
            fetched.addAll(widgets);
            log.info("[Widget] SDK returned " + widgets.size() + " widgets");
            refreshCards();
        }));
    }

    private void refreshCards() {
        cardList.getChildren().clear();
        headerLabel.setText("Widgets (" + fetched.size() + ")");
        for (CountlyFeedbackWidget w : fetched) {
            String wv = widgetVersions.getOrDefault(w.widgetId, "");
            cardList.getChildren().add(new WidgetCard(w, wv,
                widget -> openWidget(widget, wv),
                this::inspectWidget,
                this::openManualReportDialog));
        }
    }

    // ------------------- WebView presentation -------------------
    private void openWidget(CountlyFeedbackWidget widget, String widgetVersion) {
        if (!SdkUtil.requireSdk(log)) return;

        String serverUrl = AppContext.liveServerUrl;
        String appKey = AppContext.liveAppKey;
        String deviceId = Countly.instance().deviceId().getID();

        String url = FeedbackHttp.constructWebViewUrl(
            serverUrl, appKey, deviceId,
            AppContext.SDK_NAME, AppContext.SDK_VERSION,
            AppContext.APP_VERSION, AppContext.PLATFORM,
            widget.widgetId, widget.type.name(), widgetVersion);

        activeWidget = widget;
        activeWidgetVersion = widgetVersion == null ? "" : widgetVersion;
        log.info("[Widget] Opening " + widget.type + " \"" + widget.name
            + "\" (wv=" + (widgetVersion.isEmpty() ? "legacy" : widgetVersion) + ")");
        log.info("[Widget] URL: " + url);

        webView.setVisible(false);
        placeholder.setVisible(true);
        placeholder.setText("Loading widget...");
        engine.load(url);
    }

    private void closeActiveWidget(boolean userClosed) {
        if (activeWidget == null) return;
        if (userClosed) {
            // Mark as closed / cancelled — mirrors SDK's reportFeedbackWidgetManually(widget, null, null).
            Countly.instance().feedback().reportFeedbackWidgetManually(activeWidget, null, null);
            log.info("[Widget] Reported close for " + activeWidget.widgetId);
        }
        activeWidget = null;
        activeWidgetVersion = "";
        engine.load("about:blank");
        showPlaceholder("Select a widget to present it here");
    }

    private void showPlaceholder(String text) {
        placeholder.setText(text);
        placeholder.setVisible(true);
        webView.setVisible(false);
    }

    // ------------------- Inspect widget data -------------------
    private void inspectWidget(CountlyFeedbackWidget widget) {
        if (!SdkUtil.requireSdk(log)) return;
        log.info("[Widget] Fetching widget data for " + widget.widgetId);
        Countly.instance().feedback().getFeedbackWidgetData(widget, (data, error) -> Platform.runLater(() -> {
            if (error != null) {
                log.error("[Widget] Fetch data error: " + error);
                showJsonDialog("Widget data error", error);
                return;
            }
            String pretty = data == null ? "(empty)" : data.toString(2);
            log.info("[Widget] Data fetched (" + pretty.length() + " chars)");
            showJsonDialog("Widget data: " + widget.name, pretty);
        }));
    }

    private void showJsonDialog(String title, String body) {
        Dialog<Void> dlg = new Dialog<>();
        dlg.setTitle(title);
        TextArea ta = new TextArea(body);
        ta.setEditable(false);
        ta.setWrapText(false);
        ta.setPrefSize(720, 480);
        dlg.getDialogPane().setContent(ta);
        dlg.getDialogPane().getButtonTypes().add(javafx.scene.control.ButtonType.CLOSE);
        dlg.showAndWait();
    }

    // ------------------- Manual report dialog -------------------
    private void openManualReportDialog(CountlyFeedbackWidget widget) {
        if (!SdkUtil.requireSdk(log)) return;
        Dialog<Void> dlg = new Dialog<>();
        dlg.setTitle("Report manually: " + widget.type + " / " + widget.name);

        TextField ratingField = new TextField(widget.type == FeedbackWidgetType.nps ? "9" : "4");
        TextField commentField = new TextField("Great experience");
        TextField emailField = new TextField("user@example.com");
        TextField customKey = new TextField("source");
        TextField customVal = new TextField("manual-demo");

        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(8);
        g.setPadding(new Insets(10));
        g.addRow(0, new Label("rating"), ratingField);
        g.addRow(1, new Label("comment"), commentField);
        g.addRow(2, new Label("email"), emailField);
        g.addRow(3, new Label("custom key"), customKey);
        g.addRow(4, new Label("custom value"), customVal);
        dlg.getDialogPane().setContent(g);

        javafx.scene.control.ButtonType sendBtn = new javafx.scene.control.ButtonType("Send", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        javafx.scene.control.ButtonType cancelBtn = new javafx.scene.control.ButtonType("Report 'closed'", javafx.scene.control.ButtonBar.ButtonData.OTHER);
        dlg.getDialogPane().getButtonTypes().addAll(sendBtn, cancelBtn, javafx.scene.control.ButtonType.CLOSE);

        dlg.setResultConverter(btn -> {
            if (btn == sendBtn) {
                Map<String, Object> result = new LinkedHashMap<>();
                try {
                    result.put("rating", Integer.parseInt(ratingField.getText().trim()));
                } catch (NumberFormatException ex) {
                    log.error("[Widget] Invalid rating, skipped.");
                }
                result.put("comment", commentField.getText());
                result.put("email", emailField.getText());
                if (!customKey.getText().isEmpty()) {
                    result.put(customKey.getText(), customVal.getText());
                }
                Countly.instance().feedback().reportFeedbackWidgetManually(widget, null, result);
                log.info("[Widget] Manual report sent for " + widget.widgetId + ": " + result);
            } else if (btn == cancelBtn) {
                Countly.instance().feedback().reportFeedbackWidgetManually(widget, null, null);
                log.info("[Widget] Manual 'closed' report sent for " + widget.widgetId);
            }
            return null;
        });
        dlg.showAndWait();
    }

    // ------------------- URL interception -------------------
    private void interceptUrl(String url) {
        if (url == null || url.isEmpty()) return;

        String host = safeHost(url);
        boolean isCommHost = FeedbackHttp.COMM_HOST.equals(host);
        boolean isExternal = queryFlagSet(url, "cly_x_int", "1");

        if (!isCommHost && !isExternal) {
            return;
        }

        // Cancel pending navigation — we handle comm URLs ourselves.
        engine.getLoadWorker().cancel();

        Map<String, String> params = parseQuery(url);

        if ("1".equals(params.get("cly_x_int"))) {
            log.info("[Widget] External link → opening in browser: " + url);
            openInBrowser(url);
            return;
        }

        if ("1".equals(params.get("cly_widget_command"))) {
            if ("1".equals(params.get("close"))) {
                log.info("[Widget] cly_widget_command close=1");
                Platform.runLater(() -> closeActiveWidget(true));
            } else {
                log.info("[Widget] Widget command: " + params);
            }
            return;
        }

        if ("1".equals(params.get("cly_x_action_event"))) {
            String action = params.getOrDefault("action", "");
            if ("link".equals(action) && params.get("link") != null) {
                log.info("[Widget] action=link → opening " + params.get("link"));
                openInBrowser(params.get("link"));
            } else {
                log.info("[Widget] action event: " + params);
            }
            if ("1".equals(params.get("close"))) {
                log.info("[Widget] action + close=1 → dismissing");
                Platform.runLater(() -> closeActiveWidget(true));
            }
            return;
        }

        log.warn("[Widget] Unhandled comm URL: " + url);
    }

    private static String safeHost(String url) {
        try {
            return new URL(url).getHost();
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean queryFlagSet(String url, String key, String expected) {
        int q = url.indexOf('?');
        if (q < 0) return false;
        String query = url.substring(q + 1);
        String needle = key + "=" + expected;
        return query.contains(needle);
    }

    private static Map<String, String> parseQuery(String url) {
        Map<String, String> out = new HashMap<>();
        int q = url.indexOf('?');
        if (q < 0) return out;
        String query = url.substring(q + 1);
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq < 0) {
                out.put(decode(part), "");
            } else {
                out.put(decode(part.substring(0, eq)), decode(part.substring(eq + 1)));
            }
        }
        return out;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return s;
        }
    }

    private static void openInBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
            }
        } catch (Exception ignored) {
            // Headless / non-desktop env: silently fall through
        }
    }

    // JavaFX popup handler: opens target=_blank links in the system browser and
    // returns a throwaway engine so the WebView doesn't try to render them.
    private WebEngine handlePopup() {
        WebEngine popup = new WebEngine();
        popup.locationProperty().addListener((obs, oldUrl, newUrl) -> {
            if (newUrl != null && !newUrl.isEmpty()) {
                log.info("[Widget] Popup → system browser: " + newUrl);
                openInBrowser(newUrl);
            }
        });
        return popup;
    }

    public Parent getRoot() {
        return root;
    }

    @SuppressWarnings("unused")
    private static List<CountlyFeedbackWidget> emptyList() {
        return Collections.emptyList();
    }
}
