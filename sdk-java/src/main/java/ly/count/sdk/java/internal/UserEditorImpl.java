package ly.count.sdk.java.internal;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import ly.count.sdk.java.User;
import ly.count.sdk.java.UserEditor;
import org.json.JSONException;
import org.json.JSONObject;

public class UserEditorImpl implements UserEditor {
    public static final String PICTURE_PATH = "picturePath";
    public static final String PICTURE_IN_USER_PROFILE = "[CLY]_USER_PROFILE_PICTURE";
    static final String NAME = "name";
    static final String USERNAME = "username";
    static final String EMAIL = "email";
    static final String ORG = "org";
    static final String PHONE = "phone";
    static final String PICTURE = "picture";
    static final String GENDER = "gender";
    static final String BIRTHYEAR = "byear";
    static final String LOCALE = "locale";
    static final String COUNTRY = "country";
    static final String CITY = "city";
    static final String LOCATION = "location";
    static final String CUSTOM = "custom";
    private final UserImpl user;
    private final Map<String, Object> sets;
    private final List<Op> ops;
    private Log L = null;

    UserEditorImpl(UserImpl user, Log logger) {
        this.L = logger;
        this.user = user;
        this.sets = new HashMap<>();
        this.ops = new ArrayList<>();
    }

    /**
     * Transforming changes in "sets" into a json contained in "changes"
     *
     * @param changes the json object that will contain the changes
     * @throws JSONException if something goes wrong
     */
    @SuppressWarnings("unchecked")
    void perform(JSONObject changes) throws JSONException {
        for (String key : sets.keySet()) {
            Object value = sets.get(key);
            switch (key) {
                case NAME:
                    if (value == null || value instanceof String) {
                        user.name = (String) value;
                    } else {
                        L.w("user.name will be cast to String");
                        user.name = value.toString();
                    }
                    changes.put(NAME, value == null ? JSONObject.NULL : user.name);
                    break;
                case USERNAME:
                    if (value == null || value instanceof String) {
                        user.username = (String) value;
                    } else {
                        L.w("user.username will be cast to String");
                        user.username = value.toString();
                    }
                    changes.put(USERNAME, value == null ? JSONObject.NULL : user.username);
                    break;
                case EMAIL:
                    if (value == null || value instanceof String) {
                        user.email = (String) value;
                    } else {
                        L.w("user.email will be cast to String");
                        user.email = value.toString();
                    }
                    changes.put(EMAIL, value == null ? JSONObject.NULL : user.email);
                    break;
                case ORG:
                    if (value == null || value instanceof String) {
                        user.org = (String) value;
                    } else {
                        L.w("user.org will be cast to String");
                        user.org = value.toString();
                    }
                    changes.put(ORG, value == null ? JSONObject.NULL : user.org);
                    break;
                case PHONE:
                    if (value == null || value instanceof String) {
                        user.phone = (String) value;
                    } else {
                        L.w("user.phone will be cast to String");
                        user.phone = value.toString();
                    }
                    changes.put(PHONE, value == null ? JSONObject.NULL : user.phone);
                    break;
                case PICTURE:
                    //if we get here, that means that the dev gave us bytes for the picture
                    if (value == null) {
                        //there is an indication that the picture should be erased server side
                        user.picture = null;
                        user.picturePath = null;
                        changes.put(PICTURE, JSONObject.NULL);
                    } else if (value instanceof byte[]) {
                        user.picture = (byte[]) value;
                        //set a special value to indicate that the picture information is already stored in memory
                        changes.put(PICTURE_PATH, PICTURE_IN_USER_PROFILE);
                    } else {
                        L.e("[UserEditorImpl] Won't set user picture (must be of type byte[])");
                    }
                    break;
                case PICTURE_PATH:
                    if (value == null || (value instanceof String && ((String) value).isEmpty())) {
                        //there is an indication that the picture should be erased server side
                        user.picture = null;
                        user.picturePath = null;
                        changes.put(PICTURE, JSONObject.NULL);
                    } else if (value instanceof String) {
                        if (Utils.isValidURL((String) value)) {
                            //if it is a valid URL that means the picture is online, and we want to send the link to the server
                            changes.put(PICTURE, value);
                        } else {
                            //if we get here then that means it is a local file path which we would send over as bytes to the server
                            changes.put(PICTURE_PATH, value);
                        }
                        user.picturePath = value.toString();
                    } else {
                        L.e("[UserEditorImpl] Won't set user picturePath (must be String or null)");
                    }
                    break;
                case GENDER:
                    if (value == null || value instanceof User.Gender) {
                        user.gender = (User.Gender) value;
                        changes.put(GENDER, user.gender == null ? JSONObject.NULL : user.gender.toString());
                    } else if (value instanceof String) {
                        User.Gender gender = User.Gender.fromString((String) value);
                        if (gender == null) {
                            L.e("[UserEditorImpl] Cannot parse gender string: " + value + " (must be one of 'F' & 'M')");
                        } else {
                            user.gender = gender;
                            changes.put(GENDER, user.gender.toString());
                        }
                    } else {
                        L.e("[UserEditorImpl] Won't set user gender (must be of type User.Gender or one of following Strings: 'F', 'M')");
                    }
                    break;
                case BIRTHYEAR:
                    if (value == null || value instanceof Integer) {
                        user.birthyear = (Integer) value;
                        changes.put(BIRTHYEAR, value == null ? JSONObject.NULL : user.birthyear);
                    } else if (value instanceof String) {
                        try {
                            user.birthyear = Integer.parseInt((String) value);
                            changes.put(BIRTHYEAR, user.birthyear);
                        } catch (NumberFormatException e) {
                            L.e("[UserEditorImpl] user.birthyear must be either Integer or String which can be parsed to Integer" + e);
                        }
                    } else {
                        L.e("[UserEditorImpl] Won't set user birthyear (must be of type Integer or String which can be parsed to Integer)");
                    }
                    break;
                case LOCALE:
                    if (value == null || value instanceof String) {
                        user.locale = (String) value;
                        changes.put(LOCALE, value == null ? JSONObject.NULL : user.locale);
                    }
                    break;
                case COUNTRY:
                    if (value == null || value instanceof String) {
                        user.country = (String) value;
                        changes.put(COUNTRY, value == null ? JSONObject.NULL : user.country);
                    }
                    break;
                case CITY:
                    if (value == null || value instanceof String) {
                        user.city = (String) value;
                        changes.put(CITY, value == null ? JSONObject.NULL : user.city);
                    }
                    break;
                case LOCATION:
                    if (value == null || value instanceof String) {
                        user.location = (String) value;
                        changes.put(LOCATION, value == null ? JSONObject.NULL : user.location);
                    }
                    break;
                default:
                    if (value instanceof Map) { // custom property path via using "setCustom" function
                        ((Map<String, Object>) value).forEach((k, v) -> performCustomUpdate(k, v, changes));
                    } else { // directly using "set" function
                        performCustomUpdate(key, value, changes);
                    }
                    break;
            }
        }

        applyOps(changes);
    }

    private void applyOps(final JSONObject changes) throws JSONException {
        if (!ops.isEmpty() && !changes.has(CUSTOM)) {
            changes.put(CUSTOM, new JSONObject());
        }
        for (Op op : ops) {
            op.apply(changes.getJSONObject(CUSTOM));
        }
    }

    private void performCustomUpdate(final String key, final Object value, final JSONObject changes) throws JSONException {
        if (value == null || value instanceof String || value instanceof Integer || value instanceof Float || value instanceof Double || value instanceof Boolean || value instanceof Object[]) {
            if (!changes.has(CUSTOM)) {
                changes.put(CUSTOM, new JSONObject());
            }
            changes.getJSONObject(CUSTOM).put(key, value);
            if (value == null) {
                user.custom.remove(key);
            } else {
                user.custom.put(key, value);
            }
        } else {
            L.e("[UserEditorImpl] performCustomUpdate, Type of value " + value + " '" + value.getClass().getSimpleName() + "' is not supported yet, thus user property is not stored");
        }
    }

    @Override
    public UserEditor set(String key, Object value) {
        sets.put(key, value);
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public UserEditor setCustom(String key, Object value) {
        if (!sets.containsKey(CUSTOM)) {
            sets.put(CUSTOM, new HashMap<String, Object>());
        }
        Map<String, Object> custom = (Map<String, Object>) sets.get(CUSTOM);
        custom.put(key, value);
        return this;
    }

    @SuppressWarnings("unchecked")
    private UserEditor setCustomOp(String op, String key, Object value) {
        ops.add(new Op(op, key, value));
        return this;
    }

    @Override
    public UserEditor setName(String value) {
        L.d("setName: value = " + value);
        return set(NAME, value);
    }

    @Override
    public UserEditor setUsername(String value) {
        L.d("setUsername: value = " + value);
        return set(USERNAME, value);
    }

    @Override
    public UserEditor setEmail(String value) {
        L.d("setEmail: value = " + value);
        return set(EMAIL, value);
    }

    @Override
    public UserEditor setOrg(String value) {
        L.d("setOrg: value = " + value);
        return set(ORG, value);
    }

    @Override
    public UserEditor setPhone(String value) {
        L.d("setPhone: value = " + value);
        return set(PHONE, value);
    }

    //we set the bytes for the local picture
    @Override
    public UserEditor setPicture(byte[] picture) {
        L.d("setPicture: picture = " + picture);
        return set(PICTURE, picture);
    }

    //we set the url for either the online picture or a local path picture
    @Override
    public UserEditor setPicturePath(String picturePath) {
        L.d("[UserEditorImpl] setPicturePath, picturePath = " + picturePath);
        if (picturePath == null || Utils.isValidURL(picturePath) || (new File(picturePath)).isFile()) {
            //if it is a thing we can use, continue
            return set(PICTURE_PATH, picturePath);
        }
        L.w("[UserEditorImpl] setPicturePath, picturePath is not a valid file path or url");
        return this;
    }

    @Override
    public UserEditor setGender(Object gender) {
        L.d("setGender: gender = " + gender);
        return set(GENDER, gender);
    }

    @Override
    public UserEditor setBirthyear(int birthyear) {
        L.d("setBirthyear: birthyear = " + birthyear);
        return set(BIRTHYEAR, birthyear);
    }

    @Override
    public UserEditor setBirthyear(String birthyear) {
        L.d("setBirthyear: birthyear = " + birthyear);
        return set(BIRTHYEAR, birthyear);
    }

    @Override
    public UserEditor setLocale(String locale) {
        L.d("setLocale: locale = " + locale);
        return set(LOCALE, locale);
    }

    @Override
    public UserEditor setCountry(String country) {
        L.d("setCountry: country = " + country);
        if (SDKCore.enabled(CoreFeature.Location)) {
            return set(COUNTRY, country);
        } else {
            return this;
        }
    }

    @Override
    public UserEditor setCity(String city) {
        L.d("setCity: city = " + city);
        if (SDKCore.enabled(CoreFeature.Location)) {
            return set(CITY, city);
        } else {
            return this;
        }
    }

    @Override
    public UserEditor setLocation(String location) {
        L.d("setLocation: location = " + location);
        if (location != null) {
            String[] comps = location.split(",");
            if (comps.length == 2) {
                try {
                    return set(LOCATION, Double.valueOf(comps[0]) + "," + Double.valueOf(comps[1]));
                } catch (Throwable t) {
                    L.e("[UserEditorImpl] Invalid location format: " + location + " " + t);
                    return this;
                }
            } else {
                L.e("[UserEditorImpl] Invalid location format: " + location);
                return this;
            }
        } else {
            return set(LOCATION, null);
        }
    }

    @Override
    public UserEditor setLocation(double latitude, double longitude) {
        L.d("setLocation: latitude = " + latitude + " longitude" + longitude);
        if (SDKCore.enabled(CoreFeature.Location)) {
            return set(LOCATION, latitude + "," + longitude);
        } else {
            return this;
        }
    }

    @Override
    public UserEditor optOutFromLocationServices() {
        L.d("optOutFromLocationServices");
        return set(COUNTRY, "").set(CITY, "").set(LOCATION, "");
    }

    @Override
    public UserEditor inc(String key, int by) {
        L.d("inc: key " + key + " by " + by);
        return setCustomOp(Op.INC, key, by);
    }

    @Override
    public UserEditor mul(String key, double by) {
        L.d("mul: key " + key + " by " + by);
        return setCustomOp(Op.MUL, key, by);
    }

    @Override
    public UserEditor min(String key, double value) {
        L.d("min: key " + key + " value " + value);
        return setCustomOp(Op.MIN, key, value);
    }

    @Override
    public UserEditor max(String key, double value) {
        L.d("max: key " + key + " value " + value);
        return setCustomOp(Op.MAX, key, value);
    }

    @Override
    public UserEditor setOnce(String key, Object value) {
        L.d("setOnce: key " + key + " value " + value);
        if (value == null) {
            L.e("[UserEditorImpl] $setOnce operation operand cannot be null: key " + key);
            return this;
        } else {
            return setCustomOp(Op.SET_ONCE, key, value);
        }
    }

    @Override
    public UserEditor pull(String key, Object value) {
        L.d("pull: key " + key + " value " + value);
        if (value == null) {
            L.e("[UserEditorImpl] $pull operation operand cannot be null: key " + key);
            return this;
        } else {
            return setCustomOp(Op.PULL, key, value);
        }
    }

    @Override
    public UserEditor push(String key, Object value) {
        L.d("push: key " + key + " value " + value);
        if (value == null) {
            L.e("[UserEditorImpl] $push operation operand cannot be null: key " + key);
            return this;
        } else {
            return setCustomOp(Op.PUSH, key, value);
        }
    }

    @Override
    public UserEditor pushUnique(String key, Object value) {
        L.d("pushUnique: key " + key + " value " + value);
        if (value == null) {
            L.e("[UserEditorImpl] pushUnique / $addToSet operation operand cannot be null: key " + key);
            return this;
        } else {
            return setCustomOp(Op.PUSH_UNIQUE, key, value);
        }
    }

    @Override
    public UserEditor addToCohort(String key) {
        L.w("[UserEditorImpl] addToCohort, this function is deprecated and is doing nothing.");
        return this;
    }

    @Override
    public UserEditor removeFromCohort(String key) {
        L.w("[UserEditorImpl] removeFromCohort, this function is deprecated and is doing nothing.");
        return this;
    }

    @Override
    public User commit() {
        L.d("commit");
        if (SDKCore.instance == null) {
            L.e("[UserEditorImpl] Countly is not initialized");
            return null;
        }

        if (SDKCore.instance != null && SDKCore.instance.config.isBackendModeEnabled()) {
            L.w("commit: Skipping user detail, backend mode is enabled!");
            return null;
        }

        try {
            final JSONObject changes = new JSONObject();

            perform(changes);

            Storage.push(SDKCore.instance.config, user);

            ModuleRequests.injectParams(SDKCore.instance.config, params -> {
                if (changes.has(PICTURE_PATH)) {
                    try {
                        params.add(PICTURE_PATH, changes.getString(PICTURE_PATH));
                        changes.remove(PICTURE_PATH);
                    } catch (JSONException e) {
                        L.w("Won't send picturePath" + e);
                    }
                }
                if (changes.has(LOCALE) && user.locale != null) {
                    params.add("locale", user.locale);
                }
                if (changes.has(COUNTRY) && user.country != null) {
                    params.add("country_code", user.country);
                }
                if (changes.has(CITY) && user.city != null) {
                    params.add("city", user.city);
                }
                if (changes.has(LOCATION) && user.location != null) {
                    params.add("location", user.location);
                }
                params.add("user_details", changes.toString());
            });
        } catch (JSONException e) {
            L.e("[UserEditorImpl] Exception while committing changes to User profile" + e);
        }

        sets.clear();
        ops.clear();

        return user;
    }

    static class Op {
        static final String INC = "$inc";
        static final String MUL = "$mul";
        static final String MIN = "$min";
        static final String MAX = "$max";
        static final String SET_ONCE = "$setOnce";
        static final String PULL = "$pull";
        static final String PUSH = "$push";
        static final String PUSH_UNIQUE = "$addToSet";

        final String op;
        final String key;
        final Object value;

        Op(String op, String key, Object value) {
            this.op = op;
            this.key = key;
            this.value = value;
        }

        public void apply(JSONObject json) throws JSONException {
            JSONObject object;
            switch (op) {
                case INC:
                case MUL:
                    object = json.optJSONObject(key);
                    if (object == null) {
                        object = new JSONObject();
                    }
                    if (op.equals(INC)) {
                        int n = object.optInt(op, 0);
                        object.put(op, n + (int) value);
                    } else {
                        double n = object.optDouble(op, 1);
                        object.put(op, n * (double) value);
                    }
                    json.put(key, object);
                    break;
                case MIN:
                case MAX:
                    object = json.optJSONObject(key);
                    if (object == null) {
                        object = new JSONObject();
                    }
                    if (object.has(op)) {
                        object.put(op, op.equals(MIN) ? Math.min(object.getDouble(op), (Double) value) : Math.max(object.getDouble(op), (Double) value));
                    } else {
                        object.put(op, value);
                    }
                    json.put(key, object);
                    break;
                case SET_ONCE:
                    object = json.optJSONObject(key);
                    if (object == null) {
                        object = new JSONObject();
                    }
                    object.put(op, value);
                    json.put(key, object);
                    break;
                case PULL:
                case PUSH:
                case PUSH_UNIQUE:
                    object = json.optJSONObject(key);
                    if (object == null) {
                        object = new JSONObject();
                    }
                    object.accumulate(op, value);
                    json.put(key, object);
                    break;
            }
        }
    }
}
