package ly.count.sdk.java.internal;

import java.io.File;
import java.io.IOException;
import org.json.JSONObject;

public class JsonFileStorage implements IKeyValueStorage<String, Object> {

    private final JSONObject json;
    private final Log logger;

    public JsonFileStorage(File file, Log logger) {
        this.logger = logger;
        this.json = readJsonFile(file);
    }

    @Override
    public void add(String key, Object value) {
        json.put(key, value);
    }

    @Override
    public void save() {
    }

    @Override
    public void delete(String key) {
        json.remove(key);
    }

    @Override
    public void addAndSave(String key, Object value) {
        add(key, value);
    }

    @Override
    public void deleteAndSave(String key) {
        delete(key);
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
