package ly.count.sdk.java.internal;

import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.mockito.Mockito.mock;

@RunWith(JUnit4.class)
public class ScenarioDeviceIdInitTests {

    final static String alternativeDeviceID = TestUtils.DEVICE_ID + "1";
    Log L = mock(Log.class);

    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
    }

    @After
    public void stop() {
        Countly.instance().halt();
    }

    //first init

    /**
     * First init where:
     * Device ID is not provided,
     * Temporary ID mode is not provided
     *
     * SDK should generate OPEN_UDID device ID
     */
    @Test
    public void firstInitProvidedNothing() {
        Config cc = new Config("https://xxx.yyy.ly", "aaa", TestUtils.getTestSDirectory());
        Countly.instance().init(cc);

        String deviceId = null;
        while (deviceId == null) {
            try {
                deviceId = Countly.instance().getDeviceId();
            } catch (Exception ignored) {
                //do nothing
            }
        }
        Assert.assertNotNull(Countly.instance().getDeviceId());
        Assert.assertEquals(Config.DeviceIdStrategy.UUID.getIndex(), SDKCore.instance.config.getDeviceIdStrategy());
    }

    /**
     * First init where:
     * Custom Device ID is provided,
     * Temporary ID mode is not provided
     *
     * SDK should use provided device ID
     */
    @Test
    public void firstInitProvidedCustomId() {
        Config cc = new Config("https://xxx.yyy.ly", "aaa", TestUtils.getTestSDirectory());
        cc.setCustomDeviceId("test-device-id");
        Countly.instance().init(cc);

        Assert.assertEquals("test-device-id", Countly.instance().getDeviceId());
        Assert.assertEquals(Config.DeviceIdStrategy.CUSTOM_ID.getIndex(), SDKCore.instance.config.getDeviceIdStrategy());
    }

    /**
     * Followup init where previously:
     * Nothing was provided - OPEN_UDID Devices ID was generated
     *
     * now:
     * Device ID is not provided,
     * Temporary ID mode is not provided
     */
    @Test
    public void followupInitPrevNothingProvidedNothing() {
        Config cc = new Config("https://xxx.yyy.ly", "aaa", TestUtils.getTestSDirectory());
        cc.setLogListener((l, e) -> System.out.println(l));
        Countly.instance().init(cc);

        String initialDId = waitForNoNullDeviceID(Countly.instance());
        Assert.assertNotNull(initialDId);
        Assert.assertEquals(Config.DeviceIdStrategy.UUID.getIndex(), SDKCore.instance.config.getDeviceIdStrategy());

        //setup followup state
        Countly.instance().stop();
        Config cc1 = new Config("https://xxx.yyy.ly", "aaa", TestUtils.getTestSDirectory());
        cc1.setLogListener((l, e) -> System.out.println("2: " + l));
        Countly.instance().init(cc1);

        Assert.assertEquals(initialDId, Countly.instance().getDeviceId());
        Assert.assertEquals(Config.DeviceIdStrategy.UUID.getIndex(), SDKCore.instance.config.getDeviceIdStrategy());
    }

    /**
     * Followup init where previously:
     * Nothing was provided - OPEN_UDID Devices ID was generated
     *
     * now:
     * Device ID is provided,
     * Temporary ID mode is not provided
     */
    @Test
    public void followupInitPrevNothingProvidedCustomId() {
        Countly.instance().init(TestUtils.getBaseConfig(null));

        String initialDId = waitForNoNullDeviceID(Countly.instance());

        Assert.assertNotNull(initialDId);
        Assert.assertEquals(Config.DeviceIdStrategy.UUID.getIndex(), SDKCore.instance.config.getDeviceIdStrategy());

        //setup followup state
        Countly.instance().stop();
        Countly.instance().init(TestUtils.getBaseConfig(alternativeDeviceID));

        Assert.assertEquals(initialDId, Countly.instance().getDeviceId());
        Assert.assertEquals(Config.DeviceIdStrategy.UUID.getIndex(), SDKCore.instance.config.getDeviceIdStrategy());
    }

    String waitForNoNullDeviceID(Countly instance) {
        String initialDId = null;
        while (initialDId == null) {
            try {
                initialDId = instance.getDeviceId();
            } catch (Exception ignored) {
                //do nothing
            }
        }

        return initialDId;
    }

    /**
     * Followup init where previously:
     * Custom devices ID was set
     *
     * now:
     * Device ID is provided,
     * Temporary ID mode is not provided
     */
    @Test
    public void followupInitPrevCustomProvidedCustomId() {
        Countly.instance().init(TestUtils.getBaseConfig(TestUtils.DEVICE_ID));

        Assert.assertEquals(TestUtils.DEVICE_ID, Countly.instance().getDeviceId());
        Assert.assertEquals(Config.DeviceIdStrategy.CUSTOM_ID.getIndex(), SDKCore.instance.config.getDeviceIdStrategy());

        //setup followup state
        Countly.instance().stop();
        Countly.instance().init(TestUtils.getBaseConfig(alternativeDeviceID));

        Assert.assertEquals(TestUtils.DEVICE_ID, Countly.instance().getDeviceId());
        Assert.assertEquals(Config.DeviceIdStrategy.CUSTOM_ID.getIndex(), SDKCore.instance.config.getDeviceIdStrategy());
    }
}
