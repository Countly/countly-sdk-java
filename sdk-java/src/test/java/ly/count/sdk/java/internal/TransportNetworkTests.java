package ly.count.sdk.java.internal;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import ly.count.sdk.java.PredefinedUserPropertyKeys;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.mockito.Mockito.mock;

/**
 * The networking stack end to end, against a real HTTP server on loopback.
 * <p>
 * Everything else in the suite fakes the transport away, which leaves {@link Transport} the largest
 * untested class in the SDK. Here the SDK is pointed at a {@code com.sun.net.httpserver.HttpServer}
 * (part of the JDK, so no new dependency) and driven through its public API, so the assertions are
 * on what a Countly server would actually have received: the HTTP method, the path, and the
 * url-encoded parameters.
 */
@RunWith(JUnit4.class)
public class TransportNetworkTests {

    /**
     * One request as the server saw it.
     */
    private static class Received {
        String method;
        String path;
        String query;
        byte[] body;
        Map<String, String> headers = new HashMap<>();

        /**
         * @return the request parameters, whether they arrived on the query string or in the body
         */
        Map<String, String> params() {
            String source = query != null && !query.isEmpty() ? query : new String(body, StandardCharsets.UTF_8);
            return TestUtils.parseQueryParams(source);
        }

        String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private HttpServer server;
    private int port;
    private final List<Received> received = new ArrayList<>();

    private volatile int responseCode = 200;
    private volatile String responseBody = "{\"result\":\"Success\"}";

    @Before
    public void beforeTest() throws IOException {
        TestUtils.createCleanTestState();
        received.clear();
        responseCode = 200;
        responseBody = "{\"result\":\"Success\"}";

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", exchange -> {
            Received r = new Received();
            r.method = exchange.getRequestMethod();
            r.path = exchange.getRequestURI().getPath();
            r.query = exchange.getRequestURI().getRawQuery();
            r.body = readAll(exchange.getRequestBody());
            exchange.getRequestHeaders().forEach((key, values) -> {
                if (!values.isEmpty()) {
                    r.headers.put(key.toLowerCase(), values.get(0));
                }
            });

            synchronized (received) {
                received.add(r);
                received.notifyAll();
            }

            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseCode, out.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(out);
            }
        });
        // Single threaded, so the recorded order is the order the SDK sent them in.
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
    }

    @After
    public void stop() {
        Countly.instance().halt();
        if (server != null) {
            server.stop(0);
        }
    }

    // region scenarios

    /**
     * The ordinary path: an event recorded through the public API is url-encoded onto a GET query
     * string, reaches {@code /i}, and once the server accepts it the request leaves the on-disk
     * queue. Proves the whole loop, not just that a request was built.
     */
    @Test
    public void recordedEvent_reachesTheServerAsGetAndLeavesTheQueue() throws Exception {
        Countly.instance().init(config(Config.Feature.Events).setEventQueueSizeToSend(1));

        Countly.instance().events().recordEvent("networkedEvent", TestUtils.map("colour", "green"), 2, 3.5, null);

        Received request = awaitRequestWith("events");

        Assert.assertEquals("GET", request.method);
        Assert.assertEquals("/i", request.path);
        Assert.assertEquals(0, request.body.length);

        Map<String, String> params = request.params();
        Assert.assertEquals(TestUtils.SERVER_APP_KEY, params.get("app_key"));
        Assert.assertEquals(TestUtils.DEVICE_ID, Utils.urldecode(params.get("device_id")));
        Assert.assertEquals(TestUtils.SDK_NAME, params.get("sdk_name"));
        Assert.assertTrue(Long.parseLong(params.get("timestamp")) > 0);

        String events = Utils.urldecode(params.get("events"));
        Assert.assertTrue("events payload was [" + events + "]", events.contains("\"key\":\"networkedEvent\""));
        Assert.assertTrue(events.contains("\"count\":2"));
        Assert.assertTrue(events.contains("\"sum\":3.5"));
        Assert.assertTrue(events.contains("\"colour\":\"green\""));

        // The server said "result", so the request must not be left behind on disk.
        awaitEmptyRequestQueue();
    }

    /**
     * A rejected request must survive. The server answers 500, so {@code processResponse} fails the
     * send and the request stays queued for a later attempt rather than being dropped.
     */
    @Test
    public void serverRejectsTheRequest_soItStaysInTheQueue() throws Exception {
        responseCode = 500;
        responseBody = "{\"result\":\"nope\"}";

        Countly.instance().init(config(Config.Feature.Events).setEventQueueSizeToSend(1));
        Countly.instance().events().recordEvent("rejectedEvent");

        Received request = awaitRequestWith("events");
        Assert.assertTrue(Utils.urldecode(request.params().get("events")).contains("rejectedEvent"));

        // Still on disk: a 500 is retryable, so nothing may be discarded.
        Storage.await(mock(Log.class));
        Assert.assertTrue("a rejected request must stay queued", queuedRequestCount() >= 1);
    }

    /**
     * A response that is not JSON at all is a failure, not a crash. Proves the parse guard in
     * {@code processResponse} and that the request is kept.
     */
    @Test
    public void nonJsonResponse_isTreatedAsAFailureAndKeepsTheRequest() throws Exception {
        responseBody = "<html>gateway error</html>";

        Countly.instance().init(config(Config.Feature.Events).setEventQueueSizeToSend(1));
        Countly.instance().events().recordEvent("gatewayEvent");

        awaitRequestWith("events");

        Storage.await(mock(Log.class));
        Assert.assertTrue("an unparseable response must not drop the request", queuedRequestCount() >= 1);
    }




    /**
     * A device id change is an "important" request and carries the old id, which is the branch that
     * picks the important-request cooldown in the send loop. Proves the old id reaches the server so
     * it can merge the two profiles.
     */
    @Test
    public void deviceIdChangeWithMerge_sendsTheOldIdThenTracksUnderTheNewOne() throws Exception {
        Countly.instance().init(config(Config.Feature.Events).setEventQueueSizeToSend(1));

        // A login, then the first thing the application tracks as the logged in user.
        Countly.instance().deviceId().changeWithMerge("merged_network_user");
        Countly.instance().events().recordEvent("afterLogin");

        Received merge = awaitRequestWith(Params.PARAM_OLD_DEVICE_ID);
        Map<String, String> mergeParams = merge.params();
        Assert.assertEquals(TestUtils.DEVICE_ID, Utils.urldecode(mergeParams.get(Params.PARAM_OLD_DEVICE_ID)));
        Assert.assertEquals("merged_network_user", Utils.urldecode(mergeParams.get("device_id")));

        // Everything after the change must be attributed to the new id.
        Received event = awaitRequestWith("events");
        Assert.assertEquals("merged_network_user", Utils.urldecode(event.params().get("device_id")));
        Assert.assertTrue(Utils.urldecode(event.params().get("events")).contains("afterLogin"));

        awaitEmptyRequestQueue();
    }


    // endregion
    // region helpers

    private Config config(Config.Feature... features) {
        File directory = TestUtils.getTestSDirectory();
        TestUtils.checkSdkStorageRootDirectoryExist(directory);

        Config config = new Config("http://127.0.0.1:" + port, TestUtils.SERVER_APP_KEY, directory);
        config.setApplicationVersion(TestUtils.APPLICATION_VERSION);
        config.setCustomDeviceId(TestUtils.DEVICE_ID);
        config.enableFeatures(features);
        // Nothing here should wait: the cooldowns only exist to be kind to a real server.
        config.setNetworkRequestCooldown(0);
        config.setNetworkImportantRequestCooldown(0);
        return config;
    }

    /**
     * Nudges the send loop. {@code DefaultNetworking.check} is what picks the next request off the
     * queue, and the SDK only calls it when something is pushed, so a test that waits for a request
     * already on disk has to ask for it. This is the same idea as driving the content zone timer by
     * hand in {@code ModuleContentTests} rather than sleeping.
     */
    private void pump() {
        if (SDKCore.instance != null && SDKCore.instance.networking != null) {
            SDKCore.instance.networking.check(SDKCore.instance.config);
        }
    }

    private static byte[] readAll(InputStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /**
     * Waits for a request carrying the given parameter. A condition wait rather than a fixed sleep,
     * so the test is as fast as the SDK is and still fails loudly if nothing arrives.
     */
    private Received awaitRequestWith(String parameter) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        synchronized (received) {
            while (System.currentTimeMillis() < deadline) {
                for (Received candidate : received) {
                    if (candidate.params().containsKey(parameter)) {
                        return candidate;
                    }
                }
                received.wait(50);
                pump();
            }
            Assert.fail("no request carrying [" + parameter + "] arrived, saw " + received.size() + " request(s)");
        }
        return null;
    }

    private Received awaitRequestAfter(int alreadySeen) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        synchronized (received) {
            while (received.size() <= alreadySeen && System.currentTimeMillis() < deadline) {
                received.wait(50);
                pump();
            }
            Assert.assertTrue("no further request arrived", received.size() > alreadySeen);
            return received.get(alreadySeen);
        }
    }

    /**
     * Counts queue files without reading them. {@link TestUtils#getCurrentRQ()} fails the test if a
     * file disappears between listing and reading, which is exactly what the network loop does while
     * it drains, so it cannot be used to watch a queue that is still moving.
     */
    private int queuedRequestCount() {
        File[] files = TestUtils.getTestSDirectory().listFiles(
            (dir, name) -> name.startsWith("[CLY]_request_"));
        return files == null ? 0 : files.length;
    }

    private void awaitEmptyRequestQueue() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (queuedRequestCount() == 0) {
                return;
            }
            pump();
            Thread.sleep(50);
        }
        Assert.assertEquals("an accepted request must be removed from the queue", 0, queuedRequestCount());
    }

    /**
     * @return the index of {@code needle} inside {@code haystack}, or -1
     */
    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    // endregion
}
