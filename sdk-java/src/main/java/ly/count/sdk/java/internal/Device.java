package ly.count.sdk.java.internal;

import com.sun.management.OperatingSystemMXBean;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Class encapsulating most of device-specific logic: metrics, info, etc.
 */

public class Device {
    public static Device dev = new Device();

    private String device;
    private String resolution;
    private String appVersion;
    private String manufacturer;
    private String cpu;
    private String openGL;
    private Float batteryLevel;
    private String orientation;
    private Boolean online;
    private Boolean muted;
    private Log L;

    /**
     * One second in nanoseconds
     */
    protected static final Double NS_IN_SECOND = 1_000_000_000.0d;
    protected static final Double NS_IN_MS = 1_000_000.0d;
    protected static final Double MS_IN_SECOND = 1000d;
    protected static final Long BYTES_IN_MB = 1024L * 1024;

    private final Map<String, String> metricOverride = new ConcurrentHashMap<>();

    protected Device() {
        dev = this;
    }

    public void setLog(Log L) {
        this.L = L;
    }

    /**
     * Get operation system name
     *
     * @return the display name of the current operating system.
     */
    public String getOS() {
        return System.getProperty("os.name");
    }

    /**
     * Get Android version
     *
     * @return current operating system version as a displayable string.
     */
    public String getOSVersion() {
        return System.getProperty("os.version", "n/a");
        //                + " / " + System.getProperty("os.arch", "n/a");
    }

    /**
     * Get device timezone offset in seconds
     *
     * @return timezone offset in seconds
     */
    public int getTimezoneOffset() {
        return TimeZone.getDefault().getOffset(new Date().getTime()) / 60_000;
    }

    /**
     * Get current locale
     *
     * @return current locale (ex. "en_US").
     */
    public String getLocale() {
        final Locale locale = Locale.getDefault();
        return locale.getLanguage() + "_" + locale.getCountry();
    }

    /**
     * Build metrics {@link Params} object as required by Countly server
     */
    public Params buildMetrics() {
        Params params = new Params();
        Params.Obj metricObj = params.obj("metrics")
            .put("_device", getDevice())
            .put("_os", getOS())
            .put("_os_version", getOSVersion())
            .put("_resolution", getResolution())
            .put("_locale", getLocale())
            .put("_app_version", getAppVersion());

        //override metric values
        for (String k : metricOverride.keySet()) {
            if (k == null || k.isEmpty()) {
                L.w("Provided metric override key can't be null or empty");
                continue;
            }

            String overrideValue = metricOverride.get(k);

            if (overrideValue == null) {
                L.w("Provided metric override value can't be null, key:[" + k + "]");
                continue;
            }

            metricObj.put(k, overrideValue);
        }

        //add the object after adding the overrides
        metricObj.add();

        return params;
    }

    /**
     * Get total RAM in Mb
     *
     * @return total RAM in Mb
     */
    public Long getRAMTotal() {
        OperatingSystemMXBean osMxBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        long totalPhysicalMemorySize = osMxBean.getTotalPhysicalMemorySize();
        return totalPhysicalMemorySize / BYTES_IN_MB; // Convert bytes to megabytes
    }

    /**
     * Get current device RAM amount.
     *
     * @return currently available RAM in Mb
     */
    public Long getRAMAvailable() {
        OperatingSystemMXBean osMxBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        long freePhysicalMemorySize = osMxBean.getFreePhysicalMemorySize();
        return freePhysicalMemorySize / BYTES_IN_MB; // Convert bytes to megabytes
    }

    /**
     * Get current device disk space.
     *
     * @return currently available disk space in Mb
     */
    public Long getDiskAvailable() {
        long total = 0, free = 0;
        for (File f : File.listRoots()) {
            total += f.getTotalSpace();
            free += f.getUsableSpace();
        }
        return (total - free) / BYTES_IN_MB;
    }

    /**
     * Get total device disk space.
     *
     * @return total device disk space in Mb
     */
    public Long getDiskTotal() {
        long total = 0;
        for (File f : File.listRoots()) {
            total += f.getTotalSpace();
        }
        return total / BYTES_IN_MB;
    }

    /**
     * Return device name stored by {@link #setDevice(String)}
     *
     * @return String
     */
    public String getDevice() {
        return device;
    }

    /**
     * Set device name
     *
     * @return this instance for method chaining
     */
    public Device setDevice(String device) {
        this.device = device;
        return this;
    }

    /**
     * Return resolution stored by {@link #setResolution(String)}
     *
     * @return String
     */
    public String getResolution() {
        return resolution;
    }

    /**
     * Set device resolution
     *
     * @return this instance for method chaining
     */
    public Device setResolution(String resolution) {
        this.resolution = resolution;
        return this;
    }

    /**
     * Return app version stored by {@link #setAppVersion(String)}
     *
     * @return String
     */
    public String getAppVersion() {
        return appVersion;
    }

    /**
     * Set app version
     *
     * @return this instance for method chaining
     */
    public Device setAppVersion(String appVersion) {
        this.appVersion = appVersion;
        return this;
    }

    /**
     * Return device manufacturer stored by {@link #setManufacturer(String)}
     *
     * @return String
     */
    public String getManufacturer() {
        return manufacturer;
    }

    /**
     * Set device manufacturer
     *
     * @return this instance for method chaining
     */
    public Device setManufacturer(String manufacturer) {
        this.manufacturer = manufacturer;
        return this;
    }

    /**
     * Return CPU name stored by {@link #setCpu(String)}
     *
     * @return String
     */
    public String getCpu() {
        return cpu;
    }

    /**
     * Set CPU name
     *
     * @return this instance for method chaining
     */
    public Device setCpu(String cpu) {
        this.cpu = cpu;
        return this;
    }

    /**
     * Return OpenGL version stored by {@link #setOpenGL(String)}
     *
     * @return String
     */
    public String getOpenGL() {
        return openGL;
    }

    /**
     * Set OpenGL version
     *
     * @return this instance for method chaining
     */
    public Device setOpenGL(String openGL) {
        this.openGL = openGL;
        return this;
    }

    /**
     * Return battery level stored by {@link #setBatteryLevel(Float)}
     *
     * @return Float
     */
    public Float getBatteryLevel() {
        return batteryLevel;
    }

    /**
     * Set battery level (0 .. 1)
     *
     * @return this instance for method chaining
     */
    public Device setBatteryLevel(Float batteryLevel) {
        this.batteryLevel = batteryLevel;
        return this;
    }

    /**
     * Return device orientation stored by {@link #setOrientation(String)}
     *
     * @return String
     */
    public String getOrientation() {
        return orientation;
    }

    /**
     * Set device orientation
     *
     * @return this instance for method chaining
     */
    public Device setOrientation(String orientation) {
        this.orientation = orientation;
        return this;
    }

    /**
     * Return whether the device is online stored by {@link #setDevice(String)}
     *
     * @return Boolean
     */
    public Boolean isOnline() {
        return online;
    }

    /**
     * Set whether the device is online
     *
     * @return this instance for method chaining
     */
    public Device setOnline(Boolean online) {
        this.online = online;
        return this;
    }

    /**
     * Return whether the device is muted stored by {@link #setDevice(String)}
     *
     * @return Boolean
     */
    public Boolean isMuted() {
        return muted;
    }

    /**
     * Set whether the device is muted
     *
     * @return this instance for method chaining
     */
    public Device setMuted(Boolean muted) {
        this.muted = muted;
        return this;
    }

    /**
     * Set metric override value
     *
     * @param givenMetricOverride key value pair of metric override
     * @return this instance for method chaining
     */
    public @Nonnull Device setMetricOverride(@Nullable Map<String, String> givenMetricOverride) {
        if (givenMetricOverride != null) {
            metricOverride.putAll(givenMetricOverride);
        } else {
            L.w("[Device] setMetricOverride, provided metric override is 'null'");
        }
        return this;
    }
}
