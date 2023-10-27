package ly.count.sdk.java.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.json.JSONArray;
import org.json.JSONObject;

public class RemoteConfigHelper {

    public static @Nonnull Map<String, RCData> downloadedValuesIntoMap(@Nullable JSONObject jsonObject, @Nonnull Log L) {
        Map<String, RCData> result = new HashMap<>();

        if (jsonObject == null) {
            return result;
        }

        for (String key : jsonObject.keySet()) {
            Object value = jsonObject.opt(key);
            if (value != null) {
                result.put(key, new RCData(value, true));
            }
        }

        return result;
    }

    /*
     * Decide which keys to use
     * Useful if both 'keysExcept' and 'keysOnly' set
     * */
    public static @Nonnull String[] prepareKeysIncludeExclude(@Nullable final String[] keysOnly, @Nullable final String[] keysExcept, @Nonnull Log L) {
        String[] res = new String[2]; // 0 - include, 1 - exclude

        try {
            if (keysOnly != null && keysOnly.length > 0) {
                // Include list takes precedence
                res[0] = new JSONArray(Arrays.asList(keysOnly)).toString();
            } else if (keysExcept != null && keysExcept.length > 0) {
                // Include list was not used, use the exclude list
                res[1] = new JSONArray(Arrays.asList(keysExcept)).toString();
            }
        } catch (Exception ex) {
            L.e("[ModuleRemoteConfig] prepareKeysIncludeExclude, Failed at preparing keys, [" + ex + "]");
        }

        return res;
    }

    /**
     * Converts A/B testing variants fetched from the server (JSONObject) into a map
     *
     * @param variantsObj - JSON Object fetched from the server
     * @return Map of variants
     */
    public static @Nonnull Map<String, String[]> convertVariantsJsonToMap(@Nonnull JSONObject variantsObj, @Nonnull Log L) {
        Map<String, String[]> resultMap = new HashMap<>();
        JSONArray keys = variantsObj.names();

        if (keys == null) {
            return resultMap;
        }

        try {
            for (int i = 0; i < keys.length(); i++) {
                String key = keys.getString(i);
                Object value = variantsObj.get(key);

                if (value instanceof JSONArray) {
                    JSONArray jsonArray = (JSONArray) value;
                    List<String> tempVariantColl = new ArrayList<>();

                    for (int j = 0; j < jsonArray.length(); j++) {
                        JSONObject variantObject = jsonArray.optJSONObject(j);

                        if (variantObject != null && !variantObject.isNull(ModuleRemoteConfig.variantObjectNameKey)) {
                            tempVariantColl.add(variantObject.optString(ModuleRemoteConfig.variantObjectNameKey));
                        }
                    }

                    resultMap.put(key, tempVariantColl.toArray(new String[0]));
                }
            }
        } catch (Exception ex) {
            L.e("[ModuleRemoteConfig] convertVariantsJsonToMap, failed parsing:[" + ex + "]");
            return new HashMap<>();
        }

        return resultMap;
    }

    /**
     * Converts A/B testing info  fetched from the server (JSONObject) into a map
     *
     * @param experimentObj - JSON Object fetched from the server
     * @return Map of experiment information
     */
    public static @Nonnull Map<String, ExperimentInformation> convertExperimentInfoJsonToMap(@Nonnull JSONObject experimentObj, @Nonnull Log L) {
        Map<String, ExperimentInformation> experimentInfoMap = new HashMap<>();
        L.i("[ModuleRemoteConfig] convertExperimentInfoJsonToMap, parsing:[" + experimentObj + "]");

        if (!experimentObj.has("jsonArray")) {
            L.e("[ModuleRemoteConfig] convertVariantsJsonToMap, no json array found ");
            return experimentInfoMap;
        }

        JSONArray jsonArray = experimentObj.optJSONArray("jsonArray");

        L.i("[ModuleRemoteConfig] convertExperimentInfoJsonToMap, array:[" + jsonArray + "]");

        if (jsonArray == null) {
            return experimentInfoMap;
        }

        try {
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject jsonObject = jsonArray.getJSONObject(i);
                L.i("[ModuleRemoteConfig] convertExperimentInfoJsonToMap, object:[" + jsonObject + "]");
                String expID = jsonObject.getString("id");
                String expName = jsonObject.getString("name");
                String expDescription = jsonObject.getString("description");
                String currentVariant = jsonObject.optString("currentVariant");

                JSONObject variantsObject = jsonObject.getJSONObject("variants");
                Map<String, Map<String, Object>> variantsMap = new HashMap<>();

                for (String variantName : variantsObject.keySet()) {
                    JSONObject variantDetails = variantsObject.getJSONObject(variantName);
                    L.i("[ModuleRemoteConfig] convertExperimentInfoJsonToMap, details:[" + variantDetails + "]");
                    Map<String, Object> variantMap = new HashMap<>();

                    for (String key : variantDetails.keySet()) {
                        variantMap.put(key, variantDetails.get(key));
                    }

                    variantsMap.put(variantName, variantMap);
                }

                ExperimentInformation experimentInfo = new ExperimentInformation(expID, expName, expDescription, currentVariant, variantsMap);
                experimentInfoMap.put(expID, experimentInfo);
            }
        } catch (Exception ex) {
            L.e("[ModuleRemoteConfig] convertVariantsJsonToMap, failed parsing:[" + ex + "]");
            return new HashMap<>();
        }

        L.i("[ModuleRemoteConfig] convertExperimentInfoJsonToMap, conversion result:[" + experimentInfoMap + "]");
        return experimentInfoMap;
    }
}
