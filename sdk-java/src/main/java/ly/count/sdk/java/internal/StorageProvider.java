package ly.count.sdk.java.internal;

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
    DeviceIdType getDeviceIdStrategy();

    /**
     * Set device ID strategy
     *
     * @param deviceIdType device ID strategy
     */
    void setDeviceIdStrategy(DeviceIdType deviceIdType);
}
