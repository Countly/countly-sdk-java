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
        // we should wait for device ID to be acquired
        Thread.yield();
        Assert.assertTrue(Countly.instance().getDeviceId().startsWith("CLY_"));
    }

    /**
     * SDK should not generate device ID if it is provided,
     * and it should not start with "CLY_"
     */
    @Test
    public void customDeviceId() {
        Countly.instance().init(TestUtils.getBaseConfig());
        // we should wait for device ID to be acquired
        Thread.yield();
        Assert.assertFalse(Countly.instance().getDeviceId().startsWith("CLY_"));
    }
}
