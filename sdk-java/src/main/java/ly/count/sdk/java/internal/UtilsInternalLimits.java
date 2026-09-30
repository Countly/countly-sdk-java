package ly.count.sdk.java.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Applies the SDK internal limits of the SDK behavior settings: the key length {@code lkl}, the value
 * size {@code lvs}, the number of segmentation entries {@code lsv}, the stack trace lines per thread
 * {@code ltlpt} and the stack trace line length {@code ltl}. {@link Integer#MAX_VALUE} is no limit,
 * and whatever no limit cuts is returned as the same instance.
 * <p>
 * A map is never changed in place. One that has to change is rebuilt in sorted key order, so the
 * outcome never depends on the order of the map: when two keys are cut to the same key the one that
 * sorts last keeps its value, and over the entry limit the entries whose keys sort first are kept.
 * Strings are cut in UTF-16 characters and never inside a surrogate pair. Every cut is logged as a
 * warning that names the caller.
 */
final class UtilsInternalLimits {

    /**
     * The order every limit walks keys in, a {@code null} key first.
     */
    private static final Comparator<String> KEY_ORDER = Comparator.nullsFirst(Comparator.<String>naturalOrder());

    /**
     * Not instantiated: the limits are static helpers.
     */
    private UtilsInternalLimits() {
    }

    /**
     * Cuts a key to the key length limit, {@code lkl}: an event key, a view name, a segmentation key
     * or a custom user property key.
     *
     * @param key the key, {@code null} for none
     * @param maxKeyLength the limit
     * @param L logger
     * @param tag the caller, for the log
     * @return the key, cut when it is longer than the limit
     */
    @Nullable
    static String truncateKey(@Nullable String key, int maxKeyLength, @Nonnull Log L, @Nonnull String tag) {
        if (key == null || key.length() <= maxKeyLength) {
            return key;
        }

        String truncated = truncate(key, maxKeyLength);
        L.w(tag + ", the key [" + key + "] is longer than the key length limit of [" + maxKeyLength + "] of the SDK behavior settings, it is truncated to [" + truncated + "]");
        return truncated;
    }

    /**
     * Cuts a string value to the value size limit, {@code lvs}.
     *
     * @param value the value, {@code null} for none
     * @param maxValueSize the limit
     * @param L logger
     * @param tag the caller, for the log
     * @return the value, cut when it is longer than the limit
     */
    @Nullable
    static String truncateValue(@Nullable String value, int maxValueSize, @Nonnull Log L, @Nonnull String tag) {
        if (value == null || value.length() <= maxValueSize) {
            return value;
        }

        String truncated = truncate(value, maxValueSize);
        L.w(tag + ", a value of [" + value.length() + "] characters is longer than the value size limit of [" + maxValueSize + "] of the SDK behavior settings, it is truncated to [" + truncated + "]");
        return truncated;
    }

    /**
     * Cuts a value to the value size limit, {@code lvs}, when it is a string. A value of any other
     * type is returned as it is, strings inside arrays included.
     *
     * @param value the value, {@code null} for none
     * @param maxValueSize the limit
     * @param L logger
     * @param tag the caller, for the log
     * @return the value, cut when it is a string longer than the limit
     */
    @Nullable
    static Object truncateIfString(@Nullable Object value, int maxValueSize, @Nonnull Log L, @Nonnull String tag) {
        if (value instanceof String) {
            return truncateValue((String) value, maxValueSize, L, tag);
        }
        return value;
    }

    /**
     * Applies the key length, value size and segmentation entry limits, {@code lkl}, {@code lvs} and
     * {@code lsv}, to a segmentation. Keys and values are cut first, so two keys that end up the same
     * are counted once.
     *
     * @param segmentation the segmentation, {@code null} for none, never changed
     * @param limits the settings in effect
     * @param L logger
     * @param tag the caller, for the log
     * @return the segmentation within the limits, the same instance when it already was
     */
    @Nullable
    static Map<String, Object> applySegmentationLimits(@Nullable Map<String, Object> segmentation, @Nonnull ConfigurationProvider limits, @Nonnull Log L, @Nonnull String tag) {
        Map<String, Object> truncated = truncateSegmentationKeysAndValues(segmentation, limits.getMaxKeyLength(), limits.getMaxValueSize(), L, tag);
        return limitSegmentationEntries(truncated, limits.getMaxSegmentationValues(), L, tag);
    }

    /**
     * Cuts the keys of a segmentation to the key length limit, {@code lkl}, and its string values to
     * the value size limit, {@code lvs}. When two keys are cut to the same key, the entry whose key
     * sorts last keeps its value.
     *
     * @param segmentation the segmentation, {@code null} for none, never changed
     * @param maxKeyLength the key length limit
     * @param maxValueSize the value size limit
     * @param L logger
     * @param tag the caller, for the log
     * @return a copy in sorted key order when a key or a value was cut, otherwise the same instance
     */
    @Nullable
    static Map<String, Object> truncateSegmentationKeysAndValues(@Nullable Map<String, Object> segmentation, int maxKeyLength, int maxValueSize, @Nonnull Log L, @Nonnull String tag) {
        if (segmentation == null || !exceedsKeyOrValueLimit(segmentation, maxKeyLength, maxValueSize)) {
            return segmentation;
        }

        Map<String, Object> truncated = new LinkedHashMap<>();
        for (String key : sortedKeys(segmentation.keySet())) {
            String truncatedKey = truncateKey(key, maxKeyLength, L, tag);
            if (truncated.containsKey(truncatedKey)) {
                L.w(tag + ", the key [" + key + "] is truncated to the key of another segmentation entry, its value replaces that of [" + truncatedKey + "]");
            }
            truncated.put(truncatedKey, truncateIfString(segmentation.get(key), maxValueSize, L, tag));
        }
        return truncated;
    }

    /**
     * Keeps a segmentation within the segmentation entry limit, {@code lsv}, by keeping the entries
     * whose keys sort first.
     *
     * @param segmentation the segmentation, {@code null} for none, never changed
     * @param maxSegmentationValues the limit
     * @param L logger
     * @param tag the caller, for the log
     * @return a copy in sorted key order when entries were dropped, otherwise the same instance
     */
    @Nullable
    static Map<String, Object> limitSegmentationEntries(@Nullable Map<String, Object> segmentation, int maxSegmentationValues, @Nonnull Log L, @Nonnull String tag) {
        if (segmentation == null || segmentation.size() <= maxSegmentationValues) {
            return segmentation;
        }

        List<String> keys = sortedKeys(segmentation.keySet());
        Map<String, Object> kept = new LinkedHashMap<>();
        for (String key : keys.subList(0, maxSegmentationValues)) {
            kept.put(key, segmentation.get(key));
        }

        L.w(tag + ", the segmentation has [" + keys.size() + "] entries, over the limit of [" + maxSegmentationValues + "] of the SDK behavior settings, dropped the entries of the keys that sort last: " + keys.subList(maxSegmentationValues, keys.size()));
        return kept;
    }

    /**
     * Cuts the string values of a map to the value size limit, {@code lvs}, leaving its keys and its
     * number of entries as they are.
     *
     * @param map the map, never changed
     * @param maxValueSize the limit
     * @param L logger
     * @param tag the caller, for the log
     * @return a copy in the order of the map when a value was cut, otherwise the same instance
     */
    @Nonnull
    static Map<String, Object> truncateStringValues(@Nonnull Map<String, Object> map, int maxValueSize, @Nonnull Log L, @Nonnull String tag) {
        if (!exceedsKeyOrValueLimit(map, Integer.MAX_VALUE, maxValueSize)) {
            return map;
        }

        Map<String, Object> truncated = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            truncated.put(entry.getKey(), truncateIfString(entry.getValue(), maxValueSize, L, tag));
        }
        return truncated;
    }

    /**
     * Cuts every line of a stack trace to the stack trace line length limit, {@code ltl}. Line breaks,
     * {@code \n} or {@code \r\n}, are kept as they are and do not count as characters of the line.
     *
     * @param stackTrace the stack trace
     * @param maxLineLength the limit
     * @param L logger
     * @param tag the caller, for the log
     * @return the stack trace, a new one when a line was cut
     */
    @Nonnull
    static String truncateStackTraceLines(@Nonnull String stackTrace, int maxLineLength, @Nonnull Log L, @Nonnull String tag) {
        StringBuilder truncated = null;
        int truncatedLines = 0;
        int lineStart = 0;
        while (lineStart < stackTrace.length()) {
            int lineBreak = stackTrace.indexOf('\n', lineStart);
            int nextLineStart = lineBreak < 0 ? stackTrace.length() : lineBreak + 1;
            int lineEnd = lineBreak < 0 ? stackTrace.length() : lineBreak;
            if (lineEnd > lineStart && stackTrace.charAt(lineEnd - 1) == '\r') {
                lineEnd--;
            }

            if (lineEnd - lineStart > maxLineLength) {
                if (truncated == null) {
                    truncated = new StringBuilder(stackTrace.length());
                    truncated.append(stackTrace, 0, lineStart);
                }
                truncated.append(truncate(stackTrace.substring(lineStart, lineEnd), maxLineLength)).append(stackTrace, lineEnd, nextLineStart);
                truncatedLines++;
            } else if (truncated != null) {
                truncated.append(stackTrace, lineStart, nextLineStart);
            }
            lineStart = nextLineStart;
        }

        if (truncated == null) {
            return stackTrace;
        }

        L.w(tag + ", [" + truncatedLines + "] stack trace lines are longer than the line length limit of [" + maxLineLength + "] of the SDK behavior settings, they are truncated");
        return truncated.toString();
    }

    /**
     * How many lines of the stack trace of one thread the stack trace lines per thread limit,
     * {@code ltlpt}, keeps: the top ones.
     *
     * @param lineCount the number of lines of the stack trace
     * @param maxLinesPerThread the limit
     * @param threadName the thread, for the log
     * @param L logger
     * @param tag the caller, for the log
     * @return the number of top lines to keep
     */
    static int stackTraceLinesToKeep(int lineCount, int maxLinesPerThread, @Nullable String threadName, @Nonnull Log L, @Nonnull String tag) {
        if (lineCount <= maxLinesPerThread) {
            return lineCount;
        }

        L.w(tag + ", the stack trace of the thread [" + threadName + "] has [" + lineCount + "] lines, over the limit of [" + maxLinesPerThread + "] lines per thread of the SDK behavior settings, dropped the bottom ones");
        return maxLinesPerThread;
    }

    /**
     * Whether a key of a map is longer than a key length limit or one of its string values is longer
     * than a value size limit.
     *
     * @param map the map
     * @param maxKeyLength the key length limit
     * @param maxValueSize the value size limit
     * @return {@code true} when a key or a value has to be cut
     */
    private static boolean exceedsKeyOrValueLimit(@Nonnull Map<String, Object> map, int maxKeyLength, int maxValueSize) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key != null && key.length() > maxKeyLength) {
                return true;
            }
            if (value instanceof String && ((String) value).length() > maxValueSize) {
                return true;
            }
        }
        return false;
    }

    /**
     * The keys of a map in the order every limit walks them.
     *
     * @param keys the keys
     * @return the keys, sorted
     */
    @Nonnull
    private static List<String> sortedKeys(@Nonnull Collection<String> keys) {
        List<String> sorted = new ArrayList<>(keys);
        sorted.sort(KEY_ORDER);
        return sorted;
    }

    /**
     * Cuts a string that is longer than a limit. A cut never splits a surrogate pair: it falls before
     * the pair, or after it when the pair starts the string, which is then one character over the limit.
     *
     * @param value the string, longer than the limit
     * @param limit the limit, at least 1
     * @return the start of the string
     */
    @Nonnull
    private static String truncate(@Nonnull String value, int limit) {
        int end = limit;
        if (Character.isHighSurrogate(value.charAt(end - 1)) && Character.isLowSurrogate(value.charAt(end))) {
            end = end == 1 ? 2 : end - 1;
        }
        return value.substring(0, end);
    }
}
