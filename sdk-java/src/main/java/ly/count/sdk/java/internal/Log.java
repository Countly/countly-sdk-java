package ly.count.sdk.java.internal;

import ly.count.sdk.java.Config;

/**
 * Logging module. Exposes static functions for simplicity, thus can be used only from some point
 * in time when {@link Config} is created and {@link Module}s are up.
 */

public class Log {
    private static Log instance;

    private String tag;
    private Config.LoggingLevel level;


    public Log(InternalConfig config) {

        // let it be specific int and not index for visibility
        tag = config.getLoggingTag();
        level = config.getLoggingLevel();
    }


    /**
     * {@link Config.LoggingLevel#DEBUG} level logging
     *
     * @param string string to log
     */
    public  void d(String string) {
        d(string, null);
    }

    /**
     * {@link Config.LoggingLevel} level logging
     *
     * @param string string to log
     * @param t exception to log along with {@code string}
     */
    public void d(String string, Throwable t) {
        if (level != null && level.prints(Config.LoggingLevel.DEBUG)) {
            if (t == null) {
                print("[DEBUG]\t" + tag + "\t" + string);
            } else {
                print("[DEBUG]\t" + tag + "\t" + string + " / " + t);
            }
        }
    }

    /**
     * {@link Config.LoggingLevel#INFO} level logging
     *
     * @param string string to log
     */
    public void i(String string) {
        i(string, null);
    }

    /**
     * {@link Config.LoggingLevel#INFO} level logging
     *
     * @param string string to log
     * @param t exception to log along with {@code string}
     */
    public void i(String string, Throwable t) {
        if (level.prints(Config.LoggingLevel.INFO)) {
            if (t == null) {
                print("[INFO]\t" + tag + "\t" + string);
            } else {
                print("[INFO]\t" + tag + "\t" + string + " / " + t);
            }
        }
    }

    /**
     * {@link Config.LoggingLevel#WARN} level logging
     *
     * @param string string to log
     */
    public void w(String string) {
        w(string, null);
    }

    /**
     * {@link Config.LoggingLevel#WARN} level logging
     *
     * @param string string to log
     * @param t exception to log along with {@code string}
     */
    public void w(String string, Throwable t) {
        if (level != null && level.prints(Config.LoggingLevel.WARN)) {
            if (t == null) {
                print("[WARN]\t" + tag + "\t" + string);
            } else {
                print("[WARN]\t" + tag + "\t" + string + " / " + t);
            }
        }
    }

    /**
     * {@link Config.LoggingLevel#ERROR} level logging
     *
     * @param string string to log
     */
    public void e(String string) {
        e(string, null);
    }

    /**
     * {@link Config.LoggingLevel#ERROR} level logging
     *
     * @param string string to log
     * @param t exception to log along with {@code string}
     */
    public void e(String string, Throwable t) {
        if (level != null && level.prints(Config.LoggingLevel.ERROR)) {
            if (t == null) {
                print("[ERROR]\t" + tag + "\t" + string);
            } else {
                print("[ERROR]\t" + tag + "\t" + string + " / " + t);
            }
        }
    }
    
    public static void print(String msg) {
        System.out.println(msg);
    }
}
