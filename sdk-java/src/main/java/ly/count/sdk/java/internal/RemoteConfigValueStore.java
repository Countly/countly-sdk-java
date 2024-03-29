package ly.count.sdk.java.internal;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import org.json.JSONObject;

public class RemoteConfigValueStore {
    protected JSONObject values;
    protected Log L;
    protected boolean valuesCanBeCached;
    protected static final String keyValue = "v";
    protected static final String keyCacheFlag = "c";
    protected static final int cacheValCached = 0;
    protected static final int cacheValFresh = 1;

    //  Structure of the JSON objects we will have
    //   {
    //      "key": {
    //          "v”: "value",
    //          "c": 0
    //      }
    //   }

    /**
     * Constructor
     *
     * @param values values to store
     * @param valuesShouldBeCached if true, values will be cached
     * @param L logger
     */
    protected RemoteConfigValueStore(@Nonnull JSONObject values, boolean valuesShouldBeCached, final @Nonnull Log L) {
        this.values = values;
        this.valuesCanBeCached = valuesShouldBeCached;
        this.L = L;
    }

    //========================================
    // CLEANSING
    //========================================

    /**
     * If caching is not enabled, it clears all values.
     * If caching is enabled, it marks all values as "cached"
     */
    protected void cacheClearValues() {
        if (!valuesCanBeCached) {
            clearValues();
            return;
        }

        Iterator<String> iter = values.keys();
        while (iter.hasNext()) {
            String key = iter.next();
            JSONObject value = values.optJSONObject(key);

            if (value == null) {
                Object badVal = values.opt(key);
                L.w("[RemoteConfigValueStore] cacheClearValues, stored entry was not a JSON object, key:[" + key + "] value:[" + badVal + "]");
                continue;
            }

            try {
                value.put(keyCacheFlag, cacheValCached);
                values.put(key, value);
            } catch (Exception e) {
                L.e("[RemoteConfigValueStore] cacheClearValues, Failed caching remote config values, " + e);
            }
        }
    }

    /**
     * Clears all values
     */
    private void clearValues() {
        values.clear();
    }

    //========================================
    // MERGING
    //========================================

    /**
     * Merges the provided values with the stored values
     *
     * @param newValues values to merge
     * @param fullUpdate if true, all values will be replaced with the provided ones
     */
    protected void mergeValues(@Nonnull Map<String, RCData> newValues, final boolean fullUpdate) {
        L.v("[RemoteConfigValueStore] mergeValues, stored values C:" + values.length() + "provided values C:" + newValues.size());

        if (fullUpdate) {
            clearValues();
        }
        for (Map.Entry<String, RCData> entry : newValues.entrySet()) {
            String key = entry.getKey();
            Object newValue = entry.getValue().value;
            try {
                JSONObject newObj = createValueObj(newValue);
                values.put(key, newObj);
            } catch (Exception e) {
                L.e("[RemoteConfigValueStore] Failed merging remote config values");
            }
        }
        L.v("[RemoteConfigValueStore] merging done:" + values.toString());
    }

    private JSONObject createValueObj(final Object newValue) {
        JSONObject newObj = new JSONObject();
        newObj.put(keyValue, newValue);
        newObj.put(keyCacheFlag, cacheValFresh);
        return newObj;
    }

    //========================================
    // GET VALUES
    //========================================

    /**
     * Returns the value for the provided key
     *
     * @param key to get value for
     * @return value for the provided key
     */
    protected @Nonnull RCData getValue(@Nonnull final String key) {
        RCData res = new RCData(null, true);
        try {
            JSONObject rcObj = values.optJSONObject(key);
            if (rcObj == null) {
                return res;
            }
            res.value = rcObj.get(keyValue);
            res.isCurrentUsersData = rcObj.getInt(keyCacheFlag) != cacheValCached;
            return res;
        } catch (Exception ex) {
            L.e("[RemoteConfigValueStore] Got JSON exception while calling 'getValue': " + ex);
        }
        return res;
    }

    /**
     * Returns all values
     *
     * @return all values
     */
    protected @Nonnull Map<String, RCData> getAllValues() {
        Map<String, RCData> ret = new ConcurrentHashMap<>();

        Iterator<String> keys = values.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            try {
                JSONObject rcObj = values.optJSONObject(key);
                if (rcObj == null) {
                    continue;
                }
                Object rcObjVal = rcObj.opt(keyValue);
                int rcObjCache = rcObj.getInt(keyCacheFlag);
                ret.put(key, createRcData(rcObjVal, rcObjCache));
            } catch (Exception ex) {
                L.e("[RemoteConfigValueStore] Got JSON exception while calling 'getAllValues': " + ex);
            }
        }

        return ret;
    }

    private RCData createRcData(final Object value, final int cacheFlag) {
        return new RCData(value, cacheFlag != cacheValCached);
    }
}
