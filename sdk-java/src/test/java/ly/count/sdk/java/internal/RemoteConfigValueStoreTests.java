package ly.count.sdk.java.internal;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

@RunWith(JUnit4.class)
public class RemoteConfigValueStoreTests {

    /**
     * "constructor" defaults
     * Validating that static fields and instance fields are set to the correct values
     * Static fields are set to the correct values, there is no null values
     */
    @Test
    public void constructor_defaults() {
        RemoteConfigValueStore rcvs = new RemoteConfigValueStore(new JSONObject(), false, Mockito.mock(Log.class));
        Assert.assertEquals(RemoteConfigValueStore.keyValue, "v");
        Assert.assertEquals(RemoteConfigValueStore.keyCacheFlag, "c");
        Assert.assertEquals(RemoteConfigValueStore.cacheValCached, 0);
        Assert.assertEquals(RemoteConfigValueStore.cacheValFresh, 1);
        Assert.assertNotNull(rcvs.values);
        Assert.assertNotNull(rcvs.L);
    }

    /**
     * "cacheClearValues" with valid RCVS
     * Validating that the method clears the values when caching is disabled
     * RCVS must be empty because caching is disabled
     */
    @Test
    public void cacheClearValues_cachingDisabled() {
        RemoteConfigValueStore rcvs = new RemoteConfigValueStore(rcvsJson(RemoteConfigValueStore.keyCacheFlag), false, Mockito.mock(Log.class));
        Assert.assertEquals(2, rcvs.getAllValues().size());
        rcvs.cacheClearValues();
        Assert.assertEquals(rcvs.values.length(), 0);
    }

    /**
     * "cacheClearValues"
     * Validating that the method throws Null pointer exception when RCVS is null
     * "cacheClearValues" must trigger null pointer exception
     */
    @Test(expected = NullPointerException.class)
    public void cacheClearValues_nullRcValues() {
        RemoteConfigValueStore rcvs = new RemoteConfigValueStore(null, false, Mockito.mock(Log.class));
        rcvs.cacheClearValues();
    }

    /**
     * "cacheClearValues" with valid RCVS
     * Validating that the method cache clears the values when caching is enabled
     * RCVS must not be empty and values must be marked as not current users data
     */
    @Test
    public void cacheClearValues_cachingEnabled() {
        RemoteConfigValueStore rcvs = new RemoteConfigValueStore(rcvsJson(RemoteConfigValueStore.keyCacheFlag), true, Mockito.mock(Log.class));
        Assert.assertEquals(2, rcvs.getAllValues().size());
        rcvs.cacheClearValues();
        Assert.assertEquals(2, rcvs.getAllValues().size());
        Assert.assertEquals(2, rcvs.values.length());
        rcvs.getAllValues().forEach((k, v) -> Assert.assertFalse(v.isCurrentUsersData));
    }

    /**
     * "cacheClearValues" with a garbage json
     * Validating that "cacheClearValues" logs expected log when garbage json is given
     * JSON RCVS should contain the garbage but "getAllValues" must not return it, and expected log should be logger for the garbage key
     */
    @Test
    public void cacheClearValues_garbageJson() {
        RemoteConfigValueStore rcvs = new RemoteConfigValueStore(garbageJson(), true, Mockito.mock(Log.class));
        rcvs.L = Mockito.spy(rcvs.L);
        rcvs.cacheClearValues();
        Mockito.verify(rcvs.L, Mockito.times(1)).w("[RemoteConfigValueStore] cacheClearValues, stored entry was not a JSON object, key:[" + TestUtils.keysValues[0] + "] value:[garbage]");
        Assert.assertEquals(0, rcvs.getAllValues().size());
        Assert.assertEquals(1, rcvs.values.length());
    }

    /**
     * "mergeValues" with valid RCVS
     * Validation that method clears values and adds new values because full update is requested
     * RCVS must be emptied and then contain new values
     */
    @Test
    public void mergeValues_fullUpdate() {
        RemoteConfigValueStore rcvs = new RemoteConfigValueStore(rcvsJson(RemoteConfigValueStore.keyCacheFlag), true, Mockito.mock(Log.class));
        Assert.assertEquals(2, rcvs.getAllValues().size());
        Assert.assertEquals(TestUtils.keysValues[1], rcvs.getValue(TestUtils.keysValues[0]).value);
        rcvs.mergeValues(newRCValues(), true);
        Assert.assertEquals(TestUtils.keysValues[3], rcvs.getValue(TestUtils.keysValues[0]).value);
        Assert.assertEquals(3, rcvs.getAllValues().size());
        Assert.assertEquals(3, rcvs.values.length());
    }

    /**
     * "mergeValues" with valid RCVS
     * Validation that method adds new values and updates existing values because full update is not requested
     * RCVS must contain new values and updated values, and expected key's value should change to validate merging
     */
    @Test
    public void mergeValues_notFullUpdate() {
        RemoteConfigValueStore rcvs = new RemoteConfigValueStore(rcvsJson(RemoteConfigValueStore.keyCacheFlag), true, Mockito.mock(Log.class));
        Assert.assertEquals(2, rcvs.getAllValues().size());
        Assert.assertEquals(TestUtils.keysValues[1], rcvs.getValue(TestUtils.keysValues[0]).value);
        rcvs.mergeValues(newRCValues(), false);
        Assert.assertEquals(TestUtils.keysValues[3], rcvs.getValue(TestUtils.keysValues[0]).value);
        Assert.assertEquals(4, rcvs.getAllValues().size());
        Assert.assertEquals(4, rcvs.values.length());
    }

    /**
     * "mergeValues" with valid RCVS
     * Validation that method throws Null pointer exception when new values are null
     * "mergeValues" must trigger null pointer exception
     */
    @Test(expected = NullPointerException.class)
    public void mergeValues_nullNewRcValues() {
        RemoteConfigValueStore rcvs = new RemoteConfigValueStore(rcvsJson(RemoteConfigValueStore.keyCacheFlag), true, Mockito.mock(Log.class));
        Assert.assertEquals(2, rcvs.getAllValues().size());
        rcvs.mergeValues(null, false);
    }

    /**
     * "mergeValues"
     * Validation that method throws Null pointer exception when RCVS is null
     * "mergeValues" must trigger null pointer exception
     */
    @Test(expected = NullPointerException.class)
    public void mergeValues_nullRcValues() {
        RemoteConfigValueStore rcvs = new RemoteConfigValueStore(null, true, Mockito.mock(Log.class));
        rcvs.mergeValues(newRCValues(), false);
    }

    /**
     * "getValue" with null RCVS
     * Validation that method returns null when RCVS is null and logs and error log
     * "getValue" must return null and error log should be logged
     */
    @Test
    public void getValue_nullRcValues() {
        RemoteConfigValueStore rcvs = new RemoteConfigValueStore(null, true, Mockito.mock(Log.class));
        rcvs.L = Mockito.spy(rcvs.L);
        Assert.assertNull(rcvs.getValue(TestUtils.keysValues[0]).value);
        Mockito.verify(rcvs.L, Mockito.times(1)).e(Mockito.anyString());
    }

    /**
     * "getValue" with valid RCVS
     * Validating that method returns correct value
     * "getValue" must return correct value for the k-v pair
     */
    @Test
    public void getValue() {
        RemoteConfigValueStore rcvs = new RemoteConfigValueStore(rcvsJson(RemoteConfigValueStore.keyCacheFlag), true, Mockito.mock(Log.class));
        Assert.assertEquals(TestUtils.keysValues[1], rcvs.getValue(TestUtils.keysValues[0]).value);
    }

    /**
     * "getValue" with valid RCVS
     * Validating that method returns null when key does not exist
     * "getValue" must return null for the key that does not exist
     */
    @Test
    public void getValue_notExist() {
        RemoteConfigValueStore rcvs = new RemoteConfigValueStore(rcvsJson(RemoteConfigValueStore.keyCacheFlag), true, Mockito.mock(Log.class));
        Assert.assertNull(rcvs.getValue(TestUtils.keysValues[5]).value);
    }

    /**
     * "getValue" with garbage RCVS
     * Validating that method returns null when RCVS is garbage
     * "getValue" must return null for the key because k is not parsable
     */
    @Test
    public void getValue_garbageJson() {
        RemoteConfigValueStore rcvs = new RemoteConfigValueStore(garbageJson(), true, Mockito.mock(Log.class));
        Assert.assertEquals(1, rcvs.values.length());
        Assert.assertNull(rcvs.getValue(TestUtils.keysValues[0]).value);
    }

    /**
     * "getAllValues" with valid RCVS
     * Validating that size of the returned map is correct
     * "getAllValues" must return correct size of the map 2
     */
    @Test
    public void getAllValues() {
        RemoteConfigValueStore rcvs = new RemoteConfigValueStore(rcvsJson(RemoteConfigValueStore.keyCacheFlag), true, Mockito.mock(Log.class));
        Assert.assertEquals(2, rcvs.getAllValues().size());
        Assert.assertEquals(2, rcvs.values.length());
    }

    /**
     * "getAllValues" with null RCVS
     * Validating that method throws Null pointer exception when RCVS is null
     * "getAllValues" must trigger null pointer exception
     */
    @Test(expected = NullPointerException.class)
    public void getAllValues_nullRcValues() {
        RemoteConfigValueStore rcvs = new RemoteConfigValueStore(null, true, Mockito.mock(Log.class));
        rcvs.getAllValues();
    }

    /**
     * "getAllValues" with garbage RCVS
     * Validating that method returns empty map when RCVS is garbage
     * "getAllValues" must return empty map because RCVS is garbage but JSON contains it
     */
    @Test
    public void getAllValues_garbageJson() {
        RemoteConfigValueStore rcvs = new RemoteConfigValueStore(garbageJson(), true, Mockito.mock(Log.class));
        Assert.assertEquals(0, rcvs.getAllValues().size());
        Assert.assertEquals(1, rcvs.values.length());
    }

    /**
     * "getAllValues" with part-garbage RCVS (cache key invalid)
     * Validating that method returns empty map when RCVS is part-garbage
     * "getAllValues" must return empty map because RCVS is part-garbage but JSON contains it and logger should log an error
     */
    @Test
    public void getAllValues_garbageCacheKey() {
        RemoteConfigValueStore rcvs = new RemoteConfigValueStore(rcvsJson("garbage"), true, Mockito.mock(Log.class));
        rcvs.L = Mockito.spy(rcvs.L);
        Assert.assertEquals(0, rcvs.getAllValues().size());
        Mockito.verify(rcvs.L, Mockito.times(2)).e(Mockito.anyString());
        Assert.assertEquals(2, rcvs.values.length());
    }

    private JSONObject garbageJson() {
        JSONObject values = new JSONObject();
        values.put(TestUtils.keysValues[0], "garbage");
        return values;
    }

    private JSONObject rcvsJson(String keyCache) {
        JSONObject values = new JSONObject();

        values.put(TestUtils.keysValues[0], new JSONObject()
            .put(RemoteConfigValueStore.keyValue, TestUtils.keysValues[1])
            .put(keyCache, RemoteConfigValueStore.cacheValCached)
        );
        values.put(TestUtils.keysValues[2], new JSONObject()
            .put(RemoteConfigValueStore.keyValue, TestUtils.keysValues[3])
            .put(keyCache, RemoteConfigValueStore.cacheValFresh)
        );

        return values;
    }

    private Map<String, RCData> newRCValues() {
        Map<String, RCData> values = new ConcurrentHashMap<>();
        values.put(TestUtils.keysValues[3], new RCData(TestUtils.keysValues[0], true));
        values.put(TestUtils.keysValues[1], new RCData(TestUtils.keysValues[2], false));
        values.put(TestUtils.keysValues[0], new RCData(TestUtils.keysValues[3], false));

        return values;
    }
}
