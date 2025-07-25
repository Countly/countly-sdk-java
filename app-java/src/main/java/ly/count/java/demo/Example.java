package ly.count.java.demo;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import ly.count.sdk.java.PredefinedUserPropertyKeys;
import ly.count.sdk.java.internal.CountlyFeedbackWidget;
import ly.count.sdk.java.internal.LogCallback;

public class Example {

    static void basicEvent() {
        Countly.instance().events().recordEvent("Basic Event");
    }

    static void eventWithSumAndCount() {
        Countly.instance().events().recordEvent("Event With Sum And Count", 2, 23.0);
    }

    static void eventWithSegmentation() {
        Map<String, Object> segment = new ConcurrentHashMap<>();
        segment.put("Time Spent", "60");
        segment.put("Retry Attempts", "60");

        Countly.instance().events().recordEvent("Event With Segmentation", segment);
    }

    static void eventWithSumAndSegmentation() {
        Map<String, Object> segment = new ConcurrentHashMap<>();
        segment.put("Time Spent", "60");
        segment.put("Retry Attempts", "60");

        Countly.instance().events().recordEvent("Event With Sum And Segmentation", segment, 1, 23.8);
    }

    static void timedEventWithSumCountSegmentationAndDuration() {
        Map<String, Object> segment = new ConcurrentHashMap<>();
        segment.put("Time Spent", "60");
        segment.put("Retry Attempts", "60");

        Countly.instance().events().startEvent("timed event");
        Countly.instance().events().endEvent("timed event", segment, 2, 5.3);
    }

    static void setLocation() {
        Countly.instance().location().setLocation("UK", "London", "31.5204, 74.3587", null);
    }

    static void setUserProfile() {
        Countly.instance().userProfile().setProperty(PredefinedUserPropertyKeys.NAME, "Full name");
        Countly.instance().userProfile().setProperty(PredefinedUserPropertyKeys.USERNAME, "nickname");
        Countly.instance().userProfile().setProperty(PredefinedUserPropertyKeys.EMAIL, "test@test.com");
        Countly.instance().userProfile().setProperty(PredefinedUserPropertyKeys.ORGANIZATION, "Tester");
        Countly.instance().userProfile().setProperty(PredefinedUserPropertyKeys.PHONE, "+123456789");
        Countly.instance().userProfile().setProperty(PredefinedUserPropertyKeys.PICTURE, new byte[] { 1, 2, 3, 4, 5 });
        //Countly.instance().userProfile().setProperty(PredefinedUserPropertyKeys.PICTURE_PATH, "test.png"); //to provide local path
        Countly.instance().userProfile().setProperty(PredefinedUserPropertyKeys.PICTURE_PATH, "https://someurl.com/test.png");
        Countly.instance().userProfile().save();
    }

    static void setCustomProfile() {
        Countly.instance().userProfile().setProperty("mostFavoritePet", "dog");
        Countly.instance().userProfile().increment("phoneCalls");
        Countly.instance().userProfile().pushUnique("tags", "fan");
        Countly.instance().userProfile().pushUnique("tags", "singer");
        Countly.instance().userProfile().save();
    }

    static List<CountlyFeedbackWidget> getFeedbackWidgets() {
        List<CountlyFeedbackWidget> widgets = new ArrayList<>();
        Countly.instance().feedback().getAvailableFeedbackWidgets((retrievedWidgets, error) -> {
            if (error != null) {
                DemoUtils.println("Error while retrieving feedback widgets: " + error);
                return;
            }
            DemoUtils.println("Retrieved feedback widgets: " + retrievedWidgets.size());

            for (int i = 0; i < retrievedWidgets.size(); i++) {
                DemoUtils.println(i + ") Widget: " + retrievedWidgets.get(i).toString());
            }

            widgets.addAll(retrievedWidgets);
        });

        return widgets;
    }

    static void getFeedbackWidgetUrl(CountlyFeedbackWidget widget) {
        String constructedUrl = Countly.instance().feedback().constructFeedbackWidgetUrl(widget);
        DemoUtils.println("Retrieved feedback widget url: " + constructedUrl);
    }

    static void getFeedbackWidgetData(CountlyFeedbackWidget widget) {

        Countly.instance().feedback().getFeedbackWidgetData(widget, (jsonObject, error) -> {
            if (error != null) {
                DemoUtils.println("Error while retrieving feedback widget url: " + error);
                return;
            }
            DemoUtils.println("Retrieved feedback widget data: " + jsonObject.toString());
        });
    }

    static void reportFeedbackWidgetManually(CountlyFeedbackWidget widget) {
        Map<String, Object> widgetResult = new ConcurrentHashMap<>();
        widgetResult.put("rating", 5);
        widgetResult.put("comment", "This is a comment");
        widgetResult.put("email", "test@count.ly");

        Countly.instance().feedback().reportFeedbackWidgetManually(widget, null, widgetResult);
    }

    static void recordStartView() {
        Countly.instance().views().startView("Start view");
    }

    static void recordAnotherView() {
        Countly.instance().views().startView("Another view");
    }

    static void recordCrash() {
        try {
            throw new ArithmeticException("/ by zero");
        } catch (Exception e) {
            Countly.instance().crashes().recordHandledException(e);
        }
    }

    private static String randomId() {
        return "_id" + System.currentTimeMillis();
    }

    static void changeDeviceIdWithMerge() {
        Countly.instance().deviceId().changeWithMerge(randomId());
    }

    static void changeDeviceIdWithoutMerge() {
        Countly.instance().deviceId().changeWithoutMerge(randomId());
    }

    static void feedbackWidgets(Scanner scanner) {

        List<CountlyFeedbackWidget> feedbackWidgets = new ArrayList<>();
        boolean running = true;
        while (running) {
            DemoUtils.println("You should get feedback widgets first");
            DemoUtils.println("Choose your option for feedback: ");

            DemoUtils.println("0) To exit from feedback widget functionality");

            DemoUtils.println("1) Get feedback widgets");
            DemoUtils.println("2.X) Get feedback widget data with index of widget");
            DemoUtils.println("3.X) Report feedback widget manually with index of widget");
            DemoUtils.println("4.X) Construct feedback widget url with index of widget");

            String[] input = scanner.next().split("\\.");
            switch (input[0]) {
                case "0":
                    running = false;
                    break;
                case "1":
                    feedbackWidgets = getFeedbackWidgets();
                    break;
                case "2":
                    getFeedbackWidgetData(feedbackWidgets.get(Integer.parseInt(input[1])));
                    break;
                case "3":
                    reportFeedbackWidgetManually(feedbackWidgets.get(Integer.parseInt(input[1])));
                    break;
                case "4":
                    getFeedbackWidgetUrl(feedbackWidgets.get(Integer.parseInt(input[1])));
                    break;
                default:
                    break;
            }
        }
    }

    public static void main(String[] args) throws Exception {

        Scanner scanner = new Scanner(System.in);

        String COUNTLY_SERVER_URL = "https://your.server.ly";
        String COUNTLY_APP_KEY = "YOUR_APP_KEY";

        if (COUNTLY_SERVER_URL.equals("https://your.server.ly") || COUNTLY_APP_KEY.equals("YOUR_APP_KEY")) {
            DemoUtils.println("Please provide correct COUNTLY_SERVER_URL and COUNTLY_APP_KEY");
            return;
        }

        Map<String, String> metricOverride = new ConcurrentHashMap<>();
        metricOverride.put("aa", "11");
        metricOverride.put("bb", "222");

        // Countly needs persistent storage for requests, configuration storage, user profiles and other temporary data,
        // therefore requires a separate data folder to run. The data folder should be existed to run the app

        // System specific folder structure
        String[] sdkStorageRootPath = { System.getProperty("user.home"), "__COUNTLY", "java_test" };
        File sdkStorageRootDirectory = new File(String.join(File.separator, sdkStorageRootPath));

        if (!(sdkStorageRootDirectory.exists() && sdkStorageRootDirectory.isDirectory())) {
            if (!sdkStorageRootDirectory.mkdirs()) {
                DemoUtils.println("Directory creation failed");
            }
        }

        Map<String, String> customNetworkRequestHeaders = new ConcurrentHashMap<>();
        customNetworkRequestHeaders.put("X-Countly-Example", "true");
        customNetworkRequestHeaders.put("X-Countly-Example-Version", "1.0");

        Config config = new Config(COUNTLY_SERVER_URL, COUNTLY_APP_KEY, sdkStorageRootDirectory)
            .setLoggingLevel(Config.LoggingLevel.DEBUG)
            .setDeviceIdStrategy(Config.DeviceIdStrategy.UUID)
            .addCustomNetworkRequestHeaders(customNetworkRequestHeaders)
            .enableFeatures(Config.Feature.Events, Config.Feature.Sessions, Config.Feature.CrashReporting, Config.Feature.Views, Config.Feature.UserProfiles, Config.Feature.Location, Config.Feature.Feedback)
            .setRequiresConsent(true)
            //.enableParameterTamperingProtection("test-salt-checksum")
            .setLogListener(new LogCallback() {
                @Override
                public void LogHappened(String logMessage, Config.LoggingLevel logLevel) {
                    //DemoUtils.println("[" + logLevel + "] " + logMessage);
                }
            })
            .setEventQueueSizeToSend(1)//setting queue size to "1" should only be done for testing, unless you have a really good reason to do it
            .setMetricOverride(metricOverride)
            .setApplicationVersion("123.56.h");

        // Main initialization call, SDK can be used after this one is done
        Countly.instance().init(config);

        Countly.onConsent(Config.Feature.Events, Config.Feature.Sessions, Config.Feature.CrashReporting, Config.Feature.Views, Config.Feature.UserProfiles, Config.Feature.Location, Config.Feature.Feedback);

        // Usually, all interactions with SDK are to be done through a session instance:
        Countly.session().begin();
        boolean running = true;
        while (running) {

            DemoUtils.println("Choose your option: ");

            DemoUtils.println("1) Record basic event");
            DemoUtils.println("2) Record event with segmentation");
            DemoUtils.println("3) Record event with sum and count");
            DemoUtils.println("4) Record event with sum and segmentation");
            DemoUtils.println("5) Record timed event with sum, count, duration and segmentation");

            DemoUtils.println("6) Record start view");
            DemoUtils.println("7) Record another view");

            DemoUtils.println("8) Set location");
            DemoUtils.println("9) Set user profile");
            DemoUtils.println("10) Set user custom profile");
            DemoUtils.println("11) Record an exception");
            DemoUtils.println("12) Start a view called 'example_view'");
            DemoUtils.println("13) End a view called 'example_view'");

            DemoUtils.println("14) Change device id with merge");
            DemoUtils.println("15) Change device id without merge");

            DemoUtils.println("16) Enter to feedback widget functionality");

            DemoUtils.println("0) Exit ");

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
                    Countly.instance().views().startView("example_view");
                    break;
                case 13:
                    Countly.instance().views().stopViewWithName("example_view");
                    break;
                case 14:
                    changeDeviceIdWithMerge();
                    break;
                case 15:
                    changeDeviceIdWithoutMerge();
                    break;
                case 16:
                    feedbackWidgets(scanner);
                    break;
                default:
                    break;
            }
        }

        // Stop the SDK. This call does not delete all sdk generated files
        // Just in case, usually you don't want to clear data to reuse device id for next app runs
        // and to send any requests which might not be sent
        Countly.instance().stop();
    }
}
