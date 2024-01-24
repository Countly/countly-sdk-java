package ly.count.sdk.java.internal;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assert;

import static ly.count.sdk.java.internal.SDKStorage.EVENT_QUEUE_FILE_NAME;
import static ly.count.sdk.java.internal.SDKStorage.FILE_NAME_PREFIX;
import static ly.count.sdk.java.internal.SDKStorage.FILE_NAME_SEPARATOR;
import static ly.count.sdk.java.internal.SDKStorage.JSON_FILE_NAME;
import static org.mockito.Mockito.mock;

/**
 * Glossary:
 * RQ - request queue
 * EQ - event queue
 * MV - migration version
 */
public class TestUtils {
    static String SERVER_URL = "https://test.server.com";
    static String SERVER_APP_KEY = "COUNTLY_APP_KEY";
    static String DEVICE_ID = "some_random_test_device_id";
    static String SDK_NAME = "java-native";
    static String SDK_VERSION = "23.10.1";
    static String APPLICATION_VERSION = "1.0";

    public static final String[] eKeys = new String[] { "eventKey1", "eventKey2", "eventKey3", "eventKey4", "eventKey5", "eventKey6", "eventKey7" };

    public static final String[] keysValues = new String[] { "key", "value", "key1", "value1", "key2", "value2" };

    static String feedbackWidgetData =
        "[{\"_id\":\"5b2158ea790ce051e713db07\",\"email\":\"user@mailprovider.com\",\"comment\":\"Notbad,thismightbebetter.\",\"ts\":1528912105570,\"device_id\":\"fb@count.ly\",\"cd\":\"2018-06-13T17:48:26.071Z\",\"uid\":\"1\",\"contact_me\":false,\"rating\":3,\"widget_id\":\"5b21581b967c4850a7818617\"},{\"_id\":\"5b2b80823a69147963e5b826\",\"email\":\"5score@rating.com\",\"comment\":\"5comment.\",\"ts\":1529577665765,\"device_id\":\"fb@count.ly\",\"cd\":\"2018-06-21T10:40:02.746Z\",\"uid\":\"1\",\"contact_me\":true,\"rating\":5,\"widget_id\":\"5b21581b967c4850a7818617\"}]";

    private TestUtils() {
    }

    static Config getBaseConfig() {
        return getBaseConfig(DEVICE_ID);
    }

    static Config getBaseConfig(String deviceID) {
        File sdkStorageRootDirectory = getTestSDirectory();
        checkSdkStorageRootDirectoryExist(sdkStorageRootDirectory);
        Config config = new Config(SERVER_URL, SERVER_APP_KEY, sdkStorageRootDirectory);
        config.setApplicationVersion(APPLICATION_VERSION);

        config.setCustomDeviceId(deviceID);
        return config;
    }

    static void setAdditionalDeviceMetrics() {
        Device.dev.setCpu("CPU1.2");
        Device.dev.setBatteryLevel(0.52f);
        Device.dev.setManufacturer("Manufacturer");
        Device.dev.setMuted(true);
        Device.dev.setOpenGL("OpenGL2.3.1");
        Device.dev.setOnline(true);
        Device.dev.setOrientation("portrait");
        Device.dev.setResolution("100x100");
        Device.dev.setDevice("Device");
        Device.dev.setAppVersion("1.0");
    }

    static Config getConfigRemoteConfigs() {
        return getBaseConfig().enableFeatures(Config.Feature.RemoteConfig);
    }

    static Config getConfigSessions(Config.Feature... features) {
        Config config = getBaseConfig();
        config.setEventQueueSizeToSend(2);
        config.enableFeatures(features);
        config.enableFeatures(Config.Feature.Sessions);

        return config;
    }

    static Config getConfigDeviceId(String deviceId) {
        return getBaseConfig(deviceId).enableFeatures(Config.Feature.Sessions, Config.Feature.Views, Config.Feature.Events).setEventQueueSizeToSend(10);
    }

    static Config getConfigSessions() {
        return getConfigSessions((Config.Feature) null);
    }

    static Config getConfigEvents(Integer eventThreshold) {
        Config config = getBaseConfig();
        config.enableFeatures(Config.Feature.Events);

        if (eventThreshold != null) {
            config.setEventQueueSizeToSend(eventThreshold);
        }

        return config;
    }

    static Config getConfigFeedback() {
        return getConfigFeedback((Config.Feature) null);
    }

    static Config getConfigFeedback(Config.Feature... features) {
        Config config = getBaseConfig();

        config.enableFeatures(features);
        config.enableFeatures(Config.Feature.Feedback);

        return config;
    }

    public static File getTestSDirectory() {
        // System specific folder structure
        String[] sdkStorageRootPath = { System.getProperty("user.home"), "__COUNTLY", "java_test" };
        return new File(String.join(File.separator, sdkStorageRootPath));
    }

    static void checkSdkStorageRootDirectoryExist(File directory) {
        if (!(directory.exists() && directory.isDirectory())) {
            if (!directory.mkdirs()) {
                Assert.fail("Failed to create directory: " + directory.getAbsolutePath());
            }
        }
    }

    protected static Map<String, String>[] getCurrentRQ() {
        return getCurrentRQ(getTestSDirectory(), mock(Log.class));
    }

    /**
     * Get current request queue from target folder
     *
     * @param targetFolder folder where requests are stored
     * @param logger logger
     * @return array of request params
     */
    protected static Map<String, String>[] getCurrentRQ(File targetFolder, Log logger) {
        Storage.await(mock(Log.class)); // wait for request to be written to the disk

        //check whether target folder is a directory or not
        if (!targetFolder.isDirectory()) {
            logger.e("[TestUtils] " + targetFolder.getAbsolutePath() + " is not a directory");
            return new ConcurrentHashMap[0];
        }

        //get all request files from target folder
        File[] requestFiles = getRequestFiles(targetFolder);

        //create array of request params
        Map<String, String>[] resultMapArray = new ConcurrentHashMap[requestFiles.length];

        for (int i = 0; i < requestFiles.length; i++) {
            File file = requestFiles[i];
            try {
                //parse request params from file
                Map<String, String> paramMap = parseRequestParams(file);
                resultMapArray[i] = paramMap;
            } catch (IOException e) {
                Assert.fail("Failed to read request params from file reason: " + e.getMessage());
            }
        }

        return resultMapArray;
    }

    static void flushCurrentRQWithOldDeviceId(String oldDeviceId) {
        Arrays.stream(getRequestFiles(getTestSDirectory())).forEach(file -> {
            try {
                if (file.exists() && file.isFile()) {
                    String content = Utils.readFileContent(file, mock(Log.class));
                    if (content.contains(oldDeviceId)) {
                        Files.delete(file.toPath());
                    }
                }
            } catch (IOException ignored) {
                //do nothing
            }
        });
    }

    protected static List<EventImpl> getCurrentEQ() {
        return getCurrentEQ(getTestSDirectory(), mock(Log.class));
    }

    /**
     * Get current event queue from target folder
     *
     * @param targetFolder where events are stored
     * @param logger logger
     * @return array of json events
     */
    protected static List<EventImpl> getCurrentEQ(File targetFolder, Log logger) {
        List<EventImpl> events = new ArrayList<>();

        if (!targetFolder.isDirectory()) {
            logger.e("[TestUtils] " + targetFolder.getAbsolutePath() + " is not a directory");
            return events;
        }

        File file = new File(targetFolder, FILE_NAME_PREFIX + FILE_NAME_SEPARATOR + EVENT_QUEUE_FILE_NAME);
        String fileContent = "";
        try {
            fileContent = Utils.readFileContent(file, logger);
        } catch (IOException e) {
            Assert.fail("Failed to read event queue from file reason: " + e.getMessage());
        }

        Arrays.stream(fileContent.split(EventQueue.DELIMITER)).forEach(s -> {
            final EventImpl event = EventImpl.fromJSON(s, (ev) -> {
            }, logger);
            if (event != null) {
                events.add(event);
            }
        });

        return events;
    }

    /**
     * Get last item from list
     *
     * @param list
     * @param <T> type of list
     * @return last item from list
     */
    public static <T> T getLastItem(List<T> list) {
        return list.isEmpty() ? null : list.get(list.size() - 1);
    }

    /**
     * Get request files from target folder, sorted by last modified
     *
     * @param targetFolder folder where requests are stored
     * @return array of request files sorted by last modified
     */
    private static File[] getRequestFiles(File targetFolder) {

        File[] files = targetFolder.listFiles();
        if (files == null) {
            return new File[0];
        }

        //stream all files from target folder and filter only request files and sort them by last modified
        return Stream.of(files)
            .filter(file -> file.getName().startsWith("[CLY]_request_"))
            .sorted(Comparator.comparing(file -> Long.parseLong(file.getName().split("_")[2])))
            .toArray(File[]::new);
    }

    /**
     * Parse request params from file. First line of file contains are urlencoded and
     * separated by "&" symbol and key-value pairs are separated by "=" symbol (key=value).
     *
     * @param file request file
     * @return map of request params
     * @throws IOException if file cannot be read
     */
    private static Map<String, String> parseRequestParams(File file) throws IOException {
        try (Scanner scanner = new Scanner(file)) {
            String firstLine = scanner.nextLine();
            if (Utils.isEmptyOrNull(firstLine)) {
                return new ConcurrentHashMap<>();
            }

            String[] params = firstLine.split("&");

            Map<String, String> paramMap = new ConcurrentHashMap<>();
            for (String param : params) {
                String[] pair = param.split("=");
                paramMap.put(Utils.urldecode(pair[0]), pair.length == 1 ? "" : Utils.urldecode(pair[1]));
            }

            return paramMap;
        }
    }

    /**
     * Parse query params from string. String contains are urlencoded and
     * separated by "&" symbol and key-value pairs are separated by "=" symbol (key=value).
     *
     * @param data string with query params
     * @return map of query params
     */
    public static Map<String, String> parseQueryParams(String data) {
        if (data.contains("?")) {
            data = data.replace("?", "");
        }
        String[] params = data.split("&");
        Map<String, String> paramMap = new ConcurrentHashMap<>();
        for (String param : params) {
            String[] pair = param.split("=");
            paramMap.put(pair[0], pair[1]);
        }
        return paramMap;
    }

    static void validateEvent(EventImpl gonnaValidate, String key, Map<String, Object> segmentation, int count, Double sum, Double duration, String id, String pvid, String cvid, String peid) {
        Assert.assertEquals(key, gonnaValidate.key);

        if (segmentation != null) {
            Assert.assertEquals("Event segmentation size are not equal", segmentation.size(), gonnaValidate.segmentation.size());
            for (Map.Entry<String, Object> entry : segmentation.entrySet()) {
                Assert.assertEquals(entry.getValue(), gonnaValidate.segmentation.get(entry.getKey()));
            }
        }

        Assert.assertEquals(count, gonnaValidate.count);
        Assert.assertEquals(sum, gonnaValidate.sum);

        if (duration != null) {
            double delta = 0.5;
            Assert.assertTrue(duration + " expected duration, got " + gonnaValidate.duration, Math.abs(duration - gonnaValidate.duration) < delta);
        }

        Assert.assertTrue(gonnaValidate.dow >= 0 && gonnaValidate.dow < 7);
        Assert.assertTrue(gonnaValidate.hour >= 0 && gonnaValidate.hour < 24);
        Assert.assertTrue(gonnaValidate.timestamp >= 0);
        validateId(id, gonnaValidate.id, "Event ID");
        validateId(pvid, gonnaValidate.pvid, "Previous View ID");
        validateId(cvid, gonnaValidate.cvid, "Current View ID");
        validateId(peid, gonnaValidate.peid, "Previous Event ID");
    }

    // if id null
    private static void validateId(String id, String gonnaValidate, String name) {
        if (id != null && id.equals("_CLY_")) {
            validateSafeRandomVal(gonnaValidate);
        } else {
            Assert.assertEquals(name + " is not validated", id, gonnaValidate);
        }
    }

    static void validateEvent(EventImpl gonnaValidate, String key, Map<String, Object> segmentation, int count, Double sum, Double duration) {
        validateEvent(gonnaValidate, key, segmentation, count, sum, duration, null, null, null, null);
    }

    static void validateEventInEQ(String key, Map<String, Object> segmentation, int count, Double sum, Double duration, int index, int size, String id, String pvid, String cvid, String peid) {
        List<EventImpl> events = getCurrentEQ();
        validateEvent(events.get(index), key, segmentation, count, sum, duration, id, pvid, cvid, peid);
        validateEQSize(size);
    }

    static void validateEventInEQ(String key, Map<String, Object> segmentation, int count, Double sum, Double duration, int index, int size) {
        validateEventInEQ(key, segmentation, count, sum, duration, index, size, null, null, null, null);
    }

    static List<EventImpl> readEventsFromRequest() {
        return readEventsFromRequest(0, TestUtils.DEVICE_ID);
    }

    static List<EventImpl> readEventsFromRequest(int requestIndex, String deviceId) {
        Map<String, String> request = getCurrentRQ()[requestIndex];
        validateRequiredParams(request, deviceId);
        String events = request.get("events");
        List<EventImpl> result = new ArrayList<>();
        if (events == null) {
            return result;
        }
        JSONArray array = new JSONArray(events);

        array.forEach(value -> {
            result.add(EventImpl.fromJSON(value.toString(), (ev) -> {
            }, mock(Log.class)));
        });

        return result;
    }

    static void validateEQSize(int expectedSize, List<EventImpl> events, EventQueue eventQueue) {
        Assert.assertEquals(expectedSize, events.size());
        Assert.assertEquals(expectedSize, eventQueue.eqSize());
    }

    static void validateEQSize(int expectedSize, EventQueue eventQueue) {
        validateEQSize(expectedSize, TestUtils.getCurrentEQ(getTestSDirectory(), mock(Log.class)), eventQueue);
    }

    static void validateEQSize(int expectedSize) {
        Assert.assertEquals(expectedSize, getCurrentEQ().size());
    }

    static String getOS() {
        return System.getProperty("os.name");
    }

    /**
     * Write data to file
     *
     * @param fileName of file
     * @param data to write
     */
    static void writeToFile(final String fileName, final String data) {
        File file = new File(getTestSDirectory(), FILE_NAME_PREFIX + FILE_NAME_SEPARATOR + fileName);
        try (BufferedWriter writer = Files.newBufferedWriter(file.toPath())) {
            writer.write(data);
        } catch (IOException e) {
            //do nothing
        }
    }

    public static void validateRequiredParams(Map<String, String> params) {
        validateRequiredParams(params, TestUtils.DEVICE_ID);
    }

    public static void validateRequiredParams(Map<String, String> params, String deviceId) {
        int hour = Integer.parseInt(params.get("hour"));
        int dow = Integer.parseInt(params.get("dow"));
        int tz = Integer.parseInt(params.get("tz"));

        validateSdkIdentityParams(params);
        Assert.assertEquals(deviceId, params.get("device_id"));
        Assert.assertEquals(SERVER_APP_KEY, params.get("app_key"));
        Assert.assertEquals(APPLICATION_VERSION, params.get("av"));
        Assert.assertTrue(Long.parseLong(params.get("timestamp")) > 0);
        Assert.assertTrue(hour >= 0 && hour < 24);
        Assert.assertTrue(dow >= 0 && dow < 7);
        Assert.assertTrue(tz >= -720 && tz <= 840);
    }

    /**
     * Validate sdk identity params which are sdk version and name
     *
     * @param params params to validate
     */
    public static void validateSdkIdentityParams(Map<String, String> params) {
        Assert.assertEquals(SDKCore.instance.config.getSdkVersion(), params.get("sdk_version"));
        Assert.assertEquals(SDKCore.instance.config.getSdkName(), params.get("sdk_name"));
    }

    /**
     * Create a clean test state by deleting all files from test directory
     */
    public static void createCleanTestState() {
        Countly.instance().halt();

        try (Stream<Path> files = Files.list(getTestSDirectory().toPath())) {
            files.forEach(path -> {
                try {
                    Assert.assertTrue(Files.deleteIfExists(path));
                } catch (IOException ignored) {
                    //do nothing
                }
            });
        } catch (IOException ignored) {
            //do nothing
        }
    }

    /**
     * Get property from json file for test purposes
     *
     * @param key property key
     * @return property value
     */
    public static Object getJsonStorageProperty(final String key) {
        File file = new File(getTestSDirectory(), FILE_NAME_PREFIX + FILE_NAME_SEPARATOR + JSON_FILE_NAME);
        return readJsonFile(file).get(key);
    }

    /**
     * Read json file from test resources with
     * desired prefix and separator
     *
     * @param name of file
     * @return json object
     */
    static JSONObject readJsonFile(final String name) {
        return readJsonFile(new File(getTestSDirectory(), FILE_NAME_PREFIX + FILE_NAME_SEPARATOR + name));
    }

    /**
     * Read json file for test purposes
     * If file cannot be read, return empty json object
     *
     * @param file file to read
     * @return json object
     */
    static JSONObject readJsonFile(final File file) {
        try {
            return new JSONObject(Utils.readFileContent(file, mock(Log.class)));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /**
     * Create a file for test purposes
     * If file cannot be created, return null and assert fail
     *
     * @param fileName name of the file to create
     * @return created file
     */
    public static File createFile(final String fileName) {
        File file = new File(getTestSDirectory(), FILE_NAME_PREFIX + FILE_NAME_SEPARATOR + fileName);
        try {
            if (file.createNewFile()) {
                return file;
            }
        } catch (IOException e) {
            Assert.fail("Failed to create file: " + e.getMessage());
        }

        return file;
    }

    static InternalConfig getInternalConfigWithLogger(Config config) {
        InternalConfig ic = new InternalConfig(config);
        ic.setLogger(mock(Log.class));
        return ic;
    }

    static void validateRequestMakerRequiredParams(String expectedEndpoint, String customEndpoint, Boolean requestShouldBeDelayed, Boolean networkingIsEnabled) {
        Assert.assertEquals(expectedEndpoint, customEndpoint);
        Assert.assertFalse(requestShouldBeDelayed);
        Assert.assertTrue(networkingIsEnabled);
    }

    static void validateMetrics(String metrics) {
        Params params = Device.dev.buildMetrics();
        Assert.assertEquals(params.get("metrics"), metrics);
    }

    static class AtomicString {
        String value;

        public AtomicString(String value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return value;
        }
    }

    /**
     * Converts a map to json string
     *
     * @param entries map to convert
     * @return json string
     */
    protected static String json(Map<String, Object> entries) {
        return jsonObj(entries).toString();
    }

    /**
     * Converts a map to json object
     *
     * @param entries map to convert
     * @return json string
     */
    protected static JSONObject jsonObj(Map<String, Object> entries) {
        JSONObject json = new JSONObject();
        entries.forEach(json::put);
        return json;
    }

    /**
     * Converts array of objects to json string
     * Returns empty json if array is null or empty
     *
     * @param args array of objects
     * @return json string
     */
    protected static String json(Object... args) {
        if (args == null || args.length == 0) {
            return "{}";
        }
        return json(map(args));
    }

    /**
     * Converts array of objects to a 'String, Object' map
     *
     * @param args array of objects
     * @return map
     */
    protected static Map<String, Object> map(Object... args) {
        Map<String, Object> map = new ConcurrentHashMap<>();
        if (args.length % 2 == 0) {
            for (int i = 0; i < args.length; i += 2) {
                map.put(args[i].toString(), args[i + 1]);
            }
        }
        return map;
    }

    /**
     * Converts array of objects to a 'String, Object' map
     *
     * @param gonnaAddMap map to add to, creates a copy of the map
     * @param args array of objects
     * @return map
     */
    protected static Map<String, Object> map(Map<String, Object> gonnaAddMap, Object... args) {
        Map<String, Object> map = new ConcurrentHashMap<>(gonnaAddMap);
        if (args.length % 2 == 0) {
            for (int i = 0; i < args.length; i += 2) {
                map.put(args[i].toString(), args[i + 1]);
            }
        }
        return map;
    }

    /**
     * Validates a random generated safe value,
     * Value length should be 21
     * Value should contain a timestamp at the end
     * Value should be base64 encoded and first 8 should be it
     *
     * @param val
     */
    static void validateSafeRandomVal(String val) {
        Assert.assertEquals(21, val.length());

        Pattern base64Pattern = Pattern.compile("^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=|[A-Za-z0-9+/]{4})$");

        String timestampStr = val.substring(val.length() - 13);
        String base64Str = val.substring(0, val.length() - 13);

        Matcher matcher = base64Pattern.matcher(base64Str);
        if (matcher.matches()) {
            TimeUtils.Instant instant = TimeUtils.getCurrentInstant(Long.parseLong(timestampStr));
            Assert.assertTrue(instant.dow >= 0 && instant.dow < 7);
            Assert.assertTrue(instant.hour >= 0 && instant.hour < 24);
            Assert.assertTrue(instant.timestamp > 0);
            Assert.assertTrue(instant.tz >= -720 && instant.tz <= 840);
        } else {
            Assert.fail("No match for " + val);
        }
    }

    static IdGenerator idGenerator() {
        AtomicInteger counter = new AtomicInteger(0);
        return () -> TestUtils.keysValues[counter.getAndIncrement() % TestUtils.keysValues.length];
    }

    static IdGenerator incrementalViewIdGenerator() {
        AtomicInteger counter = new AtomicInteger(0);
        return () -> "idv" + counter.incrementAndGet();
    }

    static InternalConfig getConfigViews() {
        InternalConfig config = new InternalConfig(TestUtils.getBaseConfig().enableFeatures(Config.Feature.Views, Config.Feature.Events));
        config.viewIdGenerator = TestUtils.incrementalViewIdGenerator();
        return config;
    }

    static InternalConfig getConfigViews(Map<String, Object> segmentation) {
        InternalConfig config = new InternalConfig(TestUtils.getBaseConfig().enableFeatures(Config.Feature.Views, Config.Feature.Events));
        config.viewIdGenerator = TestUtils.incrementalViewIdGenerator();
        config.views.setGlobalViewSegmentation(segmentation);
        return config;
    }
}
