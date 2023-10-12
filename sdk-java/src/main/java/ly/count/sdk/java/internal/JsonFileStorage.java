package ly.count.sdk.java.internal;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import org.json.JSONObject;

public class JsonFileStorage implements IKeyValueStorage<String, Object> {
    private final JSONObject json;
    private final File file;
    private final Log logger;

    public JsonFileStorage(File file, Log logger) {
        this.logger = logger;
        this.file = file;
        this.json = readJsonFile(file);
    }

    @Override
    public void add(String key, Object value) {
        logger.i("[JsonFileStorage] add, Adding key: [" + key + "], value: [" + value + "]");
        json.put(key, value);
    }

    @Override
    public void save() {
        logger.i("[JsonFileStorage] save, Saving json file: [" + file.getAbsolutePath() + "]");
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            writer.write(json.toString());
        } catch (IOException e) {
            logger.e("[JsonFileStorage] save, Failed to save json file, reason: [" + e.getMessage() + "]");
        }
    }

    @Override
    public void delete(String key) {
        logger.i("[JsonFileStorage] delete, Deleting key: [" + key + "]");
        json.remove(key);
    }

    @Override
    public void addAndSave(String key, Object value) {
        add(key, value);
        save();
    }

    @Override
    public void deleteAndSave(String key) {
        delete(key);
        save();
    }

    @Override
    public Object get(String key) {
        return json.get(key);
    }

    private JSONObject readJsonFile(File file) {
        logger.i("[JsonFileStorage] readJsonFile, Reading json file: [" + file.getAbsolutePath() + "]");
        try {
            return new JSONObject(Utils.readFileContent(file, logger));
        } catch (IOException e) {
            logger.e("[JsonFileStorage] readJsonFile, Failed to read json file, reason: [" + e.getMessage() + "]");
            return new JSONObject();
        }
    }
}
