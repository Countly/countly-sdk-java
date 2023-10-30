package ly.count.sdk.java.internal;

import java.util.Map;
import ly.count.sdk.java.Countly;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

@RunWith(JUnit4.class)
public class ModuleRemoteConfigTests {

    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
    }

    @After
    public void stop() {
        Countly.instance().halt();
    }

    /**
     * "clearAll"
     * Returning mock json response. Validating that all values are cleared. Validating created request params.
     * The response should be parsed correctly and match with mock json data and all values should be cleared.
     */
    @Test
    public void clearAll() {
        Countly.instance().init(TestUtils.getConfigRemoteConfigs());

        JSONObject remoteConfigMockData = new JSONObject();
        remoteConfigMockData.put(TestUtils.keysValues[0], "testValue");
        remoteConfigMockData.put(TestUtils.keysValues[1], 90);
        remoteConfigMockData.put(TestUtils.keysValues[2], 67.9);
        remoteConfigMockData.put(TestUtils.keysValues[3], true);

        clearAll_base(remoteConfigMockData, 0);
    }

    /**
     * "clearAll" with values in countly store
     * Returning empty json response. Validating that all values are cleared. Validating created request params.
     * The response should not affect the current rc values and all values should be cleared.
     */
    @Test
    public void clearAll_emptyResponse() {
        loadCountlyStoreWithRCValues("{\"rc\":{\"testKey\":{\"v\":\"testValue\",\"c\":0}}}");
        //enabled rc automatic triggers to load from countly store
        Countly.instance().init(TestUtils.getConfigRemoteConfigs().enableRemoteConfigAutomaticTriggers());
        clearAll_base(new JSONObject(), 1);
    }

    private void clearAll_base(JSONObject mockData, int countlyStoreRCSize) {
        Assert.assertEquals(countlyStoreRCSize, Countly.instance().remoteConfig().getValues().size());
        SDKCore.instance.config.immediateRequestGenerator = () -> remoteConfigRequestMaker(mockData);
        //load values from request maker which provides mockData
        Countly.instance().remoteConfig().downloadAllKeys((rResult, error, fullValueUpdate, downloadedValues) -> {
            Assert.assertEquals(rResult, RequestResult.Success);
            Assert.assertNull(error);
            Assert.assertTrue(fullValueUpdate);
            Assert.assertEquals(mockData.length(), downloadedValues.size());
        });

        //validate that values are loaded correctly and same with mockData
        validateRemoteConfigValues(Countly.instance().remoteConfig().getValues(), mockData, true);

        Countly.instance().remoteConfig().clearAll();
        Assert.assertEquals(0, Countly.instance().remoteConfig().getValues().size());
    }

    /**
     * "getValue" with values in countly store
     * Validating that the value is loaded from countly store correctly and exists
     * The returned RCData should match with the expected key-value pair 'testKey'-'testValue".
     */
    @Test
    public void getValue() {
        loadCountlyStoreWithRCValues("{\"rc\":{\"testKey\":{\"v\":\"testValue\",\"c\":0}}}");
        //enabled rc automatic triggers to load from countly store
        Countly.instance().init(TestUtils.getConfigRemoteConfigs().enableRemoteConfigAutomaticTriggers());
        Assert.assertEquals(1, Countly.instance().remoteConfig().getValues().size());

        validateRCData(Countly.instance().remoteConfig().getValue("testKey"), "testValue", false);
    }

    /**
     * "getValue" with none existing key
     * Nothing to restore from countly store. Validating that store is empty
     * The returned RCData should be null.
     */
    @Test
    public void getValue_noneExisting() {
        Countly.instance().init(TestUtils.getConfigRemoteConfigs().enableRemoteConfigAutomaticTriggers());
        Assert.assertEquals(0, Countly.instance().remoteConfig().getValues().size());

        validateRCData(Countly.instance().remoteConfig().getValue(TestUtils.keysValues[0]), null, true);
    }

    /**
     * "getValue"
     * Nothing to restore from countly store. Validating that store is empty
     * The returned RCData should be null and expected log should be logged.
     */
    @Test
    public void getValue_nullKey() {
        Countly.instance().init(TestUtils.getConfigRemoteConfigs().enableRemoteConfigAutomaticTriggers());
        Assert.assertEquals(0, Countly.instance().remoteConfig().getValues().size());
        ModuleBase rcModule = SDKCore.instance.module(ModuleRemoteConfig.class);

        rcModule.L = Mockito.spy(rcModule.L);

        validateRCData(Countly.instance().remoteConfig().getValue(null), null, true);
        Mockito.verify(rcModule.L).i("[RemoteConfig] getValue, A valid key should be provided to get its value.");
    }

    private void validateRemoteConfigValues(Map<String, RCData> rcValuesStore, JSONObject rcValuesServer, boolean isCurrentUsersData) {
        Assert.assertEquals(rcValuesServer.keySet().size(), rcValuesStore.size());
        rcValuesServer.keySet().forEach(key -> validateRCData(rcValuesStore.get(key), rcValuesServer.get(key), isCurrentUsersData));
    }

    private void validateRCData(RCData rcData, Object value, boolean isCurrentUsersData) {
        Assert.assertEquals(rcData.value, value);
        Assert.assertEquals(rcData.isCurrentUsersData, isCurrentUsersData);
    }

    private void loadCountlyStoreWithRCValues(String values) {
        TestUtils.writeToFile(SDKStorage.JSON_FILE_NAME, values);
    }

    /**
     * Creates a request maker that returns the provided mock data, checks required params and metrics
     *
     * @param remoteConfigMockData mock data to return
     * @return request maker
     */
    private ImmediateRequestI remoteConfigRequestMaker(JSONObject remoteConfigMockData) {

        return (requestData, customEndpoint, cp, requestShouldBeDelayed, networkingIsEnabled, callback, log) -> {
            Map<String, String> params = TestUtils.parseQueryParams(requestData);
            Assert.assertEquals("rc", params.get("method"));

            TestUtils.validateMetrics(Utils.urldecode(params.get("metrics")));
            TestUtils.validateRequestMakerRequiredParams("/o/sdk", customEndpoint, requestShouldBeDelayed, networkingIsEnabled);
            TestUtils.validateRequiredParams(params);
            callback.callback(remoteConfigMockData);
        };
    }
}
