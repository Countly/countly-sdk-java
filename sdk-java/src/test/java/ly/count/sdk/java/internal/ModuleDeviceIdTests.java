package ly.count.sdk.java.internal;

import java.util.concurrent.atomic.AtomicInteger;
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
        SDKCore.testDummyModule = null;
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
        Countly.instance().init(TestUtils.getBaseConfig(null));
        Assert.assertTrue(Countly.instance().deviceId().getID().startsWith("CLY_"));
    }

    /**
     * Device ID acquisition process
     * Initializing the SDK with a custom ID, that should not trigger ID generation
     * The acquired device ID should not contain any "CLY_"
     */
    @Test
    public void customDeviceId() {
        Countly.instance().init(TestUtils.getBaseConfig());
        Assert.assertFalse(Countly.instance().deviceId().getID().contains("CLY_"));
    }

    /**
     * "changeWithMerge" method with possible entries
     */
    @Test
    public void changeWithMerge() {
        AtomicInteger callCount = initDummyModuleForDeviceIdChangedCallback(false);
        Countly.instance().init(TestUtils.getBaseConfig(null)); // to create sdk generated device id
        Assert.assertTrue(Countly.instance().deviceId().getID().startsWith("CLY_"));
        Assert.assertEquals(DeviceIdType.SDK_GENERATED, Countly.instance().deviceId().getType());
        Assert.assertEquals(0, callCount.get());

        Countly.instance().deviceId().changeWithMerge(TestUtils.DEVICE_ID);
        Assert.assertEquals(1, callCount.get());
    }

    @Test
    public void changeWithMerge_nullDeviceId() {
        AtomicInteger callCount = initDummyModuleForDeviceIdChangedCallback(false);
        Countly.instance().init(TestUtils.getBaseConfig(null)); // to create sdk generated device id
        Assert.assertTrue(Countly.instance().deviceId().getID().startsWith("CLY_"));
        Assert.assertEquals(DeviceIdType.SDK_GENERATED, Countly.instance().deviceId().getType());
        Assert.assertEquals(0, callCount.get());

        Countly.instance().deviceId().changeWithMerge(null);
        Assert.assertEquals(0, callCount.get());
    }

    @Test
    public void changeWithMerge_emptyDeviceId() {
        AtomicInteger callCount = initDummyModuleForDeviceIdChangedCallback(false);
        Countly.instance().init(TestUtils.getBaseConfig(null)); // to create sdk generated device id
        Assert.assertTrue(Countly.instance().deviceId().getID().startsWith("CLY_"));
        Assert.assertEquals(DeviceIdType.SDK_GENERATED, Countly.instance().deviceId().getType());
        Assert.assertEquals(0, callCount.get());

        Countly.instance().deviceId().changeWithMerge("");
        Assert.assertEquals(0, callCount.get());
    }

    @Test
    public void changeWithMerge_sameDeviceId() {
        AtomicInteger callCount = initDummyModuleForDeviceIdChangedCallback(false);
        Countly.instance().init(TestUtils.getBaseConfig(null)); // to create sdk generated device id
        Assert.assertTrue(Countly.instance().deviceId().getID().startsWith("CLY_"));
        Assert.assertEquals(DeviceIdType.SDK_GENERATED, Countly.instance().deviceId().getType());
        Assert.assertEquals(0, callCount.get());

        Countly.instance().deviceId().changeWithMerge(TestUtils.DEVICE_ID);
        Assert.assertEquals(1, callCount.get());
    }

    @Test
    public void getID_getType() {
        Countly.instance().init(TestUtils.getBaseConfig(null));
        Assert.assertTrue(Countly.instance().deviceId().getID().startsWith("CLY_"));
        Assert.assertEquals(DeviceIdType.SDK_GENERATED, Countly.instance().deviceId().getType());
    }

    @Test
    public void getID_getType_customDeviceId() {
        Countly.instance().init(TestUtils.getBaseConfig());
        Assert.assertEquals(TestUtils.DEVICE_ID, Countly.instance().deviceId().getID());
        Assert.assertEquals(DeviceIdType.DEVELOPER_SUPPLIED, Countly.instance().deviceId().getType());
    }

    private AtomicInteger initDummyModuleForDeviceIdChangedCallback(boolean withoutMerge) {
        AtomicInteger callCount = new AtomicInteger(0);
        SDKCore.testDummyModule = new ModuleBase() {
            @Override
            protected void deviceIdChanged(String oldDeviceId, boolean withMerge) {
                super.deviceIdChanged(oldDeviceId, withMerge);
                callCount.incrementAndGet();
                Assert.assertEquals(!withoutMerge, withMerge);
                Assert.assertEquals(TestUtils.DEVICE_ID, internalConfig.getDeviceId().id);
                Assert.assertEquals(DeviceIdType.DEVELOPER_SUPPLIED.index, internalConfig.getDeviceIdStrategy());
            }
        };

        return callCount;
    }
}
