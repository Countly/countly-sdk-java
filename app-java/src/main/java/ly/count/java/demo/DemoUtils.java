package ly.count.java.demo;

public final class DemoUtils {

    private DemoUtils() {
    }

    /**
     * Prints message to console
     *
     * @param message message to print
     */
    static void println(final String message) {
        System.out.println(message);
    }

    /**
     * Prints formatted message to console
     *
     * @param message message to print
     * @param args arguments to format message
     */
    static void printf(final String message, final Object... args) {
        System.out.printf(message, args);
    }
}
