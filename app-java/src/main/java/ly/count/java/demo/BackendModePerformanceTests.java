package ly.count.java.demo;

import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import ly.count.sdk.java.internal.Device;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;


public class BackendModePerformanceTests {
    final static String DEVICE_ID = "device-id";
    final static String COUNTLY_APP_KEY = "COUNTLY_APP_KEY";
    final static String COUNTLY_SERVER_URL = "https://try.count.ly/";

    private static void initSDK(int eventQueueSize, int requestQueueSize) {
        Config config = new Config(COUNTLY_SERVER_URL, COUNTLY_APP_KEY)
            .setLoggingLevel(Config.LoggingLevel.OFF)
            .enableBackendMode()
            .setRequestQueueMaxSize(requestQueueSize)
            .setDeviceIdStrategy(Config.DeviceIdStrategy.UUID)
            .setRequiresConsent(false)
            .setEventQueueSizeToSend(1);

        // System specific folder structure
        String[] sdkStorageRootPath = { System.getProperty("user.home"), "__COUNTLY", "java_test" };
        File sdkStorageRootDirectory = new File(String.join(File.separator, sdkStorageRootPath));

        if(sdkStorageRootDirectory.mkdirs()){
            System.out.println("Directory creation failed");
        }

        // Main initialization call, SDK can be used after this one is done
        Countly.init(sdkStorageRootDirectory, config);
    }

    static void performLargeRequestQueueSizeTest() {
        System.out.println("===== Test Started: 'Large request queue size' =====");
        int requestQSize = 1000000;
        System.out.printf("Before SDK Initialization: Total Memory = %dMb, Available RAM = %dMb %n", Device.dev.getRAMTotal(), Device.dev.getRAMAvailable());
        initSDK(1, requestQSize);
        System.out.printf("After SDK Initialization: Total Memory = %d Mb, Available RAM= %d Mb %n", Device.dev.getRAMTotal(), Device.dev.getRAMAvailable());

        int batchSize = requestQSize / 25;

        System.out.printf("Adding %d requests(events) into request Queue%n", batchSize);
        for (int i = 1; i < batchSize; ++i) {

            Map<String, Object> segment = new HashMap<String, Object>() {{
                put("Time Spent", 60);
                put("Retry Attempts", 60);
            }};

            Countly.backendMode().recordEvent(DEVICE_ID, "Event Key " + i, 1, 0.1, 5.0, segment, null);
        }

        System.out.printf("Adding %d requests(crash) into request Queue%n", batchSize);
        for (int i = 1; i < batchSize; ++i) {

            Map<String, Object> segmentation = new HashMap<String, Object>() {{
                put("login page", "authenticate request");
            }};

            Map<String, String> crashDetails = new HashMap<String, String>() {{
                put("_os", "Windows 11");
                put("_os_version", "11.202");
                put("_logs", "main page");
            }};

            Countly.backendMode().recordException(DEVICE_ID, "Message: " + i, "stack traces " + 1, segmentation, crashDetails,
                null);
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

            Map<String, String> location = new HashMap<String, String>() {{
                put("ip_address", "192.168.1.1");
                put("city", "Lahore");
                put("country_code", "PK");
                put("location", "31.5204,74.3587");
            }};

            Countly.backendMode().sessionBegin(DEVICE_ID, metrics, location, null);
        }

        System.out.printf("After adding %d request: Total Memory = %d Mb, Available RAM= %d Mb %n", requestQSize, Device.dev.getRAMTotal(), Device.dev.getRAMAvailable());

        Countly.stop(false);
        System.out.println("=====SDK Stop=====");
    }

    static void performLargeEventQueueTest() {
        int noOfEvents = 100000;
        System.out.println("===== Test Start: 'Large Event queues against multiple devices ids' =====");
        System.out.printf("Before SDK Initialization: Total Memory = %dMb, Available RAM = %dMb %n", Device.dev.getRAMTotal(), Device.dev.getRAMAvailable());
        initSDK(noOfEvents, 1000);
        System.out.printf("After SDK Initialization: Total Memory = %d Mb, Available RAM= %d Mb %n", Device.dev.getRAMTotal(), Device.dev.getRAMAvailable());

        int noOfDevices = 10;
        for (int d = 0; d <= noOfDevices; ++d) {
            System.out.printf("Adding %d events into event Queue against deviceID = %s%n", 100000, "device-id-" + d);
            for (int i = 1; i <= noOfEvents; ++i) {

                Map<String, Object> segment = new HashMap<String, Object>() {{
                    put("Time Spent", 60);
                    put("Retry Attempts", 60);
                }};

                Countly.backendMode().recordEvent("device-id-" + d, "Event Key " + i, 1, 0.1, 5.0, segment, null);
            }
        }
        System.out.printf("After adding %d events into event queue: Total Memory = %d Mb, Available RAM= %d Mb %n", noOfEvents * noOfDevices, Device.dev.getRAMTotal(), Device.dev.getRAMAvailable());

        Countly.stop(false);
        System.out.println("=====SDK Stop=====");
    }

    static void recordBulkDataAndSendToServer() throws InterruptedException {

        System.out.println("===== Test Start: 'Record bulk data to server' =====");
        System.out.printf("Before SDK Initialization: Total Memory = %dMb, Available RAM = %dMb %n", Device.dev.getRAMTotal(), Device.dev.getRAMAvailable());
        initSDK(100, 1000);
        System.out.printf("After SDK Initialization: Total Memory = %d Mb, Available RAM= %d Mb %n", Device.dev.getRAMTotal(), Device.dev.getRAMAvailable());
        int countOfRequest = 10;
        int remaining = countOfRequest;
        int secondsToSleep = 5;
        do {
            if (Countly.backendMode().getQueueSize() >= 100) {
                Thread.sleep(secondsToSleep * 1000);
            } else {
                if (remaining > 0) {
                    Map<String, Object> segment = new HashMap<String, Object>() {{
                        put("Time Spent", 60);
                        put("Retry Attempts", 60);
                    }};

                    Countly.backendMode().recordEvent("device-id", "Event Key " + remaining, 1, 0.1, 5.0, segment, null);
                    --remaining;
                }
            }
        } while (remaining != 0 || Countly.backendMode().getQueueSize() != 0);

        System.out.printf("After successfully sending %d requests to server: Total Memory = %d Mb, Available RAM= %d Mb %n", countOfRequest, Device.dev.getRAMTotal(), Device.dev.getRAMAvailable());

        Countly.stop(false);
        System.out.println("=====SDK Stop=====");
    }

    public static void main(String[] args) throws Exception {
        boolean running = true;
        long startTime = 0;
        Scanner scanner = new Scanner(System.in);
        while (running) {

            System.out.println("Choose your option: ");

            System.out.println("1) Perform Large Request Queue Size Test");
            System.out.println("2) Perform Large Event queues test");
            System.out.println("3) Record bulk data to server");

            int input = scanner.nextInt();
            startTime = System.currentTimeMillis();
            switch (input) {
                case 1:
                    performLargeRequestQueueSizeTest();
                    running = false;
                    System.out.printf("Time spent: %dms%n", (System.currentTimeMillis() - startTime));
                    break;
                case 2:
                    performLargeEventQueueTest();
                    running = false;
                    System.out.printf("Time spent: %dms%n", (System.currentTimeMillis() - startTime));
                    break;
                case 3:
                    startTime = System.currentTimeMillis();
                    recordBulkDataAndSendToServer();
                    running = false;
                    System.out.printf("Time spent: %dms%n", (System.currentTimeMillis() - startTime));
                    break;
                default:
                    break;
            }
        }

        System.out.println("Exit");
    }
}
