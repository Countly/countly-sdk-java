package ly.count.sdk.java.internal;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import ly.count.sdk.java.Config;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * The truncation rules of {@link UtilsInternalLimits} that the scenarios through the public API cannot
 * reach on every platform: surrogate pairs, {@code \r\n} line breaks, {@code null} keys and the
 * independence from the order of a map.
 */
@RunWith(JUnit4.class)
public class UtilsInternalLimitsTests {

    private static final String TAG = "[Caller] method";
    private final List<String> warnings = new CopyOnWriteArrayList<>();
    private Log log;

    /**
     * Starts every test with a logger that keeps every warning.
     */
    @Before
    public void beforeTest() {
        warnings.clear();
        log = new Log(Config.LoggingLevel.OFF, (message, level) -> {
            if (level == Config.LoggingLevel.WARN) {
                warnings.add(message);
            }
        });
    }

    /**
     * Keys and values are cut to the limit and never inside a surrogate pair.
     * <p>
     * Verifies that {@code null}, empty and short strings come back as the same instance without a
     * warning; that a longer string is cut to the limit with one warning naming the caller; that a
     * cut falling inside a surrogate pair falls before it, or after it when the pair starts the
     * string; and that only strings are cut by value, other types coming back as they are.
     */
    @Test
    public void strings_areCutToTheLimit_neverInsideASurrogatePair() {
        String shortKey = "key";
        Assert.assertSame(shortKey, UtilsInternalLimits.truncateKey(shortKey, 3, log, TAG));
        Assert.assertNull(UtilsInternalLimits.truncateKey(null, 1, log, TAG));
        Assert.assertEquals("", UtilsInternalLimits.truncateValue("", 1, log, TAG));
        Assert.assertEquals(0, warnings.size());

        Assert.assertEquals("abc", UtilsInternalLimits.truncateKey("abcdef", 3, log, TAG));
        Assert.assertEquals(1, warnings.size());
        Assert.assertTrue(warnings.get(0), warnings.get(0).startsWith(TAG + ", the key [abcdef] is longer than the key length limit of [3]"));
        Assert.assertEquals("abcd", UtilsInternalLimits.truncateValue("abcdefgh", 4, log, TAG));
        Assert.assertTrue(warnings.get(1), warnings.get(1).startsWith(TAG + ", a value of [8] characters is longer than the value size limit of [4]"));

        String grinning = "😀";
        Assert.assertEquals("ab", UtilsInternalLimits.truncateValue("ab" + grinning + "cd", 3, log, TAG));
        Assert.assertEquals("ab" + grinning, UtilsInternalLimits.truncateValue("ab" + grinning + "cd", 4, log, TAG));
        Assert.assertEquals(grinning, UtilsInternalLimits.truncateKey(grinning + "rest", 1, log, TAG));

        Integer number = 123456789;
        Assert.assertSame(number, UtilsInternalLimits.truncateIfString(number, 2, log, TAG));
        Assert.assertEquals("12", UtilsInternalLimits.truncateIfString("123456789", 2, log, TAG));
    }

    /**
     * A segmentation is cut and trimmed the same way whatever the order of its map, and never in place.
     * <p>
     * Verifies that a segmentation within every limit, and {@code null}, come back as the same
     * instance; that the same entries in two insertion orders give the same result, where of the keys
     * cut to the same key the one that sorts last keeps its value and past the entry limit the keys
     * that sort first are kept; that the caller's map is left as it was; that a {@code null} key
     * sorts first and is never cut; and that cutting values only keeps every key in the order of the map.
     */
    @Test
    public void segmentation_isCutTheSameWayWhateverTheOrderOfTheMap_andNeverInPlace() {
        Map<String, Object> within = TestUtils.map("abc", "value", "number", 12345678);
        Assert.assertSame(within, UtilsInternalLimits.applySegmentationLimits(within, limits(6, 5, 2), log, TAG));
        Assert.assertNull(UtilsInternalLimits.applySegmentationLimits(null, limits(1, 1, 1), log, TAG));
        Assert.assertEquals(0, warnings.size());

        Map<String, Object> forward = new LinkedHashMap<>();
        forward.put("colour_a", "red");
        forward.put("colour_b", "green");
        forward.put("colour_c", "blue");
        forward.put("amount", 5);
        forward.put("zone", "europe_west");
        Map<String, Object> backward = new LinkedHashMap<>();
        List<String> keys = Arrays.asList(forward.keySet().toArray(new String[0]));
        Collections.reverse(keys);
        for (String key : keys) {
            backward.put(key, forward.get(key));
        }
        Map<String, Object> forwardCopy = new HashMap<>(forward);

        Map<String, Object> expected = TestUtils.map("amoun", 5, "colou", "blue");
        Assert.assertEquals(expected, UtilsInternalLimits.applySegmentationLimits(forward, limits(5, 6, 2), log, TAG));
        Assert.assertEquals(expected, UtilsInternalLimits.applySegmentationLimits(backward, limits(5, 6, 2), log, TAG));
        Assert.assertEquals(Arrays.asList("amoun", "colou"), Arrays.asList(UtilsInternalLimits.applySegmentationLimits(backward, limits(5, 6, 2), log, TAG).keySet().toArray()));
        Assert.assertEquals(forwardCopy, forward);

        Map<String, Object> withNullKey = new HashMap<>();
        withNullKey.put(null, "null_key_value");
        withNullKey.put("longer_key", 1);
        Map<String, Object> limited = UtilsInternalLimits.applySegmentationLimits(withNullKey, limits(3, 100, 1), log, TAG);
        Assert.assertEquals(Collections.singletonMap(null, "null_key_value"), limited);

        Map<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("zeta", "long_value");
        ordered.put("alpha", 1);
        ordered.put("mid", "short");
        Assert.assertSame(ordered, UtilsInternalLimits.truncateStringValues(ordered, 10, log, TAG));
        Map<String, Object> valuesCut = UtilsInternalLimits.truncateStringValues(ordered, 5, log, TAG);
        Assert.assertEquals(Arrays.asList("zeta", "alpha", "mid"), Arrays.asList(valuesCut.keySet().toArray()));
        Assert.assertEquals("long_", valuesCut.get("zeta"));
        Assert.assertEquals("long_value", ordered.get("zeta"));
    }

    /**
     * Stack trace lines are cut without touching the line breaks, and the lines of a thread are
     * counted from the top.
     * <p>
     * Verifies that a stack trace within the limit comes back as the same instance; that {@code \n}
     * and {@code \r\n} breaks, empty lines and a last line without a break are kept, the {@code \r}
     * never counting as a character of its line; that one warning is logged per stack trace; and
     * that the lines to keep of a thread are the limit only when the thread has more.
     */
    @Test
    public void stackTraces_linesAreCutWithoutTouchingTheLineBreaks_andLinesPerThreadCountFromTheTop() {
        String within = "abc\r\nde\n\nf";
        Assert.assertSame(within, UtilsInternalLimits.truncateStackTraceLines(within, 3, log, TAG));

        String stackTrace = "java.lang.Exception: message\r\n\tat first.frame(File.java:1)\n\n\tat last.frame(File.java:2)\r\nno_break_at_the_end";
        Assert.assertEquals("java.lang.\r\n\tat first.\n\n\tat last.f\r\nno_break_a", UtilsInternalLimits.truncateStackTraceLines(stackTrace, 10, log, TAG));
        Assert.assertEquals(1, warnings.size());
        Assert.assertTrue(warnings.get(0), warnings.get(0).startsWith(TAG + ", [4] stack trace lines are longer than the line length limit of [10]"));
        Assert.assertEquals("12\r\n34\r\n", UtilsInternalLimits.truncateStackTraceLines("123\r\n345\r\n", 2, log, TAG));

        Assert.assertEquals(5, UtilsInternalLimits.stackTraceLinesToKeep(5, 5, "worker", log, TAG));
        Assert.assertEquals(2, warnings.size());
        Assert.assertEquals(3, UtilsInternalLimits.stackTraceLinesToKeep(5, 3, "worker", log, TAG));
        Assert.assertTrue(warnings.get(2), warnings.get(2).startsWith(TAG + ", the stack trace of the thread [worker] has [5] lines, over the limit of [3]"));
    }

    /**
     * A settings provider serving the given key length, value size and segmentation entry limits.
     *
     * @param maxKeyLength the key length limit
     * @param maxValueSize the value size limit
     * @param maxSegmentationValues the segmentation entry limit
     * @return the provider
     */
    private static ConfigurationProvider limits(int maxKeyLength, int maxValueSize, int maxSegmentationValues) {
        ModuleConfiguration limits = new ModuleConfiguration();
        limits.currentVMaxKeyLength = maxKeyLength;
        limits.currentVMaxValueSize = maxValueSize;
        limits.currentVMaxSegmentationValues = maxSegmentationValues;
        return limits;
    }
}
