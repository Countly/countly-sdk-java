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

    /**
     * Persist the most-recently-validated SDK behavior settings JSON.
     *
     * @param config serialized configuration object, or {@code null} to clear
     */
    void setServerConfig(String config);

    /**
     * Read the persisted SDK behavior settings JSON.
     *
     * @return serialized configuration, or {@code null} if none persisted yet
     */
    String getServerConfig();

    /**
     * Read the stored health check counters.
     *
     * @return the counters as JSON text, {@code null} or empty when none are stored
     */
    String getHealthCheckState();

    /**
     * Store the health check counters.
     *
     * @param state the counters as JSON text, empty to clear them
     */
    void setHealthCheckState(String state);

    /**
     * Add one automatically detected crash to the count the next start reports in the health check.
     * Written right away, without the health check counters, as it runs while the crash is reported.
     */
    void incrementAutomaticCrashCount();

    /**
     * Read the automatically detected crashes counted since the last call, and set the stored count back to 0.
     *
     * @return the stored count of automatically detected crashes
     */
    int takeAutomaticCrashCount();
}
