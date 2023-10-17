package ly.count.sdk.java.internal;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class SDKStorage implements StorageProvider {

    private Log L;
    InternalConfig config;

    //file and prefix names
    protected static final String FILE_NAME_PREFIX = "[CLY]";
    protected static final String FILE_NAME_SEPARATOR = "_";
    protected static final String EVENT_QUEUE_FILE_NAME = "event_queue";
    protected static final String JSON_FILE_NAME = "countly_store.json";

    //key names
    protected static final String device_id_key = "did";
    protected static final String device_id_strategy_key = "did_s";

    private JsonFileStorage jsonFileStorage;

    protected SDKStorage() {

    }

    public void init(InternalConfig config, Log logger) {
        this.L = logger;
        this.config = config;
        jsonFileStorage = new JsonFileStorage(createFileFullPathWithPrefix(config, JSON_FILE_NAME), L);
        Storage.init();
    }

    public void stop(InternalConfig config, boolean clear) {
        Storage.await(L);
        if (clear) {
            storablePurge(config, null);
            jsonFileStorage.clearAndSave();
        }
        jsonFileStorage = null;
        Storage.stop();
    }

    private static String getName(Storable storable) {
        return getName(storable.storagePrefix(), storable.storageId().toString());
    }

    private static String getName(String... names) {
        if (names == null || names.length == 0 || Utils.isEmptyOrNull(names[0])) {
            return FILE_NAME_PREFIX;
        } else {
            StringBuilder prefix = new StringBuilder(FILE_NAME_PREFIX);
            for (String name : names) {
                if (name == null) {
                    break;
                }
                prefix.append(FILE_NAME_SEPARATOR).append(name);
            }
            return prefix.toString();
        }
    }

    private static String extractName(String filename, String prefix) {
        if (filename.indexOf(prefix) == 0) {
            return filename.substring(prefix.length());
        } else {
            return null;
        }
    }

    public int storablePurge(InternalConfig config, String prefix) {
        prefix = getName(prefix) + FILE_NAME_SEPARATOR;

        L.i("[SDKStorage] Purging storage for prefix " + prefix);

        int deleted = 0;

        String[] files = getFileList(config);
        for (String file : files) {
            if (file.startsWith(prefix)) {
                if (deleteFile(config, file)) {
                    deleted++;
                }
            }
        }

        return deleted;
    }

    public Boolean storableWrite(ly.count.sdk.java.internal.InternalConfig config, String prefix, Long id, byte[] data) {
        String filename = getName(prefix, id.toString());

        FileOutputStream stream = null;
        FileLock lock = null;
        try {
            stream = openFileAsOutputStream(config, filename);
            lock = stream.getChannel().tryLock();
            if (lock == null) {
                return false;
            }
            stream.write(data);
            stream.close();
            return true;
        } catch (IOException e) {
            L.w("[SDKStorage] Cannot write data to " + filename + " " + e);
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException e) {
                    L.w("[SDKStorage] Couldn't close output stream for " + filename + " " + e);
                }
            }
            if (lock != null && lock.isValid()) {
                try {
                    lock.release();
                } catch (IOException e) {
                    L.w("[SDKStorage] Couldn't release lock for " + filename + " " + e);
                }
            }
        }
        return false;
    }

    public <T extends Storable> Boolean storableWrite(ly.count.sdk.java.internal.InternalConfig config, T storable) {
        return storableWrite(config, storable.storagePrefix(), storable.storageId(), storable.store(L));
    }

    private String createFileFullPath(ly.count.sdk.java.internal.InternalConfig config, String filename) {
        String directoryPath = config.getSdkStorageRootDirectory().getAbsolutePath();
        return directoryPath + File.separator + filename;
    }

    private File createFileFullPathWithPrefix(ly.count.sdk.java.internal.InternalConfig config, String filename) {
        return new File(config.getSdkStorageRootDirectory(), FILE_NAME_PREFIX + FILE_NAME_SEPARATOR + filename);
    }

    private FileInputStream openFileAsInputStream(ly.count.sdk.java.internal.InternalConfig config, String filename) throws FileNotFoundException {
        File initialFile = new File(createFileFullPath(config, filename));
        return new FileInputStream(initialFile);
    }

    private FileOutputStream openFileAsOutputStream(ly.count.sdk.java.internal.InternalConfig config, String filename) throws FileNotFoundException {
        File initialFile = new File(createFileFullPath(config, filename));
        return new FileOutputStream(initialFile);
    }

    public byte[] storableReadBytes(InternalConfig config, String filename) {
        ByteArrayOutputStream buffer = null;
        FileInputStream stream = null;

        try {
            buffer = new ByteArrayOutputStream();

            stream = openFileAsInputStream(config, filename);

            int read;
            byte[] data = new byte[4096];
            while ((read = stream.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, read);
            }

            stream.close();

            return buffer.toByteArray();
        } catch (FileNotFoundException e) {
            return null;
        } catch (IOException e) {
            L.e("[SDKStorage] Error while reading file " + filename + " " + e);
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException e) {
                    L.e("[SDKStorage] Couldn't close input stream for " + filename + " " + e);
                }
            }
            if (buffer != null) {
                try {
                    buffer.close();
                } catch (IOException e) {
                    L.e("[SDKStorage] Cannot happen" + e);
                }
            }
        }
        return null;
    }

    public byte[] storableReadBytes(InternalConfig config, String prefix, Long id) {
        return storableReadBytes(config, getName(prefix, id.toString()));
    }

    public <T extends Storable> Boolean storableRead(InternalConfig config, T storable) {
        byte[] data = storableReadBytes(config, getName(storable));
        if (data == null) {
            return null;
        } else {
            return storable.restore(data, L);
        }
    }

    public <T extends Storable> Map.Entry<Long, byte[]> storableReadBytesOneOf(InternalConfig config, T storable, boolean asc) {
        List<Long> list = storableList(config, storable.storagePrefix(), asc ? 1 : -1);
        if (list.size() > 0) {
            return new AbstractMap.SimpleEntry<>(list.get(0), storableReadBytes(config, getName(storable.storagePrefix(), list.get(0).toString())));
        }
        return null;
    }

    private boolean deleteFile(InternalConfig config, String filename) {
        File file = new File(createFileFullPath(config, filename));
        return file.delete();
    }

    public <T extends Storable> Boolean storableRemove(InternalConfig config, T storable) {
        return deleteFile(config, getName(storable.storagePrefix(), storable.storageId().toString()));
    }

    public <T extends Storable> Boolean storablePop(InternalConfig config, T storable) {
        Boolean read = storableRead(config, storable);
        if (read == null) {
            return null;
        } else if (read) {
            return storableRemove(config, storable);
        }
        return read;
    }

    private String[] getFileList(InternalConfig config) {
        File[] files = (config.getSdkStorageRootDirectory()).listFiles();
        if (files == null) {
            return new String[0];
        }

        ArrayList<String> fileNames = new ArrayList<>();

        for (File file : files) {
            if (file.isFile()) {
                fileNames.add(file.getName());
            }
        }

        String[] ret = new String[fileNames.size()];
        return fileNames.toArray(ret);
    }

    public List<Long> storableList(InternalConfig config, String prefix, int slice) {
        if (Utils.isEmptyOrNull(prefix)) {
            L.e("[SDKStorage] Cannot get list of ids without prefix");
        }
        prefix = prefix + FILE_NAME_SEPARATOR;

        String alternativePrefix = FILE_NAME_PREFIX + FILE_NAME_SEPARATOR + prefix;

        List<Long> list = new ArrayList<>();

        String[] files = getFileList(config);
        Arrays.sort(files);

        int max = slice == 0 ? Integer.MAX_VALUE : Math.abs(slice);
        for (int i = 0; i < files.length; i++) {
            int idx = slice >= 0 ? i : files.length - 1 - i;
            String file = files[idx];
            if (file.startsWith(prefix) || file.startsWith(alternativePrefix)) {
                try {
                    String name = extractName(file, file.startsWith(prefix) ? prefix : alternativePrefix);
                    if (name != null) {
                        list.add(Long.parseLong(name));
                    }
                } catch (NumberFormatException nfe) {
                    L.e("[SDKStorage] Wrong file name: " + file + " / " + prefix);
                }
                if (list.size() >= max) {
                    break;
                }
            }
        }
        return list;
    }

    protected void storeEventQueue(String eventQueue) {
        L.d("[SDKStorage] Writing EQ to json file");

        //write eventQueue that is consist of json objects of events and separated by ":::" delimiter
        //to the config.getSdkStorageRootDirectory() folder named Storage.name(this) + ".events"
        //if file already exists overwrite it
        //if file doesn't exist create it
        //if file can't be created or written, log the error
        File file = createFileFullPathWithPrefix(config, EVENT_QUEUE_FILE_NAME);

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            // Write the eventQueue to the file
            writer.write(eventQueue);
            L.d("[SDKStorage] Wrote EQ to json file");
        } catch (IOException e) {
            // Handle the error if writing fails
            L.e("[SDKStorage] Failed to write EQ to json file: " + e.toString());
        }
    }

    protected String readEventQueue() {
        L.d("[SDKStorage] Getting event queue");
        File file = createFileFullPathWithPrefix(config, EVENT_QUEUE_FILE_NAME);

        String eventQueue = "";

        try {
            eventQueue = Utils.readFileContent(file, L);
        } catch (IOException e) {
            // Handle the error if reading fails
            L.e("[SDKStorage] Failed to read EQ from json file: " + e);
        }

        return eventQueue;
    }

    @Override
    public String getDeviceID() {
        return (String) jsonFileStorage.get(device_id_key);
    }

    @Override
    public void setDeviceID(String deviceID) {
        jsonFileStorage.addAndSave(device_id_key, deviceID);
    }

    @Override
    public DeviceIdStrategy getDeviceIdStrategy() {
        return DeviceIdStrategy.valueOf((String) jsonFileStorage.get(device_id_strategy_key));
    }

    @Override
    public void setDeviceIdStrategy(DeviceIdStrategy deviceIdStrategy) {
        jsonFileStorage.addAndSave(device_id_strategy_key, deviceIdStrategy.toString());
    }
}
