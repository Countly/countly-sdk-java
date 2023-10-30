package ly.count.sdk.java.internal;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.json.JSONException;
import org.json.JSONObject;

public class RemoteConfigValueStore {
    public JSONObject values;
    private final Log L;
    public boolean valuesCanBeCached;
    public static final String keyValue = "v";
    public static final String keyCacheFlag = "c";
    public static final int cacheValCached = 0;
    public static final int cacheValFresh = 1;

    //  Structure of the JSON objects we will have
    //   {
    //      “key”: {
    //          “v”: “value”,
    //          “c”: 0
    //      }
    //   }

    //========================================
    // CLEANSING
    //========================================

    public void cacheClearValues() {
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

    public void clearValues() {
        values.clear();
    }

    //========================================
    // MERGING
    //========================================

    public void mergeValues(@Nonnull Map<String, RCData> newValues, boolean fullUpdate) {
        L.v("[RemoteConfigValueStore] mergeValues, stored values C:" + values.length() + "provided values C:" + newValues.size());

        if (fullUpdate) {
            clearValues();
        }

        for (Map.Entry<String, RCData> entry : newValues.entrySet()) {
            String key = entry.getKey();
            Object newValue = entry.getValue().value;
            JSONObject newObj = new JSONObject();
            try {
                newObj.put(keyValue, newValue);
                newObj.put(keyCacheFlag, cacheValFresh);
                values.put(key, newObj);
            } catch (Exception e) {
                L.e("[RemoteConfigValueStore] Failed merging remote config values");
            }
        }
        L.v("[RemoteConfigValueStore] merging done:" + values.toString());
    }

    //========================================
    // CONSTRUCTION
    //========================================

    private RemoteConfigValueStore(@Nonnull JSONObject values, boolean valuesShouldBeCached, @Nonnull Log L) {
        this.values = values;
        this.valuesCanBeCached = valuesShouldBeCached;
        this.L = L;
    }

    //========================================
    // GET VALUES
    //========================================

    public @Nonnull RCData getValue(@Nonnull String key) {
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

    public @Nonnull Map<String, RCData> getAllValues() {
        Map<String, RCData> ret = new HashMap<>();

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
                ret.put(key, new RCData(rcObjVal, (rcObjCache != cacheValCached)));
            } catch (Exception ex) {
                L.e("[RemoteConfigValueStore] Got JSON exception while calling 'getAllValues': " + ex);
            }
        }

        return ret;
    }

    //========================================
    // SERIALIZATION, DESERIALIZATION
    //========================================

    public static RemoteConfigValueStore dataFromString(@Nullable String storageString, boolean valuesShouldBeCached, @Nonnull Log L) {
        if (storageString == null || storageString.isEmpty()) {
            return new RemoteConfigValueStore(new JSONObject(), valuesShouldBeCached, L);
        }

        JSONObject values;
        try {
            values = new JSONObject(storageString);
        } catch (JSONException e) {
            L.e("[RemoteConfigValueStore] Couldn't decode RemoteConfigValueStore successfully: " + e);
            values = new JSONObject();
        }
        L.i("[RemoteConfigValueStore] serialization done, dataFromString:" + values);
        return new RemoteConfigValueStore(values, valuesShouldBeCached, L);
    }
}
