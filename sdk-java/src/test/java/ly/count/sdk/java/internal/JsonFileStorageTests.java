package ly.count.sdk.java.internal;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

import static ly.count.sdk.java.internal.TestUtils.keysValues;

@RunWith(JUnit4.class)
public class JsonFileStorageTests {

    private static final Log L = Mockito.mock(Log.class);
    JsonFileStorage storage;
    static final String JSON_FILE_NAME = "test.json";

    /**
     * Delete test.json file if exists
     */
    @After
    public void tearDown() {
        try {
            Files.delete(jsonFile().toPath());
        } catch (IOException ignored) {
            //do nothing
        }
    }

    /**
     * "readJsonFile" with not existing json file,
     * Non-existing file is given to constructor
     * Storage size should be 0
     */
    @Test
    public void readJsonFile_notExistingJsonFile() {
        storage = new JsonFileStorage(jsonFile(), L);
        Assert.assertEquals(0, storage.size());
    }

    /**
     * "readJsonFile"
     * Existing json file is given to constructor
     * Storage size should be 1 and key-value pair should be same
     */
    @Test
    public void readJsonFile() {
        setupJsonFile("{\"key\": \"value\"}");
        storage = new JsonFileStorage(jsonFile(), L);

        Assert.assertEquals(1, storage.size());
        Assert.assertEquals(keysValues[1], storage.get(keysValues[0]));
    }

    /**
     * "readJsonFile" with empty json file
     * Existing empty json file is given to constructor
     * Storage size should be 0
     */
    @Test
    public void readJsonFile_emptyJsonFile() {
        setupJsonFile("");
        storage = new JsonFileStorage(jsonFile(), L);

        Assert.assertEquals(0, storage.size());
    }

    /**
     * "readJsonFile" with mocked exception,
     * mock {@link File} is given to constructor and set up a simulated exception
     * logger should log expected log and storage size should be 0
     */
    @Test
    public void readJsonFile_IOException() throws IOException {
        File file = Mockito.mock(File.class);
        Mockito.doThrow(new IOException("Simulated IOException")).when(file).createNewFile();

        storage = new JsonFileStorage(file, L);

        Mockito.verify(L).e("[JsonFileStorage] readJsonFile, Failed to read json file, reason: [Simulated IOException]");
        Assert.assertEquals(0, storage.size());
        //todo add other storage calls to verify that nothing is throwing exceptions
    }

    /**
     * "addAndSave"
     * key-value pair is given to add method
     * Key-value pair must be same with the storage k-v and disk k-v
     */
    @Test
    public void addAndSave() {
        storage = new JsonFileStorage(jsonFile(), L);
        storage.addAndSave(keysValues[0], 67.2);
        Assert.assertEquals(67.2, storage.get(keysValues[0]));
        double valueInFile = ((BigDecimal) TestUtils.readJsonFile(jsonFile()).opt(keysValues[0])).doubleValue();
        Assert.assertEquals(67.2, valueInFile, 0);
        validateStorageSize(1, 1);
    }

    /**
     * "add" with null key,
     * Null key is given to add method
     * Storage size should be 0 and "get" function must return null
     */
    @Test(expected = NullPointerException.class)
    public void add_nullKey() {
        storage = new JsonFileStorage(jsonFile(), L);
        storage.add(null, keysValues[1]);
        Assert.assertNull(storage.get(null));
        validateStorageSize(0, 0);
    }

    /**
     * "add" with empty key,
     * Empty key is given to add method
     * Storage size should be 1 and "get" function must return expected value
     */
    @Test
    public void add_emptyKey() {
        storage = new JsonFileStorage(jsonFile(), L);
        storage.add("", 89);
        Assert.assertEquals(89, storage.get(""));
        validateStorageSize(1, 0);
    }

    /**
     * "add" with null value,
     * Null value is given to add method
     * Storage size should be 0 and "get" function must return null
     */
    @Test
    public void add_nullValue() {
        storage = new JsonFileStorage(jsonFile(), L);
        storage.add(keysValues[0], null);
        Assert.assertNull(storage.get(keysValues[0]));
        validateStorageSize(0, 0);
    }

    /**
     * "clear"
     * key-value pair is given to "addAndSave" method and the "clear" without "save' is called
     * Storage size should be 0 after "clear", disk size should be 1 because "save" method not called
     */
    @Test
    public void clear() {
        storage = new JsonFileStorage(jsonFile(), L);
        storage.addAndSave(keysValues[0], "??--//");
        validateStorageSize(1, 1);
        storage.clear();
        validateStorageSize(0, 1);
    }

    /**
     * "clearAndSave"
     * key-value pair is given to "addAndSave" method then we "clear and save"
     * Storage size should be 0 after clear, disk size should be 0 after "clearAndSave" method called
     */
    @Test
    public void clearAndSave() {
        storage = new JsonFileStorage(jsonFile(), L);
        storage.addAndSave(keysValues[0], new String[] { keysValues[1] });
        validateStorageSize(1, 1);
        storage.clearAndSave();
        validateStorageSize(0, 0);
    }

    /**
     * "delete"
     * key-value pair is given to "addAndSave" method then we "delete and save" a specific value
     * Storage size and disk size should be 0 after delete
     */
    @Test
    public void delete() {
        storage = new JsonFileStorage(jsonFile(), L);
        storage.addAndSave(keysValues[0], 56.34);
        validateStorageSize(1, 1);

        storage.deleteAndSave(keysValues[0]);
        validateStorageSize(0, 0);
    }

    /**
     * "delete" with null key,
     * Null key is given to delete method
     * Storage size should be same as before delete
     */
    @Test
    public void delete_nullKey() {
        storage = new JsonFileStorage(jsonFile(), L);
        storage.addAndSave(keysValues[0], 56L);
        storage.add(keysValues[2], keysValues[1]);
        validateStorageSize(2, 1);

        storage.delete(null);
        validateStorageSize(2, 1);
    }

    /**
     * "delete" with empty key,
     * Empty key is given to delete method
     * Storage size should decrease by 1
     */
    @Test
    public void delete_emptyKey() {
        storage = new JsonFileStorage(jsonFile(), L);
        storage.add(keysValues[0], 35);
        storage.add("", "test");
        validateStorageSize(2, 0);

        storage.save();
        storage.delete("");
        validateStorageSize(1, 2);
    }

    /**
     * "deleteAndSave"
     * key-value pair is given to "addAndSave" method
     * Storage size should be same as expected size in steps
     */
    @Test
    public void deleteAndSave() {
        storage = new JsonFileStorage(jsonFile(), L);

        storage.add(keysValues[0], 4214);
        storage.add(keysValues[2], 5135);
        storage.save();
        validateStorageSize(2, 2);

        storage.deleteAndSave(keysValues[0]);
        validateStorageSize(1, 1);

        storage.delete(keysValues[2]);
        validateStorageSize(0, 1);
    }

    private static File jsonFile() {
        return new File(TestUtils.getTestSDirectory(), JSON_FILE_NAME);
    }

    private void validateStorageSize(final int expectedStorageSize, final int expectedJsonFileSize) {
        Assert.assertEquals(expectedStorageSize, storage.size());
        Assert.assertEquals(expectedJsonFileSize, TestUtils.readJsonFile(jsonFile()).length());
    }

    private void setupJsonFile(final String content) {
        try {
            Files.write(jsonFile().toPath(), content.getBytes());
        } catch (IOException e) {
            Assert.fail("Failed to create test.json file, reason: [" + e.getMessage() + "]");
        }
    }
}
