package ly.count.java.demo;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;

public class BackendModeExample {
    final static String DEVICE_ID = "device-id";
    final static String COUNTLY_APP_KEY = "COUNTLY_APP_KEY";
    final static String COUNTLY_SERVER_URL = "https://xxx.server.ly/";

    private static void recordUserDetailAndProperties() {
        Map<String, Object> userDetail = new HashMap<>();
        userDetail.put("name", "Full Name");
        userDetail.put("username", "username1");
        userDetail.put("email", "user@gmail.com");
        userDetail.put("organization", "Countly");
        userDetail.put("phone", "000-111-000");
        userDetail.put("gender", "M");
        userDetail.put("byear", "1991");
        //custom detail
        userDetail.put("hair", "black");
        userDetail.put("height", 5.9);
        userDetail.put("fav-colors", "{$push: black}");
        userDetail.put("marks", "{$inc: 1}");

        Countly.backendMode().recordUserProperties(DEVICE_ID, userDetail, null);
    }

    private static void recordView() {
        Map<String, Object> segmentation = new HashMap<>();
        segmentation.put("visit", "1");
        segmentation.put("segment", "Windows");
        segmentation.put("start", "1");

        Countly.backendMode().recordView(DEVICE_ID, "SampleView", segmentation, 1646640780130L);
    }

    private static void recordEvent() {
        Map<String, Object> segment = new HashMap<String, Object>() {{
            put("Time Spent", 60);
            put("Retry Attempts", 60);
        }};

        Countly.backendMode().recordEvent(DEVICE_ID, "Event Key", 1, 0.1, 5.0, segment, null);
    }

    private static void recordExceptionWithThrowableAndSegmentation() {
        Map<String, Object> segmentation = new HashMap<String, Object>() {{
            put("login page", "authenticate request");
        }};
        Map<String, String> crashDetails = new HashMap<String, String>() {{
            put("_os", "Windows 11");
            put("_os_version", "11.202");
            put("_logs", "main page");
        }};
        try {
            int a = 10 / 0;
        } catch (Exception e) {
            Countly.backendMode().recordException(DEVICE_ID, e, segmentation, crashDetails, null);
        }
    }

    private static void recordExceptionWithMessageAndSegmentation() {
        Map<String, Object> segmentation = new HashMap<String, Object>() {{
            put("login page", "authenticate request");
        }};

        Map<String, String> crashDetails = new HashMap<String, String>() {{
            put("_os", "Windows 11");
            put("_os_version", "11.202");
            put("_logs", "main page");
        }};
        try {
            int a = 10 / 0;
        } catch (Exception e) {
            Countly.backendMode().recordException(DEVICE_ID, "Divided By Zero", "stack traces", segmentation, crashDetails, null);
        }
    }

    private static void recordDirectRequest() {
        Map<String, String> requestData = new HashMap<>();
        requestData.put("device_id", "id");
        requestData.put("timestamp", "1646640780130");
        requestData.put("end_session", "1");
        requestData.put("session_duration", "20.5");
        Countly.backendMode().recordDirectRequest(DEVICE_ID, requestData, null);
    }

    private static void startSession() {
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

    static void testWithMultipleThreads() {

        int participants = 13;
        CountDownLatch latch = new CountDownLatch(1);

        Thread[] threads = new Thread[participants];

        threads[0] = new Thread(() -> {
            try {
                latch.await();
                System.out.println("Thread[00] executing at: " + System.currentTimeMillis());
                recordEvent();
                System.out.println("Thread[00] finished at: " + System.currentTimeMillis());
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });

        threads[1] = new Thread(() -> {
            try {
                latch.await();
                System.out.println("Thread[01] executing at: " + System.currentTimeMillis());
                recordView();
                System.out.println("Thread[01] finished at: " + System.currentTimeMillis());
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });

        threads[2] = new Thread(() -> {
            try {
                latch.await();
                System.out.println("Thread[02] executing at: " + System.currentTimeMillis());
                recordUserDetailAndProperties();
                System.out.println("Thread[02] finished at: " + System.currentTimeMillis());
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });

        threads[3] = new Thread(() -> {
            try {
                latch.await();
                System.out.println("Thread[03] executing at: " + System.currentTimeMillis());
                recordExceptionWithThrowableAndSegmentation();
                System.out.println("Thread[03] finished at: " + System.currentTimeMillis());
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });

        threads[4] = new Thread(() -> {
            try {
                latch.await();
                System.out.println("Thread[04] executing at: " + System.currentTimeMillis());
                recordDirectRequest();
                System.out.println("Thread[04] finished at: " + System.currentTimeMillis());
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        threads[5] = new Thread(() -> {
            try {
                latch.await();
                System.out.println("Thread[05] executing at: " + System.currentTimeMillis());
                recordExceptionWithMessageAndSegmentation();
                System.out.println("Thread[05] finished at: " + System.currentTimeMillis());
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });

        threads[6] = new Thread(() -> {
            try {
                latch.await();
                System.out.println("Thread[06] executing at: " + System.currentTimeMillis());
                recordDirectRequest();
                System.out.println("Thread[06] finished at: " + System.currentTimeMillis());
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });

        threads[7] = new Thread(() -> {
            try {
                latch.await();
                System.out.println("Thread[07] executing at: " + System.currentTimeMillis());
                recordView();
                System.out.println("Thread[07] finished at: " + System.currentTimeMillis());
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });

        threads[8] = new Thread(() -> {
            try {
                latch.await();
                System.out.println("Thread[08] executing at: " + System.currentTimeMillis());
                recordUserDetailAndProperties();
                System.out.println("Thread[08] finished at: " + System.currentTimeMillis());
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });

        threads[9] = new Thread(() -> {
            try {
                latch.await();
                System.out.println("Thread[09] executing at: " + System.currentTimeMillis());
                recordUserDetailAndProperties();
                System.out.println("Thread[09] finished at: " + System.currentTimeMillis());
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });

        threads[10] = new Thread(() -> {
            try {
                latch.await();
                System.out.println("Thread[10] executing at: " + System.currentTimeMillis());
                startSession();
                System.out.println("Thread[10] finished at: " + System.currentTimeMillis());
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        threads[11] = new Thread(() -> {
            try {
                latch.await();
                System.out.println("Thread[11] executing at: " + System.currentTimeMillis());
                recordView();
                System.out.println("Thread[11] finished at: " + System.currentTimeMillis());
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        threads[12] = new Thread(() -> {
            try {
                latch.await();
                System.out.println("Thread[12] executing at: " + System.currentTimeMillis());
                recordUserDetailAndProperties();
                System.out.println("Thread[12] finished at: " + System.currentTimeMillis());
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });

        for (int i = 0; i < participants; i++) {
            threads[i].start();
        }

        latch.countDown();

        // System.out.println("All threads completed at: " + System.currentTimeMillis());
    }

    static void recordDataWithLegacyCalls() {
        // Record Event
        Countly.api().event("Event With Sum And Count")
            .setSum(23)
            .setCount(2).record();

        // Record view
        Countly.api().view("Start view");

        // Record Location
        Countly.api().addLocation(31.5204, 74.3587);

        // Record user properties
        Countly.api().user().edit()
            .setName("Full name")
            .setUsername("nickname")
            .setEmail("test@test.com")
            .setOrg("Tester")
            .setPhone("+123456789")
            .commit();

        // Record crash
        try {
            int a = 10 / 0;
        } catch (Exception e) {
            Countly.api().addCrashReport(e, false, "Divided by zero", null, "sample app");
        }
    }

    public static void main(String[] args) throws Exception {

        Scanner scanner = new Scanner(System.in);

        Config config = new Config(COUNTLY_SERVER_URL, COUNTLY_APP_KEY)
            .setLoggingLevel(Config.LoggingLevel.DEBUG)
            .enableBackendMode()
            .setRequestQueueMaxSize(10000)
            .setDeviceIdStrategy(Config.DeviceIdStrategy.UUID)
            .setRequiresConsent(false)
            .setEventQueueSizeToSend(1000);

        // Countly needs persistent storage for requests, configuration storage, user profiles and other temporary data,
        // therefore requires a separate data folder to run

        // System specific folder structure
        String[] sdkStorageRootPath = { System.getProperty("user.home"), "__COUNTLY", "java_test" };
        File sdkStorageRootDirectory = new File(String.join(File.separator, sdkStorageRootPath));

        if (!(sdkStorageRootDirectory.exists() && sdkStorageRootDirectory.isDirectory())) {
            if (!sdkStorageRootDirectory.mkdirs()) {
                System.out.println("Directory creation failed");
            }
        }

        // Main initialization call, SDK can be used after this one is done
        Countly.init(sdkStorageRootDirectory, config);
        boolean running = true;
        while (running) {

            System.out.println("Choose your option: ");

            System.out.println("1) Record an event with key, count, sum, duration and segmentation");
            System.out.println("2) Record a view");
            System.out.println("3) Record user properties");
            System.out.println("4) Record an exception with throwable and segmentation");
            System.out.println("5) Record an exception with message, stacktrace and segmentation");
            System.out.println("6) Start session");
            System.out.println("7) Update session");
            System.out.println("8) End session");
            System.out.println("9) Record a direct request");
            System.out.println("10) Run Multiple Threads");
            System.out.println("99) Record data with legacy calls");
            System.out.println("0) Exit ");

            int input = scanner.nextInt();
            switch (input) {
                case 0:
                    running = false;
                    break;
                case 1: { // Record an event with key, count, sum, duration and segmentation
                    recordEvent();
                }
                break;
                case 2: { // Record a view
                    recordView();
                }
                break;
                case 3: { // record user detail and properties
                    recordUserDetailAndProperties();
                }
                break;
                case 4: { // record an exception with throwable and segmentation
                    recordExceptionWithThrowableAndSegmentation();
                }
                break;
                case 5: { // record an exception with message, stacktrace and segmentation
                    recordExceptionWithMessageAndSegmentation();
                }
                break;
                case 6: { // start a session
                    startSession();
                    break;
                }
                case 7: // update session
                    Countly.backendMode().sessionUpdate(DEVICE_ID, 10, null);
                    break;
                case 8: // end session
                    Countly.backendMode().sessionEnd(DEVICE_ID, 20, null);
                    break;
                case 9: { // record a direct request
                    recordDirectRequest();
                    break;
                }
                case 10:
                    testWithMultipleThreads();
                    break;
                case 99: // record data with legacy call
                    recordDataWithLegacyCalls();
                    break;
                default:
                    break;
            }
        }

        // Gracefully stop SDK to stop all SDK threads and allow this app to exit
        // Just in case, usually you don't want to clear data to reuse device id for next app runs
        // and to send any requests which might not be sent
        Countly.stop(false);
    }
}
