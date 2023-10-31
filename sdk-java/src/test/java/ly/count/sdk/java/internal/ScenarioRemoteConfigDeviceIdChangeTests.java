package ly.count.sdk.java.internal;

import ly.count.sdk.java.Countly;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ScenarioRemoteConfigDeviceIdChangeTests {
    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
    }

    @After
    public void stop() {
        Countly.instance().halt();
    }

    /**
     * Change device id with merge scenario test
     * Returning a mock server response, loading store with mock data, validating after change,
     * After device id with merge change, rc values should be cleared and re-download again
     */
    @Test
    public void changeDeviceIdWithMerge() {
        //data preparation
        ModuleRemoteConfigTests.loadCountlyStoreWithRCValues("{\"rc\":{\"testKey\":{\"v\":\"testValue\",\"c\":0}}}");
        JSONObject remoteConfigMockData = new JSONObject();
        remoteConfigMockData.put(TestUtils.keysValues[1], false);
        remoteConfigMockData.put(TestUtils.keysValues[2], 671);
        remoteConfigMockData.put(TestUtils.keysValues[3], "sad");

        //initialization
        Countly.instance().init(TestUtils.getConfigRemoteConfigs().enableRemoteConfigAutomaticTriggers().enableRemoteConfigValueCaching());
        SDKCore.instance.config.immediateRequestGenerator = () -> ModuleRemoteConfigTests.remoteConfigRequestMaker(remoteConfigMockData, null, null);
        Assert.assertEquals(1, Countly.instance().remoteConfig().getValues().size());

        //change device id
        Countly.instance().changeDeviceIdWithMerge(TestUtils.keysValues[0]);

        Countly.instance().remoteConfig().getValues().forEach((key, value) -> Assert.assertTrue(value.isCurrentUsersData));
        Assert.assertEquals(3, Countly.instance().remoteConfig().getValues().size());
    }

    /**
     * Change device id without merge scenario test
     * Returning a mock server response, loading store with mock data, validating after change,
     * After device id without merge change, rc values should be cleared and re-download again
     */
    @Test
    public void changeDeviceIdWithoutMerge() {
        //data preparation
        ModuleRemoteConfigTests.loadCountlyStoreWithRCValues("{\"rc\":{\"testKey\":{\"v\":\"testValue\",\"c\":0}}}");
        JSONObject remoteConfigMockData = new JSONObject();
        remoteConfigMockData.put(TestUtils.keysValues[1], 90);
        remoteConfigMockData.put(TestUtils.keysValues[2], 67.9);
        remoteConfigMockData.put(TestUtils.keysValues[3], true);

        //initialization
        Countly.instance().init(TestUtils.getConfigRemoteConfigs().enableRemoteConfigAutomaticTriggers().enableRemoteConfigValueCaching());
        SDKCore.instance.config.immediateRequestGenerator = () -> ModuleRemoteConfigTests.remoteConfigRequestMaker(remoteConfigMockData, null, null);
        Assert.assertEquals(1, Countly.instance().remoteConfig().getValues().size());

        //change device id
        Countly.instance().changeDeviceIdWithoutMerge(TestUtils.keysValues[0]);

        Countly.instance().remoteConfig().getValues().forEach((key, value) -> Assert.assertTrue(value.isCurrentUsersData));
        Assert.assertEquals(3, Countly.instance().remoteConfig().getValues().size());
    }
}
