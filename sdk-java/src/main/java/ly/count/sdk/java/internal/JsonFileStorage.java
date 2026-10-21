package ly.count.sdk.java.internal;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import javax.annotation.Nonnull;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class JsonFileStorage {
    private final JSONObject json;
    private final File file;
    private final Log logger;

    /**
     * Create new instance of {@link JsonFileStorage} with given file.
     *
     * @param file to store data in
     * @param logger to use
     */
    public JsonFileStorage(@Nonnull final File file, @Nonnull Log logger) {
        this.logger = logger;
        this.file = file;
        deleteLeftoverTemporaryFile();
        this.json = readJsonFile(file);
    }

    /**
     * Deletes the temporary file of a save that was stopped before its move. The json file still
     * holds the previous save then, and a leftover file would be taken for other stored SDK data.
     */
    private void deleteLeftoverTemporaryFile() {
        try {
            Files.deleteIfExists(temporaryFileOf(file).toPath());
        } catch (IOException e) {
            logger.w("[JsonFileStorage] deleteLeftoverTemporaryFile, Failed to delete the temporary file, reason: [" + e + "]");
        }
    }

    /**
     * If key exists changes its value, otherwise adds new key-value pair
     * Null key or value is not allowed
     * Do not forget to call save to save changes to the disk/db/memory
     *
     * @param key to set
     * @param value to add
     */
    public synchronized void add(@Nonnull final String key, @Nonnull Object value) {
        logger.i("[JsonFileStorage] add, Adding key: [" + key + "], value: [" + value + "]");
        json.put(key, value);
    }

    /**
     * Saves changes to the disk/db/memory. The data is written next to the file and then moved over
     * it, so a process stopped while saving leaves the previous file whole. When the data cannot be
     * serialized, the file keeps what it had.
     */
    public synchronized void save() {
        logger.i("[JsonFileStorage] save, Saving json file: [" + file.getAbsolutePath() + "]");

        // toString returns null instead of throwing, so a failed serialization has to be caught before anything is written
        String content = json.toString();
        if (content == null) {
            logger.e("[JsonFileStorage] save, Failed to serialize the data, the json file is left as it is: [" + file.getAbsolutePath() + "]");
            return;
        }

        File temporary = temporaryFileOf(file);
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(temporary.toPath())) {
                writer.write(content);
            }
            replaceFile(temporary, file);
        } catch (IOException e) {
            logger.e("[JsonFileStorage] save, Failed to save json file, reason: [" + e.getMessage() + "]");
        }
    }

    /**
     * The file a save writes before moving it over the json file.
     *
     * @param file the json file
     * @return the temporary file next to it
     */
    static File temporaryFileOf(@Nonnull File file) {
        return new File(file.getAbsolutePath() + ".tmp");
    }

    /**
     * Moves a file over another, atomically where the file system supports it.
     *
     * @param source the file to move
     * @param target the file it replaces
     * @throws IOException when the move fails
     */
    private static void replaceFile(@Nonnull File source, @Nonnull File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Removes key-value pair
     * Null key is not allowed
     * Do not forget to call save to save changes to the disk/db/memory
     *
     * @param key to remove
     */
    public synchronized void delete(@Nonnull final String key) {
        if (!json.has(key)) {
            logger.v("[JsonFileStorage] delete, Nothing to delete");
        }
        json.remove(key);
    }

    /**
     * Adds key-value pair and saves changes to the disk/db/memory
     * Null key or value is not allowed
     *
     * @param key to set
     * @param value to add
     */
    public synchronized void addAndSave(@Nonnull final String key, @Nonnull Object value) {
        add(key, value);
        save();
    }

    /**
     * Removes key-value pair and saves changes to the disk/db/memory
     * Null key is not allowed
     *
     * @param key to remove
     */
    public synchronized void deleteAndSave(@Nonnull final String key) {
        delete(key);
        save();
    }

    /**
     * Returns value for the key
     * Null key is not allowed
     *
     * @param key to get
     * @return value
     */
    public synchronized Object get(@Nonnull final String key) {
        return json.opt(key);
    }

    /**
     * Returns a copy of the JSONObject value for the key. Changing the copy changes nothing stored:
     * hand it back through {@link #add(String, Object)} to store it, so another thread that saves
     * never serializes an object while it is being changed.
     *
     * @param key to get
     * @param defaultValue to return if key not found
     * @return a deep copy of the value, if key not found returns defaultValue
     */
    public synchronized JSONObject getJsonObj(@Nonnull final String key, final JSONObject defaultValue) {
        JSONObject value = json.optJSONObject(key, null);
        if (value == null) {
            return defaultValue;
        }
        return (JSONObject) deepCopy(value);
    }

    /**
     * Copies a JSON value, every nested object and array included, keeping every other value as it is.
     *
     * @param value the value to copy
     * @return the copy, or the value itself when it holds nothing that can change
     */
    private static Object deepCopy(final Object value) {
        if (value instanceof JSONObject) {
            JSONObject source = (JSONObject) value;
            JSONObject copy = new JSONObject();
            for (String key : source.keySet()) {
                copy.put(key, deepCopy(source.opt(key)));
            }
            return copy;
        }

        if (value instanceof JSONArray) {
            JSONArray source = (JSONArray) value;
            JSONArray copy = new JSONArray();
            for (int i = 0; i < source.length(); i++) {
                copy.put(deepCopy(source.opt(i)));
            }
            return copy;
        }

        return value;
    }

    /**
     * Returns String value for the key
     *
     * @param key to get
     * @return value, if key not found returns null
     */
    public synchronized String getString(@Nonnull final String key) {
        return getString(key, null);
    }

    /**
     * Returns String value for the key
     *
     * @param key to get
     * @param defaultValue to return if key not found
     * @return value, if key not found returns defaultValue
     */
    public synchronized String getString(@Nonnull final String key, final String defaultValue) {
        return json.optString(key, defaultValue);
    }

    /**
     * Returns integer value for the key
     *
     * @param key to get
     * @param defaultValue to return if key not found
     * @return value, if key not found returns defaultValue
     */
    public synchronized int getInt(@Nonnull final String key, final int defaultValue) {
        return json.optInt(key, defaultValue);
    }

    /**
     * Clears all data
     */
    public synchronized void clear() {
        json.clear();
    }

    /**
     * Clears all data and saves changes to the disk/db/memory
     */
    public synchronized void clearAndSave() {
        clear();
        save();
    }

    /**
     * Returns number of key-value pairs
     *
     * @return number of key-value pairs
     */
    public synchronized int size() {
        return json.length();
    }

    private JSONObject readJsonFile(@Nonnull final File file) {
        logger.i("[JsonFileStorage] readJsonFile, Reading json file: [" + file.getAbsolutePath() + "]");
        try {
            if (!file.exists()) {
                boolean result = file.createNewFile();
                logger.v("[JsonFileStorage] readJsonFile, Creating new json file: [" + file.getAbsolutePath() + "], result: [" + result + "]");
                return new JSONObject();
            }
            String fileContent = Utils.readFileContent(file, logger);
            if (Utils.isEmptyOrNull(fileContent)) {
                logger.v("[JsonFileStorage] readJsonFile, Json file is empty: [" + file.getAbsolutePath() + "]");
                return new JSONObject();
            }
            return new JSONObject(fileContent);
        } catch (IOException e) {
            logger.e("[JsonFileStorage] readJsonFile, Failed to read json file, reason: [" + e.getMessage() + "]");
            return new JSONObject();
        } catch (JSONException e) {
            logger.e("[JsonFileStorage] readJsonFile, The json file is not valid json, starting with empty storage that replaces it on the next save: [" + file.getAbsolutePath() + "], reason: [" + e.getMessage() + "]");
            return new JSONObject();
        }
    }
}
