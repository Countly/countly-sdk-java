package ly.count.sdk.java.internal;

/**
 * Device ID Type
 */
public enum DeviceIdType {
    DEVELOPER_SUPPLIED, SDK_GENERATED;

    public static DeviceIdType fromInt(int deviceIdStrategy) {
        switch (deviceIdStrategy) {
            case 0:
                return SDK_GENERATED;
            case 10:
                return DEVELOPER_SUPPLIED;
            default:
                return null;
        }
    }

    public static int toInt(String deviceIdType) {
        if (deviceIdType.equals("DEVELOPER_SUPPLIED")) {
            return 10;
        }
        return 0;
    }
}
