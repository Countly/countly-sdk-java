package ly.count.sdk.java.internal;

import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

@RunWith(JUnit4.class)
public class SdkStorageTests {

    static final String JSON_STORAGE = "countly_store.json";

    /**
     * Clean up before each test
     */
    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
    }

    /**
     * Stop SDKStorage after each test
     */
    @After
    public void stop() {
        //doing all of this just to stop the Task
        //todo eliminate this
        InternalConfig config = new InternalConfig(TestUtils.getBaseConfig());
        SDKStorage storageProvider = (new SDKStorage()).init(config, Mockito.mock(Log.class));
        storageProvider.stop(config, true);
    }

    /**
     * "getDeviceID" with fresh start
     * SDKStorage is init freshly and no device ID is set
     * getDeviceID should return null
     */
    @Test
    public void getDeviceID() {
        SDKStorage storageProvider = (new SDKStorage()).init(new InternalConfig(TestUtils.getBaseConfig()), Mockito.mock(Log.class));
        Assert.assertNull(storageProvider.getDeviceID());
    }

    /**
     * "getDeviceID" with existing countly store file
     * SDKStorage is init with already existing device id stored
     * getDeviceID should return expected id
     */
    @Test
    public void getDeviceID_existed() {
        TestUtils.writeToFile(JSON_STORAGE, "{\"did\":\"" + TestUtils.DEVICE_ID + "\"}");
        SDKStorage storageProvider = (new SDKStorage()).init(new InternalConfig(TestUtils.getBaseConfig()), Mockito.mock(Log.class));
        Assert.assertEquals(TestUtils.DEVICE_ID, storageProvider.getDeviceID());
    }

    /**
     * "getDeviceIdType" with fresh start
     * SDKStorage is init freshly and no device ID type is set
     * getDeviceIdType should return null
     */
    @Test
    public void getDeviceIdType() {
        SDKStorage storageProvider = (new SDKStorage()).init(new InternalConfig(TestUtils.getBaseConfig()), Mockito.mock(Log.class));
        Assert.assertNull(storageProvider.getDeviceIdType());
    }

    /**
     * "getDeviceIdType" with existing countly store file
     * SDKStorage is init with already existing device id type stored
     * getDeviceIdType should return expected device id type
     */
    @Test
    public void getDeviceIdType_existed() {
        TestUtils.writeToFile(JSON_STORAGE, "{\"did_t\":\"DEVELOPER_SUPPLIED\"}");
        SDKStorage storageProvider = (new SDKStorage()).init(new InternalConfig(TestUtils.getBaseConfig()), Mockito.mock(Log.class));
        Assert.assertEquals(DeviceIdType.DEVELOPER_SUPPLIED, DeviceIdType.valueOf(storageProvider.getDeviceIdType()));
    }

    /**
     * "getDeviceIdType" with existing countly store file
     * SDKStorage is init with already existing garbage device id type stored
     * getDeviceIdType should return value, but it is not a valid device id type
     */
    @Test
    public void getDeviceIdType_garbage() {
        TestUtils.writeToFile(JSON_STORAGE, "{\"did_t\":\"DEVELOPER_CREATED\"}");
        SDKStorage storageProvider = (new SDKStorage()).init(new InternalConfig(TestUtils.getBaseConfig()), Mockito.mock(Log.class));
        Stream<String> deviceIdTypeStream = Arrays.stream(DeviceIdType.values()).map(Enum::toString);
        Assert.assertTrue(deviceIdTypeStream.noneMatch(deviceIdType -> deviceIdType.equals(storageProvider.getDeviceIdType())));
    }

    /**
     * "setDeviceID" with fresh start
     * SDKStorage is init freshly and no device ID is set
     * setDeviceID should set the device id
     */
    @Test
    public void setDeviceID() {
        SDKStorage storageProvider = (new SDKStorage()).init(new InternalConfig(TestUtils.getBaseConfig()), Mockito.mock(Log.class));
        Assert.assertNull(storageProvider.getDeviceID());
        storageProvider.setDeviceID(TestUtils.DEVICE_ID);
        Assert.assertEquals(TestUtils.DEVICE_ID, storageProvider.getDeviceID());
        Assert.assertEquals(TestUtils.DEVICE_ID, TestUtils.readJsonFile(JSON_STORAGE).get("did"));
    }

    /**
     * "setDeviceIdType" with fresh start
     * SDKStorage is init freshly and no device ID type is set
     * setDeviceIdType should set the device id type
     */
    @Test
    public void setDeviceIdType() {
        SDKStorage storageProvider = (new SDKStorage()).init(new InternalConfig(TestUtils.getBaseConfig()), Mockito.mock(Log.class));
        Assert.assertNull(storageProvider.getDeviceIdType());
        storageProvider.setDeviceIdType(DeviceIdType.DEVELOPER_SUPPLIED.toString());
        Assert.assertEquals("DEVELOPER_SUPPLIED", storageProvider.getDeviceIdType());
        Assert.assertEquals("DEVELOPER_SUPPLIED", TestUtils.readJsonFile(JSON_STORAGE).get("did_t"));
    }
}
