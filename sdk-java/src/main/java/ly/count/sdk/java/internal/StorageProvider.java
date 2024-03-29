package ly.count.sdk.java.internal;

import org.json.JSONObject;

/**
 * Interface for storage provider
 */
public interface StorageProvider {

    /**
     * Get device ID
     *
     * @return device ID
     */
    String getDeviceID();

    /**
     * Set device ID
     *
     * @param deviceID device ID
     */
    void setDeviceID(String deviceID);

    /**
     * Get device ID strategy
     *
     * @return device ID strategy
     */
    String getDeviceIdType();

    /**
     * Set device ID strategy
     *
     * @param deviceIdTypeString device ID strategy
     */
    void setDeviceIdType(String deviceIdTypeString);

    /**
     * Set remote config values
     *
     * @param remoteConfigValues set of remote config values
     */
    void setRemoteConfigValues(JSONObject remoteConfigValues);

    /**
     * Get remote config values
     *
     * @return set of remote config values
     */
    JSONObject getRemoteConfigValues();

    /**
     * Get migration version
     *
     * @return migration version
     */
    Integer getMigrationVersion();

    /**
     * Set migration version
     *
     * @param migrationVersion migration version
     */
    void setMigrationVersion(Integer migrationVersion);

    /**
     * Check countly storage is empty or not
     *
     * @return true if empty, false otherwise
     */
    boolean isCountlyStorageEmpty();
}
