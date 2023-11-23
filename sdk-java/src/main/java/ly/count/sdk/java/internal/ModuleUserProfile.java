package ly.count.sdk.java.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import ly.count.sdk.java.Countly;
import ly.count.sdk.java.User;
import ly.count.sdk.java.UserPropertyKeys;
import org.json.JSONException;
import org.json.JSONObject;

public class ModuleUserProfile extends ModuleBase {
    static final String CUSTOM_KEY = "custom";
    static final String PICTURE_IN_USER_PROFILE = "[CLY]_USER_PROFILE_PICTURE";
    boolean isSynced = true;
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
        SET_ONCE(((json, key, value) -> json.put(key, json.optJSONObject(key, new JSONObject()).put("$setOnce", value)))),
        PULL((json, key, value) -> json.put(key, json.optJSONObject(key, new JSONObject()).accumulate("$pull", value))),
        PUSH((json, key, value) -> json.put(key, json.optJSONObject(key, new JSONObject()).accumulate("$push", value))),
        PUSH_UNIQUE((json, key, value) -> json.put(key, json.optJSONObject(key, new JSONObject()).accumulate("$addToSet", value)));
        final OpFunction valueTransformer;

        Op(OpFunction valueTransformer) {
            this.valueTransformer = valueTransformer;
        }
    }

    ModuleUserProfile() {
        sets = new HashMap<>();  // keys should be nullable
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
     *
     * @param changes
     * @throws JSONException
     */
    void perform(JSONObject changes) throws JSONException {
        for (String key : sets.keySet()) {
            Object value = sets.get(key);
            switch (key) {
                case UserPropertyKeys.NAME:
                case UserPropertyKeys.USERNAME:
                case UserPropertyKeys.EMAIL:
                case UserPropertyKeys.ORGANIZATION:
                case UserPropertyKeys.PHONE:
                    changes.put(key, optString(key, value));
                    break;
                case UserPropertyKeys.PICTURE:
                    if (value == null) {
                        changes.put(UserPropertyKeys.PICTURE, JSONObject.NULL);
                        internalConfig.sdk.user().picturePath = null;
                        internalConfig.sdk.user().picture = null;
                    } else if (value instanceof byte[]) {
                        internalConfig.sdk.user().picture = (byte[]) value;
                        //set a special value to indicate that the picture information is already stored in memory
                        changes.put(UserPropertyKeys.PICTURE_PATH, PICTURE_IN_USER_PROFILE);
                    }
                    break;
                case UserPropertyKeys.PICTURE_PATH:
                    if (value == null || (value instanceof String && ((String) value).isEmpty())) {
                        changes.put(UserPropertyKeys.PICTURE, JSONObject.NULL);
                        internalConfig.sdk.user().picturePath = null;
                        internalConfig.sdk.user().picture = null;
                    } else if (value instanceof String) {
                        if (Utils.isValidURL((String) value)) {
                            //if it is a valid URL that means the picture is online, and we want to send the link to the server
                            changes.put(UserPropertyKeys.PICTURE, value);
                        } else {
                            //if we get here then that means it is a local file path which we would send over as bytes to the server
                            changes.put(UserPropertyKeys.PICTURE_PATH, value);
                        }
                        internalConfig.sdk.user().picturePath = value.toString();
                    } else {
                        L.e("[UserEditorImpl] Won't set user picturePath (must be String or null)");
                    }
                    break;
                case UserPropertyKeys.GENDER:
                    if (value == null || value instanceof User.Gender) {
                        changes.put(UserPropertyKeys.GENDER, value == null ? JSONObject.NULL : value.toString());
                    } else if (value instanceof String) {
                        User.Gender gender = User.Gender.fromString((String) value);
                        if (gender == null) {
                            L.e("[UserEditorImpl] Cannot parse gender string: " + value + " (must be one of 'F' & 'M')");
                        } else {
                            changes.put(UserPropertyKeys.GENDER, gender.toString());
                        }
                    } else {
                        L.e("[UserEditorImpl] Won't set user gender (must be of type User.Gender or one of following Strings: 'F', 'M')");
                    }
                    break;
                case UserPropertyKeys.BIRTHYEAR:
                    if (value == null || value instanceof Integer) {
                        changes.put(UserPropertyKeys.BIRTHYEAR, value == null ? JSONObject.NULL : value);
                    } else if (value instanceof String) {
                        try {
                            changes.put(UserPropertyKeys.BIRTHYEAR, Integer.parseInt((String) value));
                        } catch (NumberFormatException e) {
                            L.e("[UserEditorImpl] user.birthyear must be either Integer or String which can be parsed to Integer" + e);
                        }
                    } else {
                        L.e("[UserEditorImpl] Won't set user birthyear (must be of type Integer or String which can be parsed to Integer)");
                    }
                    break;
                default:
                    performCustomUpdate(key, value, changes);
                    break;
            }
        }

        applyOps(changes);
    }

    private void applyOps(final JSONObject changes) throws JSONException {
        if (!ops.isEmpty() && !changes.has(CUSTOM_KEY)) {
            changes.put(CUSTOM_KEY, new JSONObject());
        }
        for (OpParams opParam : ops) {
            opParam.op.valueTransformer.apply(changes.getJSONObject(CUSTOM_KEY), opParam.key, opParam.value);
        }
    }

    private void performCustomUpdate(final String key, final Object value, final JSONObject changes) throws JSONException {
        if (value == null || value instanceof String || value instanceof Integer || value instanceof Float || value instanceof Double || value instanceof Boolean || value instanceof Object[]) {
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
        isSynced = true;
        Params params = new Params();
        final JSONObject json = new JSONObject();
        perform(json);
        if (json.has(UserPropertyKeys.PICTURE_PATH)) {
            try {
                params.add(UserPropertyKeys.PICTURE_PATH, json.getString(UserPropertyKeys.PICTURE_PATH));
                json.remove(UserPropertyKeys.PICTURE_PATH);
            } catch (JSONException e) {
                L.w("Won't send picturePath" + e);
            }
        }
        if (!json.isEmpty() || internalConfig.sdk.user().picturePath != null || internalConfig.sdk.user().picture != null) {
            params.add("user_details", json.toString());
            return params;
        } else {
            return null;
        }
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
        ops.add(new OpParams(key, value, mod));
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

        sets.putAll(data);
        isSynced = false;
    }

    protected void saveInternal() {
        if (isSynced) {
            L.d("[ModuleUserProfile] saveInternal, nothing to save returning");
            return;
        }
        Params generatedParams = prepareRequestParamsForUserProfile();
        if (generatedParams == null) {
            L.d("[ModuleUserProfile] saveInternal, nothing to save returning");
            return;
        }
        L.d("[ModuleUserProfile] saveInternal, generated params [" + generatedParams + "]");
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
