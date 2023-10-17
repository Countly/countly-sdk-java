package ly.count.sdk.java.internal;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import javax.annotation.Nonnull;
import org.json.JSONObject;

public class JsonFileStorage implements IKeyValueStorage<String, Object> {
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
        this.json = readJsonFile(file);
    }

    @Override
    public void add(@Nonnull final String key, @Nonnull Object value) {
        logger.i("[JsonFileStorage] add, Adding key: [" + key + "], value: [" + value + "]");
        json.put(key, value);
    }

    @Override
    public void save() {
        logger.i("[JsonFileStorage] save, Saving json file: [" + file.getAbsolutePath() + "]");

        try (BufferedWriter writer = Files.newBufferedWriter(file.toPath())) {
            writer.write(json.toString());
        } catch (IOException e) {
            logger.e("[JsonFileStorage] save, Failed to save json file, reason: [" + e.getMessage() + "]");
        }
    }

    @Override
    public void delete(@Nonnull final String key) {
        if (!json.has(key)) {
            logger.v("[JsonFileStorage] delete, Nothing to delete");
        }
        json.remove(key);
    }

    @Override
    public void addAndSave(@Nonnull final String key, @Nonnull Object value) {
        add(key, value);
        save();
    }

    @Override
    public void deleteAndSave(@Nonnull final String key) {
        delete(key);
        save();
    }

    @Override
    public Object get(@Nonnull final String key) {
        try {
            return json.get(key);
        } catch (Exception e) {
            logger.e("[JsonFileStorage] get, Failed to get value for key: [" + key + "], reason: [" + e.getMessage() + "]");
            return null;
        }
    }

    @Override
    public void clear() {
        json.clear();
    }

    @Override
    public void clearAndSave() {
        clear();
        save();
    }

    @Override
    public int size() {
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
        }
    }
}
