package ly.count.sdk.java.internal;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Request compression, against HTTP servers on loopback that read a body as gzip the way the
 * Countly server does: only when the compression marker is the only query of the URL. Each
 * settings answer carries its own {@code lkl} value, so a test can wait until the SDK has read it.
 * The assertions are on what the servers received.
 */
@RunWith(JUnit4.class)
public class RequestCompressionTests {

    private static final String SALT = "gzip-salt";
    private static final String MARKER_QUERY = "content_encoding=gzip";
    private static final int BIG = 5000;

    private Server server;
    private Server otherServer;

    /**
     * Starts every test without SDK files and with a new server.
     */
    @Before
    public void beforeTest() throws IOException {
        TestUtils.createCleanTestState();
        server = new Server();
    }

    /**
     * Stops the SDK, deletes its data and stops the servers.
     */
    @After
    public void afterTest() {
        Countly.instance().halt();
        server.stop();
        if (otherServer != null) {
            otherServer.stop();
        }
    }

    /**
     * Without {@link Config#enableRequestCompression()} the settings request does not ask for
     * gzip, and a big request goes plain although the server says it reads gzip.
     */
    @Test
    public void compressionOff_settingsRequestDoesNotAskAndRequestsArePlain() throws Exception {
        server.answer(101, true);
        Countly.instance().init(config(server));
        awaitSettings(101);

        queueRequest("big", BIG);
        Received big = server.await(withMarker("big"));

        Assert.assertNull(server.await(withMethod("sc")).params.get("gzip"));
        Assert.assertFalse(big.compressed);
        Assert.assertEquals(BIG, big.params.get("payload").length());
        Assert.assertFalse(provider().getRequestCompressionEnabled());
    }

    /**
     * Once the server says it reads gzip, a body of at least 1 KB goes compressed to the endpoint
     * with the marker as the only query, as a form body without a Content-Encoding header, and its
     * checksum is the one of the plain body.
     */
    @Test
    public void compressionOn_serverReadsGzip_bigRequestIsCompressed() throws Exception {
        server.answer(101, true);
        Countly.instance().init(config(server).enableRequestCompression().enableParameterTamperingProtection(SALT));
        awaitSettings(101);
        Assert.assertTrue(provider().getRequestCompressionEnabled());

        queueRequest("big", BIG);
        Received big = server.await(withMarker("big"));

        Assert.assertEquals("1", server.await(withMethod("sc")).params.get("gzip"));
        Assert.assertTrue(big.compressed);
        Assert.assertEquals("/i", big.path);
        Assert.assertTrue("the compressed body has " + big.bodyBytes + " bytes", big.bodyBytes < 1000);
        Assert.assertEquals("application/x-www-form-urlencoded", big.contentType);
        Assert.assertNull(big.contentEncoding);
        Assert.assertEquals(repeat('a', BIG), big.params.get("payload"));
        String[] signed = big.body.split("&checksum256=");
        Assert.assertEquals(2, signed.length);
        Assert.assertEquals(Utils.digestHex("SHA-256", signed[0] + SALT, null), signed[1]);
    }

    /**
     * A body under 1 KB goes plain, also as a POST, and a bigger one after it is compressed.
     */
    @Test
    public void compressionOn_smallRequestStaysPlain() throws Exception {
        server.answer(101, true);
        Countly.instance().init(config(server).enableRequestCompression().enableForcedHTTPPost());
        awaitSettings(101);

        queueRequest("small", 300);
        Received small = server.await(withMarker("small"));
        queueRequest("big", BIG);
        Received big = server.await(withMarker("big"));

        Assert.assertEquals("POST", small.method);
        Assert.assertFalse(small.compressed);
        Assert.assertTrue(small.bodyBytes < 1024);
        Assert.assertTrue(big.compressed);
    }

    /**
     * A settings answer without the gzip key keeps every body plain.
     */
    @Test
    public void compressionOn_serverDoesNotSayGzip_requestsArePlain() throws Exception {
        server.answer(101, false);
        Countly.instance().init(config(server).enableRequestCompression());
        awaitSettings(101);

        queueRequest("big", BIG);
        Received big = server.await(withMarker("big"));

        Assert.assertEquals("1", server.await(withMethod("sc")).params.get("gzip"));
        Assert.assertFalse(big.compressed);
        Assert.assertFalse(provider().getRequestCompressionEnabled());
    }

    /**
     * A body over 1 MB goes plain, as the server refuses a bigger compressed body, and compression
     * stays on for the requests after it.
     */
    @Test
    public void compressionOn_bodyOverOneMegabyteStaysPlain_compressionStaysOn() throws Exception {
        server.answer(101, true);
        Countly.instance().init(config(server).enableRequestCompression());
        awaitSettings(101);

        queueRequest("huge", Utils.REQUEST_COMPRESSION_MAX_BYTES);
        Received huge = server.await(withMarker("huge"));
        queueRequest("big", BIG);
        Received big = server.await(withMarker("big"));

        Assert.assertFalse(huge.compressed);
        Assert.assertTrue(huge.bodyBytes > Utils.REQUEST_COMPRESSION_MAX_BYTES);
        Assert.assertTrue(big.compressed);
    }

    /**
     * A refused compressed request stays queued and goes plain on its next try, and so do the
     * requests after it.
     */
    @Test
    public void compressedRequestRefused_retryAndLaterRequestsArePlain() throws Exception {
        server.answer(101, true);
        server.refuseCompressed = 1;
        Countly.instance().init(config(server).enableRequestCompression());
        awaitSettings(101);

        queueRequest("refused", BIG);
        server.await(withMarker("refused"));
        awaitSendHandled();
        Countly.instance().attemptToSendStoredRequests();
        Assert.assertTrue(TestUtils.waitFor(15_000, () -> server.count(withMarker("refused")) == 2));
        queueRequest("after", BIG);
        Received after = server.await(withMarker("after"));

        List<Received> refused = server.all(withMarker("refused"));
        Assert.assertEquals(Arrays.asList(true, false), compressedFlags(refused));
        Assert.assertEquals(refused.get(0).params, refused.get(1).params);
        Assert.assertFalse(after.compressed);
    }

    /**
     * After a refusal stopped compression, a settings answer without gzip leaves it stopped, and the
     * next one with "gzip": true turns it on again.
     */
    @Test
    public void compressedRequestRefused_nextGzipAnswerTurnsCompressionOnAgain() throws Exception {
        server.answer(101, true);
        server.refuseCompressed = 1;
        Countly.instance().init(config(server).enableRequestCompression());
        awaitSettings(101);
        queueRequest("refused", BIG);
        server.await(withMarker("refused"));
        Assert.assertTrue(TestUtils.waitFor(10_000, () -> !provider().getRequestCompressionEnabled()));

        server.answer(102, false);
        fetchSettings();
        awaitSettings(102);
        Assert.assertFalse(provider().getRequestCompressionEnabled());

        server.answer(103, true);
        fetchSettings();
        awaitSettings(103);
        Assert.assertTrue(TestUtils.waitFor(10_000, () -> provider().getRequestCompressionEnabled()));
        awaitSendHandled();
        Countly.instance().attemptToSendStoredRequests();
        queueRequest("after_gzip_answer", BIG);
        Received after = server.await(withMarker("after_gzip_answer"));

        Assert.assertTrue(after.compressed);
        Assert.assertEquals(Arrays.asList(true, false), compressedFlags(server.all(withMarker("refused"))));
    }

    /**
     * A compressed remote config download answered with JSON leaves compression on.
     */
    @Test
    public void compressedImmediateRequestAnsweredWithJson_compressionStaysOn() throws Exception {
        Assert.assertTrue(immediateRequestKeepsCompression(200, "{\"color\":\"red\"}"));
    }

    /**
     * A compressed remote config download answered with a page, as a proxy in front of the server
     * may send, was not accepted: compression stops.
     */
    @Test
    public void compressedImmediateRequestAnsweredWithPage_stopsCompression() throws Exception {
        Assert.assertFalse(immediateRequestKeepsCompression(200, "<html><body>Bad gateway</body></html>"));
    }

    /**
     * A compressed remote config download answered with an error status was not accepted:
     * compression stops.
     */
    @Test
    public void compressedImmediateRequestAnsweredWithError_stopsCompression() throws Exception {
        Assert.assertFalse(immediateRequestKeepsCompression(400, "{\"result\":\"Invalid request body\"}"));
    }

    /**
     * A compressed remote config download answered with nothing was not accepted: compression stops.
     */
    @Test
    public void compressedImmediateRequestAnsweredWithNothing_stopsCompression() throws Exception {
        Assert.assertFalse(immediateRequestKeepsCompression(200, ""));
    }

    /**
     * A compressed request that failed without an answer does not stop compression: the next try
     * goes compressed again.
     */
    @Test
    public void compressedRequestWithoutAnswer_compressionStaysOn() throws Exception {
        server.answer(101, true);
        server.dropCompressed = true;
        Countly.instance().init(config(server).enableRequestCompression());
        awaitSettings(101);

        queueRequest("dropped", BIG);
        server.await(withMarker("dropped"));
        awaitSendHandled();
        server.dropCompressed = false;
        Countly.instance().attemptToSendStoredRequests();
        Assert.assertTrue(TestUtils.waitFor(15_000, () -> server.count(withMarker("dropped").and(r -> r.answered)) == 1));

        List<Received> dropped = server.all(withMarker("dropped"));
        Assert.assertTrue(dropped.size() >= 2);
        for (Received received : dropped) {
            Assert.assertTrue(received.compressed);
        }
        Assert.assertTrue(provider().getRequestCompressionEnabled());
    }

    /**
     * The gzip key is read from the live answer only, never stored with the settings.
     */
    @Test
    public void gzipAnswer_isNotStored() throws Exception {
        server.answer(101, true);
        Countly.instance().init(config(server).enableRequestCompression());
        awaitSettings(101);

        ModuleConfiguration configuration = SDKCore.instance.module(ModuleConfiguration.class);
        synchronized (configuration) {
            Assert.assertFalse(configuration.latestRetrievedConfiguration.has("gzip"));
        }
        String stored = SDKCore.instance.sdkStorage.getServerConfig();
        Assert.assertNotNull(stored);
        Assert.assertFalse(stored.contains("gzip"));
    }

    /**
     * After a server URL change the requests go plain, until the new server says it reads gzip.
     */
    @Test
    public void serverUrlChange_newServerGetsPlainRequestsUntilItSaysGzip() throws Exception {
        server.answer(101, true);
        otherServer = new Server();
        otherServer.answer(102, true);
        CountDownLatch release = new CountDownLatch(1);
        otherServer.holdSettings = release;
        Countly.instance().init(config(server).enableRequestCompression());
        awaitSettings(101);

        Countly.instance().changeServerUrl(otherServer.url());
        otherServer.await(withMethod("sc"));
        queueRequest("before_answer", BIG);
        Received before = otherServer.await(withMarker("before_answer"));
        release.countDown();
        awaitSettings(102);
        queueRequest("after_answer", BIG);
        Received after = otherServer.await(withMarker("after_answer"));

        Assert.assertFalse(before.compressed);
        Assert.assertTrue(after.compressed);
        Assert.assertEquals(0, server.count(withMarker("before_answer")));
    }

    /**
     * Only an answer with "gzip": true counts, and the key is removed from it.
     */
    @Test
    public void extractGzipFlag_onlyTrueCounts() {
        JSONObject accepted = new JSONObject("{\"v\":1,\"t\":1,\"c\":{},\"gzip\":true}");
        Assert.assertTrue(ModuleConfiguration.extractGzipFlag(accepted));
        Assert.assertFalse(accepted.has("gzip"));

        for (String answer : new String[] { "{\"gzip\":false}", "{\"gzip\":\"true\"}", "{\"gzip\":1}", "{\"c\":{\"gzip\":true}}", "{}" }) {
            Assert.assertFalse(answer, ModuleConfiguration.extractGzipFlag(new JSONObject(answer)));
        }
        Assert.assertFalse(ModuleConfiguration.extractGzipFlag(null));
    }

    /**
     * Bodies under 1 KB and over 1 MB are left alone, the ones between are compressed into gzip
     * that decodes to the body.
     */
    @Test
    public void gzipRequestBody_bounds() throws IOException {
        Log log = new Log(Config.LoggingLevel.OFF, null);
        Assert.assertEquals(0, Utils.gzipRequestBody(repeat('a', Utils.REQUEST_COMPRESSION_MIN_BYTES - 1), log).length);
        Assert.assertEquals(0, Utils.gzipRequestBody(repeat('a', Utils.REQUEST_COMPRESSION_MAX_BYTES + 1), log).length);
        for (int size : new int[] { Utils.REQUEST_COMPRESSION_MIN_BYTES, Utils.REQUEST_COMPRESSION_MAX_BYTES }) {
            String body = "app_key=key&payload=" + repeat('a', size - 20);
            byte[] compressed = Utils.gzipRequestBody(body, log);
            Assert.assertTrue(compressed.length > 0 && compressed.length < body.length());
            Assert.assertEquals(body, new String(gunzip(compressed), StandardCharsets.UTF_8));
        }
    }

    /**
     * The marker fills the empty query of an endpoint, or becomes the query of a URL without one; a
     * URL that already has a query cannot carry it.
     */
    @Test
    public void compressedRequestPath_markerIsTheOnlyQuery() {
        Assert.assertEquals("http://host/i?content_encoding=gzip", Transport.compressedRequestPath("http://host/i?"));
        Assert.assertEquals("http://host/o/sdk?content_encoding=gzip", Transport.compressedRequestPath("http://host/o/sdk"));
        Assert.assertNull(Transport.compressedRequestPath("http://host/o/sdk?method=rc"));
    }

    // region helpers

    /**
     * Downloads remote config with 120 keys, a compressed POST, answered with the given status and
     * text, then tells whether a big request sent after it was compressed.
     */
    private boolean immediateRequestKeepsCompression(int remoteConfigCode, String remoteConfigAnswer) throws Exception {
        server.answer(101, true);
        server.remoteConfigCode = remoteConfigCode;
        server.remoteConfigAnswer = remoteConfigAnswer;
        Countly.instance().init(config(server, Config.Feature.RemoteConfig).enableRequestCompression());
        awaitSettings(101);

        String[] keys = new String[120];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = "remote_config_key_" + i;
        }
        CountDownLatch downloaded = new CountDownLatch(1);
        Countly.instance().remoteConfig().downloadSpecificKeys(keys, (result, error, fullValueUpdate, values) -> downloaded.countDown());
        Assert.assertTrue(downloaded.await(15, TimeUnit.SECONDS));
        queueRequest("after_download", BIG);
        Received after = server.await(withMarker("after_download"));

        Received download = server.await(withMethod("rc"));
        Assert.assertTrue(download.compressed);
        Assert.assertEquals(120, new org.json.JSONArray(download.params.get("keys")).length());
        return after.compressed;
    }

    /**
     * A configuration that sends to the given server without request cooldowns or health check.
     */
    private static Config config(Server server, Config.Feature... features) {
        File directory = TestUtils.getTestSDirectory();
        TestUtils.checkSdkStorageRootDirectoryExist(directory);

        Config config = new Config(server.url(), TestUtils.SERVER_APP_KEY, directory);
        config.setApplicationVersion(TestUtils.APPLICATION_VERSION);
        config.setCustomDeviceId(TestUtils.DEVICE_ID);
        config.disableHealthCheck();
        config.enableFeatures(features);
        config.setNetworkRequestCooldown(0);
        config.setNetworkImportantRequestCooldown(0);
        return config;
    }

    /**
     * The SDK behavior settings in effect.
     */
    private static ConfigurationProvider provider() {
        return SDKCore.instance.config.getConfigurationProvider();
    }

    /**
     * Waits until the SDK has read the settings answer that carries the given {@code lkl} value.
     */
    private static void awaitSettings(int lkl) throws InterruptedException {
        Assert.assertTrue("the settings answer with lkl " + lkl + " was not read", TestUtils.waitFor(15_000, () -> provider().getMaxKeyLength() == lkl));
    }

    /**
     * Fetches the settings again, as the refresh timer does.
     */
    private static void fetchSettings() {
        SDKCore.instance.module(ModuleConfiguration.class).fetchConfigFromServer(SDKCore.instance.config);
    }

    /**
     * Queues a request with a payload of the given length, told apart by its marker parameter.
     */
    private static void queueRequest(String marker, int payloadLength) {
        InternalConfig config = SDKCore.instance.config;
        Request request = new Request();
        request.params.add(ModuleRequests.prepareRequiredParams(config));
        request.params.add("marker", marker);
        request.params.add("payload", repeat('a', payloadLength));
        ModuleRequests.pushAsync(config, request);
    }

    /**
     * Waits until the SDK finished handling the request it is sending, so a send asked for next is
     * not folded into it.
     */
    private static void awaitSendHandled() throws InterruptedException {
        Assert.assertTrue("the send in progress must end", TestUtils.waitFor(10_000, () -> !SDKCore.instance.networking.isSending()));
    }

    /**
     * Matches the request that carries the given marker parameter.
     */
    private static Predicate<Received> withMarker(String marker) {
        return r -> marker.equals(r.params.get("marker"));
    }

    /**
     * Matches an immediate request of the given method.
     */
    private static Predicate<Received> withMethod(String method) {
        return r -> method.equals(r.params.get("method"));
    }

    /**
     * Whether each request came compressed.
     */
    private static List<Boolean> compressedFlags(List<Received> requests) {
        return requests.stream().map(r -> r.compressed).collect(Collectors.toList());
    }

    /**
     * A text of the given length made of one character.
     */
    private static String repeat(char c, int length) {
        char[] chars = new char[length];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    /**
     * Decodes gzip bytes.
     */
    private static byte[] gunzip(byte[] compressed) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return readAll(in);
        }
    }

    /**
     * Reads a stream to its end.
     */
    private static byte[] readAll(InputStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /**
     * One request as a server received it: the body decoded and its parameters read the way the
     * Countly server reads them.
     */
    private static final class Received {
        final String method;
        final String path;
        final boolean compressed;
        final String body;
        final int bodyBytes;
        final String contentType;
        final String contentEncoding;
        final Map<String, String> params;
        volatile boolean answered = false;

        /**
         * Reads a request from its exchange.
         */
        Received(HttpExchange exchange) throws IOException {
            method = exchange.getRequestMethod();
            path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();
            compressed = MARKER_QUERY.equals(query);
            byte[] raw = readAll(exchange.getRequestBody());
            bodyBytes = raw.length;
            body = new String(compressed ? gunzip(raw) : raw, StandardCharsets.UTF_8);
            contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            contentEncoding = exchange.getRequestHeaders().getFirst("Content-Encoding");
            params = TestUtils.parseQueryParams(compressed || !Utils.isNotEmpty(query) ? body : query);
            params.replaceAll((key, value) -> Utils.urldecode(value));
        }
    }

    /**
     * A Countly server stand-in on loopback that records every request. It answers the SDK behavior
     * settings with the answer set by {@link #answer(int, boolean)}, remote config with
     * {@link #remoteConfigCode} and {@link #remoteConfigAnswer}, and anything else with success; it can refuse or drop compressed
     * requests and hold its settings answer until a latch opens.
     */
    private static final class Server {
        final HttpServer http;
        final List<Received> received = new CopyOnWriteArrayList<>();
        volatile String settingsAnswer = "{}";
        volatile String remoteConfigAnswer = "{}";
        volatile int remoteConfigCode = 200;
        volatile int refuseCompressed = 0;
        volatile boolean dropCompressed = false;
        volatile CountDownLatch holdSettings = null;

        /**
         * Starts the server on a free loopback port.
         */
        Server() throws IOException {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.createContext("/", this::handle);
            http.setExecutor(Executors.newCachedThreadPool());
            http.start();
        }

        /**
         * Sets the settings answer: an {@code lkl} value that tells it apart, with "gzip": true when
         * the server reads compressed bodies.
         */
        void answer(int lkl, boolean gzip) {
            JSONObject answer = new JSONObject().put("v", 1).put("t", 1_700_000_000_000L).put("c", new JSONObject().put("lkl", lkl));
            if (gzip) {
                answer.put("gzip", true);
            }
            settingsAnswer = answer.toString();
        }

        /**
         * Records a request and answers it.
         */
        private void handle(HttpExchange exchange) throws IOException {
            Received request = new Received(exchange);
            received.add(request);
            String method = request.params.get("method");
            int code = 200;
            String response = "{\"result\":\"Success\"}";
            if ("sc".equals(method)) {
                CountDownLatch hold = holdSettings;
                if (hold != null) {
                    try {
                        hold.await(15, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                response = settingsAnswer;
            } else if ("rc".equals(method)) {
                code = remoteConfigCode;
                response = remoteConfigAnswer;
            }
            if (request.compressed && dropCompressed) {
                exchange.close();
                return;
            }
            if (request.compressed && refuseCompressed > 0) {
                refuseCompressed--;
                code = 400;
                response = "{\"result\":\"Invalid request body\"}";
            }
            byte[] out = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, out.length == 0 ? -1 : out.length);
            try (OutputStream stream = exchange.getResponseBody()) {
                stream.write(out);
            }
            request.answered = true;
        }

        /**
         * The URL the SDK reaches this server at.
         */
        String url() {
            return "http://127.0.0.1:" + http.getAddress().getPort();
        }

        /**
         * How many received requests match.
         */
        int count(Predicate<Received> filter) {
            return (int) received.stream().filter(filter).count();
        }

        /**
         * Every received request that matches, in arrival order.
         */
        List<Received> all(Predicate<Received> filter) {
            return new ArrayList<>(received.stream().filter(filter).collect(Collectors.toList()));
        }

        /**
         * Waits for the first matching request and returns it.
         */
        Received await(Predicate<Received> filter) throws InterruptedException {
            Assert.assertTrue("no matching request arrived at " + url() + ", it received " + received.size(), TestUtils.waitFor(15_000, () -> count(filter) > 0));
            return received.stream().filter(filter).findFirst().orElse(null);
        }

        /**
         * Opens a held settings answer and stops the server.
         */
        void stop() {
            CountDownLatch hold = holdSettings;
            if (hold != null) {
                hold.countDown();
            }
            http.stop(0);
        }
    }

    // endregion
}
