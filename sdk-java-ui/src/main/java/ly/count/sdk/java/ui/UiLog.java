package ly.count.sdk.java.ui;

import ly.count.sdk.java.internal.Log;
import ly.count.sdk.java.internal.SDKCore;

/**
 * Logs through the SDK's own logger, so everything this package prints honours the logging level
 * and the log listener the integrator configured. Silent while the SDK is not initialized.
 */
final class UiLog {

    private UiLog() {
    }

    static void v(String message) {
        Log logger = SDKCore.logger();
        if (logger != null) {
            logger.v(message);
        }
    }

    static void d(String message) {
        Log logger = SDKCore.logger();
        if (logger != null) {
            logger.d(message);
        }
    }

    static void i(String message) {
        Log logger = SDKCore.logger();
        if (logger != null) {
            logger.i(message);
        }
    }

    static void w(String message) {
        Log logger = SDKCore.logger();
        if (logger != null) {
            logger.w(message);
        }
    }

    static void e(String message) {
        Log logger = SDKCore.logger();
        if (logger != null) {
            logger.e(message);
        }
    }
}
