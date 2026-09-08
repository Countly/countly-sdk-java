package ly.count.sdk.java.internal;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import ly.count.sdk.java.PredefinedUserPropertyKeys;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Integration tests for {@link Transport#connection(Request)} and {@link Transport#response(HttpURLConnection)}
 * driven against a real local {@link HttpServer} (JDK built-in, no extra dependency).
 * <p>
 * Every test builds a bare {@link Transport}, initializes it against the local server via
 * {@link Transport#init(InternalConfig)} and then calls {@code connection()}/{@code response()}
 * directly, asserting on what the local server actually received on the wire (method, path,
 * query string, headers, body bytes) rather than only on internal SDK state.
 * <p>
 * {@link TransportTests} already covers {@code processResponse} in isolation; this file does not
 * duplicate that and focuses on everything upstream/downstream of it.
 */
@RunWith(JUnit4.class)
public class TransportConnectionTests {

    private HttpServer server;
    private int port;
    private final List<RecordedRequest> received = Collections.synchronizedList(new ArrayList<>());
    private volatile int nextResponseCode = 200;
    private volatile String nextResponseBody = "{\"result\":true}";

    @Before
    public void setUp() throws IOException {
        TestUtils.createCleanTestState();
        received.clear();
        nextResponseCode = 200;
        nextResponseBody = "{\"result\":true}";
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", exchange -> {
            try {
                // Case-insensitive on purpose: HTTP header names are case-insensitive on the wire,
                // and different JDKs normalize casing differently (e.g. "Content-Type" vs
                // "Content-type") when HttpURLConnection writes them out.
                Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                exchange.getRequestHeaders().forEach((k, v) -> headers.put(k, v.isEmpty() ? "" : v.get(0)));
                byte[] body = readAll(exchange.getRequestBody());
                received.add(new RecordedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getRawQuery(), headers, body));

                byte[] respBytes = nextResponseBody.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(nextResponseCode, respBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(respBytes);
                }
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        Countly.instance().halt();
    }

    // ==================== helpers ====================

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** Byte-exact, reversible string view of a raw HTTP body, safe for mixed text/binary multipart bodies. */
    private static String iso(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private Config localServerConfig() {
        return new Config("http://localhost:" + port, TestUtils.SERVER_APP_KEY, TestUtils.getTestSDirectory())
            .setApplicationVersion(TestUtils.APPLICATION_VERSION)
            .setLoggingLevel(Config.LoggingLevel.OFF);
    }

    private Transport buildTransport(Config config) {
        InternalConfig ic = new InternalConfig(config);
        ic.setLogger(new Log(Config.LoggingLevel.OFF, null));
        Transport transport = new Transport();
        transport.init(ic);
        return transport;
    }

    private RecordedRequest lastRequest() {
        Assert.assertFalse("server received no requests", received.isEmpty());
        return received.get(received.size() - 1);
    }

    private static class RecordedRequest {
        final String method;
        final String path;
        final String query;
        final Map<String, String> headers;
        final byte[] body;

        RecordedRequest(String method, String path, String query, Map<String, String> headers, byte[] body) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.headers = headers;
            this.body = body;
        }
    }

    // ==================== GET / device id fallback / custom headers ====================

    /**
     * Short request with no explicit "device_id" and no explicit endpoint, against a config with
     * custom network headers set. Proves: default endpoint "/i" is used, GET is chosen for a short
     * request, the missing device_id is filled in from {@link InternalConfig#getDeviceId()}, custom
     * headers reach the wire, and {@link Transport#response(HttpURLConnection)} reads a normal 200
     * body back correctly.
     */
    @Test
    public void connection_shortGetRequest_defaultEndpointDeviceIdFallbackAndCustomHeaders() throws IOException {
        Config config = localServerConfig();
        Map<String, String> customHeaders = new ConcurrentHashMap<>();
        customHeaders.put("X-Countly-Test", "hello-world");
        config.addCustomNetworkRequestHeaders(customHeaders);
        InternalConfig ic = new InternalConfig(config);
        ic.setLogger(new Log(Config.LoggingLevel.OFF, null));
        ic.setDeviceId(new Config.DID(Config.DID.STRATEGY_CUSTOM, "fallback-device-id"));
        Transport transport = new Transport();
        transport.init(ic);

        Request request = new Request();
        request.params.add("key1", "value1");

        HttpURLConnection connection = transport.connection(request);
        connection.connect();
        int code = connection.getResponseCode();
        String body = transport.response(connection);
        connection.disconnect();

        Assert.assertEquals(200, code);
        // response() appends '\n' after every line it reads, including the last one.
        Assert.assertEquals("{\"result\":true}\n", body);

        RecordedRequest recorded = lastRequest();
        Assert.assertEquals("GET", recorded.method);
        Assert.assertEquals("/i", recorded.path);
        Assert.assertEquals(0, recorded.body.length);
        Assert.assertEquals("hello-world", recorded.headers.get("X-Countly-Test"));

        Map<String, String> query = TestUtils.parseQueryParams(recorded.query);
        Assert.assertEquals("value1", query.get("key1"));
        Assert.assertEquals("fallback-device-id", query.get("device_id"));
        // The Request.ENDPOINT marker must never leak onto the wire.
        Assert.assertFalse(recorded.query.contains("endpoint="));
        Assert.assertFalse(request.params.has(Request.ENDPOINT));
    }

    /**
     * When the request already carries a "device_id" the fallback from {@link InternalConfig#getDeviceId()}
     * must not override it, even though a device id is configured.
     */
    @Test
    public void connection_requestOwnDeviceIdIsNotOverriddenByConfigFallback() throws IOException {
        Config config = localServerConfig();
        InternalConfig ic = new InternalConfig(config);
        ic.setLogger(new Log(Config.LoggingLevel.OFF, null));
        ic.setDeviceId(new Config.DID(Config.DID.STRATEGY_CUSTOM, "config-device-id"));
        Transport transport = new Transport();
        transport.init(ic);

        Request request = new Request();
        request.params.add("device_id", "request-own-device-id");

        HttpURLConnection connection = transport.connection(request);
        connection.connect();
        connection.getResponseCode();
        connection.disconnect();

        Map<String, String> query = TestUtils.parseQueryParams(lastRequest().query);
        Assert.assertEquals("request-own-device-id", query.get("device_id"));
    }

    // ==================== POST: forced, and auto-switched due to length ====================

    /**
     * {@link Config#enableForcedHTTPPost()} must force POST for a request that would otherwise be
     * GET-able. Proves: method is POST, Content-Type is urlencoded, the URL carries no query string,
     * and the wire body is exactly the request's param string.
     */
    @Test
    public void connection_forcedHttpPost_urlEncodedBodyWireFormat() throws IOException {
        Config config = localServerConfig().enableForcedHTTPPost();
        Transport transport = buildTransport(config);

        Request request = new Request();
        request.params.add("device_id", "d1");
        request.params.add("key1", "value1");
        request.endpoint("/i?");

        HttpURLConnection connection = transport.connection(request);
        connection.connect();
        int code = connection.getResponseCode();
        connection.disconnect();

        Assert.assertEquals(200, code);
        RecordedRequest recorded = lastRequest();
        Assert.assertEquals("POST", recorded.method);
        Assert.assertEquals("application/x-www-form-urlencoded", recorded.headers.get("Content-Type"));
        // The endpoint itself ends in '?', so the URL keeps an empty query rather than none at all.
        Assert.assertTrue("GET-style query string must be absent from a POST URL, got [" + recorded.query + "]",
            recorded.query == null || recorded.query.isEmpty());
        Assert.assertEquals(request.params.toString(), iso(recorded.body));
    }

    /**
     * A request long enough that {@link Request#isGettable(java.net.URL)} is false must switch to
     * POST automatically, without {@link Config#isHTTPPostForced()} being set. Proves the length-based
     * branch in {@code connection()}, independent from the forced-POST branch above.
     */
    @Test
    public void connection_longRequestAutoSwitchesToPostWithoutBeingForced() throws IOException {
        Config config = localServerConfig();
        Assert.assertFalse("precondition: this config must not force POST", config.isHTTPPostForced());
        Transport transport = buildTransport(config);

        Request request = new Request();
        request.params.add("device_id", "d1");
        StringBuilder longValue = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            longValue.append('a');
        }
        request.params.add("bigfield", longValue.toString());

        HttpURLConnection connection = transport.connection(request);
        connection.connect();
        connection.getResponseCode();
        connection.disconnect();

        RecordedRequest recorded = lastRequest();
        Assert.assertEquals("POST", recorded.method);
        Assert.assertTrue(iso(recorded.body).contains("bigfield="));
    }

    // ==================== Parameter tampering checksum ====================

    /**
     * With {@link Config#enableParameterTamperingProtection(String)} set, a GET-able request must
     * carry a "checksum256" query parameter equal to the SHA-256 hex digest of (params + salt),
     * computed over the params exactly as they stood right before the checksum was appended (the
     * checksum is always the last param added, so stripping its own trailing "&checksum256=..."
     * recovers that exact pre-checksum string).
     */
    @Test
    public void connection_getWithParameterTamperingProtection_checksumMatchesWireAndInternalState() throws IOException {
        String salt = "s3cr3t-salt";
        Config config = localServerConfig().enableParameterTamperingProtection(salt);
        Transport transport = buildTransport(config);

        Request request = new Request();
        request.params.add("device_id", "d1");
        request.params.add("key1", "value1");

        HttpURLConnection connection = transport.connection(request);
        connection.connect();
        connection.getResponseCode();
        connection.disconnect();

        String full = request.params.toString();
        String checksumOnWire = TestUtils.parseQueryParams(lastRequest().query).get("checksum256");
        Assert.assertNotNull("checksum256 must be present on the wire", checksumOnWire);
        Assert.assertEquals(64, checksumOnWire.length());

        String suffix = "&checksum256=" + checksumOnWire;
        Assert.assertTrue(full.endsWith(suffix));
        String withoutChecksum = full.substring(0, full.length() - suffix.length());
        String expected = Utils.digestHex("SHA-256", withoutChecksum + salt, null);
        Assert.assertEquals(expected, checksumOnWire);
        Assert.assertEquals(checksumOnWire, request.params.get("checksum256"));
    }

    /**
     * Same protection, but on a forced-POST (non-multipart) request: the checksum is appended to
     * {@code request.params} a second time (a distinct call site from the GET branch) and shipped
     * in the POST body rather than the query string.
     */
    @Test
    public void connection_postWithParameterTamperingProtection_checksumInBody() throws IOException {
        String salt = "another-salt";
        Config config = localServerConfig().enableForcedHTTPPost().enableParameterTamperingProtection(salt);
        Transport transport = buildTransport(config);

        Request request = new Request();
        request.params.add("device_id", "d1");
        request.params.add("key1", "value1");

        HttpURLConnection connection = transport.connection(request);
        connection.connect();
        connection.getResponseCode();
        connection.disconnect();

        String bodyOnWire = iso(lastRequest().body);
        Assert.assertEquals(request.params.toString(), bodyOnWire);

        String checksum = request.params.get("checksum256");
        Assert.assertNotNull(checksum);
        String full = request.params.toString();
        String suffix = "&checksum256=" + checksum;
        Assert.assertTrue(full.endsWith(suffix));
        String withoutChecksum = full.substring(0, full.length() - suffix.length());
        Assert.assertEquals(Utils.digestHex("SHA-256", withoutChecksum + salt, null), checksum);
    }

    // ==================== Multipart / picture ====================

    /**
     * A request carrying in-memory picture bytes (as {@code ModuleUserProfile.PICTURE_BYTES}) forces
     * multipart POST regardless of request length, and with parameter-tampering protection enabled
     * also exercises the multipart checksum branch of {@code addMultipart}. Proves the full wire
     * shape: multipart Content-Type with boundary, the binary part's headers and exact image bytes,
     * each remaining param as its own text part, and a checksum text part whose value matches the
     * same digest formula {@code connection()} uses internally.
     */
    @Test
    public void connection_pictureBytesInRequest_multipartWireFormatWithChecksum() throws IOException {
        String salt = "multipart-salt";
        Config config = localServerConfig().enableParameterTamperingProtection(salt);
        Transport transport = buildTransport(config);

        byte[] imageBytes = "FAKE-JPEG-BYTES-0123456789".getBytes(StandardCharsets.US_ASCII);

        Request request = new Request();
        request.params.add("device_id", "d1");
        request.params.add("key1", "value1");
        // Snapshot the params that will end up salted, BEFORE picture bytes are added: picture
        // params are stripped by getPictureDataFromRequest before the salting loop ever sees them.
        Map<String, String> saltedParams = request.params.map();
        request.params.add(ModuleUserProfile.PICTURE_BYTES, Utils.Base64.encode(imageBytes));

        HttpURLConnection connection = transport.connection(request);
        connection.connect();
        int code = connection.getResponseCode();
        connection.disconnect();

        Assert.assertEquals(200, code);
        RecordedRequest recorded = lastRequest();
        Assert.assertEquals("POST", recorded.method);

        String contentType = recorded.headers.get("Content-Type");
        Assert.assertNotNull(contentType);
        Assert.assertTrue(contentType.startsWith("multipart/form-data; boundary="));
        String boundary = contentType.substring(contentType.indexOf("boundary=") + "boundary=".length());

        String bodyText = iso(recorded.body);
        Assert.assertTrue("boundary must delimit the body", bodyText.contains("--" + boundary));
        Assert.assertTrue(bodyText.contains("Content-Disposition: form-data; name=\"binaryFile\"; filename=\"image\""));
        Assert.assertTrue(bodyText.contains("Content-Type: image/jpeg"));
        Assert.assertTrue("raw image bytes must be present verbatim in the body", bodyText.contains(iso(imageBytes)));
        Assert.assertTrue(bodyText.contains("Content-Disposition: form-data; name=\"key1\""));
        Assert.assertTrue(bodyText.contains("value1"));
        Assert.assertTrue("terminating boundary must close the multipart body", bodyText.trim().endsWith("--" + boundary + "--"));

        // Multipart checksum: same digest formula as connection(), computed over the pre-picture
        // params (urldecoded, "key=value&" joined) rather than the raw query string.
        StringBuilder salting = new StringBuilder();
        for (String key : saltedParams.keySet()) {
            salting.append(key).append('=').append(saltedParams.get(key)).append('&');
        }
        String expectedChecksum = Utils.digestHex("SHA-256", salting.substring(0, salting.length() - 1) + salt, null);
        Assert.assertTrue(bodyText.contains("Content-Disposition: form-data; name=\"checksum256\""));
        Assert.assertTrue("checksum computed over the pre-picture salted params must appear in the multipart body",
            bodyText.contains(expectedChecksum));

        // Picture params must never leak into request.params after connection() has consumed them.
        Assert.assertFalse(request.params.has(ModuleUserProfile.PICTURE_BYTES));
    }

    /**
     * Picture resolution from a local file path (the "file does not exist" branch and the
     * "read succeeds" branch of {@code getPictureDataFromRequest}), in one coherent flow:
     * a request pointing at a real file sends multipart with those exact bytes, while a request
     * pointing at a missing file degrades gracefully to a normal (non-multipart) request instead
     * of crashing.
     */
    @Test
    public void connection_pictureFromLocalPath_existingFileMultipartMissingFileDegradesGracefully() throws IOException {
        Config config = localServerConfig();

        byte[] fileBytes = "picture-bytes-from-disk".getBytes(StandardCharsets.US_ASCII);
        File pictureFile = new File(TestUtils.getTestSDirectory(), "test_picture.bin");
        Files.write(pictureFile.toPath(), fileBytes);
        try {
            Transport transportA = buildTransport(config);
            Request requestA = new Request();
            requestA.params.add("device_id", "d1");
            requestA.params.add(PredefinedUserPropertyKeys.PICTURE_PATH, pictureFile.getAbsolutePath());

            HttpURLConnection connA = transportA.connection(requestA);
            connA.connect();
            connA.getResponseCode();
            connA.disconnect();

            RecordedRequest recordedA = lastRequest();
            Assert.assertEquals("POST", recordedA.method);
            String contentTypeA = recordedA.headers.get("Content-Type");
            Assert.assertNotNull(contentTypeA);
            Assert.assertTrue(contentTypeA.startsWith("multipart/form-data"));
            Assert.assertTrue("exact bytes read from disk must be sent",
                iso(recordedA.body).contains(iso(fileBytes)));
            Assert.assertFalse(requestA.params.has(PredefinedUserPropertyKeys.PICTURE_PATH));
        } finally {
            Files.deleteIfExists(pictureFile.toPath());
        }

        received.clear();
        Transport transportB = buildTransport(config);
        Request requestB = new Request();
        requestB.params.add("device_id", "d1");
        requestB.params.add(PredefinedUserPropertyKeys.PICTURE_PATH, TestUtils.getTestSDirectory() + "/does_not_exist_at_all.bin");

        HttpURLConnection connB = transportB.connection(requestB);
        connB.connect();
        int codeB = connB.getResponseCode();
        connB.disconnect();

        Assert.assertEquals(200, codeB);
        RecordedRequest recordedB = lastRequest();
        String contentTypeB = recordedB.headers.get("Content-Type");
        Assert.assertTrue("missing picture file must not produce a multipart request",
            contentTypeB == null || !contentTypeB.startsWith("multipart/form-data"));
        Assert.assertFalse(requestB.params.has(PredefinedUserPropertyKeys.PICTURE_PATH));
    }

    // ==================== response() against non-2xx codes ====================

    /**
     * {@link Transport#response(HttpURLConnection)} falls back from {@code getInputStream()} to
     * {@code getErrorStream()} whenever the server answers with a non-2xx code; proves that fallback
     * actually returns the server's error body rather than null or an exception.
     */
    @Test
    public void response_non2xxServerCode_readsErrorStreamBody() throws IOException {
        nextResponseCode = 500;
        nextResponseBody = "{\"result\":\"Internal Server Error\"}";
        Transport transport = buildTransport(localServerConfig());

        Request request = new Request();
        request.params.add("device_id", "d1");

        HttpURLConnection connection = transport.connection(request);
        connection.connect();
        int code = connection.getResponseCode();
        String body = transport.response(connection);
        connection.disconnect();

        Assert.assertEquals(500, code);
        Assert.assertEquals(nextResponseBody + "\n", body);
    }
}
