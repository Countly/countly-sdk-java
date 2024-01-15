package ly.count.java.demo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    static Map<String, Object> map(Object... args) {
        Map<String, Object> map = new ConcurrentHashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            map.put(args[i].toString(), args[i + 1]);
        }
        return map;
    }

    static Map<String, String> mapS(String... args) {
        Map<String, String> map = new ConcurrentHashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            map.put(args[i], args[i + 1]);
        }
        return map;
    }
}
