package ly.count.sdk.java.internal;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
 * End-to-end reproducer for GitHub issue #271:
 * "Request queue stalls after the first request in SDK 24.1.5".
 *
 * The race the user hit in production: while request #1 is in-flight, the SDK
 * queues additional requests to disk. When #1 completes, its callback inside
 * Tasks.java re-enters DefaultNetworking.check() — which short-circuits if
 * tasks.isRunning() returns true. In 24.1.5 the callback fired before
 * `running` was cleared, so check() saw isRunning()=true and skipped
 * scheduling the next request. The queue silently stopped draining.
 *
 * To reproduce deterministically we hold request #1 open with a CountDownLatch
 * while we generate a backlog, then release it and assert the backlog drains
 * without any external trigger.
 */
@RunWith(JUnit4.class)
public class ScenarioRequestQueueStallTests {

    private HttpServer server;
    private int port;

    @Before
    public void setUp() {
        TestUtils.createCleanTestState();
    }

    @After
    public void tearDown() {
        Countly.instance().halt();
        if (server != null) {
            server.stop(0);
        }
    }

    private Config configForLocalServer() {
        return new Config("http://localhost:" + port, TestUtils.SERVER_APP_KEY, TestUtils.getTestSDirectory())
            .setLoggingLevel(Config.LoggingLevel.VERBOSE)
            .setDeviceIdStrategy(Config.DeviceIdStrategy.UUID)
            .enableFeatures(Config.Feature.Events, Config.Feature.Sessions)
            .setEventQueueSizeToSend(1);
    }

    /**
     * Reproduces the user-reported symptom: with multiple requests piled up
     * on disk while the network is busy, the queue must drain without
     * external prompting once the network is free again.
     *
     * Buggy 24.1.5 Tasks.java: only request #1 reaches the server.
     * Fixed: all queued requests reach the server.
     */
    @Test
    public void backloggedRequests_drainAfterInFlightCompletes() throws Exception {
        AtomicInteger requestCount = new AtomicInteger(0);
        CountDownLatch firstRequestArrived = new CountDownLatch(1);
        CountDownLatch releaseFirstRequest = new CountDownLatch(1);

        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", exchange -> {
            int n = requestCount.incrementAndGet();
            if (n == 1) {
                // Hold request #1 open until the test has built up a backlog.
                firstRequestArrived.countDown();
                try {
                    releaseFirstRequest.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
            }
            String body = "{\"result\":\"Success\"}";
            exchange.sendResponseHeaders(200, body.length());
            OutputStream os = exchange.getResponseBody();
            os.write(body.getBytes());
            os.close();
        });
        server.start();

        Countly.instance().init(configForLocalServer());
        Countly.session().begin();

        // Wait until request #1 has reached the server and is being held.
        Assert.assertTrue(
            "request #1 should reach the server within 5s",
            firstRequestArrived.await(5, TimeUnit.SECONDS)
        );

        // Build up a backlog: each recordEvent flushes a new request to disk.
        // While the server holds #1, all of these queue up because
        // DefaultNetworking.check() short-circuits on isRunning() == true.
        final int backlogSize = 5;
        for (int i = 0; i < backlogSize; i++) {
            Countly.instance().events().recordEvent("backlog_evt_" + i);
        }

        // Give the event flushes time to actually write request files to disk.
        Thread.sleep(500);

        // Release #1. From this point on no external code calls check() —
        // the queue must self-drain via the callback re-entry path that
        // issue #271 broke.
        releaseFirstRequest.countDown();

        // Poll for drain. Total expected = 1 (begin_session) + backlogSize.
        // Use >= because device-id resolution or merge requests may add extras;
        // the regression is "queue stops at 1", so any number > 1 + a generous
        // wait is the meaningful signal.
        int expectedMinimum = 1 + backlogSize;
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && requestCount.get() < expectedMinimum) {
            Thread.sleep(100);
        }

        Assert.assertTrue(
            "request queue should drain to >= " + expectedMinimum + " requests "
                + "without external check() calls — got " + requestCount.get()
                + " (queue stalled if << expected)",
            requestCount.get() >= expectedMinimum
        );
    }
}
