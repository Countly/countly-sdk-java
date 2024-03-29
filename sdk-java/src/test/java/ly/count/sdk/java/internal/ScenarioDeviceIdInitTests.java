package ly.count.sdk.java.internal;

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

    //first init where:

    /**
     * Device id generation scenario
     * Custom Device ID is not provided,
     * SDK should generate UUID device ID
     */
    @Test
    public void firstInit_ProvidedNothing() {
        Countly.instance().init(TestUtils.getBaseConfig(null));

        assertIsSDKGeneratedID(Countly.instance().deviceId().getID());
        Assert.assertEquals(DeviceIdType.SDK_GENERATED, Countly.instance().deviceId().getType());
    }

    /**
     * Device id generation scenario
     * Custom Device ID is provided,
     * SDK should not generate UUID device ID
     */
    @Test
    public void firstInit_ProvidedCustomId() {
        Countly.instance().init(TestUtils.getBaseConfig(TestUtils.DEVICE_ID));

        Assert.assertEquals(TestUtils.DEVICE_ID, Countly.instance().deviceId().getID());
        Assert.assertEquals(DeviceIdType.DEVELOPER_SUPPLIED, Countly.instance().deviceId().getType());
    }

    //followup init where:

    /**
     * Device id generation scenario
     * Custom Device ID is not provided in both first and second init
     * SDK should generate UUID device ID in first init and read and use it in second init
     */
    @Test
    public void followupInit_FirstNothingProvidedNothing() {
        Countly.instance().init(TestUtils.getBaseConfig(null));

        String initialDId = Countly.instance().deviceId().getID();
        assertIsSDKGeneratedID(initialDId);
        Assert.assertEquals(DeviceIdType.SDK_GENERATED, Countly.instance().deviceId().getType());

        //setup followup state
        Countly.instance().stop();
        Countly.instance().init(TestUtils.getBaseConfig(null));

        Assert.assertEquals(initialDId, Countly.instance().deviceId().getID());
        Assert.assertEquals(DeviceIdType.SDK_GENERATED, Countly.instance().deviceId().getType());
    }

    /**
     * Device id generation scenario
     * Custom Device ID is not provided in first init and provided in second init
     * SDK should generate UUID device ID in first init and read and use it in second init
     */
    @Test
    public void followupInit_FirstNothingProvidedCustomId() {
        Countly.instance().init(TestUtils.getBaseConfig(null));

        String initialDId = Countly.instance().deviceId().getID();

        assertIsSDKGeneratedID(initialDId);
        Assert.assertEquals(DeviceIdType.SDK_GENERATED, Countly.instance().deviceId().getType());

        //setup followup state
        Countly.instance().stop();
        Countly.instance().init(TestUtils.getBaseConfig(alternativeDeviceID));

        Assert.assertEquals(initialDId, Countly.instance().deviceId().getID());
        Assert.assertEquals(DeviceIdType.SDK_GENERATED, Countly.instance().deviceId().getType());
    }

    /**
     * Device id generation scenario
     * Custom Device ID is provided in both first and second init
     * SDK must not generate UUID device ID in first init, must store custom device
     * ID and restore and use it in second init
     */
    @Test
    public void followupInit_FirstCustomProvidedCustomId() {
        Countly.instance().init(TestUtils.getBaseConfig(TestUtils.DEVICE_ID));

        Assert.assertEquals(TestUtils.DEVICE_ID, Countly.instance().deviceId().getID());
        Assert.assertEquals(DeviceIdType.DEVELOPER_SUPPLIED, Countly.instance().deviceId().getType());

        //setup followup state
        Countly.instance().stop();
        Countly.instance().init(TestUtils.getBaseConfig(alternativeDeviceID));

        Assert.assertEquals(TestUtils.DEVICE_ID, Countly.instance().deviceId().getID());
        Assert.assertEquals(DeviceIdType.DEVELOPER_SUPPLIED, Countly.instance().deviceId().getType());
    }

    void assertIsSDKGeneratedID(String providedID) {
        // a SDK generated ID would look like: "CLY_e7905137-1c10-4b1a-a910-86ce67cffbf3"
        Assert.assertNotNull(providedID);
        Assert.assertTrue(providedID.startsWith("CLY_"));
        Assert.assertTrue(providedID.length() > 4);
    }
}
