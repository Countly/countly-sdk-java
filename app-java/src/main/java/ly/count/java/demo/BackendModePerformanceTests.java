package ly.count.java.demo;

import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import ly.count.sdk.java.internal.DeviceCore;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

public class BackendModePerformanceTests {
    final static String DEVICE_ID = "device-id";
    final static String COUNTLY_APP_KEY = "YOUR_APP_KEY";
    final static String COUNTLY_SERVER_URL = "https://try.count.ly/";


    static void performLargeRequestQueueSizeTest() {
        System.out.println("===== Test Started: 'Large request queue size' =====");
        int requestQSize = 1000000;
        System.out.println("===== Test Start =====");
        System.out.printf("Before SDK Initialization: Total Memory = %dMb, Available RAM = %dMb %n", DeviceCore.dev.getRAMTotal(), DeviceCore.dev.getRAMAvailable());

        Config config = new Config(COUNTLY_SERVER_URL, COUNTLY_APP_KEY)
                .setLoggingLevel(Config.LoggingLevel.OFF)
                .enableBackendMode()
                .setRequestQueueMaxSize(requestQSize)
                .setDeviceIdStrategy(Config.DeviceIdStrategy.UUID)
                .setRequiresConsent(false)
                .enableParameterTamperingProtection("test-salt-checksum")
                .setEventsBufferSize(1);

        // Countly needs persistent storage for requests, configuration storage, user profiles and other temporary data,
        // therefore requires a separate data folder to run
        //File targetFolder = new File("/home/zahi/countly-workspace/data");

        File targetFolder = new File("C:\\Users\\zahid\\Documents\\Countly\\data");

        // Main initialization call, SDK can be used after this one is done
        Countly.init(targetFolder, config);
        System.out.printf("After SDK Initialization: Total Memory = %d Mb, Available RAM= %d Mb %n", DeviceCore.dev.getRAMTotal(), DeviceCore.dev.getRAMAvailable());

        long startTime = System.currentTimeMillis();
        int batchSize = requestQSize / 25;

        System.out.printf("Adding %d requests(events) into request Queue%n", batchSize);
        for (int i = 1; i < batchSize; ++i) {

            Map<String, Object> segment = new HashMap<String, Object>() {{
                put("Time Spent", 60);
                put("Retry Attempts", 60);
            }};

            Countly.backendMode().recordEvent(DEVICE_ID, "Event Key " + i, 1, 0.1, 5, segment, null);
        }

        System.out.printf("Adding %d requests(crash) into request Queue%n", batchSize);
        for (int i = 1; i < batchSize; ++i) {

            Map<String, Object> segmentation = new HashMap<String, Object>() {{
                put("login page", "authenticate request");
            }};
            Countly.backendMode().recordException(DEVICE_ID, "Message: " + i, "stack traces " + 1, segmentation, null);
        }

        System.out.printf("Adding %d requests(user properties) into request Queue%n", batchSize);
        for (int i = 1; i < batchSize; ++i) {

            // User detail
            Map<String, Object> userDetail = new HashMap<>();
            userDetail.put("name", "Full Name");
            userDetail.put("username", "username1");
            userDetail.put("email", "user@gmail.com");
            userDetail.put("organization", "Countly");
            userDetail.put("phone", i);
            userDetail.put("gender", "M");
            userDetail.put("byear", "1991");
            //custom detail
            userDetail.put("hair", "black");
            userDetail.put("height", 5.9);
            userDetail.put("fav-colors", "{$push: black}");
            userDetail.put("marks", "{$inc: 1}");

            Countly.backendMode().recordUserProperties(DEVICE_ID, userDetail, null);
        }

        System.out.printf("Adding %d requests(sessions) into request Queue%n", batchSize);
        for (int i = 1; i < batchSize; ++i) {
            Map<String, String> metrics = new HashMap<String, String>() {{
                put("_os", "Android");
                put("_os_version", "10");
                put("_app_version", "1.2");
            }};

            Countly.backendMode().sessionBegin(DEVICE_ID, metrics, null);
        }

        System.out.printf("Time spent: %dms%n", (System.currentTimeMillis() - startTime));
        System.out.printf("After adding %d request: Total Memory = %d Mb, Available RAM= %d Mb %n", requestQSize, DeviceCore.dev.getRAMTotal(), DeviceCore.dev.getRAMAvailable());

        Countly.stop(false);
        System.out.println("=====SDK Stop=====");
    }

    static void performLargeEventQueueTest() {
        int noOfEvents = 100000;
        System.out.println("===== Test Start: 'Large Event queues against multiple devices ids' =====");
        System.out.printf("Before SDK Initialization: Total Memory = %dMb, Available RAM = %dMb %n", DeviceCore.dev.getRAMTotal(), DeviceCore.dev.getRAMAvailable());

        Config config = new Config(COUNTLY_SERVER_URL, COUNTLY_APP_KEY)
                .setLoggingLevel(Config.LoggingLevel.OFF)
                .enableBackendMode()
                .setDeviceIdStrategy(Config.DeviceIdStrategy.UUID)
                .setRequiresConsent(false)
                .enableParameterTamperingProtection("test-salt-checksum")
                .setEventsBufferSize(noOfEvents);

        // Countly needs persistent storage for requests, configuration storage, user profiles and other temporary data,
        // therefore requires a separate data folder to run
        //File targetFolder = new File("/home/zahi/countly-workspace/data");

        File targetFolder = new File("C:\\Users\\zahid\\Documents\\Countly\\data");

        // Main initialization call, SDK can be used after this one is done
        Countly.init(targetFolder, config);
        System.out.printf("After SDK Initialization: Total Memory = %d Mb, Available RAM= %d Mb %n", DeviceCore.dev.getRAMTotal(), DeviceCore.dev.getRAMAvailable());

        long startTime = System.currentTimeMillis();

        int noOfDevices = 10;
        for (int d = 0; d <= noOfDevices; ++d) {
            System.out.printf("Adding %d events into event Queue against deviceID = %s%n", 100000, "device-id-" + d);
            for (int i = 1; i <= noOfEvents; ++i) {

                Map<String, Object> segment = new HashMap<String, Object>() {{
                    put("Time Spent", 60);
                    put("Retry Attempts", 60);
                }};

                Countly.backendMode().recordEvent("device-id-" + d, "Event Key " + i, 1, 0.1, 5, segment, null);
            }
        }

        System.out.printf("Time spent: %dms%n", (System.currentTimeMillis() - startTime));


        System.out.printf("After adding %d events into event queue: Total Memory = %d Mb, Available RAM= %d Mb %n", noOfEvents * noOfDevices, DeviceCore.dev.getRAMTotal(), DeviceCore.dev.getRAMAvailable());

        Countly.stop(false);
        System.out.println("=====SDK Stop=====");
    }

    static void recordBulkDataAndSendToServer() throws InterruptedException {

        System.out.println("===== Test Start: 'Record bulk data to server' =====");
        System.out.printf("Before SDK Initialization: Total Memory = %dMb, Available RAM = %dMb %n", DeviceCore.dev.getRAMTotal(), DeviceCore.dev.getRAMAvailable());

        Config config = new Config("https://master.count.ly/", "8c1d653f8f474be24958b282d5e9b4c4209ee552")
                .setLoggingLevel(Config.LoggingLevel.DEBUG)
                .enableBackendMode()
                .setDeviceIdStrategy(Config.DeviceIdStrategy.UUID)
                .setRequiresConsent(false)
                .enableParameterTamperingProtection("test-salt-checksum");

        // Countly needs persistent storage for requests, configuration storage, user profiles and other temporary data,
        // therefore requires a separate data folder to run
        //File targetFolder = new File("/home/zahi/countly-workspace/data");

        File targetFolder = new File("C:\\Users\\zahid\\Documents\\Countly\\data");

        // Main initialization call, SDK can be used after this one is done
        Countly.init(targetFolder, config);
        System.out.printf("After SDK Initialization: Total Memory = %d Mb, Available RAM= %d Mb %n", DeviceCore.dev.getRAMTotal(), DeviceCore.dev.getRAMAvailable());

        int countOfRequest = 10000;
        int remaining = countOfRequest;
        int secondsToSleep = 5;
        do {

            if (Countly.backendMode().getQueueSize() >= config.getRequestQueueMaxSize()) {
               // Thread.sleep(secondsToSleep * 1000);
            } else {
                if (remaining > 0) {
                    Map<String, Object> segment = new HashMap<String, Object>() {{
                        put("Time Spent", 60);
                        put("Retry Attempts", 60);
                    }};

                    Countly.backendMode().recordEvent("device-id", "Event Key " + remaining, 1, 0.1, 5, segment, null);
                    --remaining;
                }

            }

        } while (remaining != 0 || Countly.backendMode().getQueueSize() != 0);


        System.out.printf("After successfully sending data: Total Memory = %d Mb, Available RAM= %d Mb %n", DeviceCore.dev.getRAMTotal(), DeviceCore.dev.getRAMAvailable());

        Countly.stop(false);
        System.out.println("=====SDK Stop=====");
    }

    public static void main(String[] args) throws Exception {
        boolean running = true;
        Scanner scanner = new Scanner(System.in);
        while (running) {

            System.out.println("Choose your option: ");

            System.out.println("1) Perform Large Request Queue Size Test");
            System.out.println("2) Perform Large Event queues test");
            System.out.println("3) Record bulk data to server");

            int input = scanner.nextInt();
            switch (input) {
                case 0:
                    running = false;
                    break;
                case 1:
                    performLargeRequestQueueSizeTest();
                    running = false;
                    break;
                case 2:
                    performLargeEventQueueTest();
                    running = false;
                    break;
                case 3:
                    recordBulkDataAndSendToServer();
                    running = false;
                    break;
                default:
                    break;
            }
        }

        System.out.println("Exit");
    }
}
