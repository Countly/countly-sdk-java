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
    public void generatedDeviceId() throws InterruptedException {
        Countly.instance().init(TestUtils.getBaseConfigWithoutDeviceId());
        synchronized (Countly.instance()) { // we should wait for device ID to be acquired
            Assert.assertTrue(Countly.instance().getDeviceId().startsWith("CLY_"));
        }
    }

    /**
     * SDK should not generate device ID if it is provided,
     * and it should not start with "CLY_"
     */
    @Test
    public void customDeviceId() throws InterruptedException {
        Countly.instance().init(TestUtils.getBaseConfig());
        synchronized (Countly.instance()) { // we should wait for device ID to be acquired
            Assert.assertFalse(Countly.instance().getDeviceId().startsWith("CLY_"));
        }
    }
}
