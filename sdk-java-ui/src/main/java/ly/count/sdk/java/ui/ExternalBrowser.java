package ly.count.sdk.java.ui;

import java.awt.Desktop;
import java.net.URI;

/**
 * Opens a link outside of the SDK's own web views.
 */
class ExternalBrowser {

    private ExternalBrowser() {
    }

    /**
     * Best effort: a headless JVM, or a desktop environment without a browse action, simply cannot
     * open links, and that must never take the host application down.
     *
     * @param url the link to open
     * @return {@code true} when the link was handed to the system browser
     */
    static boolean open(String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }

        try {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                return false;
            }
            Desktop.getDesktop().browse(new URI(url));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
