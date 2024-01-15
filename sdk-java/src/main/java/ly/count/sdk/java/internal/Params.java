package ly.count.sdk.java.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Object for application/x-www-form-urlencoded string building and manipulation
 */

public class Params {
    static final String PARAM_DEVICE_ID = "device_id";
    static final String PARAM_OLD_DEVICE_ID = "old_device_id";

    private StringBuilder params;

    Log L;

    public static final class Obj {
        private final String key;
        private final JSONObject json;
        private final Params params;

        final Log L;

        Obj(String key, Params params, Log givenL) {
            this.L = givenL;
            this.params = params;
            this.key = key;
            this.json = new JSONObject();
        }

        public Obj put(String key, Object value) {
            try {
                json.put(key, value);
            } catch (JSONException e) {
                L.e("Cannot put property into Params.Obj " + e.toString());
            }
            return this;
        }

        public Params add() {
            params.add(key, json.toString());
            return params;
        }
    }

    public static final class Arr {
        private final String key;
        private final Collection<String> json;
        private final Params params;

        final Log L;

        Arr(String key, Params params, Log givenL) {
            this.L = givenL;
            this.params = params;
            this.key = key;
            this.json = new ArrayList<>();
        }

        public Arr put(JSONable value) {
            json.add(value.toJSON(L));
            return this;
        }

        public Arr put(Collection collection) {
            for (Object value : collection)
                if (value instanceof JSONable) {
                    json.add(((JSONable) value).toJSON(L));
                } else if (value instanceof String) {
                    json.add((String) value);
                }
            return this;
        }

        public Params add() {
            if (json.size() > 0) {
                params.add(key, "[" + Utils.join(json, ",") + "]");
            } else {
                params.add(key, "[]");
            }
            return params;
        }
    }

    public Params(Object... objects) {
        params = new StringBuilder();
        if (objects != null && objects.length == 1 && (objects[0] instanceof Object[])) {
            addObjects((Object[]) objects[0]);
        } else if (objects != null && objects.length == 1 && (objects[0] instanceof Params)) {
            params.append(objects[0].toString());
        } else if (objects != null && objects.length == 1 && (objects[0] instanceof String)) {
            params.append(objects[0].toString());
        } else {
            addObjects(objects);
        }
    }

    /**
     * Constructor
     *
     * @param params string representation of the Params object
     */
    public Params(String params) {
        this.params = new StringBuilder(params);
    }

    /**
     * Constructor
     */
    public Params() {
        this.params = new StringBuilder();
    }

    /**
     * Adds a key/value pair to the Params object
     *
     * @param objects key/value pairs
     * @return this Params object
     */
    public Params add(Object... objects) {
        return addObjects(objects);
    }

    /**
     * Adds a key/value pair to the Params object
     *
     * @param key key
     * @param value value
     * @return this Params object
     */
    public Params add(final String key, final Object value) {
        if (params.length() > 0) {
            params.append('&');
        }
        params.append(key).append('=');
        if (value != null) {
            params.append(Utils.urlencode(value.toString(), L));
        }
        return this;
    }

    /**
     * Adds a Params object to the Params object
     *
     * @param params to add
     * @return this Params object
     */
    public Params add(final Params params) {
        if (params == null || params.length() == 0) {
            return this;
        }
        if (this.params.length() > 0) {
            this.params.append('&');
        }
        this.params.append(params);
        return this;
    }

    /**
     * Adds a string to the Params object
     *
     * @param string to add
     * @return this Params object
     */
    public Params add(final String string) {
        if (params != null) {
            this.params.append(string);
        }
        return this;
    }

    /**
     * Returns a new Obj object
     *
     * @param key to use
     * @return new Obj object
     */
    public Obj obj(final String key) {
        return new Obj(key, this, L);
    }

    /**
     * Returns a new Arr object
     *
     * @param key to use
     * @return new Arr object
     */
    public Arr arr(final String key) {
        return new Arr(key, this, L);
    }

    /**
     * Removes the provided key from the Params object
     *
     * @param key to remove
     * @return value of the provided key, null if not found
     */
    public String remove(final String key) {
        String query = params.toString();
        String[] pairs = query.split("&");
        String result = null;
        StringBuilder newParams = new StringBuilder();

        for (String pair : pairs) {
            String[] comps = pair.split("=");
            if (comps.length == 2 && comps[0].equals(key)) {
                result = Utils.urldecode(comps[1]);
            } else {
                if (newParams.length() > 0) {
                    newParams.append('&');
                }
                newParams.append(pair);
            }
        }

        params = newParams;
        return result;
    }

    /**
     * Returns a map of the Params object
     *
     * @return map of the Params object
     */
    public Map<String, String> map() {
        Map<String, String> map = new ConcurrentHashMap<>();
        List<String> pairs = new ArrayList<>(Arrays.asList(params.toString().split("&")));
        for (String pair : pairs) {
            String[] comps = pair.split("=");
            if (comps.length == 2) {
                map.put(comps[0], Utils.urldecode(comps[1]));
            }
        }
        return map;
    }

    /**
     * Returns the value of the provided key
     *
     * @param key to get value for
     * @return value of the provided key, null if not found
     */
    public String get(final String key) {
        if (!has(key)) {
            return null;
        }
        String[] pairs = params.toString().split("&");
        for (String pair : pairs) {
            String comps[] = pair.split("=");
            if (comps.length == 2 && comps[0].equals(key)) {
                return Utils.urldecode(comps[1]);
            }
        }
        return null;
    }

    /**
     * Checks if the Params object contains the provided key
     *
     * @param key to check for
     * @return true if the Params object contains the provided key, false otherwise
     */
    public boolean has(final String key) {
        return params.indexOf("&" + key + "=") != -1 || params.indexOf(key + "=") == 0;
    }

    private Params addObjects(Object[] objects) {
        if (objects.length % 2 != 0) {
            L.e("Bad number of parameters");
        } else {
            for (int i = 0; i < objects.length; i += 2) {
                add(objects[i] == null ? ("unknown" + i) : objects[i].toString(), objects.length > i + 1 ? objects[i + 1] : null);
            }
        }
        return this;
    }

    /**
     * Returns the length of the Params object
     *
     * @return length of the Params object
     */
    public int length() {
        return params.length();
    }

    /**
     * Clears the Params object
     */
    public void clear() {
        params = new StringBuilder();
    }

    /**
     * Returns the string representation of the Params object
     *
     * @return string representation of the Params object
     */
    @Override
    public String toString() {
        return params.toString();
    }

    /**
     * Returns the hash code of the Params object
     *
     * @return hash code of the Params object
     */
    @Override
    public int hashCode() {
        return params.hashCode();
    }

    /**
     * Compares two Params objects
     *
     * @param obj to compare to
     * @return true if equal, false otherwise
     */
    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Params)) {
            return false;
        }
        Params p = (Params) obj;

        return p.params.toString().equals(params.toString());
    }
}
