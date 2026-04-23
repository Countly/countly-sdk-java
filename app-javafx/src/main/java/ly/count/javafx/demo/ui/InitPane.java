package ly.count.javafx.demo.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import ly.count.javafx.demo.AppContext;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;

public class InitPane {

    private final ScrollPane scroll = new ScrollPane();
    private final VBox root = new VBox(14);

    // Core endpoint + identity
    private final TextField serverField = new TextField();
    private final TextField appKeyField = new TextField();
    private final TextField customIdField = new TextField();
    private final TextField appVersionField = new TextField();
    private final TextField sdkPlatformField = new TextField();
    private final TextField saltField = new TextField();
    private final ComboBox<Config.LoggingLevel> loggingLevelBox = new ComboBox<>();

    // Feature toggles (per Config.Feature)
    private final Map<Config.Feature, CheckBox> featureBoxes = new EnumMap<>(Config.Feature.class);

    // Flags
    private final CheckBox requiresConsent = new CheckBox("Requires consent (GDPR)");
    private final CheckBox backendMode = new CheckBox("Enable backend mode");
    private final CheckBox autoRcTriggers = new CheckBox("Enable RC auto-download triggers");
    private final CheckBox rcCaching = new CheckBox("Enable RC value caching");
    private final CheckBox rcAutoEnroll = new CheckBox("Auto-enroll AB on RC download");
    private final CheckBox forcePost = new CheckBox("Force HTTP POST");
    private final CheckBox disableUnhandledCrash = new CheckBox("Disable unhandled crash reporting");
    private final CheckBox disableAutoUserProps = new CheckBox("Disable auto-send user properties");
    private final CheckBox disableLocationBox = new CheckBox("Disable location tracking");

    // Numeric / tuning fields
    private final TextField eventQueueSizeField = new TextField();
    private final TextField sessionUpdateDelayField = new TextField();
    private final TextField maxBreadcrumbsField = new TextField();
    private final TextField requestQueueMaxField = new TextField();
    private final TextField netConnectTimeoutField = new TextField();
    private final TextField netReadTimeoutField = new TextField();
    private final TextField netRequestCooldownField = new TextField();
    private final TextField netImportantCooldownField = new TextField();

    private final Button initButton = new Button("Initialize SDK");
    private final Button beginButton = new Button("Begin session");
    private final Button updateButton = new Button("Update session");
    private final Button endButton = new Button("End session");
    private final Button stopButton = new Button("Stop SDK");
    private final Button haltButton = new Button("Halt (stop + clear data)");
    private final Button grantButton = new Button("Grant consent: ALL");
    private final Button revokeButton = new Button("Revoke consent: ALL");

    private final LogPanel log;
    private final Label statusLabel;

    public InitPane(LogPanel log, Label statusLabel) {
        this.log = log;
        this.statusLabel = statusLabel;

        serverField.setText(AppContext.DEFAULT_SERVER_URL);
        appKeyField.setText(AppContext.DEFAULT_APP_KEY);
        customIdField.setText(AppContext.DEFAULT_DEVICE_ID);
        appVersionField.setText(AppContext.APP_VERSION);
        sdkPlatformField.setText(AppContext.PLATFORM);

        // SDK defaults surfaced as placeholder/initial values
        eventQueueSizeField.setText("1"); // demo: flush events immediately
        sessionUpdateDelayField.setPromptText("60");
        maxBreadcrumbsField.setPromptText("100");
        requestQueueMaxField.setPromptText("1000");
        netConnectTimeoutField.setPromptText("30");
        netReadTimeoutField.setPromptText("30");
        netRequestCooldownField.setPromptText("1000");
        netImportantCooldownField.setPromptText("5000");

        loggingLevelBox.getItems().addAll(Config.LoggingLevel.values());
        loggingLevelBox.setValue(Config.LoggingLevel.DEBUG);

        for (Config.Feature f : Config.Feature.values()) {
            CheckBox cb = new CheckBox(f.name());
            cb.setSelected(true); // preserve previous "enable all" default
            featureBoxes.put(f, cb);
        }

        root.setPadding(new Insets(16));
        root.getChildren().addAll(
            title("SDK Initialization"),
            buildForm(),
            new Separator(),
            title("Features"),
            buildFeatures(),
            new Separator(),
            title("Flags"),
            buildFlags(),
            new Separator(),
            title("Tuning"),
            buildTuning(),
            new Separator(),
            buildButtons(),
            new Label("Storage dir: " + AppContext.storageDir().getAbsolutePath())
        );

        scroll.setContent(root);
        scroll.setFitToWidth(true);

        initButton.setOnAction(e -> initSdk());
        beginButton.setOnAction(e -> runWithSdk(() -> { Countly.session().begin();   log.info("session().begin()"); }));
        updateButton.setOnAction(e -> runWithSdk(() -> { Countly.session().update();  log.info("session().update()"); }));
        endButton.setOnAction(e -> runWithSdk(() -> { Countly.session().end();     log.info("session().end()"); }));
        stopButton.setOnAction(e -> runWithSdk(() -> { Countly.instance().stop();   log.info("Countly.stop()"); updateStatus(); }));
        haltButton.setOnAction(e -> runWithSdk(() -> { Countly.instance().halt();   log.info("Countly.halt()"); updateStatus(); }));
        grantButton.setOnAction(e -> runWithSdk(() -> {
            Countly.onConsent(Config.Feature.values());
            log.info("onConsent(ALL)");
        }));
        revokeButton.setOnAction(e -> runWithSdk(() -> {
            Countly.onConsentRemoval(Config.Feature.values());
            log.info("onConsentRemoval(ALL)");
        }));
    }

    private GridPane buildForm() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.addRow(0, new Label("Server URL:"),                   grow(serverField));
        grid.addRow(1, new Label("App Key:"),                      grow(appKeyField));
        grid.addRow(2, new Label("Custom device ID (optional):"),  grow(customIdField));
        grid.addRow(3, new Label("Application version:"),          grow(appVersionField));
        grid.addRow(4, new Label("SDK platform:"),                 grow(sdkPlatformField));
        grid.addRow(5, new Label("Logging level:"),                loggingLevelBox);
        grid.addRow(6, new Label("Tampering salt (optional):"),    grow(saltField));
        return grid;
    }

    private VBox buildFeatures() {
        HBox row = new HBox(12);
        for (CheckBox cb : featureBoxes.values()) {
            row.getChildren().add(cb);
        }
        VBox box = new VBox(4, row);
        box.setPadding(new Insets(2, 0, 2, 0));
        return box;
    }

    private VBox buildFlags() {
        HBox col1 = new HBox(20, new VBox(4, requiresConsent, backendMode, forcePost));
        VBox rcBox = new VBox(4, autoRcTriggers, rcCaching, rcAutoEnroll);
        VBox disableBox = new VBox(4, disableUnhandledCrash, disableAutoUserProps, disableLocationBox);
        HBox row = new HBox(30, col1, rcBox, disableBox);
        row.setPadding(new Insets(2, 0, 0, 0));
        return new VBox(row);
    }

    private GridPane buildTuning() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.addRow(0, new Label("Event queue size to send:"),       eventQueueSizeField);
        grid.addRow(1, new Label("Session update delay (seconds):"), sessionUpdateDelayField);
        grid.addRow(2, new Label("Max breadcrumbs:"),                maxBreadcrumbsField);
        grid.addRow(3, new Label("Request queue max size:"),         requestQueueMaxField);
        grid.addRow(4, new Label("Network connect timeout (s):"),    netConnectTimeoutField);
        grid.addRow(5, new Label("Network read timeout (s):"),       netReadTimeoutField);
        grid.addRow(6, new Label("Network request cooldown (ms):"),  netRequestCooldownField);
        grid.addRow(7, new Label("Important request cooldown (ms):"), netImportantCooldownField);
        return grid;
    }

    private HBox buildButtons() {
        HBox row1 = new HBox(8, initButton, beginButton, updateButton, endButton);
        HBox row2 = new HBox(8, stopButton, haltButton, grantButton, revokeButton);
        row1.setAlignment(Pos.CENTER_LEFT);
        row2.setAlignment(Pos.CENTER_LEFT);
        VBox wrap = new VBox(8, row1, row2);
        return new HBox(wrap);
    }

    private void initSdk() {
        if (Countly.isInitialized()) {
            log.warn("SDK already initialized. Stop it before re-init.");
            return;
        }
        try {
            Config config = new Config(
                serverField.getText().trim(),
                appKeyField.getText().trim(),
                AppContext.storageDir()
            )
                .setLoggingLevel(loggingLevelBox.getValue())
                .setLogListener((msg, lvl) -> log.sdk("[" + lvl + "] " + msg));

            Config.Feature[] selected = selectedFeatures();
            if (selected.length > 0) {
                config.setFeatures(selected);
            }

            String appVersion = appVersionField.getText().trim();
            if (!appVersion.isEmpty()) {
                config.setApplicationVersion(appVersion);
            }

            String platform = sdkPlatformField.getText().trim();
            if (!platform.isEmpty()) {
                config.setSdkPlatform(platform);
            }

            String salt = saltField.getText().trim();
            if (!salt.isEmpty()) {
                config.enableParameterTamperingProtection(salt);
            }

            String custom = customIdField.getText().trim();
            if (!custom.isEmpty()) {
                config.setCustomDeviceId(custom);
            } else {
                config.setDeviceIdStrategy(Config.DeviceIdStrategy.UUID);
            }

            if (requiresConsent.isSelected()) {
                config.setRequiresConsent(true);
            }
            if (backendMode.isSelected()) {
                config.enableBackendMode();
            }
            if (autoRcTriggers.isSelected()) {
                config.enableRemoteConfigAutomaticTriggers();
            }
            if (rcCaching.isSelected()) {
                config.enableRemoteConfigValueCaching();
            }
            if (rcAutoEnroll.isSelected()) {
                config.enrollABOnRCDownload();
            }
            if (forcePost.isSelected()) {
                config.enableForcedHTTPPost();
            }
            if (disableUnhandledCrash.isSelected()) {
                config.disableUnhandledCrashReporting();
            }
            if (disableAutoUserProps.isSelected()) {
                config.disableAutoSendUserProperties();
            }
            if (disableLocationBox.isSelected()) {
                config.disableLocation();
            }

            applyIntField(eventQueueSizeField,     "event queue size",        config::setEventQueueSizeToSend);
            applyIntField(sessionUpdateDelayField, "session update delay",    config::setUpdateSessionTimerDelay);
            applyIntField(maxBreadcrumbsField,     "max breadcrumbs",         config::setMaxBreadcrumbCount);
            applyIntField(requestQueueMaxField,    "request queue max size",  config::setRequestQueueMaxSize);
            applyIntField(netConnectTimeoutField,  "network connect timeout", config::setNetworkConnectTimeout);
            applyIntField(netReadTimeoutField,     "network read timeout",    config::setNetworkReadTimeout);
            applyIntField(netRequestCooldownField, "network request cooldown", config::setNetworkRequestCooldown);
            applyIntField(netImportantCooldownField, "important request cooldown", config::setNetworkImportantRequestCooldown);

            Countly.instance().init(config);

            // Cache credentials so the feedback pane can hit the server directly
            // for the raw /o/sdk?method=feedback request (needed to recover the
            // 'wv' field that the SDK's typed CountlyFeedbackWidget omits).
            AppContext.liveServerUrl = serverField.getText().trim();
            AppContext.liveAppKey = appKeyField.getText().trim();

            if (requiresConsent.isSelected() && selected.length > 0) {
                Countly.onConsent(selected);
                log.info("Consent granted to selected features");
            }

            Countly.session().begin();
            log.info("SDK initialized + session begun (server=" + serverField.getText() + ")");
            log.info("Enabled features: " + Arrays.toString(selected));
            updateStatus();
        } catch (Exception ex) {
            log.error("Init failed: " + ex.getMessage());
        }
    }

    private Config.Feature[] selectedFeatures() {
        List<Config.Feature> out = new ArrayList<>();
        for (Map.Entry<Config.Feature, CheckBox> e : featureBoxes.entrySet()) {
            if (e.getValue().isSelected()) {
                out.add(e.getKey());
            }
        }
        return out.toArray(new Config.Feature[0]);
    }

    private void applyIntField(TextField field, String label, java.util.function.IntConsumer setter) {
        String raw = field.getText() == null ? "" : field.getText().trim();
        if (raw.isEmpty()) {
            return;
        }
        try {
            setter.accept(Integer.parseInt(raw));
        } catch (NumberFormatException ex) {
            log.warn("Ignoring invalid " + label + " value: " + raw);
        }
    }

    private void runWithSdk(Runnable r) {
        if (!Countly.isInitialized()) {
            log.warn("SDK not initialized yet.");
            return;
        }
        try {
            r.run();
        } catch (Exception ex) {
            log.error(ex.getMessage());
        }
    }

    private void updateStatus() {
        if (Countly.isInitialized()) {
            String id = Countly.instance().deviceId() != null
                ? Countly.instance().deviceId().getID()
                : "?";
            statusLabel.setText("SDK: initialized · device=" + id);
            statusLabel.getStyleClass().setAll("status-pill", "status-ok");
        } else {
            statusLabel.setText("SDK: not initialized");
            statusLabel.getStyleClass().setAll("status-pill");
        }
    }

    private static Label title(String s) {
        Label l = new Label(s);
        l.getStyleClass().add("section-title");
        return l;
    }

    private static TextField grow(TextField f) {
        f.setPrefWidth(480);
        return f;
    }

    public Parent getRoot() {
        return scroll;
    }
}
