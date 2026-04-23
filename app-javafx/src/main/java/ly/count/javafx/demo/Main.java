package ly.count.javafx.demo;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import ly.count.javafx.demo.ui.MainView;
import ly.count.sdk.java.Countly;

public class Main extends Application {

    @Override
    public void start(Stage stage) {
        MainView view = new MainView();
        Scene scene = new Scene(view.getRoot(), 1280, 820);
        scene.getStylesheets().add(
            getClass().getResource("/styles/app.css").toExternalForm()
        );
        stage.setTitle("Countly Java SDK - JavaFX Demo");
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> {
            if (Countly.isInitialized()) {
                Countly.instance().stop();
            }
            Platform.exit();
        });
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
