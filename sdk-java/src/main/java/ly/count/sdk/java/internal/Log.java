package ly.count.sdk.java.internal;

import ly.count.sdk.java.Config;

/**
 * Logging module. Exposes static functions for simplicity, thus can be used only from some point
 * in time when {@link Config} is created and {@link Module}s are up.
 */

public class Log extends ModuleBase {
    private static Log instance;

    private String tag;
    private boolean testMode;
    private Config.LoggingLevel level;

    public static final class Module {
        String name;

        Module(String name) {
            this.name = name;
        }

        public void d(String message) { if(instance != null) instance.d("[" + name + "] " + message); }
        public void d(String message, Throwable throwable) { if(instance != null) instance.d("[" + name + "] " + message, throwable); }
        public void i(String message) { if(instance != null) instance.i("[" + name + "] " + message); }
        public void i(String message, Throwable throwable) { if(instance != null) instance.i("[" + name + "] " + message, throwable); }
        public void w(String message) { if(instance != null) instance.w("[" + name + "] " + message); }
        public void w(String message, Throwable throwable) { if(instance != null) instance.w("[" + name + "] " + message, throwable); }
        public void e(String message) { if(instance != null) instance.e("[" + name + "] " + message); }
        public void e(String message, Throwable throwable) { if(instance != null) instance.e("[" + name + "] " + message, throwable); }
        public void wtf(String message) { if(instance != null) instance.wtf("[" + name + "] " + message); }
        public void wtf(String message, Throwable throwable) { if(instance != null) instance.wtf("[" + name + "] " + message, throwable); }
    }

    @Override
    public void init(InternalConfig config) {
        instance = this;

        // let it be specific int and not index for visibility
        tag = config.getLoggingTag();
        level = config.getLoggingLevel();
        testMode = config.isTestModeEnabled();

    }

    @Override
    public Integer getFeature() {
        return CoreFeature.Logs.getIndex();
    }

    public static void deinit() {
        instance = null;
    }

    public static Module module(String name) {
        return new Module(name);
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
        if (level.prints(Config.LoggingLevel.DEBUG)) {
            if (t == null) {
                System.out.println("[DEBUG]\t" + tag + "\t" + string);
            } else {
                System.out.println("[DEBUG]\t" + tag + "\t" + string + " / " + t);
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
                System.out.println("[INFO]\t" + tag + "\t" + string);
            } else {
                System.out.println("[INFO]\t" + tag + "\t" + string + " / " + t);
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
        if (level.prints(Config.LoggingLevel.WARN)) {
            if (t == null) {
                System.out.println("[WARN]\t" + tag + "\t" + string);
            } else {
                System.out.println("[WARN]\t" + tag + "\t" + string + " / " + t);
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
        if (level.prints(Config.LoggingLevel.ERROR)) {
            if (t == null) {
                System.out.println("[ERROR]\t" + tag + "\t" + string);
            } else {
                System.out.println("[ERROR]\t" + tag + "\t" + string + " / " + t);
            }
        }
    }

    /**
     * {@link Config.LoggingLevel#ERROR} (Android wtf) level logging which throws an
     * exception when {@link Config#testMode} is enabled.
     *
     * @param string string to log
     * @throws IllegalStateException when {@link Config#testMode} is on
     */

    public void wtf(String string) {
        wtf(string, null);
    }

    /**
     * {@link Config.LoggingLevel#ERROR} (Android wtf) level logging which throws an
     * exception when {@link Config#testMode} is enabled.
     *
     * @param string string to log
     * @param t exception to log along with {@code string}
     */
    public void wtf(String string, Throwable t) {
        if (level != Config.LoggingLevel.OFF) {
            if (t == null) {
                System.out.println("[WTF]\t" + tag + "\t" + string);
            } else {
                System.out.println("[WTF]\t" + tag + "\t" + string + " / " + t);
            }
        }
    }
}
