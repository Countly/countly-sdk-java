package ly.count.sdk.java.internal;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import ly.count.sdk.java.Crash;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Crash-encapsulating class
 */

public class CrashImpl implements Crash, Storable {
    private final Log L;
    private Long id;
    private final JSONObject data;
    private Throwable throwable;
    private Map<Thread, StackTraceElement[]> traces;
    private Thread tracesMainThread;
    //whether "_error" holds the dump of addTraces rather than the stack trace of a throwable
    private boolean errorIsTraceDump = false;
    private String[] logs;

    protected CrashImpl(Log logger) {
        this(TimeUtils.uniqueTimestampMs(), logger);
    }

    protected CrashImpl(Long id, Log logger) {
        this.L = logger;
        this.id = id;
        this.data = new JSONObject();
        this.add("_nonfatal", true);
    }

    @Override
    public CrashImpl addThrowable(Throwable throwable) {
        this.throwable = throwable;
        this.errorIsTraceDump = false;

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        this.throwable.printStackTrace(pw);
        return add("_error", sw.toString());
    }

    @Override
    public CrashImpl addException(Exception e) {
        return addThrowable(e);
    }

    @Override
    public CrashImpl addTraces(Thread main, Map<Thread, StackTraceElement[]> traces) {
        if (traces == null) {
            L.e("[CrashImpl traces cannot be null");
            return this;
        } else {
            this.traces = traces;
            String dump = printAllTraces(main, traces, Integer.MAX_VALUE, "[CrashImpl] addTraces");
            this.tracesMainThread = main;
            this.errorIsTraceDump = true;
            return add("_type", "anr").add("_error", dump);
        }
    }

    /**
     * Prints the stack traces of every thread, the main thread first.
     *
     * @param main the main thread, {@code null} for none
     * @param threadTraces the stack trace of each thread
     * @param maxLinesPerThread how many of the top lines of each thread to print at most
     * @param tag the caller, for the log of a thread that has more lines
     * @return the printed stack traces
     */
    private String printAllTraces(Thread main, @Nonnull Map<Thread, StackTraceElement[]> threadTraces, int maxLinesPerThread, @Nonnull String tag) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);

        if (main != null && threadTraces.containsKey(main)) {
            pw.println("Thread [main]:");
            printTraces(pw, main, true, threadTraces.get(main), maxLinesPerThread, tag);
            pw.append("\n\n");
        }

        for (Thread thread : threadTraces.keySet()) {
            if (thread != main) {
                printTraces(pw, thread, false, threadTraces.get(thread), maxLinesPerThread, tag);
                pw.append("\n\n");
            }
        }
        return sw.toString();
    }

    /**
     * Prints the stack trace of one thread, under a header naming the thread unless it is the main one.
     *
     * @param pw where to print
     * @param thread the thread
     * @param mainThread whether it is the main thread, whose header is printed by the caller
     * @param traces the stack trace of the thread
     * @param maxLines how many of the top lines to print at most
     * @param tag the caller, for the log when lines are left out
     */
    private void printTraces(PrintWriter pw, Thread thread, boolean mainThread, StackTraceElement[] traces, int maxLines, @Nonnull String tag) {
        if (!mainThread && thread != null) {
            pw.append("Thread [").append(thread.getName()).append("]:\n");
        }
        int lines = UtilsInternalLimits.stackTraceLinesToKeep(traces.length, maxLines, thread == null ? null : thread.getName(), L, tag);
        for (int i = 0; i < lines; i++) {
            StackTraceElement el = traces[i];
            pw.append("\tat ").append(el == null ? "<<Unknown>>" : el.toString()).append("\n");
        }
    }

    @Override
    public CrashImpl setFatal(boolean fatal) {
        return add("_nonfatal", !fatal);
    }

    @Override
    public CrashImpl setName(String name) {
        return add("_name", name);
    }

    @Override
    public CrashImpl setSegments(Map<String, String> segments) {
        return addSegments(new HashMap<>(segments));
    }

    protected CrashImpl addSegments(Map<String, Object> segments) {
        if (segments != null && !segments.isEmpty()) {
            return add("_custom", new JSONObject(segments));
        }
        return this;
    }

    @Override
    public CrashImpl setLogs(String[] logs) {
        if (logs != null && logs.length > 0) {
            this.logs = logs.clone();
            return add("_logs", Utils.join(Arrays.asList(logs), "\n"));
        }
        return this;
    }

    /**
     * Applies the SDK internal limits of the SDK behavior settings to what this crash sends: the lines
     * per thread of a dump made by {@link #addTraces(Thread, Map)}, the length of every stack trace
     * line, the keys, string values and number of the custom segments and the length of every
     * breadcrumb. The stack trace of a throwable keeps all its lines. Nothing changes while no limit
     * is exceeded.
     *
     * @param limits the settings in effect
     * @param tag the caller, for the log
     */
    void applyInternalLimits(@Nonnull ConfigurationProvider limits, @Nonnull String tag) {
        int maxLinesPerThread = limits.getMaxStackTraceLinesPerThread();
        if (errorIsTraceDump && traceDumpExceeds(maxLinesPerThread)) {
            add("_error", printAllTraces(tracesMainThread, traces, maxLinesPerThread, tag));
        }

        String error = data.optString("_error", null);
        if (error != null) {
            String truncatedError = UtilsInternalLimits.truncateStackTraceLines(error, limits.getMaxStackTraceLineLength(), L, tag);
            if (truncatedError.length() != error.length()) {
                add("_error", truncatedError);
            }
        }

        JSONObject custom = data.optJSONObject("_custom");
        if (custom != null) {
            Map<String, Object> segments = new LinkedHashMap<>();
            for (String key : custom.keySet()) {
                segments.put(key, custom.opt(key));
            }
            Map<String, Object> limitedSegments = UtilsInternalLimits.applySegmentationLimits(segments, limits, L, tag);
            if (!segments.equals(limitedSegments)) {
                add("_custom", new JSONObject(limitedSegments));
            }
        }

        applyValueSizeLimitToLogs(limits.getMaxValueSize(), tag);
    }

    /**
     * Whether a thread of the dump made by {@link #addTraces(Thread, Map)} has more stack trace lines
     * than a limit.
     *
     * @param maxLinesPerThread the limit
     * @return {@code true} when a thread has more lines
     */
    private boolean traceDumpExceeds(int maxLinesPerThread) {
        for (StackTraceElement[] threadTraces : traces.values()) {
            if (threadTraces != null && threadTraces.length > maxLinesPerThread) {
                return true;
            }
        }
        return false;
    }

    /**
     * Cuts every breadcrumb given to {@link #setLogs(String[])} to the value size limit.
     *
     * @param maxValueSize the limit
     * @param tag the caller, for the log
     */
    private void applyValueSizeLimitToLogs(int maxValueSize, @Nonnull String tag) {
        if (logs == null) {
            return;
        }

        String[] truncatedLogs = new String[logs.length];
        boolean truncated = false;
        for (int i = 0; i < logs.length; i++) {
            truncatedLogs[i] = UtilsInternalLimits.truncateValue(logs[i], maxValueSize, L, tag);
            truncated |= logs[i] != null && truncatedLogs[i].length() != logs[i].length();
        }

        if (truncated) {
            setLogs(truncatedLogs);
        }
    }

    @Override
    public Throwable getThrowable() {
        return throwable;
    }

    @Override
    public Map<Thread, StackTraceElement[]> getTraces() {
        return traces;
    }

    @Override
    public boolean isFatal() {
        try {
            return !this.data.has("_nonfatal") || !this.data.getBoolean("_nonfatal");
        } catch (JSONException e) {
            return true;
        }
    }

    @Override
    public String getName() {
        try {
            return this.data.has("_name") ? this.data.getString("_name") : null;
        } catch (JSONException e) {
            return null;
        }
    }

    @Override
    public Map<String, String> getSegments() {
        try {
            if (!this.data.has("_custom")) {
                return null;
            }
            JSONObject object = this.data.getJSONObject("_custom");
            Map<String, String> map = new ConcurrentHashMap<>();
            Iterator<String> iterator = object.keys();
            while (iterator.hasNext()) {
                String key = iterator.next();
                map.put(key, object.getString(key));
            }
            return map;
        } catch (JSONException e) {
            return null;
        }
    }

    @Override
    public List<String> getLogs() {
        try {
            String logs = this.data.getString("_logs");
            return Utils.isEmptyOrNull(logs) ? null : Arrays.asList(logs.split("\n"));
        } catch (JSONException e) {
            return null;
        }
    }

    protected CrashImpl add(String key, Object value) {
        if (Utils.isNotEmpty(key) && value != null) {
            try {
                this.data.put(key, value);
            } catch (JSONException e) {
                L.e("[CrashImpl Couldn't add " + key + " to a crash" + e);
            }
        }
        return this;
    }

    @Override
    public byte[] store(Log L) {
        try {
            return data.toString().getBytes(Utils.UTF8);
        } catch (UnsupportedEncodingException e) {
            if (L != null) {
                L.e("[CrashImpl UTF is not supported" + e);
            }
            return null;
        }
    }

    public boolean restore(byte[] data, Log L) {
        try {
            String json = new String(data, Utils.UTF8);
            try {
                JSONObject obj = new JSONObject(json);
                Iterator<String> iterator = obj.keys();
                while (iterator.hasNext()) {
                    String k = iterator.next();
                    this.data.put(k, obj.get(k));
                }
            } catch (JSONException e) {
                if (L != null) {
                    L.e("[CrashImpl Couldn't decode crash data successfully" + e);
                }
            }
            return true;
        } catch (UnsupportedEncodingException e) {
            if (L != null) {
                L.e("[CrashImpl Cannot deserialize crash" + e);
            }
        }

        return false;
    }

    @Override
    public Long storageId() {
        return id;
    }

    @Override
    public String storagePrefix() {
        return getStoragePrefix();
    }

    @Override
    public void setId(Long id) {
        this.id = id;
    }

    public static String getStoragePrefix() {
        return "crash";
    }

    public CrashImpl putMetrics(Long runningTime) {
        add("_device", Device.dev.getDevice());
        add("_os", Device.dev.getOS());
        add("_os_version", Device.dev.getOSVersion());
        add("_resolution", Device.dev.getResolution());
        add("_app_version", Device.dev.getAppVersion());
        add("_manufacture", Device.dev.getManufacturer());
        add("_cpu", Device.dev.getCpu());
        add("_opengl", Device.dev.getOpenGL());
        add("_ram_current", Device.dev.getRAMAvailable());
        add("_ram_total", Device.dev.getRAMTotal());
        add("_disk_current", Device.dev.getDiskAvailable());
        add("_disk_total", Device.dev.getDiskTotal());
        add("_bat", Device.dev.getBatteryLevel());
        add("_run", runningTime);
        add("_orientation", Device.dev.getOrientation());
        add("_online", Device.dev.isOnline());
        add("_muted", Device.dev.isMuted());
        return this;
    }

    public String getJSON() {
        return data.toString();
    }

    public JSONObject getData() {
        return data;
    }

    @Override
    public String toString() {
        return data.toString();
    }
}
