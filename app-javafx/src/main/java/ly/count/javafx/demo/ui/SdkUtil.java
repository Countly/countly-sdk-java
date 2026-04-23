package ly.count.javafx.demo.ui;

import ly.count.sdk.java.Countly;

final class SdkUtil {

    static boolean requireSdk(LogPanel log) {
        if (!Countly.isInitialized()) {
            log.warn("SDK not initialized. Initialize it from the Init tab first.");
            return false;
        }
        return true;
    }

    static void run(LogPanel log, String label, Runnable r) {
        if (!requireSdk(log)) {
            return;
        }
        try {
            r.run();
            log.info(label);
        } catch (Exception e) {
            log.error(label + " failed: " + e.getMessage());
        }
    }

    private SdkUtil() {}
}
