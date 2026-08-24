package ly.count.sdk.java.internal;

import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
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
 *
 * Timing note: every wait here is a *generous upper bound* on a condition we
 * poll for, never a fixed sleep calibrated to one machine. The regression this
 * guards against is "the queue stops forever", so a slow CI runner must make
 * the test slower, never red. Only the drain assertion is a real assertion —
 * the setup waits are preconditions.
 *
 * Deliberately left on the production session update interval (60s). Shortening
 * it makes the SDK's own timer drain the backlog, which would make this test
 * pass even with the issue-271 bug present. The callback re-entry path must be
 * the only thing that can drain the queue here.
 */
@RunWith(JUnit4.class)
public class ScenarioRequestQueueStallTests {

    /**
     * Upper bound for a precondition to become true. Not a measurement.
     *
     * Sized from observed behaviour with a large margin: while a request is in
     * flight the synchronous Countly calls in the setup phase have been seen to
     * block for ~17s on a developer machine, and the drain runs at roughly one
     * request per second. These bounds cost nothing when the test passes -- the
     * waits return as soon as their condition holds -- so they are set well
     * above the worst case rather than close to the typical case.
     */
    private static final long SETUP_TIMEOUT_MS = 60_000;
    /** Upper bound for the queue to drain once request #1 is released. */
    private static final long DRAIN_TIMEOUT_MS = 60_000;
    /**
     * How long the server handler holds request #1 open. Must comfortably
     * exceed the whole setup phase: if it expires on its own the request
     * completes early and the backlog scenario never happens, which would show
     * up as a confusing drain failure rather than an honest timeout.
     */
    private static final long HOLD_TIMEOUT_MS = 120_000;
    private static final int BACKLOG_SIZE = 5;
    private static final int POLL_INTERVAL_MS = 50;

    private HttpServer server;
    private int port;
    /** Released in tearDown too, so a failed assertion never leaves the handler thread parked. */
    private final CountDownLatch releaseFirstRequest = new CountDownLatch(1);

    @Before
    public void setUp() {
        TestUtils.createCleanTestState();
    }

    @After
    public void tearDown() {
        releaseFirstRequest.countDown();
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
     * Polls until the condition holds or the budget runs out.
     *
     * @return true if the condition became true within the budget
     */
    private static boolean waitFor(long timeoutMs, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        return condition.getAsBoolean();
    }

    /** Number of persisted request files currently waiting on disk. */
    private static int queuedRequestFileCount() {
        File[] files = TestUtils.getTestSDirectory().listFiles();
        if (files == null) {
            return 0;
        }
        int count = 0;
        for (File file : files) {
            if (file.isFile() && file.getName().startsWith("[CLY]_request_")) {
                count++;
            }
        }
        return count;
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

        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", exchange -> {
            int n = requestCount.incrementAndGet();
            if (n == 1) {
                // Hold request #1 open until the test has built up a backlog.
                firstRequestArrived.countDown();
                try {
                    releaseFirstRequest.await(HOLD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            String body = "{\"result\":\"Success\"}";
            byte[] bytes = body.getBytes("UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        Countly.instance().init(configForLocalServer());
        Countly.session().begin();

        // Queue the backlog BEFORE waiting for request #1 to land.
        //
        // Ordering matters here and getting it wrong is what made this test
        // flaky. DefaultNetworking.check() no-ops while config.getDeviceId() is
        // still null, so the check() triggered by begin_session can lose that
        // startup race. Every subsequently queued request calls check() again
        // (SDKCore.onRequest -> Signal.Ping -> checkNetworking), so recording
        // the events here guarantees the queue head gets dispatched. Waiting
        // for request #1 first instead left the 60s session timer as the only
        // retry, which is far longer than any sane test timeout.
        //
        // Each recordEvent flushes a new request to disk; while the server
        // holds #1 they all pile up, because check() short-circuits on
        // isRunning() == true.
        for (int i = 0; i < BACKLOG_SIZE; i++) {
            Countly.instance().events().recordEvent("backlog_evt_" + i);
        }

        // Precondition: request #1 reached the server and is being held.
        Assert.assertTrue(
            "request #1 never reached the local server within " + SETUP_TIMEOUT_MS
                + "ms - the SDK never sent anything, so the drain scenario could not be set up",
            firstRequestArrived.await(SETUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        );

        // Precondition: the backlog is actually persisted. Waiting on the
        // observable state beats sleeping a fixed interval and hoping the
        // writes landed - that guess is what made this test runner-dependent.
        Assert.assertTrue(
            "expected " + BACKLOG_SIZE + " queued request files on disk within "
                + SETUP_TIMEOUT_MS + "ms, found " + queuedRequestFileCount(),
            waitFor(SETUP_TIMEOUT_MS, () -> queuedRequestFileCount() >= BACKLOG_SIZE)
        );

        // Guard against this test quietly becoming vacuous: if the backlog had
        // already drained while #1 was held, the assertion below would pass
        // without ever exercising the callback re-entry path.
        int expectedMinimum = 1 + BACKLOG_SIZE;
        Assert.assertTrue(
            "the backlog drained before request #1 was released (" + requestCount.get()
                + " requests) - the stall scenario was never set up, so this test would "
                + "no longer prove anything about issue #271",
            requestCount.get() < expectedMinimum
        );

        // Release #1. From this point on no external code calls check() —
        // the queue must self-drain via the callback re-entry path that
        // issue #271 broke.
        releaseFirstRequest.countDown();

        // The actual assertion. Total expected = 1 (begin_session) + backlog.
        // Use >= because device-id resolution or merge requests may add extras;
        // the regression is "queue stops at 1".
        boolean drained = waitFor(DRAIN_TIMEOUT_MS, () -> requestCount.get() >= expectedMinimum);

        Assert.assertTrue(
            "request queue should drain to >= " + expectedMinimum + " requests "
                + "without external check() calls — got " + requestCount.get()
                + " (queue stalled if << expected)",
            drained
        );
    }
}
