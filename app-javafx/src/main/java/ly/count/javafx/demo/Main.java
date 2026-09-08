package ly.count.javafx.demo;

import java.awt.MouseInfo;
import java.awt.Point;
import java.util.List;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.stage.Screen;
import javafx.stage.Stage;
import ly.count.javafx.demo.ui.MainView;
import ly.count.sdk.java.Countly;
import ly.count.sdk.java.ui.CountlyWebView;

public class Main extends Application {

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 820;

    @Override
    public void start(Stage stage) {
        MainView view = new MainView();
        Scene scene = new Scene(view.getRoot(), WIDTH, HEIGHT);
        scene.getStylesheets().add(
            getClass().getResource("/styles/app.css").toExternalForm()
        );
        // A demo is exactly where the extra logging is wanted: it reports the bundled WebKit
        // version and anything a widget or content page failed to fetch. Needs the SDK's logging
        // level set to DEBUG or VERBOSE on the Init tab to show up in the log panel.
        CountlyWebView.setWebViewDiagnosticsEnabled(true);

        stage.setTitle("Countly Java SDK - JavaFX Demo");
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> {
            if (Countly.isInitialized()) {
                Countly.instance().stop();
            }
            Platform.exit();
        });
        centreOnActiveScreen(stage);
        stage.show();
    }

    /**
     * JavaFX centres a new stage on the primary screen, which on a multiple monitor desk is often
     * not the one being used. Centre on the screen holding the mouse pointer instead, so the demo,
     * and the content the SDK places relative to it, opens where the user is looking.
     */
    private static void centreOnActiveScreen(Stage stage) {
        try {
            Point pointer = MouseInfo.getPointerInfo().getLocation();
            List<Screen> screens = Screen.getScreensForRectangle(pointer.x, pointer.y, 1, 1);
            Rectangle2D bounds = screens.isEmpty()
                ? Screen.getPrimary().getVisualBounds()
                : screens.get(0).getVisualBounds();

            double width = Math.min(WIDTH, bounds.getWidth());
            double height = Math.min(HEIGHT, bounds.getHeight());
            stage.setWidth(width);
            stage.setHeight(height);
            stage.setX(bounds.getMinX() + (bounds.getWidth() - width) / 2);
            stage.setY(bounds.getMinY() + (bounds.getHeight() - height) / 2);
        } catch (Throwable t) {
            // Headless, no pointer, or a locked down desktop: let JavaFX place the window.
            System.out.println("[Demo] Could not centre on the active screen: " + t);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
