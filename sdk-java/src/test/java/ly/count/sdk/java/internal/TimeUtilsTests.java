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
}
