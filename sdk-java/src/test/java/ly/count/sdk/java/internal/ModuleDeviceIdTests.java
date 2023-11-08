package ly.count.sdk.java.internal;

import java.util.Map;
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
        TestUtils.AtomicString deviceID = new TestUtils.AtomicString(TestUtils.DEVICE_ID);
        AtomicInteger callCount = initDummyModuleForDeviceIdChangedCallback(deviceID, false, DeviceIdType.DEVELOPER_SUPPLIED);
        Countly.instance().init(TestUtils.getBaseConfig(null)); // to create sdk generated device id
        String oldDeviceId = Countly.instance().deviceId().getID();

        Assert.assertTrue(oldDeviceId.startsWith("CLY_"));
        Assert.assertEquals(DeviceIdType.SDK_GENERATED, Countly.instance().deviceId().getType());
        Assert.assertEquals(0, callCount.get());

        Countly.instance().deviceId().changeWithMerge(deviceID.value);
        Assert.assertEquals(1, callCount.get());

        deviceID.value += "1";
        Countly.instance().deviceId().changeWithMerge(deviceID.value);
        Assert.assertEquals(2, callCount.get());
        validateDeviceIdChangeRequest(new String[] { oldDeviceId, TestUtils.DEVICE_ID }, new String[] { TestUtils.DEVICE_ID, TestUtils.DEVICE_ID + "1" }, 2);
    }

    @Test
    public void changeWithoutMerge() {
        TestUtils.AtomicString deviceID = new TestUtils.AtomicString(TestUtils.keysValues[0]);
        AtomicInteger callCount = initDummyModuleForDeviceIdChangedCallback(deviceID, true, DeviceIdType.DEVELOPER_SUPPLIED);
        Countly.instance().init(TestUtils.getBaseConfig()); // to create sdk generated device id
        Assert.assertEquals(TestUtils.DEVICE_ID, Countly.instance().deviceId().getID());
        Assert.assertEquals(DeviceIdType.DEVELOPER_SUPPLIED, Countly.instance().deviceId().getType());
        Assert.assertEquals(0, callCount.get());

        Countly.instance().deviceId().changeWithoutMerge(deviceID.value);
        Assert.assertEquals(1, callCount.get());

        deviceID.value += "1";
        Countly.instance().deviceId().changeWithoutMerge(deviceID.value);
        Assert.assertEquals(2, callCount.get());
        validateDeviceIdChangeRequest(new String[] { TestUtils.DEVICE_ID, TestUtils.keysValues[0] }, new String[] { TestUtils.keysValues[0], TestUtils.keysValues[0] + "1" }, 2);
    }

    @Test
    public void changeWithMerge_nullDeviceId() {
        AtomicInteger callCount = initDummyModuleForDeviceIdChangedCallback(null, false, DeviceIdType.SDK_GENERATED);
        Countly.instance().init(TestUtils.getBaseConfig(null)); // to create sdk generated device id
        Assert.assertTrue(Countly.instance().deviceId().getID().startsWith("CLY_"));
        Assert.assertEquals(DeviceIdType.SDK_GENERATED, Countly.instance().deviceId().getType());
        Assert.assertEquals(0, callCount.get());

        Countly.instance().deviceId().changeWithMerge(null);
        Assert.assertEquals(0, callCount.get());

        validateDeviceIdChangeRequest(null, null, 0);
    }

    @Test
    public void changeWithMerge_emptyDeviceId() {
        AtomicInteger callCount = initDummyModuleForDeviceIdChangedCallback(new TestUtils.AtomicString(""), false, DeviceIdType.SDK_GENERATED);
        Countly.instance().init(TestUtils.getBaseConfig(null)); // to create sdk generated device id
        Assert.assertTrue(Countly.instance().deviceId().getID().startsWith("CLY_"));
        Assert.assertEquals(DeviceIdType.SDK_GENERATED, Countly.instance().deviceId().getType());
        Assert.assertEquals(0, callCount.get());

        Countly.instance().deviceId().changeWithMerge("");
        Assert.assertEquals(0, callCount.get());
        validateDeviceIdChangeRequest(null, null, 0);
    }

    @Test
    public void changeWithMerge_sameDeviceId() {
        AtomicInteger callCount = initDummyModuleForDeviceIdChangedCallback(new TestUtils.AtomicString(TestUtils.DEVICE_ID), false, DeviceIdType.DEVELOPER_SUPPLIED);
        Countly.instance().init(TestUtils.getBaseConfig(null)); // to create sdk generated device id
        String oldDeviceId = Countly.instance().deviceId().getID();
        Assert.assertTrue(oldDeviceId.startsWith("CLY_"));
        Assert.assertEquals(DeviceIdType.SDK_GENERATED, Countly.instance().deviceId().getType());
        Assert.assertEquals(0, callCount.get());

        Countly.instance().deviceId().changeWithMerge(TestUtils.DEVICE_ID);
        Assert.assertEquals(1, callCount.get());

        Countly.instance().deviceId().changeWithMerge(TestUtils.DEVICE_ID);
        Assert.assertEquals(1, callCount.get());

        validateDeviceIdChangeRequest(new String[] { oldDeviceId }, new String[] { TestUtils.DEVICE_ID }, 1);
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

    @Test
    public void logout() {
        TestUtils.AtomicString deviceID = new TestUtils.AtomicString(TestUtils.DEVICE_ID);
        AtomicInteger callCount = initDummyModuleForDeviceIdChangedCallback(deviceID, true, DeviceIdType.SDK_GENERATED);
        Countly.instance().init(TestUtils.getBaseConfig(null)); // to create sdk generated device id
        Assert.assertTrue(Countly.instance().deviceId().getID().startsWith("CLY_"));
        Assert.assertEquals(DeviceIdType.SDK_GENERATED, Countly.instance().deviceId().getType());
        Assert.assertEquals(0, callCount.get());

        Countly.instance().logout();
        Assert.assertEquals(1, callCount.get());
    }

    private void validateDeviceIdChangeRequest(String[] oldDeviceIds, String[] deviceIds, final int rqSize) {
        Map<String, String>[] requests = TestUtils.getCurrentRQ();
        Assert.assertEquals(rqSize, TestUtils.getCurrentRQ().length);
        if (rqSize < 1) {
            return;
        }

        for (int i = 0; i < rqSize; i++) {
            TestUtils.validateRequiredParams(requests[i], deviceIds[i]);
            Assert.assertEquals(oldDeviceIds[i], requests[i].get("old_device_id"));
        }
    }

    private AtomicInteger initDummyModuleForDeviceIdChangedCallback(TestUtils.AtomicString deviceId, boolean withoutMerge, DeviceIdType type) {
        AtomicInteger callCount = new AtomicInteger(0);
        SDKCore.testDummyModule = new ModuleBase() {
            @Override
            protected void deviceIdChanged(String oldDeviceId, boolean withMerge) {
                super.deviceIdChanged(oldDeviceId, withMerge);
                callCount.incrementAndGet();
                Assert.assertEquals(!withoutMerge, withMerge);
                if (type == DeviceIdType.SDK_GENERATED) {
                    Assert.assertTrue(internalConfig.getDeviceId().id.startsWith("CLY_"));
                } else {
                    Assert.assertEquals(deviceId.value, internalConfig.getDeviceId().id);
                }
                Assert.assertEquals(type.index, internalConfig.getDeviceId().strategy);
            }
        };

        return callCount;
    }
}
