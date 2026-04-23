package ly.count.javafx.demo.ui;

import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

public class MainView {

    private final BorderPane root = new BorderPane();
    private final LogPanel logPanel = new LogPanel();
    private final Label statusLabel = new Label("SDK: not initialized");

    public MainView() {
        root.getStyleClass().add("app-root");

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        tabs.getTabs().addAll(
            tab("Init",           new InitPane(logPanel, statusLabel).getRoot()),
            tab("Events",         new EventsPane(logPanel).getRoot()),
            tab("Views",          new ViewsPane(logPanel).getRoot()),
            tab("User Profile",   new UserProfilePane(logPanel).getRoot()),
            tab("Location",       new LocationPane(logPanel).getRoot()),
            tab("Crashes",        new CrashesPane(logPanel).getRoot()),
            tab("Device ID",      new DeviceIdPane(logPanel, statusLabel).getRoot()),
            tab("Remote Config",  new RemoteConfigPane(logPanel).getRoot()),
            tab("Feedback Widgets", new FeedbackWidgetsPane(logPanel).getRoot())
        );

        SplitPane split = new SplitPane();
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.getItems().addAll(tabs, logPanel.getRoot());
        split.setDividerPositions(0.75);
        SplitPane.setResizableWithParent(logPanel.getRoot(), true);
        VBox.setVgrow(split, Priority.ALWAYS);

        root.setTop(buildHeader());
        root.setCenter(split);

        logPanel.info("Ready. Head to the Init tab to configure and start the SDK.");
    }

    private HBox buildHeader() {
        Label title = new Label("Countly Java SDK · JavaFX Demo");
        title.getStyleClass().add("app-title");
        statusLabel.getStyleClass().add("status-pill");
        HBox header = new HBox(12, title, spacer(), statusLabel);
        header.setPadding(new Insets(12, 16, 12, 16));
        header.getStyleClass().add("header-bar");
        return header;
    }

    private static javafx.scene.Node spacer() {
        javafx.scene.layout.Region r = new javafx.scene.layout.Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    private static Tab tab(String name, Parent content) {
        Tab t = new Tab(name);
        t.setContent(content);
        return t;
    }

    public Parent getRoot() {
        return root;
    }
}
