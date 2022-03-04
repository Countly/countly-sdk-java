package ly.count.java.demo;

import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

public class BackendModeExample {
    static void recordEvent() {
        Map<String, String> segment = new HashMap<String, String>() {{
            put("Time Spent", "60");
            put("Retry Attempts", "60");
        }};

        Countly.backendMode().recordEvent("8c1d653f8f474be24958b282d5e9b4c4209ee552", "Event Key", 1, 0, 5, segment, 0);
    }

    static void setLocation() {
        Countly.api().addLocation(31.5204, 74.3587);
    }

    static void recordUserProperties() {
        Map<String, String> userDetail = new HashMap<>();
        userDetail.put("name", "Full Name");
        userDetail.put("username", "username1");
        userDetail.put("email", "user@gmail.com");
        userDetail.put("organization", "Countly");
        userDetail.put("phone", "000-111-000");
        userDetail.put("gender", "M");
        userDetail.put("byear", "1991");

        Countly.backendMode().recordUserProperties("8c1d653f8f474be24958b282d5e9b4c4209ee552", userDetail, 0);
    }

    static void recordView() {

        Map<String, String> segmentation = new HashMap<String, String>() {{
            put("name", "SampleView");
            put("visit", "1");
            put("segment", "Windows");
            put("start", "1");
        }};

        Countly.backendMode().recordView("8c1d653f8f474be24958b282d5e9b4c4209ee552", "[CLY]_view", segmentation, 0);
    }

    static void recordCrash() {
        Map<String, String> segmentation = new HashMap<String, String>() {{
            put("login page", "authenticate request");
        }};
        try {
            int a = 10 / 0;
        } catch (Exception e) {
            Countly.backendMode().recordException("8c1d653f8f474be24958b282d5e9b4c4209ee552", e, segmentation, 0);
        }
    }

    static void recordAnotherCrash() {
        Map<String, String> segmentation = new HashMap<String, String>() {{
            put("login page", "authenticate request");
        }};
        try {
            int a = 10 / 0;
        } catch (Exception e) {
            Countly.backendMode().recordException("8c1d653f8f474be24958b282d5e9b4c4209ee552", "Divided By Zero", "stack traces", segmentation, 0);
        }
    }

    static void sessionBegin() {
        Countly.backendMode().sessionBegin("8c1d653f8f474be24958b282d5e9b4c4209ee552", 0);
    }

    static void sessionUpdate() {
        Countly.backendMode().sessionUpdate("8c1d653f8f474be24958b282d5e9b4c4209ee552", 10, 0);
    }

    static void sessionEnd() {
        Countly.backendMode().sessionEnd("8c1d653f8f474be24958b282d5e9b4c4209ee552", 20, 0);
    }

    public static void main(String[] args) throws Exception {

        Scanner scanner = new Scanner(System.in);

        String COUNTLY_SERVER_URL = "https://master.count.ly/";
        String COUNTLY_APP_KEY = "8c1d653f8f474be24958b282d5e9b4c4209ee552";

        Config config = new Config(COUNTLY_SERVER_URL, COUNTLY_APP_KEY)
                .setLoggingLevel(Config.LoggingLevel.DEBUG)
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
                case 6:
                    sessionBegin();
                    break;
                case 7:
                    sessionUpdate();
                    break;
                case 8:
                    sessionEnd();
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
