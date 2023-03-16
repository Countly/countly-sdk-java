package ly.count.sdk.java.internal;

import ly.count.sdk.java.Config;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.*;

/**
 * Utility class
 */

public class Utils {
    protected static final Utils utils = new Utils();//todo remove this when possible

    public static final String UTF8 = "UTF-8";
    public static final String CRLF = "\r\n";
    public static final char[] BASE_16 = "0123456789ABCDEF".toCharArray();

    /**
     * Joins objects with a separator
     *
     * @param objects objects to join
     * @param separator separator to use
     * @return resulting string
     */
    public static <T> String join(Collection<T> objects, String separator) {
        StringBuilder sb = new StringBuilder();
        Iterator<T> iter = objects.iterator();
        while (iter.hasNext()) {
            sb.append(iter.next());
            if (iter.hasNext()) {
                sb.append(separator);
            }
        }
        return sb.toString();
    }

    /**
     * URLDecoder wrapper to remove try-catch
     *
     * @param str string to decode
     * @return url-decoded {@code str}
     */
    public static String urldecode(String str) {
        try {
            return URLDecoder.decode(str, UTF8);
        } catch (UnsupportedEncodingException e) {
            return null;
        }
    }

    /**
     * Get fields declared by class and its superclasses filtering test-related which
     * contain $ in their name
     *
     * @param cls class to check
     * @param goUp whether to return parent class fields as well
     * @return list of declared fields
     */
    public static List<Field> reflectiveGetDeclaredFields(Class<?> cls, boolean goUp) {
        return reflectiveGetDeclaredFields(new ArrayList<Field>(), cls, goUp);
    }

    public static List<Field> reflectiveGetDeclaredFields(List<Field> list, Class<?> cls, boolean goUp) {
        List<Field> curr = new ArrayList<>(Arrays.asList(cls.getDeclaredFields()));
        for (int i = 0; i < curr.size(); i++) {
            if (curr.get(i).getName().contains("$")) {
                curr.remove(i);
                i--;
            }
        }
        list.addAll(curr);
        if (goUp && cls.getSuperclass() != null) {
            reflectiveGetDeclaredFields(list, cls.getSuperclass(), goUp);
        }
        return list;
    }

    public static Field findField(Class cls, String name) throws NoSuchFieldException {
        try {
            return cls.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            if (cls.getSuperclass() == null) {
                throw e;
            } else {
                return findField(cls.getSuperclass(), name);
            }
        }
    }

    /**
     * StringUtils.isEmpty replacement.
     *
     * @param str string to check
     * @return true if null or empty string, false otherwise
     */
    public static boolean isEmptyOrNull(String str) {
        return str == null || "".equals(str);
    }

    /**
     * StringUtils.isNotEmpty replacement.
     *
     * @param str string to check
     * @return false if null or empty string, true otherwise
     */
    public static boolean isNotEmpty(String str) {
        return !isEmptyOrNull(str);
    }

    public static boolean isNotEqual(Object a, Object b) {
        return !isEqual(a, b);
    }

    public static boolean isEqual(Object a, Object b) {
        if (a == null || b == null || a == b) {
            return a == b;
        }
        return a.equals(b);
    }

    public static boolean contains(String string, String part) {
        if (string == null) {
            return false;
        } else if (part == null) {
            return false;
        } else {
            return string.contains(part);
        }
    }

    /**
     * URLEncoder wrapper to remove try-catch
     *
     * @param str string to encode
     * @return url-encoded {@code str}
     */
    public static String urlencode(String str, Log L) {
        try {
            return URLEncoder.encode(str, UTF8);
        } catch (UnsupportedEncodingException e) {
            if (L != null) {
                L.e("Utils No UTF-8 encoding?" + e);
            }
            return "";
        }
    }

    public static boolean reflectiveClassExists(String cls, Log L) {
        boolean res = utils._reflectiveClassExists(cls, L);
        return res;
    }

    /**
     * Check whether class exists in default class loader.
     *
     * @param cls Class name to check
     * @return true if class exists, false otherwise
     */
    public boolean _reflectiveClassExists(String cls, Log L) {
        try {
            Class.forName(cls);
            return true;
        } catch (ClassNotFoundException e) {
            if (L != null) {
                L.e("Utils Class " + cls + " not found");
            }
            return false;
        }
    }

    /**
     * Reflective method call encapsulation.
     *
     * @param className class to call method in
     * @param instance instance to call on, null for static methods
     * @param methodName method name
     * @param args optional arguments to pass to that method
     * @return false in case of failure, method result otherwise
     */
    public static Object reflectiveCall(String className, Object instance, String methodName, Log L, Object... args) {
        return utils._reflectiveCall(className, instance, methodName, L, args);
    }

    public Object _reflectiveCall(String className, Object instance, String methodName, Log L, Object... args) {
        try {
            if (L != null) {
                L.e("Utils cls " + className + ", inst " + instance);
            }
            className = className == null && instance != null ? instance.getClass().getName() : className;
            Class<?> cls = instance == null ? Class.forName(className) : instance.getClass();
            Class<?> types[] = null;

            if (args != null && args.length > 0) {
                types = new Class[args.length];

                for (int i = 0; i < types.length; i++) {
                    types[i] = args[i].getClass();
                }
            }
            Method method = cls.getDeclaredMethod(methodName, types);
            return method.invoke(instance, args);
        } catch (ClassNotFoundException t) {
            if (L != null) {
                L.e("Utils Cannot call " + methodName + " of " + className + t.toString());
            }
            return false;
        } catch (NoSuchMethodException t) {
            if (L != null) {
                L.e("Utils Cannot call " + methodName + " of " + className + t.toString());
            }
            return false;
        } catch (IllegalAccessException t) {
            if (L != null) {
                L.e("Utils Cannot call " + methodName + " of " + className + t.toString());
            }
            return false;
        } catch (InvocationTargetException t) {
            if (L != null) {
                L.e("Utils Cannot call " + methodName + " of " + className + t.toString());
            }
            return false;
        }
    }

    /**
     * Reflective method call encapsulation with argument types specified explicitly before each parameter.
     *
     * @param className class to call method in
     * @param instance instance to call on, null for static methods
     * @param methodName method name
     * @param args optional arguments to pass to that method in format [arg1 class, arg1 value, arg2 class, arg2 value]
     * @return false in case of failure, method result otherwise
     */
    public static Object reflectiveCallStrict(String className, Object instance, String methodName, Log L, Object... args) {
        return utils._reflectiveCallStrict(className, instance, methodName, L, args);
    }

    public Object _reflectiveCallStrict(String className, Object instance, String methodName, Log L, Object... args) {
        try {
            Class<?> cls = instance == null ? Class.forName(className) : instance.getClass();
            Class<?> types[] = args == null || args.length == 0 ? null : new Class[args.length / 2];
            Object arguments[] = args == null || args.length == 0 ? null : new Object[args.length / 2];

            if (args != null && args.length > 0) {
                for (int i = 0; i < args.length; i += 2) {
                    types[i / 2] = (Class<?>) args[i];
                    arguments[i / 2] = args[i + 1];
                }
            }
            Method method = cls.getDeclaredMethod(methodName, types);
            return method.invoke(instance, arguments);
        } catch (ClassNotFoundException t) {
            if (L != null) {
                L.e("Utils Cannot call " + methodName + " of " + className + t.toString());
            }
            return false;
        } catch (NoSuchMethodException t) {
            if (L != null) {
                L.e("Utils Cannot call " + methodName + " of " + className + t.toString());
            }
            return false;
        } catch (IllegalAccessException t) {
            if (L != null) {
                L.e("Utils Cannot call " + methodName + " of " + className + t.toString());
            }
            return false;
        } catch (InvocationTargetException t) {
            if (L != null) {
                L.e("Utils Cannot call " + methodName + " of " + className + t.toString());
            }
            return false;
        }
    }

    public static Boolean reflectiveSetField(Object object, String name, Object value, Log L) {
        return utils._reflectiveSetField(object, object.getClass(), name, value, L);
    }

    public static Boolean reflectiveSetField(Class cls, String name, Object value, Log L) {
        return utils._reflectiveSetField(null, cls, name, value, L);
    }

    public Boolean _reflectiveSetField(Object object, Class cls, String name, Object value, Log L) {
        try {
            Field field = findField(cls, name);
            boolean accessible = field.isAccessible();
            if (!accessible) {
                field.setAccessible(true);
            }
            field.set(object, value);
            if (!accessible) {
                field.setAccessible(false);
            }
            return true;
        } catch (IllegalAccessException e) {
            if (L != null) {
                L.e("Utils Cannot access field " + name + " of " + cls + e.toString());
            }
        } catch (NoSuchFieldException e) {
            if (L != null) {
                L.e("Utils No field " + name + " in " + cls + e.toString());
            }
        }
        return false;
    }

    public static <T> T reflectiveGetField(Object object, String name, Log L) {
        return utils._reflectiveGetField(object, object.getClass(), name, L);
    }

    public static <T> T reflectiveGetField(Class cls, String name, Log L) {
        return utils._reflectiveGetField(null, cls, name, L);
    }

    @SuppressWarnings("unchecked")
    public <T> T _reflectiveGetField(Object object, Class cls, String name, Log L) {
        try {
            Field field = findField(cls, name);
            boolean accessible = field.isAccessible();
            if (!accessible) {
                field.setAccessible(true);
            }
            T value = (T) field.get(object);
            if (!accessible) {
                field.setAccessible(false);
            }
            return value;
        } catch (IllegalAccessException e) {
            if (L != null) {
                L.e("Utils Cannot access field " + name + " of " + object.getClass() + e.toString());
            }
        } catch (NoSuchFieldException e) {
            if (L != null) {
                L.e("Utils No field " + name + " in " + object.getClass() + e.toString());
            }
        }
        return null;
    }

    /**
     * Calculate digest (SHA-1, SHA-256, etc.) hash of the string provided
     *
     * @param digestName digest name like {@code "SHA-256"}, must be supported by Java, see {@link MessageDigest}
     * @param string string to hash
     * @return hash of the string or null in case of error
     */
    public static String digestHex(String digestName, String string, Log L) {
        try {
            MessageDigest digest = MessageDigest.getInstance(digestName);
            byte[] bytes = string.getBytes(UTF8);
            digest.update(bytes, 0, bytes.length);
            return hex(digest.digest());
        } catch (Throwable e) {
            if (L != null) {
                L.e("Utils Cannot calculate sha1" + " / " + e);
            }
            return null;
        }
    }

    /**
     * Get hexadecimal string representation of a byte array
     *
     * @param bytes array of bytes to convert
     * @return hex string of the byte array in lower case
     */
    public static String hex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = BASE_16[v >>> 4];
            hexChars[j * 2 + 1] = BASE_16[v & 0x0F];
        }
        return new String(hexChars).toLowerCase();
    }

    /**
     * Read stream into a byte array
     *
     * @param stream input to read
     * @return stream contents or {@code null} in case of error
     */
    public static byte[] readStream(InputStream stream, Log L) {
        if (stream == null) {
            return null;
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            byte[] buffer = new byte[1024];
            int len = 0;
            while ((len = stream.read(buffer)) != -1) {
                bytes.write(buffer, 0, len);
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            if (L != null) {
                L.e("Utils Couldn't read stream" + e.toString());
            }
            return null;
        } finally {
            try {
                bytes.close();
                stream.close();
            } catch (Throwable ignored) {
            }
        }
    }

    public static String trimKey(final int limit, final String key, Log logger) {
        String k = key;
        if (key.length() > limit) {
            logger.d("[Utils] RecordEventInternal : Max allowed key length is " + limit);
            k = key.substring(0, limit);
        }
        return k;
    }

    public static String trimValue(final int limit, final String fieldName, final String value, Log logger) {
        String v = value;
        if (value != null && value.length() > limit) {
            logger.d("[Utils] TrimValue : Max allowed '" + fieldName + "' length is " + limit + ". " + value + " will be truncated.");
            v = value.substring(0, limit);
        }

        return v;
    }

    public static String[] trimValues(final int limit, final String[] values, Log logger) {
        for (int i = 0; i < values.length; ++i) {
            if (values[i].length() > limit) {
                logger.d("[Utils] TrimKey : Max allowed value length is " + limit + ". " + values[i] + " will be truncated.");
                values[i] = values[i].substring(0, limit);
            }
        }

        return values;
    }

    public static Map<String, String> fixSegmentKeysAndValues(final int keyLength, final int valueLength, final Map<String, String> segments, Log logger) {
        if (segments == null || segments.size() == 0) {
            return segments;
        }

        Map<String, String> segmentation = new HashMap<>();

        for (Map.Entry<String, String> entry : segments.entrySet()) {
            String k = entry.getKey();
            String v = entry.getValue();
            if (k == null || k.length() == 0 || v == null) {
                continue;
            }

            k = trimKey(keyLength, k, logger);
            v = trimValue(valueLength, k, v, logger);

            segmentation.put(k, v);
        }
        return segmentation;
    }

    public static class Base64 {
        public static String encode(byte[] bytes) {
            return ly.count.sdk.java.internal.Base64.encodeBytes(bytes);
        }

        public static String encode(String string) {
            try {
                return encode(string.getBytes(UTF8));
            } catch (UnsupportedEncodingException e) {
                // shouldn't happen
                return null;
            }
        }

        public static byte[] decode(String string, Log L) {
            byte[] res = null;
            try {
                res = ly.count.sdk.java.internal.Base64.decode(string);
            } catch (IOException e) {
                //should not get here
                if (L != null) {
                    L.e("Utils Error while decoding base64 string, " + e);
                }
            }
            return res;
        }

        public static String decodeToString(String string, Log L) {
            try {
                return new String(decode(string, L), UTF8);
            } catch (UnsupportedEncodingException e) {
                // shouldn't happen
                return null;
            }
        }
    }
}
