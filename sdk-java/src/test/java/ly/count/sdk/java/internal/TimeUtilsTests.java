package ly.count.sdk.java.internal;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class TimeUtilsTests {

    /**
     * "getTime" method test.
     * Validate whether the "getTime" method returns a valid time object.
     */
    @Test
    public void getTime() {

        TimeUtils.Time time = TimeUtils.getTime();

        Assert.assertTrue(time.timestamp > 0);
        Assert.assertTrue(time.hour >= 0 && time.hour <= 23);
        Assert.assertTrue(time.dow >= 0 && time.dow <= 6);
        Assert.assertTrue(time.tz >= -720 && time.tz <= 840);
    }

    @Test
    public void uniqueTimestamp() {
        long last = TimeUtils.uniqueTimestamp();
        Assert.assertTrue(last > 0);
    }

    @Test
    public void uniformTimestamp() {
        long last = 0;
        for (int i = 0; i < 100; i++) {
            long now = TimeUtils.uniformTimestamp();
            Assert.assertTrue(now > last);
            last = now;
        }
    }

    @Test
    public void nsToMs() {
        Assert.assertEquals(1, TimeUtils.nsToMs(1000000));
    }

    @Test
    public void nsToSec() {
        Assert.assertEquals(1, TimeUtils.nsToSec(1000000000));
    }

    @Test
    public void secToMs() {
        Assert.assertEquals(1000, TimeUtils.secToMs(1));
    }

    @Test
    public void secToNs() {
        Assert.assertEquals(1000000000, TimeUtils.secToNs(1));
    }
}
