package ly.count.sdk.java.internal;

import ly.count.sdk.java.Config;

/**
 * Logging module. Exposes static functions for simplicity, thus can be used only from some point
 * in time when {@link Config} is created and {@link Module}s are up.
 */

public class Log {

    private String tag;
    LogCallback logListener = null;
    private Config.LoggingLevel level;


    public Log(InternalConfig config) {
        // let it be specific int and not index for visibility
        tag = config.getLoggingTag();
        level = config.getLoggingLevel();
        logListener = config.getLogListener();
    }

    /**
     * {@link Config.LoggingLevel} level logging
     *
     * @param string string to log
     */
    public void d(String string) {
        String logLevelTag = "[DEBUG]\t";
        String msg = tag + "\t" + string;

        print(logLevelTag + msg, Config.LoggingLevel.DEBUG);
        informListener(msg, Config.LoggingLevel.DEBUG);
    }

    /**
     * {@link Config.LoggingLevel#INFO} level logging
     *
     * @param string string to log
     */
    public void i(String string) {
        String logLevelTag = "[INFO]\t";
        String msg = tag + "\t" + string;

        print(logLevelTag + msg, Config.LoggingLevel.INFO);
        informListener(msg, Config.LoggingLevel.INFO);
    }

    /**
     * {@link Config.LoggingLevel#WARN} level logging
     *
     * @param string string to log
     */
    public void w(String string) {
        String logLevelTag = "[WARN]\t";
        String msg = tag + "\t" + string;

        print(logLevelTag + msg, Config.LoggingLevel.WARN);
        informListener(msg, Config.LoggingLevel.WARN);
    }

    /**
     * {@link Config.LoggingLevel#ERROR} level logging
     *
     * @param string string to log
     */
    public void e(String string) {
        String logLevelTag = "[ERROR]\t";
        String msg = tag + "\t" + string;

        print(logLevelTag + msg, Config.LoggingLevel.ERROR);
        informListener(msg, Config.LoggingLevel.ERROR);
    }

    /**
     * {@link Config.LoggingLevel#VERBOSE} level logging
     *
     * @param string string to log
     */
    public void v(String string) {
        String logLevelTag = "[VERBOSE]\t";
        String msg =  tag + "\t" + string;

        print(logLevelTag + msg, Config.LoggingLevel.VERBOSE);
        informListener(msg, Config.LoggingLevel.VERBOSE);
    }

    private void print(String msg, Config.LoggingLevel level) {
        if (level != null && level.prints(level)) {
            System.out.println(msg);
        }
    }

    private void informListener(String msg, Config.LoggingLevel level) {
        if (logListener != null) {
            logListener.LogHappened(msg, level);
        }
    }
}
