package ly.count.sdk.java.internal;

import java.io.File;
import ly.count.sdk.java.Countly;
import ly.count.sdk.java.PredefinedUserPropertyKeys;
import ly.count.sdk.java.User;
import ly.count.sdk.java.UserEditor;
import org.json.JSONException;
import org.json.JSONObject;

public class UserEditorImpl implements UserEditor {
    private final Log L;

    private final UserImpl user;

    UserEditorImpl(UserImpl user, Log logger) {
        this.L = logger;
        this.user = user;
    }

    @Override
    public UserEditor set(String key, Object value) {
        Countly.instance().userProfile().setProperty(key, value);
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public UserEditor setCustom(String key, Object value) {
        Countly.instance().userProfile().setProperty(key, value);
        return this;
    }

    @Override
    public UserEditor setName(String value) {
        L.d("setName: value = " + value);
        return set(PredefinedUserPropertyKeys.NAME, value);
    }

    @Override
    public UserEditor setUsername(String value) {
        L.d("setUsername: value = " + value);
        return set(PredefinedUserPropertyKeys.USERNAME, value);
    }

    @Override
    public UserEditor setEmail(String value) {
        L.d("setEmail: value = " + value);
        return set(PredefinedUserPropertyKeys.EMAIL, value);
    }

    @Override
    public UserEditor setOrg(String value) {
        L.d("setOrg: value = " + value);
        return set(PredefinedUserPropertyKeys.ORGANIZATION, value);
    }

    @Override
    public UserEditor setPhone(String value) {
        L.d("setPhone: value = " + value);
        return set(PredefinedUserPropertyKeys.PHONE, value);
    }

    //we set the bytes for the local picture
    @Override
    public UserEditor setPicture(byte[] picture) {
        L.d("setPicture: picture = " + picture);
        return set(PredefinedUserPropertyKeys.PICTURE, picture);
    }

    //we set the url for either the online picture or a local path picture
    @Override
    public UserEditor setPicturePath(String picturePath) {
        L.d("[UserEditorImpl] setPicturePath, picturePath = " + picturePath);
        if (picturePath == null || Utils.isValidURL(picturePath) || (new File(picturePath)).isFile()) {
            //if it is a thing we can use, continue
            return set(PredefinedUserPropertyKeys.PICTURE_PATH, picturePath);
        }
        L.w("[UserEditorImpl] setPicturePath, picturePath is not a valid file path or url");
        return this;
    }

    @Override
    public UserEditor setGender(Object gender) {
        L.d("setGender: gender = " + gender);
        return set(PredefinedUserPropertyKeys.GENDER, gender);
    }

    @Override
    public UserEditor setBirthyear(int birthyear) {
        L.d("setBirthyear: birthyear = " + birthyear);
        return set(PredefinedUserPropertyKeys.BIRTH_YEAR, birthyear);
    }

    @Override
    public UserEditor setBirthyear(String birthyear) {
        L.d("setBirthyear: birthyear = " + birthyear);
        return set(PredefinedUserPropertyKeys.BIRTH_YEAR, birthyear);
    }

    @Override
    public UserEditor setLocale(String locale) {
        L.d("setLocale: locale = " + locale);
        return this;
    }

    @Override
    public UserEditor setCountry(String country) {
        L.d("setCountry: country = " + country);
        String countryLegacy = country == null ? JSONObject.NULL.toString() : country;
        callLocationService(() -> SDKCore.instance.module(ModuleLocation.class).setLocationLegacy(countryLegacy, null, null));
        return this;
    }

    @Override
    public UserEditor setCity(String city) {
        L.d("setCity: city = " + city);
        String cityLegacy = city == null ? JSONObject.NULL.toString() : city;
        callLocationService(() -> SDKCore.instance.module(ModuleLocation.class).setLocationLegacy(null, cityLegacy, null));
        return this;
    }

    @Override
    public UserEditor setLocation(String location) {
        L.d("setLocation: location = " + location);
        if (location != null) {
            String[] comps = location.split(",");
            if (comps.length == 2) {
                try {
                    String locationString = Double.parseDouble(comps[0]) + "," + Double.parseDouble(comps[1]);
                    callLocationService(() -> SDKCore.instance.module(ModuleLocation.class).setLocationLegacy(null, null, locationString));
                    return this;
                } catch (Throwable t) {
                    L.e("[UserEditorImpl] Invalid location format: " + location + " " + t);
                    return this;
                }
            } else {
                L.e("[UserEditorImpl] Invalid location format: " + location);
                return this;
            }
        } else {
            callLocationService(() -> SDKCore.instance.module(ModuleLocation.class).setLocationLegacy(null, null, JSONObject.NULL));
            return this;
        }
    }

    @Override
    public UserEditor setLocation(double latitude, double longitude) {
        L.d("setLocation: latitude = " + latitude + " longitude" + longitude);
        String locationString = latitude + "," + longitude;
        callLocationService(() -> SDKCore.instance.module(ModuleLocation.class).setLocationLegacy(null, null, locationString));
        return this;
    }

    @Override
    public UserEditor optOutFromLocationServices() {
        L.d("optOutFromLocationServices");
        callLocationService(() -> SDKCore.instance.module(ModuleLocation.class).setLocationLegacy(JSONObject.NULL, JSONObject.NULL, JSONObject.NULL));
        return this;
    }

    private void callLocationService(Runnable runnable) {
        if (Countly.instance().location() != null) {
            runnable.run();
        }
    }

    @Override
    public UserEditor inc(String key, int by) {
        L.d("inc: key " + key + " by " + by);
        Countly.instance().userProfile().incrementBy(key, by);
        return this;
    }

    @Override
    public UserEditor mul(String key, double by) {
        L.d("mul: key " + key + " by " + by);
        Countly.instance().userProfile().multiply(key, by);
        return this;
    }

    /**
     * now value is mapped to int
     *
     * @param key
     * @param value
     * @return
     */
    @Override
    public UserEditor min(String key, double value) {
        L.d("min: key " + key + " value " + value);
        Countly.instance().userProfile().saveMin(key, value);
        return this;
    }

    @Override
    public UserEditor max(String key, double value) {
        L.d("max: key " + key + " value " + value);
        Countly.instance().userProfile().saveMax(key, value);
        return this;
    }

    @Override
    public UserEditor setOnce(String key, Object value) {
        L.d("setOnce: key " + key + " value " + value);
        if (value == null) {
            L.e("[UserEditorImpl] $setOnce operation operand cannot be null: key " + key);
            return this;
        } else {
            Countly.instance().userProfile().setOnce(key, value);
            return this;
        }
    }

    @Override
    public UserEditor pull(String key, Object value) {
        L.d("pull: key " + key + " value " + value);
        if (value == null) {
            L.e("[UserEditorImpl] $pull operation operand cannot be null: key " + key);
            return this;
        } else {
            Countly.instance().userProfile().pull(key, value);
            return this;
        }
    }

    @Override
    public UserEditor push(String key, Object value) {
        L.d("push: key " + key + " value " + value);
        if (value == null) {
            L.e("[UserEditorImpl] $push operation operand cannot be null: key " + key);
            return this;
        } else {
            Countly.instance().userProfile().push(key, value);
            return this;
        }
    }

    @Override
    public UserEditor pushUnique(String key, Object value) {
        L.d("pushUnique: key " + key + " value " + value);
        if (value == null) {
            L.e("[UserEditorImpl] pushUnique / $addToSet operation operand cannot be null: key " + key);
            return this;
        } else {
            Countly.instance().userProfile().pushUnique(key, value);
            return this;
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

        if (SDKCore.instance.config.isBackendModeEnabled()) {
            L.w("commit: Skipping user detail, backend mode is enabled!");
            return null;
        }

        try {
            Countly.instance().userProfile().save();
        } catch (JSONException e) {
            L.e("[UserEditorImpl] Exception while committing changes to User profile" + e);
        }
        return user;
    }
}
