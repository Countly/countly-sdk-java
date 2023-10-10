package ly.count.sdk.java.internal;

import ly.count.sdk.java.Countly;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ModuleDeviceIdTests {

    @After
    public void stop() {
        Countly.instance().halt();
    }

    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
    }

    /**
     * SDK should generate device ID if it is not provided,
     * and it should start with "CLY_"
     */
    @Test
    public void generatedDeviceId() {
        Countly.instance().init(TestUtils.getBaseConfigWithoutDeviceId());
        String deviceId = null;
        int loopCount = 0;
        while (deviceId == null && loopCount < 20) { //wait for device ID to be generated
            try {
                deviceId = Countly.instance().getDeviceId();
            } catch (Exception ignored) {
                loopCount++;
            }
        }
        if (loopCount >= 20) {
            Assert.fail("Device ID was not generated in 20 try");
        }
        Assert.assertTrue(deviceId.startsWith("CLY_"));
    }

    /**
     * SDK should not generate device ID if it is provided,
     * and it should not start with "CLY_"
     */
    @Test
    public void customDeviceId() {
        Countly.instance().init(TestUtils.getBaseConfig());
        Assert.assertFalse(Countly.instance().getDeviceId().startsWith("CLY_"));
    }
}
