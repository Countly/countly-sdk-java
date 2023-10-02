package ly.count.sdk.java.internal;

import java.util.Calendar;

public class TimeUtils {

    public static class Time {
        public final long timestamp;
        public final int hour;
        public final int dow;
        public final int tz;

        private Time(long timestamp, int hour, int dow, int tz) {
            this.timestamp = timestamp;
            this.hour = hour;
            this.dow = dow;
            this.tz = tz;
        }
    }

    public static Time getTime() {
        long timestamp = uniqueTimestamp();

        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timestamp);
        return new Time(timestamp,
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

    protected static final Device.TimeGenerator uniqueTimer = new UniqueTimeGenerator();
    protected static final Device.TimeGenerator uniformTimer = new UniformTimeGenerator();

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
}
