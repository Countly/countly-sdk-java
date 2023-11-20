package ly.count.sdk.java.internal;

import javax.annotation.Nullable;
import ly.count.sdk.java.Countly;

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
        sendLocation(true);
    }

    void sendLocation(boolean locationDisabled) {
        L.d("[ModuleLocation] Calling 'sendLocation'");
        //TODO when session module added, add sending location with begin session request
        Params params = prepareLocationParams(locationDisabled);
        ModuleRequests.pushAsync(internalConfig, new Request(params), true, null);
    }

    void setLocationInternal(@Nullable String countryCode, @Nullable String city, @Nullable String gpsCoordinates, @Nullable String ipAddress) {
        L.d("[ModuleLocation] setLocationInternal, Setting location parameters, cc[" + countryCode + "] cy[" + city + "] gps[" + gpsCoordinates + "] ip[" + ipAddress + "]");

        if ((countryCode == null && city != null) || (city == null && countryCode != null)) {
            L.w("[ModuleLocation] setLocationInternal, both city and country code need to be set at the same time to be sent");
        }
        country = countryCode;
        this.city = city;
        location = gpsCoordinates;
        ip = ipAddress;

        if (countryCode != null || city != null || gpsCoordinates != null || ipAddress != null) {
            locationDisabled = false;
        }

        sendLocation(locationDisabled);
    }

    private Params prepareLocationParams(boolean locationDisabled) {
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

    private void saveLocationToParamsLegacyInternal(Params params) {
        if (countryLegacy != null) {
            params.add("country_code", countryLegacy);
        }
        if (cityLegacy != null) {
            params.add("city", cityLegacy);
        }
        if (locationLegacy != null) {
            params.add("location", locationLegacy);
        }
        countryLegacy = null;
        cityLegacy = null;
        locationLegacy = null;
    }

    public class Location {

        protected void setLocationLegacy(@Nullable Object countryCode, @Nullable Object city, @Nullable Object gpsCoordinates) {
            L.d("[Location] setLocationLegacy, calling legacy calls to send locations");
            if (countryCode != null) {
                countryLegacy = countryCode.toString();
            }
            if (city != null) {
                cityLegacy = city.toString();
            }
            if (gpsCoordinates != null) {
                locationLegacy = gpsCoordinates.toString();
            }
        }

        protected void saveLocationToParamsLegacy(Params params) {
            L.d("[Location] saveLocationToParamsLegacy, calling legacy calls to send locations");
            saveLocationToParamsLegacyInternal(params);
        }

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
