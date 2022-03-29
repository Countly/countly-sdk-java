package ly.count.java.demo;

import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

public class BackendModeExample {
    final static String DEVICE_ID = "device-id";
    final static String COUNTLY_APP_KEY = "YOUR_APP_KEY";
    final static String COUNTLY_SERVER_URL = "https://try.count.ly/";

    static void recordEvent() {
        Map<String, Object> segment = new HashMap<String, Object>() {{
            put("Time Spent", 60);
            put("Retry Attempts", 60);
        }};

        Countly.backendMode().recordEvent(DEVICE_ID, "Event Key", 1, 0.1, 5, segment, null);
    }

    static void recordUserProperties() {
        // User detail
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

    static void recordView() {

        Map<String, Object> segmentation = new HashMap<String, Object>() {{
            put("visit", "1");
            put("segment", "Windows");
            put("start", "1");
        }};

        Countly.backendMode().recordView(DEVICE_ID, "SampleView", segmentation, 1646640780130L);
    }

    static void recordCrash() {
        Map<String, Object> segmentation = new HashMap<String, Object>() {{
            put("login page", "authenticate request");
        }};
        try {
            int a = 10 / 0;
        } catch (Exception e) {
            Countly.backendMode().recordException(DEVICE_ID, e, segmentation, null);
        }
    }

    static void recordAnotherCrash() {
        Map<String, Object> segmentation = new HashMap<String, Object>() {{
            put("login page", "authenticate request");
        }};
        try {
            int a = 10 / 0;
        } catch (Exception e) {
            Countly.backendMode().recordException(DEVICE_ID, "Divided By Zero", "stack traces", segmentation, null);
        }
    }

    static void recordDirectRequest() {
        Map<String, String> requestData = new HashMap<>();
        requestData.put("device_id", "id");
        requestData.put("timestamp", "1646640780130");
        requestData.put("end_session", "1");
        requestData.put("session_duration", "20.5");
        Countly.backendMode().recordDirectRequest(DEVICE_ID, requestData, null);
    }

    public static void main(String[] args) throws Exception {

        Scanner scanner = new Scanner(System.in);

        Config config = new Config(COUNTLY_SERVER_URL, COUNTLY_APP_KEY)
                .setLoggingLevel(Config.LoggingLevel.DEBUG)
                .enableBackendMode()
                .setRequestQueueMaxSize(100)
                .setDeviceIdStrategy(Config.DeviceIdStrategy.UUID)
                .setRequiresConsent(false)
                .enableParameterTamperingProtection("test-salt-checksum")
                .setEventsBufferSize(2);

        // Countly needs persistent storage for requests, configuration storage, user profiles and other temporary data,
        // therefore requires a separate data folder to run
        //File targetFolder = new File("/home/zahi/countly-workspace/data");

        File targetFolder = new File("C:\\Users\\zahid\\Documents\\Countly\\data");

        // Main initialization call, SDK can be used after this one is done
        Countly.init(targetFolder, config);
        boolean running = true;
        while (running) {

            System.out.println("Choose your option: ");

            System.out.println("1) Record an event");
            System.out.println("2) Record a view");
            System.out.println("3) Record user properties");
            System.out.println("4) Record an exception");
            System.out.println("5) Record another exception");
            System.out.println("6) Start session");
            System.out.println("7) Update session");
            System.out.println("8) End session");
            System.out.println("9) Record direct request");
            System.out.println("0) Exit ");

            int input = scanner.nextInt();
            switch (input) {
                case 0:
                    running = false;
                    break;
                case 1:
                    recordEvent();
                    break;
                case 2:
                    recordView();
                    break;
                case 3:
                    recordUserProperties();
                    break;
                case 4:
                    recordCrash();
                    break;
                case 5:
                    recordAnotherCrash();
                    break;
                case 6: {
                    Map<String, String> metrics = new HashMap<String, String>() {{
                        put("_os", "Android");
                        put("_os_version", "10");
                        put("_app_version", "1.2");
                    }};

                    Countly.backendMode().sessionBegin(DEVICE_ID, metrics, null);
                    break;
                }
                case 7:
                    Countly.backendMode().sessionUpdate(DEVICE_ID, 10, null);
                    break;
                case 8:
                    Countly.backendMode().sessionEnd(DEVICE_ID, 20, null);
                    break;
                case 9:
                    recordDirectRequest();
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
