package ly.count.sdk.java.internal;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import ly.count.sdk.java.Crash;

/**
 * Crash-encapsulating class
 */

public class CrashImplCore implements Crash, Storable {
    private static final Log.Module L = Log.module("CrashImpl");

    private final Long id;
    private final JSONObject data;
    private Throwable throwable;
    private Map<Thread, StackTraceElement[]> traces;

    protected CrashImplCore() {
        this(DeviceCore.dev.uniformTimestamp());
    }

    protected CrashImplCore(Long id) {
        this.id = id;
        this.data = new JSONObject();
        this.add("_nonfatal", true);
    }

    @Override
    public CrashImplCore addThrowable(Throwable throwable) {
        if (throwable == null) {
            L.wtf("Throwable cannot be null");
            return this;
        } else {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            throwable.printStackTrace(pw);
            return add("_error", sw.toString());
        }
    }

    @Override
    public CrashImplCore addException(Exception e) {
        return addThrowable(e);
    }

    @Override
    public CrashImplCore addTraces(Thread main, Map<Thread, StackTraceElement[]> traces) {
        if (traces == null) {
            L.wtf("traces cannot be null");
            return this;
        } else {
            this.traces = traces;

            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);

            if (main != null && traces.containsKey(main)) {
                pw.println("Thread [main]:");
                printTraces(pw, null, traces.get(main));
                pw.append("\n\n");
            }

            for (Thread thread : traces.keySet()) {
                if (thread != main) {
                    printTraces(pw, thread, traces.get(thread));
                    pw.append("\n\n");
                }
            }
            return add("_type", "anr").add("_error", sw.toString());
        }
    }

    private void printTraces(PrintWriter pw, Thread thread, StackTraceElement[] traces) {
        if (thread != null) {
            pw.append("Thread [").append(thread.getName()).append("]:\n");
        }
        for (StackTraceElement el : traces) {
            pw.append("\tat ").append(el == null ? "<<Unknown>>" : el.toString()).append("\n");
        }
    }

    @Override
    public CrashImplCore setFatal(boolean fatal) {
        return add("_nonfatal", !fatal);
    }

    @Override
    public CrashImplCore setName(String name) {
        return add("_name", name);
    }

    @Override
    public CrashImplCore setSegments(Map<String, String> segments) {
        if (segments != null && segments.size() > 0) {
            return add("_custom", new JSONObject(segments));
        }
        return this;
    }

    @Override
    public CrashImplCore setLogs(String[] logs) {
        if (logs != null && logs.length > 0) {
            return add("_logs", Utils.join(Arrays.asList(logs), "\n"));
        }
        return this;
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
            Map<String, String> map = new HashMap<>();
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
            return Utils.isEmpty(logs) ? null : Arrays.asList(logs.split("\n"));
        } catch (JSONException e) {
            return null;
        }
    }

    protected CrashImplCore add(String key, Object value) {
        if (Utils.isNotEmpty(key) && value != null) {
            try {
                this.data.put(key, value);
            } catch (JSONException e) {
                L.wtf("Couldn't add " + key + " to a crash", e);
            }
        }
        return this;
    }

    @Override
    public byte[] store() {
        try {
            return data.toString().getBytes(Utils.UTF8);
        } catch (UnsupportedEncodingException e) {
            L.wtf("UTF is not supported", e);
            return null;
        }
    }

    public boolean restore(byte[] data) {
        try {
            String json = new String (data, Utils.UTF8);
            try {
                JSONObject obj = new JSONObject(json);
                Iterator<String> iterator = obj.keys();
                while (iterator.hasNext()) {
                    String k = iterator.next();
                    this.data.put(k, obj.get(k));
                }
            } catch (JSONException e) {
                L.e("Couldn't decode crash data successfully", e);
            }
            return true;
        } catch (UnsupportedEncodingException e) {
            L.wtf("Cannot deserialize crash", e);
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

    public static String getStoragePrefix() {
        return "crash";
    }

    public CrashImplCore putMetricsCore(CtxCore ctx, Long runningTime) {
        String version = ctx.getConfig().getApplicationVersion();
        return add("_os", DeviceCore.dev.getOS())
                .add("_app_version", Utils.isEmpty(version) ? "0.0" : version)
                .add("_os_version", DeviceCore.dev.getOSVersion())
                .add("_ram_current", DeviceCore.dev.getRAMAvailable())
                .add("_ram_total", DeviceCore.dev.getRAMTotal())
                .add("_disk_current", DeviceCore.dev.getDiskAvailable())
                .add("_disk_total", DeviceCore.dev.getDiskTotal())
                .add("_run", runningTime);
    }

    public String getJSON() {
        return data.toString();
    }

    @Override
    public String toString() {
        return data.toString();
    }
}
