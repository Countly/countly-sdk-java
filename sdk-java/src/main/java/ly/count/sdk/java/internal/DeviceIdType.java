package ly.count.sdk.java.internal;

/**
 * Device ID Type
 */
public enum DeviceIdType {
    DEVELOPER_SUPPLIED(0), SDK_GENERATED(10);

    public final int index;

    DeviceIdType(int index) {
        this.index = index;
    }

    public static DeviceIdType fromInt(int deviceIdType, Log L) {
        switch (deviceIdType) {
            case 0:
                return SDK_GENERATED;
            case 10:
                return DEVELOPER_SUPPLIED;
            default:
                L.e("DeviceIdType, provided int value is not recognized, defaulting to SDK_GENERATED, [" + deviceIdType + "]");
                return SDK_GENERATED;
        }
    }

    public static int toInt(String deviceIdType) {
        if (deviceIdType.equals("DEVELOPER_SUPPLIED")) {
            return 10;
        }
        return 0;
    }
}
