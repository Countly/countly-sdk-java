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
     * Forced POST plus parameter tampering protection plus custom headers, which is the configuration
     * a customer behind a proxy runs. Proves the parameters move from the query string into an
     * url-encoded body, that the checksum is computed over them, and that the configured headers are
     * really put on the wire.
     */
    @Test
    public void forcedPost_sendsAnUrlEncodedBodyWithChecksumAndCustomHeaders() throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Tenant", "acme");
        headers.put("X-Ignored-Empty-Key", "value");

        Config config = config(Config.Feature.Events).setEventQueueSizeToSend(1);
        config.enableForcedHTTPPost();
        config.enableParameterTamperingProtection("pepper");
        config.addCustomNetworkRequestHeaders(headers);

        Countly.instance().init(config);
        Countly.instance().events().recordEvent("postedEvent");

        Received request = awaitRequestWith("events");

        Assert.assertEquals("POST", request.method);
        Assert.assertEquals("/i", request.path);
        Assert.assertTrue("forced POST must not leave parameters on the query string, got [" + request.query + "]",
            request.query == null || request.query.isEmpty());
        Assert.assertEquals("application/x-www-form-urlencoded", request.headers.get("content-type"));
        Assert.assertEquals("acme", request.headers.get("x-tenant"));

        Map<String, String> params = request.params();
        Assert.assertTrue(Utils.urldecode(params.get("events")).contains("postedEvent"));

        // The checksum is a SHA-256 hex digest, and it must be the one the salt produces.
        String checksum = params.get("checksum256");
        Assert.assertNotNull("a tampering protected request must carry a checksum", checksum);
        Assert.assertTrue(checksum.matches("[0-9a-f]{64}"));

        String withoutChecksum = request.bodyText().substring(0, request.bodyText().indexOf("&checksum256="));
        Assert.assertEquals(Utils.digestHex("SHA-256", withoutChecksum + "pepper", mock(Log.class)), checksum);
    }

    /**
     * A user picture given as raw bytes forces a multipart POST, which is the only request shape the
     * SDK builds by hand. Proves the boundary, the binary part and the text parts all land, and that
     * the picture bytes arrive intact.
     */
    @Test
    public void userPictureBytes_areSentAsMultipartFormData() throws Exception {
        byte[] picture = new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x10, 0x20, 0x30 };

        Countly.instance().init(config(Config.Feature.UserProfiles));
        Countly.instance().userProfile().setProperty(PredefinedUserPropertyKeys.PICTURE, picture);
        Countly.instance().userProfile().save();

        Received request = awaitRequestAfter(0);

        Assert.assertEquals("POST", request.method);
        String contentType = request.headers.get("content-type");
        Assert.assertNotNull(contentType);
        Assert.assertTrue("expected multipart, got [" + contentType + "]", contentType.startsWith("multipart/form-data; boundary="));

        String boundary = contentType.substring(contentType.indexOf("boundary=") + "boundary=".length());
        String body = request.bodyText();
        Assert.assertTrue(body.startsWith("--" + boundary));
        Assert.assertTrue("the body must close with the terminating boundary", body.contains("--" + boundary + "--"));

        // The binary part carries the image, the text parts carry the ordinary parameters.
        Assert.assertTrue(body.contains("Content-Disposition: form-data; name=\"binaryFile\"; filename=\"image\""));
        Assert.assertTrue(body.contains("Content-Type: image/jpeg"));
        Assert.assertTrue(body.contains("Content-Disposition: form-data; name=\"app_key\""));
        Assert.assertTrue(body.contains(TestUtils.SERVER_APP_KEY));

        Assert.assertTrue("the picture bytes must survive the multipart encoding", indexOf(request.body, picture) >= 0);
    }

    /**
     * A picture given as a local file path is read from disk instead, and a path that does not exist
     * must not stop the rest of the request. One flow, both branches of
     * {@code getPictureDataFromRequest}.
     */
    @Test
    public void userPicturePath_isReadFromDiskAndAMissingFileStillSendsTheRequest() throws Exception {
        File pictureFile = new File(TestUtils.getTestSDirectory(), "avatar.jpg");
        byte[] picture = new byte[] { 0x41, 0x42, 0x43, 0x44 };
        Files.write(pictureFile.toPath(), picture);

        Countly.instance().init(config(Config.Feature.UserProfiles));
        Countly.instance().userProfile().setProperty(PredefinedUserPropertyKeys.PICTURE_PATH, pictureFile.getAbsolutePath());
        Countly.instance().userProfile().save();

        Received fromDisk = awaitRequestAfter(0);
        Assert.assertEquals("POST", fromDisk.method);
        Assert.assertTrue(fromDisk.headers.get("content-type").startsWith("multipart/form-data"));
        Assert.assertTrue("the file contents must be the multipart payload", indexOf(fromDisk.body, picture) >= 0);

        // A path that is not there: the picture is dropped, the request still goes.
        int before = countReceived();
        Countly.instance().userProfile().setProperty(PredefinedUserPropertyKeys.PICTURE_PATH,
            new File(TestUtils.getTestSDirectory(), "missing-avatar.jpg").getAbsolutePath());
        Countly.instance().userProfile().save();

        Received missing = awaitRequestAfter(before);
        Assert.assertTrue("the request must still carry the app key",
            missing.bodyText().contains(TestUtils.SERVER_APP_KEY) || String.valueOf(missing.query).contains(TestUtils.SERVER_APP_KEY));
        Assert.assertTrue("a missing picture file must not become a multipart request",
            missing.headers.get("content-type") == null || !missing.headers.get("content-type").startsWith("multipart/"));
    }

    /**
     * A device id change is an "important" request and carries the old id, which is the branch that
     * picks the important-request cooldown in the send loop. Proves the old id reaches the server so
     * it can merge the two profiles.
     */
    @Test
    public void deviceIdChangeWithMerge_sendsTheOldIdOnTheWire() throws Exception {
        Countly.instance().init(config(Config.Feature.Events).setEventQueueSizeToSend(1));

        Countly.instance().deviceId().changeWithMerge("merged_network_user");

        Received request = awaitRequestWith(Params.PARAM_OLD_DEVICE_ID);
        Map<String, String> params = request.params();
        Assert.assertEquals(TestUtils.DEVICE_ID, Utils.urldecode(params.get(Params.PARAM_OLD_DEVICE_ID)));
        Assert.assertEquals("merged_network_user", Utils.urldecode(params.get("device_id")));

        awaitEmptyRequestQueue();
    }

    /**
     * A request longer than the SDK's GET budget switches to POST on its own, with no configuration.
     * Proves the length based decision in {@code Request.isGettable} really changes the wire format.
     */
    @Test
    public void anOversizedRequest_fallsBackToPostOnItsOwn() throws Exception {
        Countly.instance().init(config(Config.Feature.Events).setEventQueueSizeToSend(1));

        StringBuilder longValue = new StringBuilder();
        for (int i = 0; i < 130; i++) {
            longValue.append("segmentvalue");
        }
        Countly.instance().events().recordEvent("bigEvent", TestUtils.map("payload", longValue.toString()), 1, null, null);

        Received request = awaitRequestWith("events");
        Assert.assertEquals("a request over the GET budget must become a POST", "POST", request.method);
        Assert.assertTrue(request.query == null || request.query.isEmpty());
        Assert.assertTrue(Utils.urldecode(request.bodyText()).contains("bigEvent"));

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

    private static byte[] readAll(InputStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private int countReceived() {
        synchronized (received) {
            return received.size();
        }
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
            }
            Assert.assertTrue("no further request arrived", received.size() > alreadySeen);
            return received.get(alreadySeen);
        }
    }

    private int queuedRequestCount() {
        return TestUtils.getCurrentRQ().length;
    }

    private void awaitEmptyRequestQueue() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (queuedRequestCount() == 0) {
                return;
            }
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
