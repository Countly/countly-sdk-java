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
     * Device ID acquisition process
     * Initializing the SDK with no custom ID, that should trigger ID generation
     * The acquired device ID should start with "CLY_"
     */
    @Test
    public void generatedDeviceId() {
        Countly.instance().init(TestUtils.getBaseConfig().setCustomDeviceId(null));
        String deviceId = null;
        //todo remove this "while loop" mess after refactoring the SDK
        while (deviceId == null) { //wait for device ID to be generated
            try {
                deviceId = Countly.instance().getDeviceId();
            } catch (Exception ignored) {
                //do nothing
            }
        }
        Assert.assertTrue(deviceId.startsWith("CLY_"));
    }

    /**
     * Device ID acquisition process
     * Initializing the SDK with a custom ID, that should not trigger ID generation
     * The acquired device ID should not contain any "CLY_"
     */
    @Test
    public void customDeviceId() {
        Countly.instance().init(TestUtils.getBaseConfig());
        Assert.assertFalse(Countly.instance().getDeviceId().contains("CLY_"));
    }
}
