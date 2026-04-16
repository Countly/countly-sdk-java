package ly.count.sdk.java.internal;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * End-to-end scenario tests for GitHub issue #264:
 * "Non-JSON Server Response Causes Permanent Networking Deadlock"
 *
 * These tests spin up a local HTTP server that returns non-JSON responses
 * (simulating 502/503 error pages), then verify the SDK recovers gracefully
 * and resumes sending requests when the server comes back.
 */
@RunWith(JUnit4.class)
public class ScenarioNetworkDeadlockTests {

    private HttpServer server;
    private int port;

    @Before
    public void setUp() throws Exception {
        TestUtils.createCleanTestState();
    }

    @After
    public void tearDown() {
        Countly.instance().halt();
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * Start a local HTTP server with a custom handler.
     * Returns the port number.
     */
    private void startServer(ServerBehavior behavior) throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", exchange -> {
            Response resp = behavior.respond();
            exchange.sendResponseHeaders(resp.code, resp.body.length());
            OutputStream os = exchange.getResponseBody();
            os.write(resp.body.getBytes());
            os.close();
        });
        server.start();
    }

    private Config configForLocalServer() {
        return new Config("http://localhost:" + port, TestUtils.SERVER_APP_KEY, TestUtils.getTestSDirectory())
            .setLoggingLevel(Config.LoggingLevel.VERBOSE)
            .setDeviceIdStrategy(Config.DeviceIdStrategy.UUID)
            .enableFeatures(Config.Feature.Events, Config.Feature.Sessions)
            .setEventQueueSizeToSend(1);
    }

    // ==================== Scenario tests ====================

    /**
     * Helper: starts a server with the given response, inits the SDK, records an event,
     * waits for the request round-trip, and asserts isSending() is false.
     */
    private void assertResponseDoesNotDeadlock(int code, String body) throws Exception {
        startServer(() -> new Response(code, body));

        Countly.instance().init(configForLocalServer());
        Countly.session().begin();

        Countly.instance().events().recordEvent("test_event");
        Thread.sleep(2000);

        Assert.assertFalse(
            "SDK networking should NOT be stuck after response [" + code + ": " + body.substring(0, Math.min(body.length(), 40)) + "]",
            SDKCore.instance.networking.isSending()
        );
    }

    /**
     * Server returns HTML 502 error — the primary scenario from issue #264.
     * Before the fix: JSONException propagates, executor deadlocks permanently.
     */
    @Test
    public void html502Response_sdkDoesNotDeadlock() throws Exception {
        assertResponseDoesNotDeadlock(502, "<html><body><h1>502 Bad Gateway</h1></body></html>");
    }

    /** Plain text response from a load balancer should not deadlock. */
    @Test
    public void plainTextResponse_sdkDoesNotDeadlock() throws Exception {
        assertResponseDoesNotDeadlock(200, "OK");
    }

    /** Empty response body should not deadlock. */
    @Test
    public void emptyResponse_sdkDoesNotDeadlock() throws Exception {
        assertResponseDoesNotDeadlock(200, "");
    }

    /** Valid JSON with non-2xx code should not deadlock. */
    @Test
    public void jsonErrorResponse_sdkDoesNotDeadlock() throws Exception {
        assertResponseDoesNotDeadlock(500, "{\"result\":\"Internal Server Error\"}");
    }

    /**
     * Scenario: Server initially returns HTML, then starts returning valid JSON.
     * Verifies the SDK can resume sending after transient 502 errors.
     */
    @Test
    public void serverRecovery_sdkResumesSending() throws Exception {
        AtomicInteger requestCount = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);

        startServer(() -> {
            int count = requestCount.incrementAndGet();
            if (count <= 1) {
                return new Response(502, "<html><body><h1>502 Bad Gateway</h1></body></html>");
            } else {
                successCount.incrementAndGet();
                return new Response(200, "{\"result\":\"Success\"}");
            }
        });

        Countly.instance().init(configForLocalServer());
        Countly.session().begin();
        Thread.sleep(2000);

        Assert.assertFalse(
            "SDK should recover from 502 HTML response",
            SDKCore.instance.networking.isSending()
        );

        for (int i = 0; i < 5; i++) {
            if (!SDKCore.instance.networking.isSending()) {
                SDKCore.instance.networking.check(SDKCore.instance.config);
            }
            Thread.sleep(1000);
        }

        Assert.assertTrue(
            "SDK should have sent requests after server recovered (got " + successCount.get() + " successes)",
            successCount.get() > 0
        );
    }

    /**
     * Scenario: Server closes connection abruptly without sending a response body.
     * Transport.response() returns null, which used to cause NPE in processResponse().
     * The catch(Exception) in send() is the last safety net for this path.
     */
    @Test
    public void connectionReset_sdkDoesNotDeadlock() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", exchange -> {
            // Send headers but close immediately — some JVMs produce IOException,
            // others may produce unexpected exceptions in the response reader
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        server.start();

        Countly.instance().init(configForLocalServer());
        Countly.session().begin();

        Countly.instance().events().recordEvent("test_event");
        Thread.sleep(2000);

        Assert.assertFalse(
            "SDK networking should NOT be stuck after abrupt connection close",
            SDKCore.instance.networking.isSending()
        );
    }

    // ==================== Helpers ====================

    @FunctionalInterface
    interface ServerBehavior {
        Response respond();
    }

    static class Response {
        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }
}
