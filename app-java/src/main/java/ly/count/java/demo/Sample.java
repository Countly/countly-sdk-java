package ly.count.java.demo;

import java.io.Console;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import ly.count.sdk.java.View;
import ly.count.sdk.java.internal.LogCallback;

public class Sample {

    static void basicEvent() {
        Countly.api().event("Basic Event").record();
    }

    static void eventWithSumAndCount() {
        Countly.api().event("Event With Sum And Count")
            .setSum(23)
            .setCount(2).record();
    }

    static void eventWithSegmentation() {

        Map<String, String> segment = new HashMap<String, String>() {{
            put("Time Spent", "60");
            put("Retry Attempts", "60");
        }};

        Countly.api().event("Event With Sum")
            .setSegmentation(segment).record();
    }

    static void eventWithSumAndSegmentation() {
        Map<String, String> segment = new HashMap<String, String>() {{
            put("Time Spent", "60");
            put("Retry Attempts", "60");
        }};

        Countly.api().event("Event With Sum")
            .setSum(23)
            .setSegmentation(segment).record();
    }

    static void timedEventWithSumCountSegmentationAndDuration() {
        Map<String, String> segment = new HashMap<String, String>() {{
            put("Time Spent", "60");
            put("Retry Attempts", "60");
        }};

        Countly.api().timedEvent("timed event")
            .setCount(2)
            .setSum(5)
            .setSegmentation(segment)
            .setDuration(5.3).record();
    }

    static void setLocation() {
        Countly.api().addLocation(31.5204, 74.3587);
    }

    static void setUserProfile() {
        Countly.api().user().edit()
            .setName("Full name")
            .setUsername("nickname")
            .setEmail("test@test.com")
            .setOrg("Tester")
            .setPhone("+123456789")
            .commit();
    }

    static void setCustomProfile() {
        Countly.api().user().edit()
            .set("mostFavoritePet", "dog")
            .inc("phoneCalls", 1)
            .pushUnique("tags", "fan")
            .pushUnique("skill", "singer")
            .commit();
    }

    static void recordStartView() {
        Countly.api().view("Start view");
    }

    static void recordAnotherView() {
        Countly.api().view("Another view");
    }

    static void recordCrash() {
        try {
            int a = 10 / 0;
        } catch (Exception e) {
            Countly.api().addCrashReport(e, false, "Divided by zero", null, "sample app");
        }
    }

    public static void main(String[] args) throws Exception {

        Scanner scanner = new Scanner(System.in);

        String COUNTLY_SERVER_URL = "https://try.count.ly/";
        String COUNTLY_APP_KEY = "YOUR_APP_KEY";

        Map<String, String> metricOverride = new HashMap<>();
        metricOverride.put("aa", "11");
        metricOverride.put("bb", "222");

        Config config = new Config(COUNTLY_SERVER_URL, COUNTLY_APP_KEY)
            .setLoggingLevel(Config.LoggingLevel.DEBUG)
            .setDeviceIdStrategy(Config.DeviceIdStrategy.UUID)
            .enableFeatures(Config.Feature.Events, Config.Feature.Sessions, Config.Feature.CrashReporting, Config.Feature.Views, Config.Feature.UserProfiles, Config.Feature.Location)
            .setRequiresConsent(true)
            //.enableParameterTamperingProtection("test-salt-checksum")
            .setLogListener(new LogCallback() {
                @Override
                public void LogHappened(String logMessage, Config.LoggingLevel logLevel) {
                    //System.out.println("[" + logLevel + "] " + logMessage);
                }
            })
            .setEventsBufferSize(1)
            .setMetricOverride(metricOverride)
            .setApplicationVersion("123.56.h");

        // Countly needs persistent storage for requests, configuration storage, user profiles and other temporary data,
        // therefore requires a separate data folder to run
        //File targetFolder = new File("/home/zahi/countly-workspace/data");

        File targetFolder = new File("d:\\__COUNTLY\\java_test\\");

        // Main initialization call, SDK can be used after this one is done
        Countly.init(targetFolder, config);

        Countly.onConsent(Config.Feature.Events, Config.Feature.Sessions, Config.Feature.CrashReporting, Config.Feature.Views, Config.Feature.UserProfiles, Config.Feature.Location);

        // Usually, all interactions with SDK are to be done through a session instance:
        Countly.session().begin();
        boolean running = true;
        while (running) {

            System.out.println("Choose your option: ");

            System.out.println("1) Record basic event");
            System.out.println("2) Record event with segmentation");
            System.out.println("3) Record event with sum and count");
            System.out.println("4) Record event with sum and segmentation");
            System.out.println("5) Record timed event with sum, count, duration and segmentation");

            System.out.println("6) Record start view");
            System.out.println("7) Record another view");

            System.out.println("8) Set location");
            System.out.println("9) Set user profile");
            System.out.println("10) Set user custom profile");
            System.out.println("11) Record an exception");
            System.out.println("12) Start a view called 'example_view'");
            System.out.println("13) End a view called 'example_view'");
            System.out.println("0) Exit ");

            int input = scanner.nextInt();
            switch (input) {
                case 0:
                    running = false;
                    break;
                case 1:
                    basicEvent();
                    break;
                case 2:
                    eventWithSegmentation();
                    break;
                case 3:
                    eventWithSumAndCount();
                    break;
                case 4:
                    eventWithSumAndSegmentation();
                    break;
                case 5:
                    timedEventWithSumCountSegmentationAndDuration();
                    break;
                case 6:
                    recordStartView();
                    break;
                case 7:
                    recordAnotherView();
                    break;
                case 8:
                    setLocation();
                    break;
                case 9:
                    setUserProfile();
                    break;
                case 10:
                    setCustomProfile();
                    break;
                case 11:
                    recordCrash();
                    break;
                case 12:
                    Countly.session().view("example_view").start(true);
                    break;
                case 13:
                    Countly.session().view("example_view").stop(false);
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
