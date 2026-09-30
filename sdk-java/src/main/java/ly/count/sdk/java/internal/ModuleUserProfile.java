package ly.count.sdk.java.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import ly.count.sdk.java.Countly;
import ly.count.sdk.java.PredefinedUserPropertyKeys;
import ly.count.sdk.java.User;
import org.json.JSONException;
import org.json.JSONObject;

public class ModuleUserProfile extends ModuleBase {
    static final String CUSTOM_KEY = "custom";
    boolean isSynced = true;
    static final String PICTURE_BYTES = "[CLY]_picture_bytes";
    /**
     * The keys of {@link PredefinedUserPropertyKeys}, which neither the user property filter nor the
     * user property cache limit of the SDK behavior settings apply to.
     */
    static final Set<String> PREDEFINED_KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        PredefinedUserPropertyKeys.NAME, PredefinedUserPropertyKeys.USERNAME, PredefinedUserPropertyKeys.EMAIL,
        PredefinedUserPropertyKeys.ORGANIZATION, PredefinedUserPropertyKeys.PHONE, PredefinedUserPropertyKeys.PICTURE,
        PredefinedUserPropertyKeys.PICTURE_PATH, PredefinedUserPropertyKeys.GENDER, PredefinedUserPropertyKeys.BIRTH_YEAR)));
    UserProfile userProfileInterface;
    private final Map<String, Object> sets;
    private final List<OpParams> ops;

    private static class OpParams {
        final String key;
        final Object value;
        final Op op;

        OpParams(String key, Object value, Op op) {
            this.key = key;
            this.value = value;
            this.op = op;
        }
    }

    private interface OpFunction {
        void apply(JSONObject json, String key, Object value) throws JSONException;
    }

    enum Op {
        INC((json, key, value) -> {
            JSONObject object = json.optJSONObject(key, new JSONObject());
            object.put("$inc", object.optInt("$inc", 0) + (int) value);
            json.put(key, object);
        }),
        MUL((json, key, value) -> {
            JSONObject object = json.optJSONObject(key, new JSONObject());
            object.put("$mul", object.optDouble("$mul", 1) * (double) value);
            json.put(key, object);
        }),
        MIN((json, key, value) -> {
            JSONObject object = json.optJSONObject(key, new JSONObject());
            object.put("$min", Math.min(object.optDouble("$min", (Double) value), (Double) value));
            json.put(key, object);
        }),
        MAX((json, key, value) -> {
            JSONObject object = json.optJSONObject(key, new JSONObject());
            object.put("$max", Math.max(object.optDouble("$max", (Double) value), (Double) value));
            json.put(key, object);
        }),
        SET_ONCE((json, key, value) -> json.put(key, json.optJSONObject(key, new JSONObject()).put("$setOnce", value))),
        PULL((json, key, value) -> json.put(key, json.optJSONObject(key, new JSONObject()).accumulate("$pull", value))),
        PUSH((json, key, value) -> json.put(key, json.optJSONObject(key, new JSONObject()).accumulate("$push", value))),
        PUSH_UNIQUE((json, key, value) -> json.put(key, json.optJSONObject(key, new JSONObject()).accumulate("$addToSet", value)));
        final OpFunction valueTransformer;

        Op(OpFunction valueTransformer) {
            this.valueTransformer = valueTransformer;
        }
    }

    ModuleUserProfile() {
        sets = new LinkedHashMap<>();  // keys should be nullable, insertion order decides what the cache limit drops
        ops = new ArrayList<>();
    }

    /**
     * Gets a value of a property, if it is null returns 'JSONObject.NULL'
     *
     * @param key to log
     * @param value to check
     * @return opt out value
     */
    private Object optString(String key, Object value) {
        if (value == null) {
            return JSONObject.NULL;
        }
        if (!(value instanceof String)) {
            L.d("[ModuleUserProfile] optString, value is not a String, thus toString is going to be used for the key:[" + key + "]");
        }
        return value.toString();
    }

    /**
     * Transforming changes in "sets" into a json contained in "changes"
     * <p>
     * The SDK internal limits of the SDK behavior settings in effect apply here: string values of
     * predefined properties, the picture excepted, are cut to the value size limit, and custom
     * properties get the key length, value size and segmentation entry limits, the entry limit
     * counting sets only, never modifications.
     *
     * @param changes JSONObject to store changes
     * @param params Params to store changes
     * @throws JSONException if something goes wrong
     */
    void perform(JSONObject changes, Params params) throws JSONException {
        ConfigurationProvider limits = internalConfig.getConfigurationProvider();
        int maxValueSize = limits.getMaxValueSize();
        Map<String, Object> customSets = supportedCustomSets();
        Map<String, Object> limitedCustomSets = UtilsInternalLimits.applySegmentationLimits(customSets, limits, L, "[ModuleUserProfile] perform");
        boolean customSetsLimited = !customSets.equals(limitedCustomSets);

        for (String key : sets.keySet()) {
            Object value = sets.get(key);
            switch (key) {
                case PredefinedUserPropertyKeys.NAME:
                case PredefinedUserPropertyKeys.USERNAME:
                case PredefinedUserPropertyKeys.EMAIL:
                case PredefinedUserPropertyKeys.ORGANIZATION:
                case PredefinedUserPropertyKeys.PHONE:
                    changes.put(key, optString(key, UtilsInternalLimits.truncateIfString(value, maxValueSize, L, "[ModuleUserProfile] perform")));
                    break;
                case PredefinedUserPropertyKeys.PICTURE:
                    if (value == null) {
                        changes.put(PredefinedUserPropertyKeys.PICTURE, JSONObject.NULL);
                        internalConfig.sdk.user().picturePath = null;
                        internalConfig.sdk.user().picture = null;
                    } else if (value instanceof byte[]) {
                        internalConfig.sdk.user().picture = (byte[]) value;
                        //set a special value to indicate that the picture information is already stored in memory
                        params.add(PICTURE_BYTES, Utils.Base64.encode((byte[]) value));
                    }
                    break;
                case PredefinedUserPropertyKeys.PICTURE_PATH:
                    if (value == null || (value instanceof String && ((String) value).isEmpty())) {
                        changes.put(PredefinedUserPropertyKeys.PICTURE, JSONObject.NULL);
                        internalConfig.sdk.user().picturePath = null;
                        internalConfig.sdk.user().picture = null;
                    } else if (value instanceof String) {
                        if (Utils.isValidURL((String) value)) {
                            //if it is a valid URL that means the picture is online, and we want to send the link to the server
                            changes.put(PredefinedUserPropertyKeys.PICTURE, value);
                        } else {
                            //if we get here then that means it is a local file path which we would send over as bytes to the server
                            params.add(PredefinedUserPropertyKeys.PICTURE_PATH, value);
                        }
                        internalConfig.sdk.user().picturePath = value.toString();
                    } else {
                        L.e("[UserEditorImpl] Won't set user picturePath (must be String or null)");
                    }
                    break;
                case PredefinedUserPropertyKeys.GENDER:
                    value = UtilsInternalLimits.truncateIfString(value, maxValueSize, L, "[ModuleUserProfile] perform");
                    if (value == null || value instanceof User.Gender) {
                        changes.put(PredefinedUserPropertyKeys.GENDER, value == null ? JSONObject.NULL : value.toString());
                    } else if (value instanceof String) {
                        User.Gender gender = User.Gender.fromString((String) value);
                        if (gender == null) {
                            L.e("[UserEditorImpl] Cannot parse gender string: " + value + " (must be one of 'F' & 'M')");
                        } else {
                            changes.put(PredefinedUserPropertyKeys.GENDER, gender.toString());
                        }
                    } else {
                        L.e("[UserEditorImpl] Won't set user gender (must be of type User.Gender or one of following Strings: 'F', 'M')");
                    }
                    break;
                case PredefinedUserPropertyKeys.BIRTH_YEAR:
                    value = UtilsInternalLimits.truncateIfString(value, maxValueSize, L, "[ModuleUserProfile] perform");
                    if (value == null || value instanceof Integer) {
                        changes.put(PredefinedUserPropertyKeys.BIRTH_YEAR, value == null ? JSONObject.NULL : value);
                    } else if (value instanceof String) {
                        try {
                            changes.put(PredefinedUserPropertyKeys.BIRTH_YEAR, Integer.parseInt((String) value));
                        } catch (NumberFormatException e) {
                            L.e("[UserEditorImpl] user.birthyear must be either Integer or String which can be parsed to Integer" + e);
                        }
                    } else {
                        L.e("[UserEditorImpl] Won't set user birthyear (must be of type Integer or String which can be parsed to Integer)");
                    }
                    break;
                default:
                    if (!customSetsLimited || !customSets.containsKey(key)) {
                        performCustomUpdate(key, value, changes);
                    }
                    break;
            }
        }

        if (customSetsLimited) {
            for (Map.Entry<String, Object> entry : limitedCustomSets.entrySet()) {
                performCustomUpdate(entry.getKey(), entry.getValue(), changes);
            }
        }

        applyOps(changes, limits);
    }

    /**
     * The pending sets of custom properties that {@link #performCustomUpdate} sends, the ones with a
     * value of a supported type, in the order they were first set.
     *
     * @return the custom properties to send
     */
    private Map<String, Object> supportedCustomSets() {
        Map<String, Object> customSets = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : sets.entrySet()) {
            if (!isPredefinedKey(entry.getKey()) && isSupportedCustomValue(entry.getValue())) {
                customSets.put(entry.getKey(), entry.getValue());
            }
        }
        return customSets;
    }

    /**
     * Whether a custom property can be sent with a value, which has to be of a supported type.
     *
     * @param value the value
     * @return {@code true} for a supported type, {@code false} for any other type and for {@code null}
     */
    private static boolean isSupportedCustomValue(@Nullable Object value) {
        return value instanceof String || value instanceof Integer || value instanceof Float || value instanceof Double || value instanceof Boolean || value instanceof Object[];
    }

    /**
     * Applies the pending modifications to the custom properties of the changes, with their keys cut
     * to the key length limit and their string values cut to the value size limit.
     *
     * @param changes the changes to send
     * @param limits the SDK behavior settings in effect
     * @throws JSONException if a modification cannot be written
     */
    private void applyOps(final JSONObject changes, @Nonnull ConfigurationProvider limits) throws JSONException {
        if (!ops.isEmpty() && !changes.has(CUSTOM_KEY)) {
            changes.put(CUSTOM_KEY, new JSONObject());
        }
        for (OpParams opParam : ops) {
            String key = UtilsInternalLimits.truncateKey(opParam.key, limits.getMaxKeyLength(), L, "[ModuleUserProfile] applyOps");
            Object value = UtilsInternalLimits.truncateIfString(opParam.value, limits.getMaxValueSize(), L, "[ModuleUserProfile] applyOps");
            opParam.op.valueTransformer.apply(changes.getJSONObject(CUSTOM_KEY), key, value);
        }
    }

    private void performCustomUpdate(final String key, final Object value, final JSONObject changes) throws JSONException {
        if (value == null || isSupportedCustomValue(value)) {
            if (!changes.has(CUSTOM_KEY)) {
                changes.put(CUSTOM_KEY, new JSONObject());
            }
            JSONObject custom = changes.getJSONObject(CUSTOM_KEY).put(key, value);
            if (value == null) {
                custom.remove(key);
            } else {
                custom.put(key, value);
            }
        } else {
            L.e("[UserEditorImpl] performCustomUpdate, Type of value " + value + " '" + value.getClass().getSimpleName() + "' is not supported yet, thus user property is not stored");
        }
    }

    /**
     * Returns &user_details= prefixed url to add to request data when making request to server
     *
     * @return a String user_details url part with provided user data
     */
    private Params prepareRequestParamsForUserProfile() {

        if (isSynced) {
            L.d("[ModuleUserProfile] prepareRequestParamsForUserProfile, nothing to save returning");
            return new Params();
        }

        isSynced = true;
        Params params = new Params();
        final JSONObject json = new JSONObject();
        perform(json, params);

        if (!json.isEmpty() || params.has(PICTURE_BYTES) || params.has(PredefinedUserPropertyKeys.PICTURE_PATH)) {
            params.add("user_details", json.toString());
        }

        return params;
    }

    /**
     * Atomic modifications on custom user property.
     * If value null, call will be ignored
     *
     * @param key String with property name to modify
     * @param value String value to use in modification
     * @param mod String with modification command
     */
    private void modifyCustomData(String key, Object value, Op mod) {
        if (value == null) {
            L.w("[ModuleUserProfile] modifyCustomData, value is null, thus nothing to modify");
            return;
        }

        if (!UtilsListingFilters.applyUserPropertyFilter(key, internalConfig.getConfigurationProvider())) {
            L.w("[ModuleUserProfile] modifyCustomData, key [" + key + "] is filtered out by the user property filter of the SDK behavior settings, ignoring the modification");
            return;
        }

        ops.add(new OpParams(key, value, mod));
        applyCacheLimitToModifications();
        isSynced = false;
    }

    /**
     * This mainly performs the filtering of provided values
     * This single call would be used for both predefined properties and custom user properties
     *
     * @param data Map with user data
     */
    protected void setPropertiesInternal(@Nonnull Map<String, Object> data) {
        if (data.isEmpty()) {
            L.w("[ModuleUserProfile] setPropertiesInternal, no data was provided");
            return;
        }

        ConfigurationProvider configProvider = internalConfig.getConfigurationProvider();
        Map<String, Object> accepted = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            String key = entry.getKey();
            if (!isPredefinedKey(key) && !UtilsListingFilters.applyUserPropertyFilter(key, configProvider)) {
                L.w("[ModuleUserProfile] setPropertiesInternal, key [" + key + "] is filtered out by the user property filter of the SDK behavior settings, it will not be set");
                continue;
            }
            accepted.put(key, entry.getValue());
        }

        if (accepted.isEmpty()) {
            return;
        }

        sets.putAll(accepted);
        applyCacheLimitToCustomProperties();
        isSynced = false;
    }

    /**
     * Whether a user property key is one of {@link PredefinedUserPropertyKeys}.
     *
     * @param key the key
     * @return {@code true} for a predefined key
     */
    private static boolean isPredefinedKey(@Nullable String key) {
        return PREDEFINED_KEYS.contains(key);
    }

    /**
     * Keeps the pending custom properties within the user property cache limit of the SDK behavior
     * settings by dropping the oldest ones. Predefined properties are not counted.
     */
    private void applyCacheLimitToCustomProperties() {
        int cacheLimit = internalConfig.getConfigurationProvider().getUserPropertyCacheLimit();
        if (sets.size() <= cacheLimit) {
            return;
        }

        int customCount = 0;
        for (String key : sets.keySet()) {
            if (!isPredefinedKey(key)) {
                customCount++;
            }
        }

        int overflow = customCount - cacheLimit;
        if (overflow <= 0) {
            return;
        }

        List<String> dropped = new ArrayList<>(overflow);
        Iterator<String> keys = sets.keySet().iterator();
        while (keys.hasNext() && dropped.size() < overflow) {
            String key = keys.next();
            if (!isPredefinedKey(key)) {
                keys.remove();
                dropped.add(key);
            }
        }

        L.w("[ModuleUserProfile] applyCacheLimitToCustomProperties, [" + customCount + "] custom user properties are pending, over the cache limit of [" + cacheLimit + "] set by the SDK behavior settings, dropped the oldest ones: " + dropped);
    }

    /**
     * Keeps the keys with pending modifications within the user property cache limit of the SDK
     * behavior settings by dropping every modification of the oldest keys.
     */
    private void applyCacheLimitToModifications() {
        int cacheLimit = internalConfig.getConfigurationProvider().getUserPropertyCacheLimit();
        if (ops.size() <= cacheLimit) {
            return;
        }

        Set<String> modifiedKeys = new LinkedHashSet<>();
        for (OpParams op : ops) {
            modifiedKeys.add(op.key);
        }

        int overflow = modifiedKeys.size() - cacheLimit;
        if (overflow <= 0) {
            return;
        }

        Set<String> dropped = new LinkedHashSet<>();
        Iterator<String> keys = modifiedKeys.iterator();
        while (dropped.size() < overflow) {
            dropped.add(keys.next());
        }
        ops.removeIf(op -> dropped.contains(op.key));

        L.w("[ModuleUserProfile] applyCacheLimitToModifications, [" + modifiedKeys.size() + "] custom user properties have pending modifications, over the cache limit of [" + cacheLimit + "] set by the SDK behavior settings, dropped the modifications of the oldest ones: " + dropped);
    }

    protected void saveInternal() {
        Params generatedParams = prepareRequestParamsForUserProfile();
        if (internalConfig.sdk.location() != null) {
            internalConfig.sdk.module(ModuleLocation.class).saveLocationToParamsLegacy(generatedParams);
        }

        L.d("[ModuleUserProfile] saveInternal, generated params [" + generatedParams + "]");
        if (generatedParams.length() <= 0) {
            L.d("[ModuleUserProfile] saveInternal, nothing to save returning");
            return;
        }

        if (internalConfig.isAutoSendUserProperties() && internalConfig.sdk.events() != null) {
            internalConfig.sdk.module(ModuleEvents.class).checkEventQueueToSend(true);
        }

        ModuleRequests.pushAsync(internalConfig, new Request(generatedParams));
        clearInternal();
    }

    protected void clearInternal() {
        L.d("[ModuleUserProfile] clearInternal");

        sets.clear();
        ops.clear();
        isSynced = true;
    }

    @Override
    public void init(InternalConfig internalConfig) {
        super.init(internalConfig);
        userProfileInterface = new UserProfile();
    }

    @Override
    public void initFinished(InternalConfig internalConfig) {
        super.initFinished(internalConfig);
    }

    @Override
    public void stop(InternalConfig config, boolean clearData) {
        userProfileInterface = null;
    }

    @Override
    protected void onTimer() {
        if (internalConfig.isAutoSendUserProperties()) {
            saveInternal();
        }
    }

    @Override
    public void deviceIdChanged(String oldDeviceId, boolean withMerge) {
        super.deviceIdChanged(oldDeviceId, withMerge);
        L.d("[ModuleUserProfile] deviceIdChanged: oldDeviceId = " + oldDeviceId + ", withMerge = " + withMerge);
        if (internalConfig.isAutoSendUserProperties() && !withMerge) {
            saveInternal();
        }
    }

    public class UserProfile {

        /**
         * Increment custom property value by 1.
         *
         * @param key String with property name to increment
         */
        public void increment(String key) {
            synchronized (Countly.instance()) {
                modifyCustomData(key, 1, Op.INC);
            }
        }

        /**
         * Increment custom property value by provided value.
         *
         * @param key String with property name to increment
         * @param value int value by which to increment
         */
        public void incrementBy(String key, int value) {
            synchronized (Countly.instance()) {
                modifyCustomData(key, value, Op.INC);
            }
        }

        /**
         * Multiply custom property value by provided value.
         *
         * @param key String with property name to multiply
         * @param value int value by which to multiply
         */
        public void multiply(String key, double value) {
            synchronized (Countly.instance()) {
                modifyCustomData(key, value, Op.MUL);
            }
        }

        /**
         * Save maximal value between existing and provided.
         *
         * @param key String with property name to check for max
         * @param value int value to check for max
         */
        public void saveMax(String key, double value) {
            synchronized (Countly.instance()) {
                modifyCustomData(key, value, Op.MAX);
            }
        }

        /**
         * Save minimal value between existing and provided.
         *
         * @param key String with property name to check for min
         * @param value int value to check for min
         */
        public void saveMin(String key, double value) {
            synchronized (Countly.instance()) {
                modifyCustomData(key, value, Op.MIN);
            }
        }

        /**
         * Set value only if property does not exist yet
         *
         * @param key String with property name to set
         * @param value String value to set
         */
        public void setOnce(String key, Object value) {
            synchronized (Countly.instance()) {
                modifyCustomData(key, value, Op.SET_ONCE);
            }
        }

        /**
         * Create array property, if property does not exist and add value to array
         * You can only use it on array properties or properties that do not exist yet
         *
         * @param key String with property name for array property
         * @param value String with value to add to array
         */
        public void push(String key, Object value) {
            synchronized (Countly.instance()) {
                modifyCustomData(key, value, Op.PUSH);
            }
        }

        /**
         * Create array property, if property does not exist and add value to array, only if value is not yet in the array
         * You can only use it on array properties or properties that do not exist yet
         *
         * @param key String with property name for array property
         * @param value String with value to add to array
         */
        public void pushUnique(String key, Object value) {
            synchronized (Countly.instance()) {
                modifyCustomData(key, value, Op.PUSH_UNIQUE);
            }
        }

        /**
         * Create array property, if property does not exist and remove value from array
         * You can only use it on array properties or properties that do not exist yet
         *
         * @param key String with property name for array property
         * @param value String with value to remove from array
         */
        public void pull(String key, Object value) {
            synchronized (Countly.instance()) {
                modifyCustomData(key, value, Op.PULL);
            }
        }

        /**
         * Set a single user property. It can be either a custom one or one of the predefined ones.
         *
         * @param key the key for the user property
         * @param value the value for the user property to be set. The value should be the allowed data type.
         */
        public void setProperty(String key, Object value) {
            synchronized (Countly.instance()) {
                L.i("[UserProfile] Calling 'setProperty'");

                Map<String, Object> data = new HashMap<>(); // keys should be nullable
                data.put(key, value);

                setPropertiesInternal(data);
            }
        }

        /**
         * Provide a map of user properties to set.
         * Those can be either custom user properties or predefined user properties
         *
         * @param data Map of user properties to set
         */
        public void setProperties(Map<String, Object> data) {
            synchronized (Countly.instance()) {
                L.i("[UserProfile] Calling 'setProperties'");

                if (data == null) {
                    L.i("[UserProfile] Provided data can not be 'null'");
                    return;
                }
                setPropertiesInternal(data);
            }
        }

        /**
         * Send provided values to server
         */
        public void save() {
            synchronized (Countly.instance()) {
                L.i("[UserProfile] Calling 'save'");
                saveInternal();
            }
        }

        /**
         * Clear queued operations / modifications
         */
        public void clear() {
            synchronized (Countly.instance()) {
                L.i("[UserProfile] Calling 'clear'");
                clearInternal();
            }
        }
    }
}
