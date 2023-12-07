package ly.count.sdk.java.internal;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class TimeUtilsTests {

    /**
     * "getTime" method
     * Create a time instant
     * Validate whether the "getTime" method returns a valid time object.
     */
    @Test
    public void getInstant() {

        TimeUtils.Instant time = TimeUtils.getCurrentInstantUnique();

        Assert.assertTrue(time.timestamp > 0);
        Assert.assertTrue(time.hour >= 0 && time.hour <= 23);
        Assert.assertTrue(time.dow >= 0 && time.dow <= 6);
        Assert.assertTrue(time.tz >= -720 && time.tz <= 840);
    }

    /**
     * The "uniqueTimestampMs" utility function
     * Generate 10 timestamps in quick succession
     * All of them should be unique.
     */
    @Test
    public void uniqueTimestamp() {
        long last = 0;
        for (int i = 0; i < 10; i++) {
            long now = TimeUtils.uniqueTimestampMs();
            Assert.assertTrue(now > last);
            last = now;
        }
    }

    /**
     * The "nsToMs" utility function
     * pass it ns time make sure it is correctly translated to ms
     */
    @Test
    public void nsToMs() {
        Assert.assertEquals(1, TimeUtils.nsToMs(1000000));
    }

    /**
     * The "nsToSec" utility function
     * pass it ns time make sure it is correctly translated to sec
     */
    @Test
    public void nsToSec() {
        Assert.assertEquals(1, TimeUtils.nsToSec(1000000000));
    }

    /**
     * The "secToMs" utility function
     * pass it sec time make sure it is correctly translated to ms
     */
    @Test
    public void secToMs() {
        Assert.assertEquals(1000, TimeUtils.secToMs(1));
    }

    /**
     * The "secToNs" utility function
     * pass it sec time make sure it is correctly translated to ns
     */
    @Test
    public void secToNs() {
        Assert.assertEquals(1000000000, TimeUtils.secToNs(1));
    }
}
