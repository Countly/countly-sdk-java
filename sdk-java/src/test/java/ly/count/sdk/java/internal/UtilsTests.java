package ly.count.sdk.java.internal;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import ly.count.sdk.java.Config;
import org.junit.After;
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

    @After
    public void cleanupEveryTests() {
    }

    @Test
    public void trimKey() {
        String result = Utils.trimKey(3, "Key_01", logger);
        Assert.assertEquals("Key", result);
    }

    @Test
    public void trimValue() {
        String result = Utils.trimValue(5, "Key", "Value1", logger);
        Assert.assertEquals("Value", result);
    }

    @Test
    public void trimValues() {
        String[] result = Utils.trimValues(3, new String[] { "zelda", "link", "ganon" }, logger);
        Assert.assertEquals(3, result.length);
        Assert.assertEquals("zel", result[0]);
        Assert.assertEquals("lin", result[1]);
        Assert.assertEquals("gan", result[2]);
    }

    @Test
    public void trimSegmentation() {
        Map<String, String> segmentation = new HashMap<String, String>() {{
            put("key_10", "value1_");
            put("key_20", "value2_");
        }};

        Map<String, String> result = Utils.fixSegmentKeysAndValues(5, 6, segmentation, logger);
        Assert.assertEquals(2, result.size());
        Assert.assertEquals("value1", result.get("key_1"));
        Assert.assertEquals("value2", result.get("key_2"));
    }

    @Test
    public void base_64_decodeToString() {
        String decodeSource = "MTIzNDU=";
        String decodeTarget = "12345";

        Assert.assertEquals(decodeTarget, Utils.Base64.decodeToString(decodeSource, null));
    }

    @Test
    public void base_64_decodeToByte() {
        String decodeSource = "MTIzNDU=";
        String decodeTarget = "12345";
        byte[] decodeTargetBytes = decodeTarget.getBytes();
        byte[] resBytes = Utils.Base64.decode(decodeSource, null);

        Assert.assertArrayEquals(decodeTargetBytes, resBytes);
    }

    @Test
    public void base_64_encodeByte() {
        String source = "12345";
        byte[] sourceBytes = source.getBytes();
        String resTarget = "MTIzNDU=";

        Assert.assertEquals(resTarget, Utils.Base64.encode(sourceBytes));
    }

    @Test
    public void base_64_encodeString() {
        String source = "12345";
        String resTarget = "MTIzNDU=";

        Assert.assertEquals(resTarget, Utils.Base64.encode(source));
    }

    @Test(expected = NullPointerException.class)
    public void urlencode_null() {
        final String givenString = null;
        final String res = Utils.urlencode(givenString, null);
    }

    @Test
    public void urlencode_empty() {
        final String givenString = "";
        final String res = Utils.urlencode(givenString, null);
        junit.framework.Assert.assertEquals(givenString, res);
    }

    @Test
    public void urlencode_symbols() {
        final String givenString = "~!@ #$%^&()_+{ }:\"|[]\\|,./<>?";
        final String res = Utils.urlencode(givenString, null);
        junit.framework.Assert.assertEquals("%7E%21%40+%23%24%25%5E%26%28%29_%2B%7B+%7D%3A%22%7C%5B%5D%5C%7C%2C.%2F%3C%3E%3F", res);
    }

    @Test
    public void urlencode_basicAlphanumericals() {
        final String givenString = "TheQuickBrownFoxJumpsOverTheLazyDog1234567890.-*_";
        final String res = Utils.urlencode(givenString, null);
        junit.framework.Assert.assertEquals(givenString, res);
    }

    @Test(expected = NullPointerException.class)
    public void joinCollection_nullCollection() {
        String separator = "g";
        Collection<Object> objects = null;

        Utils.join(objects, separator);
    }

    @Test
    public void joinCollection_emptyCollection() {
        String separator = "g";
        Collection<Object> objects = new ArrayList<Object>() {
        };

        String res = Utils.join(objects, separator);
        junit.framework.Assert.assertEquals("", res);
    }

    @Test
    public void joinCollection_nullseparator() {
        String separator = null;
        Collection<Object> objects = new ArrayList<Object>() {
        };
        objects.add("1");
        objects.add("2");
        objects.add("3");

        String res = Utils.join(objects, separator);
        junit.framework.Assert.assertEquals("1null2null3", res);
    }

    @Test
    public void joinCollection_emptyseparator() {
        String separator = "";
        Collection<Object> objects = new ArrayList<Object>() {
        };
        objects.add("1");
        objects.add("2");
        objects.add("3");

        String res = Utils.join(objects, separator);
        junit.framework.Assert.assertEquals("123", res);
    }

    @Test
    public void joinCollection_simpleStrings() {
        String separator = "f";
        Collection<Object> objects = new ArrayList<Object>() {
        };
        objects.add("11");
        objects.add("22");
        objects.add("33");

        String res = Utils.join(objects, separator);
        junit.framework.Assert.assertEquals("11f22f33", res);
    }

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
        junit.framework.Assert.assertEquals("str&string&int&999&float&0.2&long&2&double&0.2", res);
    }

    @Test
    public void isEmpty() {
        junit.framework.Assert.assertFalse(Utils.isEmptyOrNull("notthatempty"));
        junit.framework.Assert.assertTrue(Utils.isEmptyOrNull(""));
        junit.framework.Assert.assertTrue(Utils.isEmptyOrNull(null));
    }

    @Test
    public void isNotEmpty() {
        junit.framework.Assert.assertTrue(Utils.isNotEmpty("notthatempty"));
        junit.framework.Assert.assertFalse(Utils.isNotEmpty(""));
        junit.framework.Assert.assertFalse(Utils.isNotEmpty(null));
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
        file.createNewFile();
        FileWriter writer = new FileWriter(file);
        writer.write(fileContent);
        writer.close();

        String result = Utils.readFileContent(file);
        //delete file
        file.delete();
        Assert.assertEquals(fileContent, result);
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

        String result = Utils.readFileContent(file);

        Assert.assertNotEquals(fileContent, result);
        Assert.assertEquals("", result);
    }

    /**
     * If the file is not readable for some reason,
     * the method should throw exception.
     */
    @Test(expected = IOException.class)
    public void readFileContent_fileNotReadable() throws IOException {
        try {
            String fileContent = "testContent";

            File file = new File(TEST_FILE_NAME);
            file.createNewFile();
            FileWriter writer = new FileWriter(file);
            writer.write(fileContent);
            writer.close();
            file.setReadable(false);

            Utils.readFileContent(file);
        } finally {
            File file = new File(TEST_FILE_NAME);
            if (file.exists()) {
                file.delete();
            }
        }
    }
}
