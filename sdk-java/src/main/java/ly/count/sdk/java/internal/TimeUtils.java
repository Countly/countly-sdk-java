package ly.count.sdk.java.internal;

import java.util.Calendar;

public class TimeUtils {

    protected static final Double NS_IN_SECOND = 1000000000.0d;
    protected static final Double NS_IN_MS = 1000000.0d;
    protected static final Double MS_IN_SECOND = 1000d;
    private static final Device.TimeGenerator uniqueTimer = new UniqueTimeGenerator();
    private static final Device.TimeGenerator uniformTimer = new UniformTimeGenerator();

    public static class Instant {
        public final long timestamp;
        public final int hour;
        public final int dow;
        public final int tz;

        private Instant(long timestamp, int hour, int dow, int tz) {
            this.timestamp = timestamp;
            this.hour = hour;
            this.dow = dow;
            this.tz = tz;
        }
    }

    /**
     * Generates unique time in milliseconds.
     * And extracts hour, day of week and timezone offset to time objects.
     *
     * @return time object
     */
    public static Instant getTime() {
        long timestamp = uniqueTimestamp();

        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timestamp);
        return new Instant(timestamp,
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.DAY_OF_WEEK) - 1, // Calendar days are 1-based, Countly days are 0-based
            calendar.get(Calendar.ZONE_OFFSET) / 60000); //convert it to seconds
    }

    /**
     * General interface for time generators.
     */
    public interface TimeGenerator {
        long timestamp();
    }

    /**
     * Wraps {@link System#currentTimeMillis()} to always return different value, even within
     * same millisecond and even when time changes. Works in a limited window of 10 timestamps for now.
     *
     * @return unique time in ms
     */
    public static synchronized long uniqueTimestamp() {
        return uniqueTimer.timestamp();
    }

    /**
     * Wraps {@link System#currentTimeMillis()} to return always rising values.
     * Resolves issue with device time updates via NTP or manually where time must go up.
     *
     * @return uniform time in ms
     */
    public static synchronized long uniformTimestamp() {
        return uniformTimer.timestamp();
    }

    /**
     * Convert time in nanoseconds to milliseconds
     *
     * @param ns time in nanoseconds
     * @return ns in milliseconds
     */
    public static long nsToMs(long ns) {
        return Math.round(ns / NS_IN_MS);
    }

    /**
     * Convert time in nanoseconds to seconds
     *
     * @param ns time in nanoseconds
     * @return ns in seconds
     */
    public static long nsToSec(long ns) {
        return Math.round(ns / NS_IN_SECOND);
    }

    /**
     * Convert time in seconds to nanoseconds
     *
     * @param sec time in seconds
     * @return sec in nanoseconds
     */
    public static long secToNs(long sec) {
        return Math.round(sec * NS_IN_SECOND);
    }

    /**
     * Convert time in seconds to milliseconds
     *
     * @param sec time in seconds
     * @return sec in nanoseconds
     */
    public static long secToMs(long sec) {
        return Math.round(sec * MS_IN_SECOND);
    }
}
