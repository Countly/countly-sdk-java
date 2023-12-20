package ly.count.sdk.java.internal;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ly.count.sdk.java.Config;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class UtilsTests {

    Log logger;

    static final String TEST_FILE_NAME = "testFile";

    @Before
    public void setupEveryTest() {
        logger = new Log(Config.LoggingLevel.VERBOSE, null);
    }

    /**
     * "trimKey"
     * A valid key is given to the function
     * returned value should be the expected trimmed key
     */
    @Test
    public void trimKey() {
        String result = Utils.trimKey(3, "Key_01", logger);
        Assert.assertEquals("Key", result);
    }

    /**
     * "trimValue"
     * A valid value is given to the function
     * returned value should be the expected trimmed value
     */
    @Test
    public void trimValue() {
        String result = Utils.trimValue(5, "Key", "Value1", logger);
        Assert.assertEquals("Value", result);
    }

    /**
     * "trimValues"
     * A valid set of values is given to the function
     * returned array should contain expected trimmed values
     */
    @Test
    public void trimValues() {
        String[] result = Utils.trimValues(3, new String[] { "zelda", "link", "value" }, logger);
        Assert.assertEquals(3, result.length);
        Assert.assertEquals("zel", result[0]);
        Assert.assertEquals("lin", result[1]);
        Assert.assertEquals("val", result[2]);
    }

    /**
     * "fixSegmentKeysAndValues"
     * A valid set of values is given to the function
     * Should return the expected map
     */
    @Test
    public void fixSegmentKeysAndValues() {
        Map<String, String> segmentation = new HashMap<>();
        segmentation.put("key_10", "value1_");
        segmentation.put("key_20", "value2_");

        Map<String, String> result = Utils.fixSegmentKeysAndValues(5, 6, segmentation, logger);
        Assert.assertEquals(2, result.size());
        Assert.assertEquals("value1", result.get("key_1"));
        Assert.assertEquals("value2", result.get("key_2"));
    }

    /**
     * "fixSegmentKeysAndValues"
     * An invalid set of values is given to the function
     * Should return an empty map
     */
    @Test
    public void fixSegmentKeysAndValues_nullEmpty() {
        Map<String, String> segmentation = new HashMap<>();
        segmentation.put("key_10", null);
        segmentation.put(null, "value2_");
        segmentation.put("", "value2_");

        Map<String, String> result = Utils.fixSegmentKeysAndValues(5, 6, segmentation, logger);
        Assert.assertEquals(0, result.size());
    }

    /**
     * "Base64.decodeToString"
     * A valid base 64 string is given to the function
     * Should return base 64 decoded string
     */
    @Test
    public void base_64_decodeToString() {
        String decodeSource = "MTIzNDU=";
        String decodeTarget = "12345";

        Assert.assertEquals(decodeTarget, Utils.Base64.decodeToString(decodeSource, null));
    }

    /**
     * "Base64.decodeToString"
     * An invalid base 64 string is given to the function
     * Should return null
     */
    @Test
    public void base_64_decodeToString_invalidBase64String() {
        String decodeSource = "InvalidBase64String#$";
        Assert.assertNull(Utils.Base64.decodeToString(decodeSource, logger));
    }

    /**
     * "Base64.decode"
     * An invalid base 64 string is given to the function
     * Should return null
     */
    @Test
    public void base_64_decode_invalidBase64String() {
        String decodeSource = "InvalidBase64String#$";
        Assert.assertNull(Utils.Base64.decode(decodeSource, logger));
    }

    /**
     * "Base64.decode"
     * A valid base64 encoded value is given to the function
     * Should return the expected base64 decoded value
     */
    @Test
    public void base_64_decodeToByte() {
        String decodeSource = "MTIzNDU=";
        String decodeTarget = "12345";
        byte[] decodeTargetBytes = decodeTarget.getBytes();
        byte[] resBytes = Utils.Base64.decode(decodeSource, null);

        Assert.assertArrayEquals(decodeTargetBytes, resBytes);
    }

    /**
     * "Base64.encode"
     * A valid byte array is given to the function
     * Should return the expected base64 encoded value
     */
    @Test
    public void base_64_encodeByte() {
        String source = "12345";
        byte[] sourceBytes = source.getBytes();
        String resTarget = "MTIzNDU=";

        Assert.assertEquals(resTarget, Utils.Base64.encode(sourceBytes));
    }

    /**
     * "Base64.encode"
     * A valid string is given to the function
     * Should return the expected base64 encoded value
     */
    @Test
    public void base_64_encodeString() {
        String source = "12345";
        String resTarget = "MTIzNDU=";

        Assert.assertEquals(resTarget, Utils.Base64.encode(source));
    }

    /**
     * "urlencode"
     * A null string is given to the function
     * Should throw null pointer expection
     */
    @Test(expected = NullPointerException.class)
    public void urlencode_null() {
        Utils.urlencode(null, null);
    }

    /**
     * "urlencode"
     * An empty string is given to the function
     * Should return empty string
     */
    @Test
    public void urlencode_empty() {
        final String givenString = "";
        final String res = Utils.urlencode(givenString, null);
        Assert.assertEquals(givenString, res);
    }

    /**
     * "urlencode"
     * A valid string which is need to be encoded is given to the function
     * Should return the encoded string
     */
    @Test
    public void urlencode_symbols() {
        final String givenString = "~!@ #$%^&()_+{ }:\"|[]\\|,./<>?❤️";
        final String res = Utils.urlencode(givenString, null);
        Assert.assertEquals("%7E%21%40+%23%24%25%5E%26%28%29_%2B%7B+%7D%3A%22%7C%5B%5D%5C%7C%2C.%2F%3C%3E%3F%E2%9D%A4%EF%B8%8F", res);
    }

    /**
     * "urlencode"
     * A valid string which is not need to be encoded is given to the function
     * Should return the same string
     */
    @Test
    public void urlencode_basicAlphanumericals() {
        final String givenString = "TheQuickBrownFoxJumpsOverTheLazyDog1234567890.-*_";
        final String res = Utils.urlencode(givenString, null);
        Assert.assertEquals(givenString, res);
    }

    /**
     * "urlencode"
     * An invalid encoding is given to the function and a valid string given
     * Should return empty string
     */
    @Test
    public void urlencode_unsupportedEncoding() {
        Assert.assertEquals("", Utils.urlencode("urlencode_unsupportedEncoding", logger, "UTF-9"));
    }

    /**
     * "join"
     * A null array is given to the function
     * Should throw null pointer expection
     */
    @Test(expected = NullPointerException.class)
    public void joinCollection_nullCollection() {
        Utils.join(null, "g");
    }

    /**
     * "join"
     * An empty collection is given to the function
     * Should return empty string
     */
    @Test
    public void joinCollection_emptyCollection() {
        Assert.assertEquals("", Utils.join(new ArrayList<>(), "g"));
    }

    /**
     * "join"
     * A null separator is given to the function
     * Should return the flatten string value of the array with joined by 'null'
     */
    @Test
    public void joinCollection_nullSeparator() {
        Collection<Object> objects = Arrays.asList("1", "2", "3");
        Assert.assertEquals("1null2null3", Utils.join(objects, null));
    }

    /**
     * "join"
     * An empty separator is given to the function
     * Should return the flatten string value of the array
     */
    @Test
    public void joinCollection_emptySeparator() {
        Collection<Object> objects = Arrays.asList("1", "2", "3");
        Assert.assertEquals("123", Utils.join(objects, ""));
    }

    /**
     * "join"
     * A collection of strings is given to the function
     * Should return the string value
     */
    @Test
    public void joinCollection_simpleStrings() {
        Collection<Object> objects = Arrays.asList("11", "22", "33");

        String res = Utils.join(objects, "f");
        Assert.assertEquals("11f22f33", res);
    }

    /**
     * "join"
     * A collection of strings with numbers is given to the function
     * Should return the string value
     */
    @Test
    public void joinCollection_stringsWithNumbers() {
        String separator = "&";
        Collection<Object> objects = new ArrayList<Object>() {
        };
        objects.add("str");
        objects.add("string");
        objects.add("int");
        objects.add(999);
        objects.add("float");
        objects.add(.2f);
        objects.add("long");
        objects.add(2L);
        objects.add("double");
        objects.add(.2);

        String res = Utils.join(objects, separator);
        Assert.assertEquals("str&string&int&999&float&0.2&long&2&double&0.2", res);
    }

    /**
     * "isEmptyOrNull"
     * Check out different strings to that they are empty or null
     */
    @Test
    public void isEmptyOrNull() {
        Assert.assertFalse(Utils.isEmptyOrNull("notthatempty"));
        Assert.assertTrue(Utils.isEmptyOrNull(""));
        Assert.assertTrue(Utils.isEmptyOrNull(null));
    }

    /**
     * "isNotEmpty"
     * Check out different strings to that they are not empty or not
     */
    @Test
    public void isNotEmpty() {
        Assert.assertTrue(Utils.isNotEmpty("notthatempty"));
        Assert.assertFalse(Utils.isNotEmpty(""));
        Assert.assertFalse(Utils.isNotEmpty(null));
    }

    /**
     * It checks if the "isValidDataType" method is called.
     * And if the data types passed there are valid
     * for the segmentation.
     */
    @Test
    public void isValidDataType() {
        Assert.assertTrue(Utils.isValidDataType("string"));
        Assert.assertTrue(Utils.isValidDataType(6));
        Assert.assertTrue(Utils.isValidDataType(6.0));
        Assert.assertTrue(Utils.isValidDataType(6.0f));
        Assert.assertTrue(Utils.isValidDataType(BigDecimal.valueOf(6.0)));
        Assert.assertTrue(Utils.isValidDataType(6L));
        Assert.assertTrue(Utils.isValidDataType(true));
        Assert.assertTrue(Utils.isValidDataType(false));
        Assert.assertFalse(Utils.isValidDataType(null));
        Assert.assertFalse(Utils.isValidDataType(new Object()));
        Assert.assertFalse(Utils.isValidDataType(new ArrayList<>()));
        Assert.assertFalse(Utils.isValidDataType(new HashMap<>()));
    }

    /**
     * It checks if the "readFileContent" method is called.
     * And if the created file is read correctly.
     */
    @Test
    public void readFileContent() throws IOException {
        String fileName = "testFile";
        String fileContent = "testContent";

        File file = new File(fileName);
        Files.createFile(file.toPath());
        try (BufferedWriter writer = Files.newBufferedWriter(file.toPath())) {
            writer.write(fileContent);
            writer.close();

            String result = Utils.readFileContent(file, logger);
            //delete file
            Files.delete(file.toPath());
            Assert.assertEquals(fileContent, result);
        }
    }

    /**
     * If the file does not exist,
     * the method should return an empty string.
     */
    @Test
    public void readFileContent_fileNotExist() throws IOException {
        String fileName = "testFile";
        String fileContent = "testContent";

        File file = new File(fileName);

        String result = Utils.readFileContent(file, logger);

        Assert.assertNotEquals(fileContent, result);
        Assert.assertEquals("", result);
    }

    /**
     * If the file is not readable for some reason,
     * the method should return empty string.
     */
    @Test
    public void readFileContent_fileNotReadable() throws IOException {
        try {
            String fileContent = "testContent";

            File file = new File(TEST_FILE_NAME);
            Files.createFile(file.toPath());
            BufferedWriter writer = Files.newBufferedWriter(file.toPath());
            writer.write(fileContent);
            writer.close();
            Files.setPosixFilePermissions(file.toPath(), EnumSet.of(PosixFilePermission.OWNER_WRITE));

            String content = Utils.readFileContent(file, logger);
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                Assert.assertEquals(fileContent, content);
            } else {
                Assert.assertEquals("", content);
            }
        } finally {
            File file = new File(TEST_FILE_NAME);
            Files.deleteIfExists(file.toPath());
        }
    }

    /**
     * "isValidURL"
     * Check out different strings to that they are a valid URL
     */
    @Test
    public void isValidURL() {
        Assert.assertTrue(Utils.isValidURL("https://xxx.server.ly"));
        Assert.assertFalse(Utils.isValidURL(""));
        Assert.assertFalse(Utils.isValidURL(null));
        Assert.assertFalse(Utils.isValidURL("test"));
        Assert.assertFalse(Utils.isValidURL("/Users/Countly/test.txt"));
    }

    /**
     * "urldecode"
     * A valid URL decoded string is given
     * Should return the decoded string
     */
    @Test
    public void urldecode() {
        String decodeTarget = "~!@ #$%^&()_+{ }:\"|[]\\|,./<>?TheQuickBrownFoxJumpsOverTheLazyDog1234567890.-*_";
        String decodeSource = "%7E%21%40+%23%24%25%5E%26%28%29_%2B%7B+%7D%3A%22%7C%5B%5D%5C%7C%2C.%2F%3C%3E%3FTheQuickBrownFoxJumpsOverTheLazyDog1234567890.-*_";

        Assert.assertEquals(decodeTarget, Utils.urldecode(decodeSource));
    }

    /**
     * "urldecode"
     * An invalid URL decoded string is given
     * Should return null
     */
    @Test
    public void urldecode_invalid() {
        String decodeSource = " #$%^&()_+{";
        Assert.assertNull(Utils.urldecode(decodeSource));
    }

    /**
     * "isEqual"
     * Check out different objects to that they are equal or not
     */
    @Test
    public void isEqual() {
        Assert.assertTrue(Utils.isEqual(null, null));
        Assert.assertFalse(Utils.isEqual(null, "test"));
        Assert.assertFalse(Utils.isEqual("test", null));
        Assert.assertTrue(Utils.isEqual("test", "test"));
        Assert.assertFalse(Utils.isEqual("test", "test1"));
        Assert.assertTrue(Utils.isEqual(1, 1));
        Assert.assertFalse(Utils.isEqual(1, 2));
        Assert.assertTrue(Utils.isEqual(1.0, 1.0));
        Assert.assertFalse(Utils.isEqual(1.0, 2.0));
        Assert.assertTrue(Utils.isEqual(1.0f, 1.0f));
        Assert.assertFalse(Utils.isEqual(1.0f, null));
        Assert.assertFalse(Utils.isEqual(1.0f, 2.0f));
        Assert.assertTrue(Utils.isEqual(BigDecimal.valueOf(1.0), BigDecimal.valueOf(1.0)));
        Assert.assertFalse(Utils.isEqual(BigDecimal.valueOf(1.0), BigDecimal.valueOf(2.0)));
        Assert.assertTrue(Utils.isEqual(1L, 1L));
        Assert.assertFalse(Utils.isEqual(1L, 2L));
        Assert.assertTrue(Utils.isEqual(true, true));
        Assert.assertFalse(Utils.isEqual(true, false));
        Assert.assertFalse(Utils.isEqual(false, null));
    }

    /**
     * "contains"
     * Check out different strings to that they are contained or not
     */
    @Test
    public void contains() {
        Assert.assertTrue(Utils.contains("test", "test"));
        Assert.assertTrue(Utils.contains("test", "es"));
        Assert.assertFalse(Utils.contains("test", "test1"));
        Assert.assertFalse(Utils.contains("test", "es1"));
        Assert.assertFalse(Utils.contains("test", null));
        Assert.assertFalse(Utils.contains(null, "test"));
    }

    /**
     * "hex"
     * Validate given string to "hex" matches with expected
     * Function should return the expected string hexadecimal representation of the given byte array
     */
    @Test
    public void hex() {
        Assert.assertEquals("6865782d74657374", Utils.hex("hex-test".getBytes()));
    }

    /**
     * "hex"
     * A null string and an empty string is given to the function
     * Should return empty string for both cases
     */
    @Test
    public void hex_nullEmpty() {
        Assert.assertEquals("", Utils.hex(null));
        Assert.assertEquals("", Utils.hex("".getBytes()));
    }

    /**
     * "digestHex"
     * A valid string and a valid algorithm is given to the function
     * Should return the string value generated by the algorithm
     */
    @Test
    public void digestHex() {
        String expectedSHA256 = "41bb6dd513e3572d640450a0abb37271221585ea2554cc8a45928dd4caad6e2c";
        Assert.assertEquals(expectedSHA256, Utils.digestHex("SHA-256", "test digest hex", logger));
    }

    /**
     * "digestHex"
     * A null string, an empty string and an invalid algorithm is given to the function
     * Should return null for null string and invalid algorithm
     * Should return the string value for empty string
     */
    @Test
    public void digestHex_nullEmptyInvalid() {
        Assert.assertNull(Utils.digestHex("SHA-256", null, logger));
        Assert.assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Utils.digestHex("SHA-256", "", logger));
        Assert.assertNull(Utils.digestHex("SHA-XX", null, logger));
    }

    /**
     * "readStream"
     * A valid input stream is given and a null input stream is given
     * - First assert should return null because null stream given
     * - Second assert should return the string value
     */
    @Test
    public void readStream() {
        String value = "tryitoout";
        Assert.assertNull(Utils.readStream(null, logger));
        Assert.assertEquals(value, new String(Utils.readStream(new ByteArrayInputStream(value.getBytes()), logger)));
    }

    /**
     * "safeRandomVal"
     * An random value is generated and validated by the regex, and consist of 2 parts
     * Should return the string value generated by the algorithm and matches with the regex
     *
     * @throws NumberFormatException for parsing part 2
     */
    @Test
    public void safeRandomVal() throws NumberFormatException {
        String val = Utils.safeRandomVal();
        Pattern pattern = Pattern.compile("^([a-zA-Z0-9]{8})(\\d+)$");

        Matcher matcher = pattern.matcher(val);
        if (matcher.matches()) {
            Assert.assertFalse(matcher.group(1).isEmpty());
            Assert.assertTrue(Long.parseLong(matcher.group(2)) > 0);
        } else {
            Assert.fail("No match for " + val);
        }
    }
}
