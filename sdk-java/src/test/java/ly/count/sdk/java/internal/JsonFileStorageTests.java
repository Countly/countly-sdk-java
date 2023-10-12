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

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@RunWith(JUnit4.class)
public class JsonFileStorageTests {

    Log L = mock(Log.class);
    IKeyValueStorage<String, Object> storage;

    static final String JSON_FILE_NAME = "test.json";

    @After
    public void tearDown() {
        L = mock(Log.class);
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
     * Existing mock json file is given to constructor
     * Storage size should be 1 and key-value pair should be same
     */
    @Test
    public void readJsonFile() {
        setupJsonFile("{\"key\": \"value\"}");
        storage = new JsonFileStorage(jsonFile(), L);

        Assert.assertEquals(1, storage.size());
        Assert.assertEquals("value", storage.get("key"));
    }

    /**
     * "readJsonFile" with empty json file
     * Existing empty mock json file is given to constructor
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
        File file = mock(File.class);
        doThrow(new IOException("Simulated IOException")).when(file).createNewFile();

        storage = new JsonFileStorage(file, L);

        verify(L).e("[JsonFileStorage] readJsonFile, Failed to read json file, reason: [Simulated IOException]");
        Assert.assertEquals(0, storage.size());
    }

    /**
     * "addAndSave"
     * Mock key-value pair is given to add method
     * Key-value pair must be same with the storage k-v and disk k-v
     */
    @Test
    public void addAndSave() {
        storage = new JsonFileStorage(jsonFile(), L);
        storage.addAndSave("key", 67.2);
        Assert.assertEquals(67.2, storage.get("key"));
        Assert.assertEquals(67.2, ((BigDecimal) TestUtils.readJsonFile(jsonFile()).opt("key")).doubleValue(), 0);
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
        storage.add(null, "value");
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
        storage.add("key", null);
        Assert.assertNull(storage.get("key"));
        validateStorageSize(0, 0);
    }

    /**
     * "clear"
     * Mock key-value pair is given to "addAndSave" method
     * Storage size should be 0 after clear, disk size should be 1 because "save" method not called
     */
    @Test
    public void clear() {
        storage = new JsonFileStorage(jsonFile(), L);
        storage.addAndSave("key", "??--//");
        validateStorageSize(1, 1);
        storage.clear();
        validateStorageSize(0, 1);
    }

    /**
     * "clearAndSave"
     * Mock key-value pair is given to "addAndSave" method
     * Storage size should be 0 after clear, disk size should be 0 after "clearAndSave" method called
     */
    @Test
    public void clearAndSave() {
        storage = new JsonFileStorage(jsonFile(), L);
        storage.addAndSave("key", new String[] { "value" });
        validateStorageSize(1, 1);
        storage.clearAndSave();
        validateStorageSize(0, 0);
    }

    /**
     * "delete"
     * Mock key-value pair is given to "addAndSave" method
     * Storage size and disk size should be 0 after delete
     */
    @Test
    public void delete() {
        storage = new JsonFileStorage(jsonFile(), L);
        storage.addAndSave("key", 56.34);
        validateStorageSize(1, 1);

        storage.deleteAndSave("key");
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
        storage.addAndSave("key", 56L);
        storage.add("key1", "value");
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
        storage.add("key", 35);
        storage.add("", "test");
        validateStorageSize(2, 0);

        storage.save();
        storage.delete("");
        validateStorageSize(1, 2);
    }

    /**
     * "deleteAndSave"
     * Mock key-value pair is given to "addAndSave" method
     * Storage size should be same as expected size in steps
     */
    @Test
    public void deleteAndSave() {
        storage = new JsonFileStorage(jsonFile(), L);

        storage.add("key", 4214);
        storage.add("key2", 5135);
        storage.save();
        validateStorageSize(2, 2);

        storage.deleteAndSave("key");
        validateStorageSize(1, 1);

        storage.delete("key2");
        validateStorageSize(0, 1);
    }

    private static File jsonFile() {
        return new File(TestUtils.getTestSDirectory(), JSON_FILE_NAME);
    }

    private void validateStorageSize(int expectedStorageSize, int expectedJsonFileSize) {
        Assert.assertEquals(expectedStorageSize, storage.size());
        Assert.assertEquals(expectedJsonFileSize, TestUtils.readJsonFile(jsonFile()).length());
    }

    private void setupJsonFile(String content) {
        try {
            Files.write(jsonFile().toPath(), content.getBytes());
        } catch (IOException e) {
            Assert.fail("Failed to create test.json file, reason: [" + e.getMessage() + "]");
        }
    }
}
