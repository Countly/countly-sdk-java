package ly.count.java.demo;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import org.json.JSONObject;

/**
 * Reproducer for GitHub issue #263:
 * "NullPointerException in SDKCore.recover() permanently blocks SDK initialization
 * when a crash file exists"
 *
 * This simulates the scenario where:
 * 1. A previous app run crashed and left a [CLY]_crash_* file on disk
 * 2. The app restarts and calls Countly.init()
 * 3. SDKCore.recover() finds the crash file and tries to process it
 *
 * WITHOUT the fix: NPE at networking.check(config) because networking is null
 * WITH the fix: SDK initializes normally, crash is queued as a request
 */
public class ReproduceIssue263 {

    public static void main(String[] args) {
        String[] sdkStorageRootPath = { System.getProperty("user.home"), "__COUNTLY", "java_issue_263" };
        File sdkStorageRootDirectory = new File(String.join(File.separator, sdkStorageRootPath));

        if (!(sdkStorageRootDirectory.exists() && sdkStorageRootDirectory.isDirectory())) {
            if (!sdkStorageRootDirectory.mkdirs()) {
                System.out.println("[FAIL] Directory creation failed");
                return;
            }
        }

        // Step 1: Plant a fake crash file as if a previous run crashed
        long crashTimestamp = System.currentTimeMillis() - 2000;
        File crashFile = new File(sdkStorageRootDirectory, "[CLY]_crash_" + crashTimestamp);

        JSONObject crashData = new JSONObject();
        crashData.put("_error", "java.lang.RuntimeException: simulated crash from previous session\n"
            + "\tat com.example.App.doSomething(App.java:42)\n"
            + "\tat com.example.App.main(App.java:10)");
        crashData.put("_nonfatal", false);
        crashData.put("_os", "Java");
        crashData.put("_os_version", System.getProperty("java.version"));
        crashData.put("_device", "ReproducerDevice");
        crashData.put("_resolution", "1920x1080");

        try (BufferedWriter writer = Files.newBufferedWriter(crashFile.toPath())) {
            writer.write(crashData.toString());
        } catch (IOException e) {
            System.out.println("[FAIL] Could not write crash file: " + e.getMessage());
            return;
        }

        System.out.println("[INFO] Planted crash file: " + crashFile.getAbsolutePath());
        System.out.println("[INFO] Crash file exists: " + crashFile.exists());
        System.out.println();

        // Step 2: Initialize SDK — this is where the NPE would occur
        System.out.println("[TEST] Initializing SDK with crash file present...");
        System.out.println("[TEST] If issue #263 is NOT fixed, you will see a NullPointerException below.");
        System.out.println();

        try {
            Config config = new Config("https://test.server.ly", "TEST_APP_KEY", sdkStorageRootDirectory)
                .setLoggingLevel(Config.LoggingLevel.DEBUG)
                .enableFeatures(Config.Feature.CrashReporting, Config.Feature.Events, Config.Feature.Sessions);

            Countly.instance().init(config);

            System.out.println();
            System.out.println("[PASS] SDK initialized successfully!");
            System.out.println("[INFO] Crash file still exists: " + crashFile.exists());

            if (!crashFile.exists()) {
                System.out.println("[PASS] Crash file was processed and removed during recovery.");
            } else {
                System.out.println("[WARN] Crash file was NOT removed — recovery may have partially failed.");
            }

            // Check for request files (crash should be converted to a request)
            File[] requestFiles = sdkStorageRootDirectory.listFiles(
                (dir, name) -> name.startsWith("[CLY]_request_"));
            if (requestFiles != null && requestFiles.length > 0) {
                System.out.println("[PASS] Found " + requestFiles.length + " request file(s) — crash was queued for sending.");
            }

            // Clean shutdown
            Countly.instance().halt();
            System.out.println("[INFO] SDK stopped cleanly.");

        } catch (NullPointerException e) {
            System.out.println();
            System.out.println("[FAIL] *** NullPointerException — Issue #263 is NOT fixed! ***");
            System.out.println("[FAIL] " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.out.println();
            System.out.println("[FAIL] Unexpected exception: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Cleanup: remove test files
            System.out.println();
            System.out.println("[INFO] Cleaning up test directory...");
            File[] files = sdkStorageRootDirectory.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            sdkStorageRootDirectory.delete();
            System.out.println("[INFO] Done.");
        }
    }
}
