package ly.count.sdk.java.internal;

import ly.count.sdk.java.Config;

/**
 * Logging module. Exposes static functions for simplicity, thus can be used only from some point
 * in time when {@link Config} is created and {@link Module}s are up.
 */

public class Log {
    LogCallback logListener = null;
    private Config.LoggingLevel configLevel;


    public Log(InternalConfig config) {
        // let it be specific int and not index for visibility
        configLevel = config.getLoggingLevel();
        logListener = config.getLogListener();
    }

    /**
     * {@link Config.LoggingLevel} level logging
     *
     * @param logMessage string to log
     */
    public void d(String logMessage) {
        print("[DEBUG] [Countly]\t" + logMessage, Config.LoggingLevel.DEBUG);
        informListener(logMessage, Config.LoggingLevel.DEBUG);
    }

    /**
     * {@link Config.LoggingLevel#INFO} level logging
     *
     * @param logMessage string to log
     */
    public void i(String logMessage) {
        print("[INFO] [Countly]\t" + logMessage, Config.LoggingLevel.INFO);
        informListener(logMessage, Config.LoggingLevel.INFO);
    }

    /**
     * {@link Config.LoggingLevel#WARN} level logging
     *
     * @param logMessage string to log
     */
    public void w(String logMessage) {
        print("[WARN] [Countly]\t" + logMessage, Config.LoggingLevel.WARN);
        informListener(logMessage, Config.LoggingLevel.WARN);
    }

    /**
     * {@link Config.LoggingLevel#ERROR} level logging
     *
     * @param logMessage string to log
     */
    public void e(String logMessage) {
        print("[ERROR] [Countly]\t" + logMessage, Config.LoggingLevel.ERROR);
        informListener(logMessage, Config.LoggingLevel.ERROR);
    }

    /**
     * {@link Config.LoggingLevel#VERBOSE} level logging
     *
     * @param logMessage string to log
     */
    public void v(String logMessage) {
        print("[VERBOSE] [Countly]\t" + logMessage, Config.LoggingLevel.VERBOSE);
        informListener(logMessage, Config.LoggingLevel.VERBOSE);
    }

    private void print(String msg, Config.LoggingLevel level) {
        if (level != null && configLevel.prints(level)) {
            System.out.println(msg);
        }
    }

    private void informListener(String msg, Config.LoggingLevel level) {
        if (logListener != null) {
            logListener.LogHappened(msg, level);
        }
    }
}
