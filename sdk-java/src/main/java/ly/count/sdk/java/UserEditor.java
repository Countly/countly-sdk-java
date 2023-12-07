package ly.count.sdk.java;

import ly.count.sdk.java.internal.ModuleLocation;
import ly.count.sdk.java.internal.ModuleUserProfile;

/**
 * Editor object for {@link User} modifications. Changes applied only after {@link #commit()} call.
 *
 * @deprecated All functions of this class are deprecated, please use {@link Countly#userProfile()} instead via "instance()" call
 */
public interface UserEditor {
    /**
     * Sets property of user profile to the value supplied. All standard Countly properties
     * like name, username, etc. (see {@link User}) are detected by key and put into standard
     * profile properties, others are put into custom property:
     * {name: "John", username: "johnsnow", custom: {lord: true, kingdom: "North", dead: false}}
     *
     * @param key name of user profile property
     * @param value value for this property, null to delete property
     * @return this instance for method chaining
     * @see User
     * @deprecated use {@link ModuleUserProfile.UserProfile#setProperty(String, Object)} instead
     */
    UserEditor set(String key, Object value);

    /**
     * Sets custom property of user profile to the value supplied.
     *
     * @param key name of user profile property
     * @param value value for this property, null to delete property
     * @return this instance for method chaining
     * @deprecated use {@link ModuleUserProfile.UserProfile#setProperty(String, Object)} instead
     */
    UserEditor setCustom(String key, Object value);

    /**
     * Sets name of the user
     *
     * @param value name of the user
     * @return this instance for method chaining
     * @deprecated use {@link ModuleUserProfile.UserProfile#setProperty(String, Object)} instead
     */
    UserEditor setName(String value);

    /**
     * Sets username of the user
     *
     * @param value username of the user
     * @return this instance for method chaining
     * @deprecated use {@link ModuleUserProfile.UserProfile#setProperty(String, Object)} instead
     */
    UserEditor setUsername(String value);

    /**
     * Sets email of the user
     *
     * @param value email of the user
     * @return this instance for method chaining
     * @deprecated use {@link ModuleUserProfile.UserProfile#setProperty(String, Object)} instead
     */
    UserEditor setEmail(String value);

    /**
     * Sets org of the user
     *
     * @param value org of the user
     * @return this instance for method chaining
     * @deprecated use {@link ModuleUserProfile.UserProfile#setProperty(String, Object)} instead
     */
    UserEditor setOrg(String value);

    /**
     * Sets phone of the user
     *
     * @param value phone of the user
     * @return this instance for method chaining
     * @deprecated use {@link ModuleUserProfile.UserProfile#setProperty(String, Object)} instead
     */
    UserEditor setPhone(String value);

    /**
     * Sets picture of the user
     *
     * @param picture picture of the user
     * @return this instance for method chaining
     * @deprecated and this function will do nothing
     */
    UserEditor setPicture(byte[] picture);

    /**
     * Sets picture of the user
     *
     * @param picturePath picture of the user
     * @return this instance for method chaining
     * @deprecated use {@link ModuleUserProfile.UserProfile#setProperty(String, Object)} instead
     */
    UserEditor setPicturePath(String picturePath);

    /**
     * Sets gender of the user
     *
     * @param gender of the user
     * @return this instance for method chaining
     * @deprecated use {@link ModuleUserProfile.UserProfile#setProperty(String, Object)} instead
     */
    UserEditor setGender(Object gender);

    /**
     * Sets birthyear of the user
     *
     * @param birthyear of the user
     * @return this instance for method chaining
     * @deprecated use {@link ModuleUserProfile.UserProfile#setProperty(String, Object)} instead
     */
    UserEditor setBirthyear(int birthyear);

    /**
     * Sets birthyear of the user
     *
     * @param birthyear of the user
     * @return this instance for method chaining
     * @deprecated use {@link ModuleUserProfile.UserProfile#setProperty(String, Object)} instead
     */
    UserEditor setBirthyear(String birthyear);

    /**
     * Sets locale of the user
     *
     * @param locale of the user
     * @return this instance for method chaining
     * @deprecated and this function will do nothing
     */
    UserEditor setLocale(String locale);

    /**
     * Sets country of the user
     *
     * @param country of the user
     * @return this instance for method chaining
     * @deprecated use {@link ModuleLocation.Location#setLocation(String, String, String, String)} instead
     */
    UserEditor setCountry(String country);

    /**
     * Sets city of the user
     *
     * @param city of the user
     * @return this instance for method chaining
     * @deprecated use {@link ModuleLocation.Location#setLocation(String, String, String, String)} instead
     */
    UserEditor setCity(String city);

    /**
     * Sets location of the user
     *
     * @param location of the user
     * @return this instance for method chaining
     * @deprecated use {@link ModuleLocation.Location#setLocation(String, String, String, String)} instead
     */
    UserEditor setLocation(String location);

    /**
     * Sets location of the user
     *
     * @param latitude of the user
     * @param longitude of the user
     * @return this instance for method chaining
     * @deprecated use {@link ModuleLocation.Location#setLocation(String, String, String, String)} instead
     */
    UserEditor setLocation(double latitude, double longitude);

    /**
     * Clears location values from the user
     *
     * @return this instance for method chaining
     * @deprecated use {@link ModuleLocation.Location#disableLocation()} instead
     */
    UserEditor optOutFromLocationServices();

    /**
     * Increments a user profile property
     *
     * @return UserEditor instance to chain calls
     * @deprecated use {@link ModuleUserProfile.UserProfile#incrementBy(String, int)} instead
     */
    UserEditor inc(String key, int by);

    /**
     * Set a user profile property for the multiplied value
     *
     * @return UserEditor instance to chain calls
     * @deprecated use {@link ModuleUserProfile.UserProfile#multiply(String, double)} instead
     */
    UserEditor mul(String key, double by);

    /**
     * Set a user profile property for the min value
     *
     * @return UserEditor instance to chain calls
     * @deprecated use {@link ModuleUserProfile.UserProfile#saveMin(String, double)} instead
     */
    UserEditor min(String key, double value);

    /**
     * Set a user profile property for the max value
     *
     * @return UserEditor instance to chain calls
     * @deprecated use {@link ModuleUserProfile.UserProfile#saveMax(String, double)} instead
     */
    UserEditor max(String key, double value);

    /**
     * Set a user profile property
     *
     * @return UserEditor instance to chain calls
     * @deprecated use {@link ModuleUserProfile.UserProfile#setOnce(String, Object)} instead
     */
    UserEditor setOnce(String key, Object value);

    /**
     * Pull a value from a user profile property
     *
     * @return UserEditor instance to chain calls
     * @deprecated use {@link ModuleUserProfile.UserProfile#pull(String, Object)} instead
     */
    UserEditor pull(String key, Object value);

    /**
     * Push a value to a user profile property
     *
     * @return UserEditor instance to chain calls
     * @deprecated use {@link ModuleUserProfile.UserProfile#push(String, Object)} instead
     */
    UserEditor push(String key, Object value);

    /**
     * Push a unique value to a user profile property
     *
     * @return UserEditor instance to chain calls
     * @deprecated use {@link ModuleUserProfile.UserProfile#pushUnique(String, Object)} instead
     */
    UserEditor pushUnique(String key, Object value);

    /**
     * Add user to cohort
     *
     * @param key name of the cohort
     * @return UserEditor instance to chain calls
     * @deprecated this will do nothing
     */
    UserEditor addToCohort(String key);

    /**
     * Remove user from cohort
     *
     * @param key name of the cohort
     * @return UserEditor instance to chain calls
     * @deprecated this will do nothing
     */
    UserEditor removeFromCohort(String key);

    /**
     * Sets birthyear of the user
     *
     * @return user class instance
     * @deprecated use {@link ModuleUserProfile.UserProfile#save()} instead
     */
    User commit();
}
