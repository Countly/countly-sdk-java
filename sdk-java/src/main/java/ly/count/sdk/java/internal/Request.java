package ly.count.sdk.java.internal;

import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Map;

/**
 * Class which encapsulates request logic and manipulation: building, status of sending, etc.
 */

public class Request implements Storable {
    public static final String MODULE = "module";
    public static final String ENDPOINT = "endpoint";
    private Long id;

    /**
     * This string is written to request file to ensure it can be fully read from other end of queue.
     */
    private static final String EOR = "\n[CLY][CLY][CLY]";

    /**
     * Params without device_id
     */
    public Params params;

    /**
     * Create request from params with current time as id.
     */
    public Request(Object... params) {
        this.id = TimeUtils.uniqueTimestampMs();
        this.params = new Params(params);
    }

    public Request own(Class<? extends ModuleBase> module) {
        this.params.add(MODULE, module.getName());
        return this;
    }

    public Request endpoint(String value) {
        this.params.add(ENDPOINT, value);
        return this;
    }

    @SuppressWarnings("unchecked")
    public Class<? extends ModuleBase> owner() {
        String name = this.params.get(MODULE);
        try {
            return name == null ? null : (Class<? extends ModuleBase>) Class.forName(name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /**
     * Deserialization constructor (use existing id).
     */
    public Request(Long id) {
        this.id = id;
    }

    boolean isEmpty() {
        Map<String, String> map = this.params.map();
        return map.isEmpty() || (map.size() == 1 && map.containsKey(Params.PARAM_DEVICE_ID));
    }

    boolean isGettable(URL serverUrl) {
        return isGettable(serverUrl, 0);
    }

    boolean isGettable(URL serverUrl, int addition) {
        return (serverUrl.toString().length() + 3 + params.length() + addition) < 1024;
    }

    static Request build(Object... params) {
        return new Request(params);
    }

    @Override
    public int hashCode() {
        return id.hashCode() + params.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Request)) {
            return false;
        }
        Request r = (Request) obj;
        return id.equals(r.id) && params.equals(r.params);
    }

    @Override
    public String toString() {
        String p = params == null ? "" : params.toString();
        return "[" + id + "] " + p;
    }

    @Override
    public Long storageId() {
        return id;
    }

    @Override
    public String storagePrefix() {
        return Request.getStoragePrefix();
    }

    @Override
    public void setId(Long id) {
        this.id = id;
    }

    public static String getStoragePrefix() {
        return "request";
    }

    @Override
    public byte[] store(Log L) {
        try {
            return (params.toString() + EOR).getBytes(Utils.UTF8);
        } catch (UnsupportedEncodingException e) {
            return null;
        }
    }

    @Override
    public boolean restore(byte[] data, Log L) {
        String str = new String(data, Charset.forName(Utils.UTF8));
        if (str.endsWith(EOR)) {
            params = new Params(str.substring(0, str.length() - EOR.length()));
            return true;
        } else {
            return false;
        }
    }
}
