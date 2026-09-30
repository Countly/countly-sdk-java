package ly.count.sdk.java.internal;

import ly.count.sdk.java.Config;

/**
 * Logging module. Exposes static functions for simplicity, thus can be used only from some point
 * in time when {@link Config} is created and {@link ModuleBase}s are up.
 */

public class Log {
    private final LogCallback logListener;
    private final Config.LoggingLevel loggingLevel;
    //the lowest level printed: the developer level unless the SDK behavior settings override it
    private volatile Config.LoggingLevel printLevel;

    public Log(Config.LoggingLevel loggingLevel, LogCallback logListener) {
        if (loggingLevel == null) {
            throw new NullPointerException("Logging level can't null.");
        }
        this.loggingLevel = loggingLevel;
        this.printLevel = loggingLevel;
        this.logListener = logListener;
    }

    /**
     * Applies the {@code log} switch of the SDK behavior settings to what is printed. Switched off,
     * nothing is printed. Switched on, the developer level is printed, or every level when the
     * developer level is {@link Config.LoggingLevel#OFF}. The log listener receives every line either way.
     *
     * @param loggingEnabled the resolved {@code log} switch
     */
    void setLoggingEnabled(boolean loggingEnabled) {
        if (!loggingEnabled) {
            printLevel = Config.LoggingLevel.OFF;
        } else if (loggingLevel == Config.LoggingLevel.OFF) {
            printLevel = Config.LoggingLevel.VERBOSE;
        } else {
            printLevel = loggingLevel;
        }
    }

    /**
     * The lowest level printed, which the SDK behavior settings can change.
     *
     * @return the level, {@link Config.LoggingLevel#OFF} while nothing is printed
     */
    Config.LoggingLevel getPrintLevel() {
        return printLevel;
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
        if (level != null && printLevel.prints(level)) {
            System.out.println(msg);
        }
    }

    private void informListener(String msg, Config.LoggingLevel level) {
        if (logListener != null) {
            logListener.LogHappened(msg, level);
        }
    }
}
