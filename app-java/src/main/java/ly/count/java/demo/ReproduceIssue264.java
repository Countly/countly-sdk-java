package ly.count.java.demo;

import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;

/**
 * Reproduces GitHub Issue #264:
 * "Non-JSON Server Response Causes Permanent Networking Deadlock"
 *
 * This app starts a local HTTP server that returns HTML (simulating a 502 error page),
 * initializes the Countly SDK against it, records events, and checks whether the SDK
 * gets permanently stuck.
 *
 * Run with: ./gradlew app-java:run
 * (after setting mainClassName = 'ly.count.java.demo.ReproduceIssue264' in app-java/build.gradle)
 */
public class ReproduceIssue264 {

    public static void main(String[] args) throws Exception {
        AtomicInteger requestCount = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);

        // Start a local HTTP server that returns HTML for the first 3 requests,
        // then valid JSON for subsequent requests (simulating server recovery)
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();

        server.createContext("/", exchange -> {
            int count = requestCount.incrementAndGet();
            String body;
            int code;

            if (count <= 3) {
                code = 502;
                body = "<html><body><h1>502 Bad Gateway</h1><p>The server is temporarily unavailable.</p></body></html>";
                System.out.println("[Mock Server] Request #" + count + " -> returning HTML 502 (simulating outage)");
            } else {
                code = 200;
                body = "{\"result\":\"Success\"}";
                successCount.incrementAndGet();
                System.out.println("[Mock Server] Request #" + count + " -> returning JSON 200 (server recovered)");
            }

            exchange.sendResponseHeaders(code, body.length());
            OutputStream os = exchange.getResponseBody();
            os.write(body.getBytes());
            os.close();
        });

        server.start();
        System.out.println("=== Issue #264 Reproduction ===");
        System.out.println("[Mock Server] Started on port " + port);
        System.out.println();

        // Setup SDK storage directory
        String[] sdkStorageRootPath = { System.getProperty("user.home"), "__COUNTLY", "java_issue264" };
        File sdkStorageRootDirectory = new File(String.join(File.separator, sdkStorageRootPath));
        if (!(sdkStorageRootDirectory.exists() && sdkStorageRootDirectory.isDirectory())) {
            sdkStorageRootDirectory.mkdirs();
        }

        // Initialize SDK pointing to our mock server
        Config config = new Config("http://localhost:" + port, "TEST_APP_KEY", sdkStorageRootDirectory)
            .setLoggingLevel(Config.LoggingLevel.WARN)
            .setDeviceIdStrategy(Config.DeviceIdStrategy.UUID)
            .enableFeatures(Config.Feature.Events, Config.Feature.Sessions)
            .setEventQueueSizeToSend(1);

        Countly.instance().init(config);
        System.out.println("[SDK] Initialized against mock server");

        // Start session (triggers first request -> will get HTML 502)
        Countly.session().begin();
        System.out.println("[SDK] Session started");

        // Record an event (triggers another request -> will get HTML 502)
        Countly.instance().events().recordEvent("test_event_during_outage");
        System.out.println("[SDK] Event recorded");

        // Wait for requests to be attempted
        System.out.println();
        System.out.println("[Test] Waiting 3 seconds for initial requests...");
        Thread.sleep(3000);

        // Check if SDK is deadlocked via reflection (SDKCore.instance.networking is protected)
        boolean isSending = isNetworkingSending();

        System.out.println();
        System.out.println("============================================================");
        if (isSending) {
            System.out.println("  BUG REPRODUCED: isSending() = true (DEADLOCKED!)");
            System.out.println("  The SDK is permanently stuck. No further requests");
            System.out.println("  will ever be sent, even when the server recovers.");
        } else {
            System.out.println("  FIX CONFIRMED: isSending() = false (recovered)");
            System.out.println("  The SDK handled the non-JSON response gracefully.");
        }
        System.out.println("============================================================");
        System.out.println();

        // Try to trigger recovery by calling check
        System.out.println("[Test] Triggering networking check cycles (server now returns JSON)...");
        triggerNetworkingChecks(5);

        int totalRequests = requestCount.get();
        int successes = successCount.get();

        System.out.println();
        System.out.println("============================================================");
        System.out.println("  Total requests received by server: " + totalRequests);
        System.out.println("  Successful (JSON 200) responses:   " + successes);
        if (successes > 0) {
            System.out.println("  SDK successfully retried after server recovered!");
        } else if (!isNetworkingSending()) {
            System.out.println("  SDK recovered from error. Requests will retry on");
            System.out.println("  the next timer tick (no deadlock).");
        } else {
            System.out.println("  SDK is STILL deadlocked. Bug confirmed.");
        }
        System.out.println("============================================================");

        // Cleanup
        Countly.instance().stop();
        server.stop(0);
        System.out.println();
        System.out.println("[Done] Cleanup complete.");
    }

    /**
     * Access SDKCore.instance.networking.isSending() via reflection
     * since these fields are protected/package-private.
     */
    private static boolean isNetworkingSending() throws Exception {
        Class<?> sdkCoreClass = Class.forName("ly.count.sdk.java.internal.SDKCore");
        Field instanceField = sdkCoreClass.getDeclaredField("instance");
        instanceField.setAccessible(true);
        Object sdkCore = instanceField.get(null);

        Field networkingField = sdkCoreClass.getDeclaredField("networking");
        networkingField.setAccessible(true);
        Object networking = networkingField.get(sdkCore);

        return (boolean) networking.getClass().getMethod("isSending").invoke(networking);
    }

    /**
     * Trigger SDKCore.instance.networking.check(config) via reflection.
     */
    private static void triggerNetworkingChecks(int count) throws Exception {
        Class<?> sdkCoreClass = Class.forName("ly.count.sdk.java.internal.SDKCore");
        Field instanceField = sdkCoreClass.getDeclaredField("instance");
        instanceField.setAccessible(true);
        Object sdkCore = instanceField.get(null);

        Field networkingField = sdkCoreClass.getDeclaredField("networking");
        networkingField.setAccessible(true);
        Object networking = networkingField.get(sdkCore);

        Field configField = sdkCoreClass.getDeclaredField("config");
        configField.setAccessible(true);
        Object internalConfig = configField.get(sdkCore);

        java.lang.reflect.Method checkMethod = networking.getClass().getMethod("check",
            Class.forName("ly.count.sdk.java.internal.InternalConfig"));

        for (int i = 0; i < count; i++) {
            if (!isNetworkingSending()) {
                checkMethod.invoke(networking, internalConfig);
            }
            Thread.sleep(1000);
        }
    }
}