package ly.count.sdk.java.backend.helper;

import java.util.Calendar;

public class TimeHelper {

    public static class TimeInstant {
        public final int hour;
        public final int dow; //0-Sunday, 1-Monday, 2-Tuesday, 3-Wednesday, 4-Thursday, 5-Friday, 6-Saturday
        public final long timestamp;

        protected TimeInstant(long timestampInMillis, int hour, int dow) {
            this.dow = dow;
            this.hour = hour;
            this.timestamp = timestampInMillis;
        }

        public static TimeInstant get(long timestampInMillis) {
            if (timestampInMillis < 0L) {
                throw new IllegalArgumentException("timestampInMillis must be greater than or equal to zero");
            }
            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(timestampInMillis);
            final int hour = calendar.get(Calendar.HOUR_OF_DAY);
            // Calendar days are 1-based, Countly days are 0-based
            final int dow = calendar.get(Calendar.DAY_OF_WEEK) - 1;
            return new TimeInstant(timestampInMillis, hour, dow);
        }
    }

    private long lastMilliSecTimeStamp = 0;

    public long getUniqueUnixTime() {
        long calculatedMillis = System.currentTimeMillis();

        if (lastMilliSecTimeStamp >= calculatedMillis) {
            ++lastMilliSecTimeStamp;
        } else {
            lastMilliSecTimeStamp = calculatedMillis;
        }

        return lastMilliSecTimeStamp;
    }

    public TimeInstant getUniqueInstant() {
        long currentTimestamp = getUniqueUnixTime();
        TimeInstant timeInstant = TimeInstant.get(currentTimestamp);
        return timeInstant;
    }
}
