package ly.count.sdk.java.internal;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.stream.Stream;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import org.json.JSONArray;
import org.junit.Assert;

import static ly.count.sdk.java.internal.SDKStorage.EVENT_QUEUE_FILE_NAME;
import static ly.count.sdk.java.internal.SDKStorage.FILE_NAME_PREFIX;
import static ly.count.sdk.java.internal.SDKStorage.FILE_NAME_SEPARATOR;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

public class TestUtils {
    static String SERVER_URL = "https://test.count.ly";
    static String SERVER_APP_KEY = "COUNTLY_APP_KEY";
    static String DEVICE_ID = "some_random_test_device_id";

    static String SDK_NAME = "java-native";

    static String SDK_VERSION = "23.8.0";

    public static final String[] eKeys = new String[] { "eventKey1", "eventKey2", "eventKey3", "eventKey4", "eventKey5", "eventKey6", "eventKey7" };

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

        config.setCustomDeviceId(deviceID);
        return config;
    }

    static Config getConfigSessions(Config.Feature... features) {
        File sdkStorageRootDirectory = getTestSDirectory();
        checkSdkStorageRootDirectoryExist(sdkStorageRootDirectory);
        Config config = new Config(SERVER_URL, SERVER_APP_KEY, sdkStorageRootDirectory);
        config.setCustomDeviceId(DEVICE_ID);
        config.setEventQueueSizeToSend(2);
        config.enableFeatures(features);
        config.enableFeatures(Config.Feature.Sessions);

        return config;
    }

    static Config getConfigSessions() {
        return getConfigSessions((Config.Feature) null);
    }

    static Config getConfigEvents(Integer eventThreshold) {
        File sdkStorageRootDirectory = getTestSDirectory();
        checkSdkStorageRootDirectoryExist(sdkStorageRootDirectory);
        Config config = new Config(SERVER_URL, SERVER_APP_KEY, sdkStorageRootDirectory);
        config.setCustomDeviceId(DEVICE_ID);

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
        File sdkStorageRootDirectory = getTestSDirectory();
        checkSdkStorageRootDirectoryExist(sdkStorageRootDirectory);
        Config config = new Config(SERVER_URL, SERVER_APP_KEY, sdkStorageRootDirectory);
        config.setCustomDeviceId(DEVICE_ID);
        config.setApplicationVersion("1.0");

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
                throw new RuntimeException("Directory creation failed");
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
        Storage.await(mock(Log.class)); // wait for request to be write to the disk

        //check whether target folder is a directory or not
        if (!targetFolder.isDirectory()) {
            logger.e("[TestUtils] " + targetFolder.getAbsolutePath() + " is not a directory");
            return new HashMap[0];
        }

        //get all request files from target folder
        File[] requestFiles = getRequestFiles(targetFolder);

        //create array of request params
        Map<String, String>[] resultMapArray = new HashMap[requestFiles.length];

        for (int i = 0; i < requestFiles.length; i++) {
            File file = requestFiles[i];
            try {
                //parse request params from file
                Map<String, String> paramMap = parseRequestParams(file);
                resultMapArray[i] = paramMap;
            } catch (IOException e) {
                logger.e("[TestUtils] " + e.getMessage());
            }
        }

        return resultMapArray;
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
            //do nothing
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
            .sorted(Comparator.comparing(file -> Long.parseLong(file.getName().split("_")[3])))
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
            String urlDecodedStr = Utils.urldecode(firstLine);

            if (urlDecodedStr == null) {
                return new HashMap<>();
            }

            String[] params = urlDecodedStr.split("&");

            Map<String, String> paramMap = new HashMap<>();
            for (String param : params) {
                String[] pair = param.split("=");
                paramMap.put(pair[0], pair[1]);
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
        Map<String, String> paramMap = new HashMap<>();
        for (String param : params) {
            String[] pair = param.split("=");
            paramMap.put(pair[0], pair[1]);
        }
        return paramMap;
    }

    static void validateEvent(EventImpl gonnaValidate, String key, Map<String, Object> segmentation, int count, Double sum, Double duration) {
        Assert.assertEquals(key, gonnaValidate.key);
        Assert.assertEquals(segmentation, gonnaValidate.segmentation);
        Assert.assertEquals(count, gonnaValidate.count);
        Assert.assertEquals(sum, gonnaValidate.sum);

        if (duration != null) {
            double delta = 0.1;
            Assert.assertTrue(Math.abs(duration - gonnaValidate.duration) < delta);
        }

        Assert.assertTrue(gonnaValidate.dow >= 0 && gonnaValidate.dow < 7);
        Assert.assertTrue(gonnaValidate.hour >= 0 && gonnaValidate.hour < 24);
        Assert.assertTrue(gonnaValidate.timestamp >= 0);
    }

    static void validateEventInEQ(String key, Map<String, Object> segmentation, int count, Double sum, Double duration, int index, int size) {
        List<EventImpl> events = getCurrentEQ();
        validateEvent(events.get(index), key, segmentation, count, sum, duration);
        validateEQSize(size);
    }

    static List<EventImpl> readEventsFromRequest() {
        return readEventsFromRequest(0);
    }

    static List<EventImpl> readEventsFromRequest(int requestIndex) {
        JSONArray array = new JSONArray(getCurrentRQ()[requestIndex].get("events"));
        List<EventImpl> result = new ArrayList<>();

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

    public static void validateRequiredParams(Map<String, String> params) {
        int hour = Integer.parseInt(params.get("hour"));
        int dow = Integer.parseInt(params.get("dow"));
        int tz = Integer.parseInt(params.get("tz"));

        validateSdkIdentityParams(params);
        Assert.assertEquals(SDKCore.instance.config.getDeviceId().id, params.get("device_id"));
        Assert.assertEquals(SDKCore.instance.config.getServerAppKey(), params.get("app_key"));
        Assert.assertTrue(Long.valueOf(params.get("timestamp")) > 0);
        Assert.assertTrue(hour > 0 && hour < 24);
        Assert.assertTrue(dow >= 0 && dow < 7);
        Assert.assertTrue(tz >= -720 && tz <= 840);
    }

    public static void validateSdkIdentityParams(Map<String, String> params) {
        Assert.assertEquals(SDKCore.instance.config.getSdkVersion(), params.get("sdk_version"));
        Assert.assertEquals(SDKCore.instance.config.getSdkName(), params.get("sdk_name"));
    }

    public static void createCleanTestState() {
        Countly.instance().halt();
        try {
            for (File file : getTestSDirectory().listFiles()) {
                file.delete();
            }
        } catch (Exception ignored) {
            //do nothing
        }
    }

    public static CtxCore getMockCtxCore() {
        CtxCore ctxCore = mock(CtxCore.class);

        //todo too hacky, burn it
        given(ctxCore.getLogger()).willReturn(mock(Log.class));
        InternalConfig ic = mock(InternalConfig.class);
        given(ic.getLogger()).willReturn(mock(Log.class));
        given(ctxCore.getConfig()).willReturn(ic);
        return ctxCore;
    }
}