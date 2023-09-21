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

import static ly.count.sdk.java.internal.SDKStorage.EVENT_QUEUE_FILE_NAME;
import static ly.count.sdk.java.internal.SDKStorage.FILE_NAME_PREFIX;
import static ly.count.sdk.java.internal.SDKStorage.FILE_NAME_SEPARATOR;

public class TestUtils {

    private static String DELIMETER = ":::";

    private TestUtils() {
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
            fileContent = Utils.readFileContent(file);
        } catch (IOException e) {
            //do nothing
        }

        //EventImplQueue.DELIMITER
        Arrays.stream(fileContent.split(DELIMETER)).forEach(s -> {
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
}