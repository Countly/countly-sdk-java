package ly.count.java.demo;

public class DemoUtils {

    private DemoUtils() {
    }

    /**
     * Prints message to console
     *
     * @param message message to print
     */
    protected static void println(String message) {
        System.out.println(message);
    }

    /**
     * Prints formatted message to console
     *
     * @param message message to print
     * @param args arguments to format message
     */
    protected static void printf(String message, Object... args) {
        System.out.printf(message, args);
    }
}
