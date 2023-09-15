package ly.count.sdk.java.internal;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private final Map<String, String> metricOverride = new HashMap<>();

    protected Device() {
        dev = this;
    }

    public void setLog(Log L) {
        this.L = L;
    }

    /**
     * One second in nanoseconds
     */
    protected static final Double NS_IN_SECOND = 1000000000.0d;
    protected static final Double NS_IN_MS = 1000000.0d;
    protected static final Double MS_IN_SECOND = 1000d;
    protected static final Long BYTES_IN_MB = 1024L * 1024;

    /**
     * General interface for time generators.
     */
    public interface TimeGenerator {
        long timestamp();
    }

    protected final TimeGenerator uniqueTimer = new UniqueTimeGenerator();
    protected final TimeGenerator uniformTimer = new UniformTimeGenerator();

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
        return TimeZone.getDefault().getOffset(new Date().getTime()) / 60000;
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
        if (metricOverride != null) {
            for (String k : metricOverride.keySet()) {
                if (k == null || k.length() == 0) {
                    //L.w("Provided metric override key can't be null or empty");//todo add log
                    continue;
                }

                String overrideValue = metricOverride.get(k);

                if (overrideValue == null) {
                    //L.w("Provided metric override value can't be null, key:[" + k + "]");//todo add log
                    continue;
                }

                metricObj.put(k, overrideValue);
            }
        }

        //add the object after adding the overrides
        metricObj.add();

        return params;
    }

    /**
     * Wraps {@link System#currentTimeMillis()} to always return different value, even within
     * same millisecond and even when time changes. Works in a limited window of 10 timestamps for now.
     *
     * @return unique time in ms
     */
    public synchronized long uniqueTimestamp() {
        return uniqueTimer.timestamp();
    }

    /**
     * Wraps {@link System#currentTimeMillis()} to return always rising values.
     * Resolves issue with device time updates via NTP or manually where time must go up.
     *
     * @return uniform time in ms
     */
    public synchronized long uniformTimestamp() {
        return uniformTimer.timestamp();
    }

    /**
     * Get current day of week
     *
     * @return day of week value, Sunday = 0, Saturday = 6
     */
    public int currentDayOfWeek() {
        int day = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
        switch (day) {
            case Calendar.SUNDAY:
                return 0;
            case Calendar.MONDAY:
                return 1;
            case Calendar.TUESDAY:
                return 2;
            case Calendar.WEDNESDAY:
                return 3;
            case Calendar.THURSDAY:
                return 4;
            case Calendar.FRIDAY:
                return 5;
            case Calendar.SATURDAY:
                return 6;
        }
        return 0;
    }

    /**
     * Get current hour of day
     *
     * @return current hour of day
     */
    public int currentHour() {
        return Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
    }

    public int getHourFromCalendar(Calendar calendar) {
        return calendar.get(Calendar.HOUR_OF_DAY);
    }

    /**
     * Convert time in nanoseconds to milliseconds
     *
     * @param ns time in nanoseconds
     * @return ns in milliseconds
     */
    public long nsToMs(long ns) {
        return Math.round(ns / NS_IN_MS);
    }

    /**
     * Convert time in nanoseconds to seconds
     *
     * @param ns time in nanoseconds
     * @return ns in seconds
     */
    public long nsToSec(long ns) {
        return Math.round(ns / NS_IN_SECOND);
    }

    /**
     * Convert time in seconds to nanoseconds
     *
     * @param sec time in seconds
     * @return sec in nanoseconds
     */
    public long secToNs(long sec) {
        return Math.round(sec * NS_IN_SECOND);
    }

    /**
     * Convert time in seconds to milliseconds
     *
     * @param sec time in seconds
     * @return sec in nanoseconds
     */
    public long secToMs(long sec) {
        return Math.round(sec * MS_IN_SECOND);
    }

    /**
     * Get total RAM in Mb
     *
     * @return total RAM in Mb or null if cannot determine
     */
    public Long getRAMTotal() {
        RandomAccessFile reader = null;
        try {
            reader = new RandomAccessFile("/proc/meminfo", "r");
            String load = reader.readLine();

            // Get the Number value from the string
            Pattern p = Pattern.compile("(\\d+)");
            Matcher m = p.matcher(load);
            String value = "";
            while (m.find()) {
                value = m.group(1);
            }
            return Long.parseLong(value) / 1024;
        } catch (NumberFormatException e) {
            if (L != null) {
                L.e("[DeviceCore] Cannot parse meminfo " + e.toString());
            }

            return null;
        } catch (IOException e) {
            if (L != null) {
                L.e("[DeviceCore] Cannot read meminfo " + e.toString());
            }
            return null;
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Get current device RAM amount.
     *
     * @return currently available RAM in Mb or {@code null} if couldn't determine
     */
    public Long getRAMAvailable() {
        Long total = Runtime.getRuntime().totalMemory();
        Long availMem = Runtime.getRuntime().freeMemory();
        return (total - availMem) / BYTES_IN_MB;
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

    public Device setMetricOverride(Map<String, String> givenMetricOverride) {
        metricOverride.putAll(givenMetricOverride);
        return this;
    }
}
