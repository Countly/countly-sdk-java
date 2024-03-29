package ly.count.sdk.java.internal;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
     * @return url-decoded {@code str}, empty string if decoding failed
     */
    public static String urldecode(String str) {
        try {
            return URLDecoder.decode(str, UTF8);
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
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
        return reflectiveGetDeclaredFields(new ArrayList<>(), cls, goUp);
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
        return str == null || str.isEmpty();
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
     * this class is for the test purposes
     *
     * @param str string to encode
     * @param encoding encoding to use (for testing)
     * @return url-encoded {@code str}
     */
    protected static String urlencode(final String str, Log L, final String encoding) {
        try {
            return URLEncoder.encode(str, encoding);
        } catch (UnsupportedEncodingException e) {
            if (L != null) {
                L.e("[Utils] urlencode, No " + encoding + " encoding?" + e);
            }
            return "";
        }
    }

    /**
     * URLEncoder wrapper to remove try-catch
     *
     * @param str string to encode
     * @return url-encoded {@code str}
     */
    public static String urlencode(final String str, Log L) {
        return urlencode(str, L, UTF8);
    }

    /**
     * Calculate digest (SHA-1, SHA-256, etc.) hash of the string provided
     *
     * @param digestName digest name like {@code "SHA-256"}, must be supported by Java, see {@link MessageDigest}
     * @param string string to hash
     * @return hash of the string or null in case of error
     */
    public static String digestHex(final String digestName, final String string, Log L) {
        try {
            MessageDigest digest = MessageDigest.getInstance(digestName);
            byte[] bytes = string.getBytes(StandardCharsets.UTF_8);
            digest.update(bytes, 0, bytes.length);
            return hex(digest.digest());
        } catch (Throwable e) {
            if (L != null) {
                L.e("[Utils] digestHex, Cannot calculate sha1" + " / " + e);
            }
            return null;
        }
    }

    /**
     * Get hexadecimal string representation of a byte array
     *
     * @param bytes array of bytes to convert
     * @return hex string of the byte array in lower case, if null or empty returns empty string
     */
    public static String hex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
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
            int len;
            while ((len = stream.read(buffer)) != -1) {
                bytes.write(buffer, 0, len);
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            if (L != null) {
                L.e("[Utils] readStream, Couldn't read stream" + e);
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
        if (segments == null || segments.isEmpty()) {
            return segments;
        }

        Map<String, String> segmentation = new ConcurrentHashMap<>();

        for (Map.Entry<String, String> entry : segments.entrySet()) {
            String k = entry.getKey();
            String v = entry.getValue();
            if (isEmptyOrNull(k) || v == null) {
                continue;
            }

            k = trimKey(keyLength, k, logger);
            v = trimValue(valueLength, k, v, logger);

            segmentation.put(k, v);
        }
        return segmentation;
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

    /**
     * Read file content using UTF-8 encoding into a string and
     * append lines to a "StringBuilder" and return it
     * If file doesn't exist, return empty string
     *
     * @param file to read
     * @param logger to log errors
     * @return file contents or empty string
     * @throws IOException if file exists but couldn't be read
     */
    public static String readFileContent(File file, Log logger) throws IOException {
        StringBuilder fileContent = new StringBuilder();

        if (!file.exists()) {
            logger.v("[Utils] readFileContent : File doesn't exist: " + file.getAbsolutePath() + ". returning empty string");
            return fileContent.toString();
        }

        if (!file.canRead()) {
            logger.v("[Utils] readFileContent : File exists but can't be read: " + file.getAbsolutePath() + ". returning empty string");
            return fileContent.toString();
        }

        try (BufferedReader reader = Files.newBufferedReader(file.toPath())) {
            String line;
            while ((line = reader.readLine()) != null) {
                fileContent.append(line);
            }
        }

        return fileContent.toString();
    }

    public static class Base64 {
        public static String encode(byte[] bytes) {
            return java.util.Base64.getEncoder().encodeToString(bytes);
        }

        public static String encode(String string) {
            return encode(string.getBytes(StandardCharsets.UTF_8));
        }

        public static byte[] decode(String string, Log L) {
            byte[] res = null;
            try {
                res = java.util.Base64.getDecoder().decode(string);
            } catch (IllegalArgumentException e) {
                //should not get here
                if (L != null) {
                    L.e("[Utils] [Base64] decode, Error while decoding base64 string, " + e);
                }
            }
            return res;
        }

        public static String decodeToString(String string, Log L) {
            byte[] result = decode(string, L);
            if (result == null) {
                return null;
            }
            return new String(decode(string, L), StandardCharsets.UTF_8);
        }
    }

    /**
     * Check whether given string is a valid URL or not
     *
     * @param url to validate
     * @return true if it is a valid url by RFC2396
     */
    public static boolean isValidURL(String url) {
        try {
            new java.net.URL(url).toURI();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Creates a crypto-safe SHA-256 hashed random value
     *
     * @return returns a random string value
     */
    public static String safeRandomVal() {
        long timestamp = System.currentTimeMillis();
        SecureRandom random = new SecureRandom();
        byte[] value = new byte[6];
        random.nextBytes(value);
        String b64Value = Utils.Base64.encode(value);
        return b64Value + timestamp;
    }

    /**
     * Removes invalid data types from segments
     *
     * @param segments to check
     * @param L logger
     */
    public static void removeInvalidDataFromSegments(Map<String, Object> segments, Log L) {

        if (segments == null || segments.isEmpty()) {
            return;
        }

        List<String> toRemove = segments.entrySet().stream()
            .filter(entry -> !Utils.isValidDataType(entry.getValue()))
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());

        toRemove.forEach(key -> {
            L.w("[Utils] removeInvalidDataFromSegments, In segmentation Data type '" + segments.get(key) + "' of item '" + key + "' isn't valid.");
            segments.remove(key);
        });
    }
}
