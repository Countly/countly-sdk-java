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
import org.junit.Assert;

import static ly.count.sdk.java.internal.SDKStorage.EVENT_QUEUE_FILE_NAME;
import static ly.count.sdk.java.internal.SDKStorage.FILE_NAME_PREFIX;
import static ly.count.sdk.java.internal.SDKStorage.FILE_NAME_SEPARATOR;
import static org.mockito.Mockito.mock;

public class TestUtils {
    static String SERVER_URL = "https://test.count.ly";
    static String SERVER_APP_KEY = "COUNTLY_APP_KEY";
    static String DEVICE_ID = "some_random_test_device_id";

    public static final String[] eKeys = new String[] { "eventKey1", "eventKey2", "eventKey3", "eventKey4", "eventKey5", "eventKey6", "eventKey7" };

    static String feedbackWidgetData =
        "[{\"_id\":\"5b2158ea790ce051e713db07\",\"email\":\"user@mailprovider.com\",\"comment\":\"Notbad,thismightbebetter.\",\"ts\":1528912105570,\"device_id\":\"fb@count.ly\",\"cd\":\"2018-06-13T17:48:26.071Z\",\"uid\":\"1\",\"contact_me\":false,\"rating\":3,\"widget_id\":\"5b21581b967c4850a7818617\"},{\"_id\":\"5b2b80823a69147963e5b826\",\"email\":\"5score@rating.com\",\"comment\":\"5comment.\",\"ts\":1529577665765,\"device_id\":\"fb@count.ly\",\"cd\":\"2018-06-21T10:40:02.746Z\",\"uid\":\"1\",\"contact_me\":true,\"rating\":5,\"widget_id\":\"5b21581b967c4850a7818617\"}]";

    private TestUtils() {
    }

    static Config getBaseConfig() {
        File sdkStorageRootDirectory = getTestSDirectory();
        checkSdkStorageRootDirectoryExist(sdkStorageRootDirectory);
        Config config = new Config(SERVER_URL, SERVER_APP_KEY, sdkStorageRootDirectory);
        config.setCustomDeviceId(DEVICE_ID);

        return config;
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
        File sdkStorageRootDirectory = getTestSDirectory();
        checkSdkStorageRootDirectoryExist(sdkStorageRootDirectory);
        Config config = new Config(SERVER_URL, SERVER_APP_KEY, sdkStorageRootDirectory);
        config.setCustomDeviceId(DEVICE_ID);

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

    protected static Map<String, String>[] getCurrentRequestQueue() {
        return getCurrentRequestQueue(getTestSDirectory(), mock(Log.class));
    }

    /**
     * Get current request queue from target folder
     *
     * @param targetFolder folder where requests are stored
     * @param logger logger
     * @return array of request params
     */
    protected static Map<String, String>[] getCurrentRequestQueue(File targetFolder, Log logger) {

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

    /**
     * Get current event queue from target folder
     *
     * @param targetFolder where events are stored
     * @param logger logger
     * @return array of json events
     */
    protected static List<EventImpl> getCurrentEventQueue(File targetFolder, Log logger) {
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

    static void validateEventQueueSize(int expectedSize, List<EventImpl> events, EventQueue eventQueue) {
        Assert.assertEquals(expectedSize, events.size());
        Assert.assertEquals(expectedSize, eventQueue.eqSize());
    }

    static void validateEventQueueSize(int expectedSize, EventQueue eventQueue) {
        validateEventQueueSize(expectedSize, TestUtils.getCurrentEventQueue(getTestSDirectory(), mock(Log.class)), eventQueue);
    }

    static String getOs() {
        return System.getProperty("os.name");
    }
}