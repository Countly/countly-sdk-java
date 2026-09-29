package ly.count.consumer;

import ly.count.sdk.java.ui.CountlyWebView;
import ly.count.sdk.java.ui.JavaFxContentDisplay;

/**
 * Compiles against the UI artifact the way an integrator would.
 */
public class Probe {
    /**
     * Reads a setting of the UI artifact and names its content display class.
     *
     * @param args unused
     */
    public static void main(String[] args) {
        System.out.println("widgets within app " + CountlyWebView.isShowingWidgetsWithinApp() + ", display " + JavaFxContentDisplay.class.getName());
    }
}
