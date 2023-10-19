package ly.count.sdk.java.internal;

import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(JUnit4.class)
public class SdkStorageTests {

    SDKStorage storageProvider;

    static final String JSON_STORAGE = "countly_store.json";

    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
    }

    private void init() {
        storageProvider = new SDKStorage();
        storageProvider.init(new InternalConfig(TestUtils.getBaseConfig()), Mockito.mock(Log.class));
    }

    @After
    public void stop() {
        storageProvider.stop(new InternalConfig(TestUtils.getBaseConfig()), true);
    }

    /**
     * "getDeviceID" with fresh start
     * SDKStorage is init freshly and no device ID is set
     * getDeviceID should return null
     */
    @Test
    public void getDeviceID() {
        init();
        assertNull(storageProvider.getDeviceID());
    }

    /**
     * "getDeviceID" with existing countly store file
     * SDKStorage is init with already existing device id stored
     * getDeviceID should return expected id
     */
    @Test
    public void getDeviceID_existed() {
        TestUtils.writeToFile(JSON_STORAGE, "{\"did\":\"test\"}");
        init();
        assertEquals("test", storageProvider.getDeviceID());
    }

    /**
     * "getDeviceIdType" with fresh start
     * SDKStorage is init freshly and no device ID type is set
     * getDeviceIdType should return null
     */
    @Test
    public void getDeviceIdType() {
        init();
        assertNull(storageProvider.getDeviceIdType());
    }

    /**
     * "getDeviceIdType" with existing countly store file
     * SDKStorage is init with already existing device id type stored
     * getDeviceIdType should return expected device id type
     */
    @Test
    public void getDeviceIdType_existed() {
        TestUtils.writeToFile(JSON_STORAGE, "{\"did_t\":\"DEVELOPER_SUPPLIED\"}");
        init();
        assertEquals(DeviceIdType.DEVELOPER_SUPPLIED, DeviceIdType.valueOf(storageProvider.getDeviceIdType()));
    }

    /**
     * "getDeviceIdType" with existing countly store file
     * SDKStorage is init with already existing garbage device id type stored
     * getDeviceIdType should return value, but it is not a valid device id type
     */
    @Test
    public void getDeviceIdType_garbage() {
        TestUtils.writeToFile(JSON_STORAGE, "{\"did_t\":\"DEVELOPER_CREATED\"}");
        init();
        Stream<String> deviceIdTypeStream = Arrays.stream(DeviceIdType.values()).map(Enum::toString);
        assertTrue(deviceIdTypeStream.noneMatch(deviceIdType -> deviceIdType.equals(storageProvider.getDeviceIdType())));
    }

    /**
     * "setDeviceID" with fresh start
     * SDKStorage is init freshly and no device ID is set
     * setDeviceID should set the device id
     */
    @Test
    public void setDeviceID() {
        init();
        assertNull(storageProvider.getDeviceID());
        storageProvider.setDeviceID("test");
        assertEquals("test", storageProvider.getDeviceID());
        assertEquals("test", TestUtils.readJsonFile(JSON_STORAGE).get("did"));
    }

    /**
     * "setDeviceIdType" with fresh start
     * SDKStorage is init freshly and no device ID type is set
     * setDeviceIdType should set the device id type
     */
    @Test
    public void setDeviceIdType() {
        init();
        assertNull(storageProvider.getDeviceIdType());
        storageProvider.setDeviceIdType(DeviceIdType.DEVELOPER_SUPPLIED.toString());
        assertEquals("DEVELOPER_SUPPLIED", storageProvider.getDeviceIdType());
        assertEquals("DEVELOPER_SUPPLIED", TestUtils.readJsonFile(JSON_STORAGE).get("did_t"));
    }
}
