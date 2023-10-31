package ly.count.sdk.java.internal;

import java.util.Arrays;
import java.util.Map;
import ly.count.sdk.java.Countly;
import org.json.JSONArray;
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
        Countly.instance().init(TestUtils.getConfigRemoteConfigs().enableRemoteConfigValueCaching());

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
        Countly.instance().init(TestUtils.getConfigRemoteConfigs().enableRemoteConfigAutomaticTriggers().enableRemoteConfigValueCaching());
        clearAll_base(new JSONObject(), 1);
    }

    private void clearAll_base(JSONObject mockData, int countlyStoreRCSize) {
        Assert.assertEquals(countlyStoreRCSize, Countly.instance().remoteConfig().getValues().size());
        SDKCore.instance.config.immediateRequestGenerator = () -> remoteConfigRequestMaker(mockData, null, null);
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
        Countly.instance().init(TestUtils.getConfigRemoteConfigs().enableRemoteConfigAutomaticTriggers().enableRemoteConfigValueCaching());
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

    /**
     * "registerDownloadCallback"
     * Validating that the null callback is not registered
     * Download callbacks size should not be change.
     */
    @Test
    public void registerDownloadCallback_null() {
        Countly.instance().init(TestUtils.getConfigRemoteConfigs());
        ModuleRemoteConfig rcModule = SDKCore.instance.module(ModuleRemoteConfig.class);

        Assert.assertEquals(0, rcModule.downloadCallbacks.size());
        Countly.instance().remoteConfig().registerDownloadCallback(null);
        Assert.assertEquals(0, rcModule.downloadCallbacks.size());
    }

    /**
     * "registerDownloadCallback"
     * Validating that the callback is registered
     * Download callbacks size should be 1 at the end.
     */
    @Test
    public void registerDownloadCallback() {
        Countly.instance().init(TestUtils.getConfigRemoteConfigs());
        ModuleRemoteConfig rcModule = SDKCore.instance.module(ModuleRemoteConfig.class);

        Assert.assertEquals(0, rcModule.downloadCallbacks.size());
        Countly.instance().remoteConfig().registerDownloadCallback((rResult, error, fullValueUpdate, downloadedValues) -> {
        });
        Assert.assertEquals(1, rcModule.downloadCallbacks.size());
    }

    /**
     * "removeDownloadCallback"
     * Validating that the download callbacks size
     * Download callbacks size should be 0 at the end and not change.
     */
    @Test
    public void removeDownloadCallback_null() {
        Countly.instance().init(TestUtils.getConfigRemoteConfigs());
        ModuleRemoteConfig rcModule = SDKCore.instance.module(ModuleRemoteConfig.class);

        Assert.assertEquals(0, rcModule.downloadCallbacks.size());
        Countly.instance().remoteConfig().removeDownloadCallback(null);
        Assert.assertEquals(0, rcModule.downloadCallbacks.size());
    }

    /**
     * "removeDownloadCallback" with callback is registered at init
     * Validating that the download callbacks size changes
     * Download callbacks size should be 0 at the end.
     */
    @Test
    public void removeDownloadCallback() {
        RCDownloadCallback callback = (rResult, error, fullValueUpdate, downloadedValues) -> {
            Assert.assertEquals(RequestResult.Success, rResult);
        };
        Countly.instance().init(TestUtils.getConfigRemoteConfigs().remoteConfigRegisterGlobalCallback(callback));
        ModuleRemoteConfig rcModule = SDKCore.instance.module(ModuleRemoteConfig.class);

        Assert.assertEquals(1, rcModule.downloadCallbacks.size());
        Countly.instance().remoteConfig().removeDownloadCallback(callback);
        Assert.assertEquals(0, rcModule.downloadCallbacks.size());
    }

    /**
     * "downloadAllKeys"
     * Returning a mock json response, validating that included keys at the created request
     * The response should be parsed correctly and match with mock json data and included keys should be at the request
     */
    @Test
    public void downloadSpecificKeys() {
        downloadSpecificKeys_base(new String[] { TestUtils.keysValues[0] });
    }

    /**
     * "downloadAllKeys"
     * Returning a mock json response, validating that included keys param not exist at the request
     * The response should be parsed correctly and match with mock json data and included keys param should not exist at the request
     */
    @Test
    public void downloadSpecificKeys_nullInput() {
        downloadSpecificKeys_base(null);
    }

    /**
     * "downloadOmittingKeys"
     * Returning a mock json response, validating that excluded keys at the created request
     * The response should be parsed correctly and match with mock json data and excluded keys should be at the request
     */
    @Test
    public void downloadOmittingKeys() {
        downloadOmittingKeys_base(new String[] { TestUtils.keysValues[0] });
    }

    /**
     * "downloadOmittingKeys"
     * Returning a mock json response, validating that excluded keys param not exist at the request
     * The response should be parsed correctly and match with mock json data and excluded keys param must not exist
     */
    @Test
    public void downloadOmittingKeys_nullInput() {
        downloadOmittingKeys_base(null);
    }

    private void downloadSpecificKeys_base(String[] included) {
        downloadKeys_base(null, included, () -> Countly.instance().remoteConfig().downloadSpecificKeys(included,
            (rResult, error, fullValueUpdate, downloadedValues) -> {
                Assert.assertEquals(RequestResult.Success, rResult);
                Assert.assertNull(error);
            }));
    }

    private void downloadOmittingKeys_base(String[] omitted) {
        downloadKeys_base(omitted, null, () -> Countly.instance().remoteConfig().downloadOmittingKeys(omitted, null));
    }

    private void downloadKeys_base(String[] omitted, String[] included, Runnable downloadKeys) {
        RCDownloadCallback callback = (rResult, error, fullValueUpdate, downloadedValues) -> {
            Assert.assertEquals(RequestResult.Success, rResult);
            Assert.assertNull(error);
            Assert.assertEquals(1, downloadedValues.size());
        };
        JSONObject serverResponse = new JSONObject();
        serverResponse.put(TestUtils.keysValues[0], TestUtils.keysValues[1]);

        Countly.instance().init(TestUtils.getConfigRemoteConfigs().remoteConfigRegisterGlobalCallback(callback).enableRemoteConfigValueCaching());
        SDKCore.instance.config.immediateRequestGenerator = () -> remoteConfigRequestMaker(serverResponse, included, omitted);

        downloadKeys.run();
        validateRemoteConfigValues(Countly.instance().remoteConfig().getValues(), serverResponse, true);
    }

    private void validateRemoteConfigValues(Map<String, RCData> rcValuesStore, JSONObject rcValuesServer, boolean isCurrentUsersData) {
        Assert.assertEquals(rcValuesServer.keySet().size(), rcValuesStore.size());
        rcValuesServer.keySet().forEach(key -> validateRCData(rcValuesStore.get(key), rcValuesServer.get(key), isCurrentUsersData));
    }

    private void validateRCData(RCData rcData, Object value, boolean isCurrentUsersData) {
        Assert.assertEquals(rcData.value, value);
        Assert.assertEquals(rcData.isCurrentUsersData, isCurrentUsersData);
    }

    protected static void loadCountlyStoreWithRCValues(String values) {
        TestUtils.writeToFile(SDKStorage.JSON_FILE_NAME, values);
    }

    /**
     * Creates a request maker that returns the provided mock data, checks required params and metrics
     *
     * @param remoteConfigMockData mock data to return
     * @return request maker
     */
    protected static ImmediateRequestI remoteConfigRequestMaker(JSONObject remoteConfigMockData, String[] keysInclude, String[] keysExclude) {

        return (requestData, customEndpoint, cp, requestShouldBeDelayed, networkingIsEnabled, callback, log) -> {
            Map<String, String> params = TestUtils.parseQueryParams(requestData);
            Assert.assertEquals("rc", params.get("method"));
            if (keysInclude != null) {
                Assert.assertEquals(new JSONArray(Arrays.asList(keysInclude)).toString(), Utils.urldecode(params.get("keys")));
            }
            if (keysExclude != null) {
                Assert.assertEquals(new JSONArray(Arrays.asList(keysExclude)).toString(), Utils.urldecode(params.get("omit_keys")));
            }
            TestUtils.validateMetrics(Utils.urldecode(params.get("metrics")));
            TestUtils.validateRequestMakerRequiredParams("/o/sdk?", customEndpoint, requestShouldBeDelayed, networkingIsEnabled);
            TestUtils.validateRequiredParams(params);
            callback.callback(remoteConfigMockData);
        };
    }
}
