package ly.count.sdk.java.internal;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import ly.count.sdk.java.Countly;
import org.json.JSONObject;

public class ModuleLocation extends ModuleBase {
    boolean locationDisabled = false;
    Location locationInterface;
    String country;
    String city;
    String location;
    String ip;

    String countryLegacy;
    String cityLegacy;
    String locationLegacy;

    ModuleLocation() {
        locationInterface = new Location();
    }

    @Override
    public void init(InternalConfig internalConfig) {
        super.init(internalConfig);
        locationInterface = new Location();
    }

    void disableLocationInternal() {
        L.d("[ModuleLocation] Calling 'disableLocationInternal'");
        country = null;
        city = null;
        location = null;
        ip = null;
        locationDisabled = true;
        sendLocation();
    }

    void sendLocation() {
        L.d("[ModuleLocation] Calling 'sendLocation'");
        SessionImpl session = internalConfig.sdk.getSession();
        if (session == null || session.getBegan() != null || !isLocationSentWithSessionBegin()) {
            ModuleRequests.pushAsync(internalConfig, new Request(prepareLocationParams()), true, null);
        } // else case, values are added to the session begin request
    }

    /**
     * Whether a session that has not begun yet sends the location with its begin request, which the
     * SDK behavior settings allow only while both session tracking and location tracking are enabled.
     *
     * @return {@code true} when the location can wait for the begin request
     */
    private boolean isLocationSentWithSessionBegin() {
        ConfigurationProvider configProvider = internalConfig.getConfigurationProvider();
        return configProvider.getSessionTrackingEnabled() && configProvider.getLocationTrackingEnabled();
    }

    void setLocationInternal(@Nullable String countryCode, @Nullable String cityName, @Nullable String gpsCoordinates, @Nullable String ipAddress) {
        ConfigurationProvider configProvider = internalConfig.getConfigurationProvider();
        if (!configProvider.getTrackingEnabled() || !configProvider.getLocationTrackingEnabled()) {
            L.d("[ModuleLocation] setLocationInternal, location tracking disabled by SDK behavior settings; ignoring");
            return;
        }
        L.d("[ModuleLocation] setLocationInternal, Setting location parameters, cc[" + countryCode + "] cy[" + city + "] gps[" + gpsCoordinates + "] ip[" + ipAddress + "]");

        if (countryCode != null ^ city != null) {
            L.w("[ModuleLocation] setLocationInternal, both city and country code need to be set at the same time to be sent");
        }
        country = countryCode;
        city = cityName;
        location = gpsCoordinates;
        ip = ipAddress;

        if (countryCode != null || city != null || gpsCoordinates != null || ipAddress != null) {
            locationDisabled = false;
        }
        sendLocation();
    }

    Params prepareLocationParams() {
        Params params = new Params();

        if (locationDisabled) {
            //if location is disabled or consent not given, send empty location info
            //this way it is cleared server side and geoip is not used
            //do this only if allowed
            params.add("location", "");
        } else {
            //if we get here, location consent was given
            //location should be sent, add all the fields we have
            if (!Utils.isEmptyOrNull(location)) {
                params.add("location", location);
            }
            if (!Utils.isEmptyOrNull(city)) {
                params.add("city", city);
            }
            if (!Utils.isEmptyOrNull(country)) {
                params.add("country_code", country);
            }
            if (!Utils.isEmptyOrNull(ip)) {
                params.add("ip", ip);
            }
        }
        return params;
    }

    @Override
    public void stop(InternalConfig internalConfig, boolean clear) {
        locationInterface = null;
    }

    @Override
    public void initFinished(@Nonnull InternalConfig config) {
        if (config.isLocationDisabled()) {
            //disable location if needed, unless a settings response during init already did and sent the erase request
            if (!locationDisabled) {
                disableLocationInternal();
            }
        } else {
            //if we are not disabling location, check for other set values
            String[] locParams = config.getLocationParams(); // country, city, location, ip
            if (locParams[3] != null || locParams[2] != null || locParams[1] != null || locParams[0] != null) {
                setLocationInternal(locParams[0], locParams[1], locParams[2], locParams[3]);
            }
        }
    }

    /**
     * Disables location when a server response turned location tracking off, which erases the
     * location stored on the server as {@link Location#disableLocation()} does, and drops the
     * location values held for the next user details request.
     *
     * @param config configuration of the running SDK
     */
    @Override
    protected void onSdkConfigurationChanged(InternalConfig config) {
        synchronized (Countly.instance()) {
            if (config.getConfigurationProvider().getLocationTrackingEnabled()) {
                return;
            }
            dropDisallowedLegacyValues();
            if (!locationDisabled) {
                L.d("[ModuleLocation] onSdkConfigurationChanged, location tracking was disabled by the SDK behavior settings, disabling location");
                disableLocationInternal();
            }
        }
    }

    protected void saveLocationToParamsLegacy(Params params) {
        if (isLegacyValueAllowed(countryLegacy)) {
            params.add("country_code", countryLegacy);
        }
        if (isLegacyValueAllowed(cityLegacy)) {
            params.add("city", cityLegacy);
        }
        if (isLegacyValueAllowed(locationLegacy)) {
            params.add("location", locationLegacy);
        }
        countryLegacy = null;
        cityLegacy = null;
        locationLegacy = null;
    }

    protected void setLocationLegacy(@Nullable Object countryCode, @Nullable Object city, @Nullable Object gpsCoordinates) {
        L.d("[Location] setLocationLegacy, calling legacy calls to send locations");
        if (isLegacyValueAllowed(countryCode)) {
            countryLegacy = countryCode.toString();
        }
        if (isLegacyValueAllowed(city)) {
            cityLegacy = city.toString();
        }
        if (isLegacyValueAllowed(gpsCoordinates)) {
            locationLegacy = gpsCoordinates.toString();
        }
    }

    /**
     * Drops the held deprecated location values that may not be sent while location tracking is off,
     * so a value set before the SDK behavior settings turned location tracking off is not sent after
     * they turn it on again.
     */
    private void dropDisallowedLegacyValues() {
        if (!isLegacyValueAllowed(countryLegacy)) {
            countryLegacy = null;
        }
        if (!isLegacyValueAllowed(cityLegacy)) {
            cityLegacy = null;
        }
        if (!isLegacyValueAllowed(locationLegacy)) {
            locationLegacy = null;
        }
    }

    /**
     * Whether a deprecated location value can be held and sent with the user details. While the SDK
     * behavior settings disable location tracking, only the JSON null that opts out of location
     * services can, as it erases a location instead of tracking one.
     *
     * @param value the value, or {@code null} when none is set
     * @return {@code true} when the value is set and can be sent
     */
    private boolean isLegacyValueAllowed(@Nullable Object value) {
        if (value == null) {
            return false;
        }
        if (JSONObject.NULL.toString().equals(value.toString()) || internalConfig.getConfigurationProvider().getLocationTrackingEnabled()) {
            return true;
        }
        L.d("[ModuleLocation] isLegacyValueAllowed, location tracking disabled by SDK behavior settings; dropping the location value");
        return false;
    }

    public class Location {

        /**
         * Disable sending of location data. Erases server side saved location information
         */
        public void disableLocation() {
            synchronized (Countly.instance()) {
                L.i("[Location] Calling 'disableLocation'");

                disableLocationInternal();
            }
        }

        /**
         * Set location parameters. If they are set before begin_session, they will be sent as part of it.
         * If they are set after, then they will be sent as a separate request.
         * If this is called after disabling location, it will enable it.
         *
         * @param countryCode ISO Country code for the user's country
         * @param city Name of the user's city
         * @param gpsCoordinates comma separate lat and lng values. For example, "56.42345,123.45325"
         * @param ipAddress ipAddress like "192.168.88.33"
         */
        public void setLocation(@Nullable String countryCode, @Nullable String city, @Nullable String gpsCoordinates, @Nullable String ipAddress) {
            synchronized (Countly.instance()) {
                L.i("[Location] Calling 'setLocation'");

                setLocationInternal(countryCode, city, gpsCoordinates, ipAddress);
            }
        }
    }
}
