package ly.count.sdk.java.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Utility class
 */

public class Utils {
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

    /**
     * Convert map of 'String, Object' key value pairs to
     * 'String, String' key value pairs <br>
     * <br>
     * Null values are converted to empty strings <br>
     * Null keys are accepted and stay null
     * <p>
     *
     * @param map to convert
     * @return resulting 'String, String' map
     */
    public static Map<String, String> mapify(Map<String, Object> map) {
        if (map == null) {
            return new HashMap<>();
        }

        return map.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, entry -> {
                Object value = entry.getValue();
                if (value == null) {
                    return "";
                } else {
                    return value.toString();
                }
            }));
    }

    /**
     * Given value is valid if it is one of the following types: <br>
     * Boolean, Integer, Long, String, Double, Float, BigDecimal <br>
     * <br>
     * If value is null returns false
     *
     * @param value to check
     * @return true if value is valid, false otherwise
     */
    public static boolean isValidDataType(Object value) {
        if (value == null) {
            return false;
        }

        return
            value instanceof Boolean ||
                value instanceof Integer ||
                value instanceof Long ||
                value instanceof String ||
                value instanceof Double ||
                value instanceof BigDecimal ||
                value instanceof Float;
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
