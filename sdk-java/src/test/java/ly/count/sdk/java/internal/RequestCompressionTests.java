package ly.count.sdk.java.internal;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;
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
    private static final int BIG = 5000;

    private GzipStandInServer server;
    private GzipStandInServer otherServer;

    /**
     * Starts every test without SDK files and with a new server.
     */
    @Before
    public void beforeTest() throws IOException {
        TestUtils.createCleanTestState();
        server = new GzipStandInServer();
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
    public void compressionOff_settingsRequestDoesNotAskAndRequestsArePlain() throws InterruptedException {
        server.answer(101, true);
        Countly.instance().init(config(server));
        awaitSettings(101);

        queueRequest("big", BIG);
        GzipStandInRequest big = server.await(withMarker("big"));

        Assert.assertNull(server.await(withMethod("sc")).params.get("gzip"));
        Assert.assertFalse(big.compressed);
        Assert.assertEquals(BIG, big.params.get("payload").length());
        Assert.assertFalse(provider().isRequestCompressionAllowed());
    }

    /**
     * Once the server says it reads gzip, a body of at least 1 KB goes compressed to the endpoint
     * with the marker as the only query, as a form body without a Content-Encoding header, and its
     * checksum is the one of the plain body.
     */
    @Test
    public void compressionOn_serverReadsGzip_bigRequestIsCompressed() throws InterruptedException {
        server.answer(101, true);
        Countly.instance().init(config(server).enableRequestCompression().enableParameterTamperingProtection(SALT));
        awaitSettings(101);
        Assert.assertTrue(provider().isRequestCompressionAllowed());

        queueRequest("big", BIG);
        GzipStandInRequest big = server.await(withMarker("big"));

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
    public void compressionOn_smallRequestStaysPlain() throws InterruptedException {
        server.answer(101, true);
        Countly.instance().init(config(server).enableRequestCompression().enableForcedHTTPPost());
        awaitSettings(101);

        queueRequest("small", 300);
        GzipStandInRequest small = server.await(withMarker("small"));
        queueRequest("big", BIG);
        GzipStandInRequest big = server.await(withMarker("big"));

        Assert.assertEquals("POST", small.method);
        Assert.assertFalse(small.compressed);
        Assert.assertTrue(small.bodyBytes < 1024);
        Assert.assertTrue(big.compressed);
    }

    /**
     * A settings answer without the gzip key keeps every body plain.
     */
    @Test
    public void compressionOn_serverDoesNotSayGzip_requestsArePlain() throws InterruptedException {
        server.answer(101, false);
        Countly.instance().init(config(server).enableRequestCompression());
        awaitSettings(101);

        queueRequest("big", BIG);
        GzipStandInRequest big = server.await(withMarker("big"));

        Assert.assertEquals("1", server.await(withMethod("sc")).params.get("gzip"));
        Assert.assertFalse(big.compressed);
        Assert.assertFalse(provider().isRequestCompressionAllowed());
    }

    /**
     * A body over 1 MB goes plain, as the server refuses a bigger compressed body, and compression
     * stays on for the requests after it.
     */
    @Test
    public void compressionOn_bodyOverOneMegabyteStaysPlain_compressionStaysOn() throws InterruptedException {
        server.answer(101, true);
        Countly.instance().init(config(server).enableRequestCompression());
        awaitSettings(101);

        queueRequest("huge", Utils.REQUEST_COMPRESSION_MAX_BYTES);
        GzipStandInRequest huge = server.await(withMarker("huge"));
        queueRequest("big", BIG);
        GzipStandInRequest big = server.await(withMarker("big"));

        Assert.assertFalse(huge.compressed);
        Assert.assertTrue(huge.bodyBytes > Utils.REQUEST_COMPRESSION_MAX_BYTES);
        Assert.assertTrue(big.compressed);
    }

    /**
     * A refused compressed request stays queued and goes plain on its next try, and so do the
     * requests after it.
     */
    @Test
    public void compressedRequestRefused_retryAndLaterRequestsArePlain() throws InterruptedException {
        server.answer(101, true);
        server.refuseCompressed.set(1);
        Countly.instance().init(config(server).enableRequestCompression());
        awaitSettings(101);

        queueRequest("refused", BIG);
        server.await(withMarker("refused"));
        awaitSendHandled();
        Countly.instance().attemptToSendStoredRequests();
        Assert.assertTrue(TestUtils.waitFor(15_000, () -> server.count(withMarker("refused")) == 2));
        queueRequest("after", BIG);
        GzipStandInRequest after = server.await(withMarker("after"));

        List<GzipStandInRequest> refused = server.all(withMarker("refused"));
        Assert.assertEquals(Arrays.asList(true, false), compressedFlags(refused));
        Assert.assertEquals(refused.get(0).params, refused.get(1).params);
        Assert.assertFalse(after.compressed);
    }

    /**
     * After a refusal stopped compression, a settings answer without gzip leaves it stopped, and the
     * next one with "gzip": true turns it on again.
     */
    @Test
    public void compressedRequestRefused_nextGzipAnswerTurnsCompressionOnAgain() throws InterruptedException {
        server.answer(101, true);
        server.refuseCompressed.set(1);
        Countly.instance().init(config(server).enableRequestCompression());
        awaitSettings(101);
        queueRequest("refused", BIG);
        server.await(withMarker("refused"));
        Assert.assertTrue(TestUtils.waitFor(10_000, () -> !provider().isRequestCompressionAllowed()));

        server.answer(102, false);
        fetchSettings();
        awaitSettings(102);
        Assert.assertFalse(provider().isRequestCompressionAllowed());

        server.answer(103, true);
        fetchSettings();
        awaitSettings(103);
        Assert.assertTrue(TestUtils.waitFor(10_000, () -> provider().isRequestCompressionAllowed()));
        awaitSendHandled();
        Countly.instance().attemptToSendStoredRequests();
        queueRequest("after_gzip_answer", BIG);
        GzipStandInRequest after = server.await(withMarker("after_gzip_answer"));

        Assert.assertTrue(after.compressed);
        Assert.assertEquals(Arrays.asList(true, false), compressedFlags(server.all(withMarker("refused"))));
    }

    /**
     * A compressed remote config download answered with JSON leaves compression on.
     */
    @Test
    public void compressedImmediateRequestAnsweredWithJson_compressionStaysOn() throws InterruptedException {
        Assert.assertTrue(immediateRequestKeepsCompression(200, "{\"color\":\"red\"}"));
    }

    /**
     * A compressed remote config download answered with a page, as a proxy in front of the server
     * may send, was not accepted: compression stops.
     */
    @Test
    public void compressedImmediateRequestAnsweredWithPage_stopsCompression() throws InterruptedException {
        Assert.assertFalse(immediateRequestKeepsCompression(200, "<html><body>Bad gateway</body></html>"));
    }

    /**
     * A compressed remote config download answered with an error status was not accepted:
     * compression stops.
     */
    @Test
    public void compressedImmediateRequestAnsweredWithError_stopsCompression() throws InterruptedException {
        Assert.assertFalse(immediateRequestKeepsCompression(400, "{\"result\":\"Invalid request body\"}"));
    }

    /**
     * A compressed remote config download answered with nothing was not accepted: compression stops.
     */
    @Test
    public void compressedImmediateRequestAnsweredWithNothing_stopsCompression() throws InterruptedException {
        Assert.assertFalse(immediateRequestKeepsCompression(200, ""));
    }

    /**
     * A compressed request that failed without an answer does not stop compression: the next try
     * goes compressed again.
     */
    @Test
    public void compressedRequestWithoutAnswer_compressionStaysOn() throws InterruptedException {
        server.answer(101, true);
        server.dropCompressed.set(true);
        Countly.instance().init(config(server).enableRequestCompression());
        awaitSettings(101);

        queueRequest("dropped", BIG);
        server.await(withMarker("dropped"));
        awaitSendHandled();
        server.dropCompressed.set(false);
        Countly.instance().attemptToSendStoredRequests();
        Assert.assertTrue(TestUtils.waitFor(15_000, () -> server.count(withMarker("dropped").and(r -> r.answered.get())) == 1));

        List<GzipStandInRequest> dropped = server.all(withMarker("dropped"));
        Assert.assertTrue(dropped.size() >= 2);
        for (GzipStandInRequest received : dropped) {
            Assert.assertTrue(received.compressed);
        }
        Assert.assertTrue(provider().isRequestCompressionAllowed());
    }

    /**
     * The gzip key is read from the live answer only, never stored with the settings.
     */
    @Test
    public void gzipAnswer_isNotStored() throws InterruptedException {
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
    public void serverUrlChange_newServerGetsPlainRequestsUntilItSaysGzip() throws IOException, InterruptedException {
        server.answer(101, true);
        otherServer = new GzipStandInServer();
        otherServer.answer(102, true);
        CountDownLatch release = new CountDownLatch(1);
        otherServer.holdSettings.set(release);
        Countly.instance().init(config(server).enableRequestCompression());
        awaitSettings(101);

        Countly.instance().changeServerUrl(otherServer.url());
        otherServer.await(withMethod("sc"));
        queueRequest("before_answer", BIG);
        GzipStandInRequest before = otherServer.await(withMarker("before_answer"));
        release.countDown();
        awaitSettings(102);
        queueRequest("after_answer", BIG);
        GzipStandInRequest after = otherServer.await(withMarker("after_answer"));

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
            Assert.assertFalse(answer, gzipFlagOf(answer));
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
            Assert.assertEquals(body, gunzipText(compressed));
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
    private boolean immediateRequestKeepsCompression(int remoteConfigCode, String remoteConfigAnswer) throws InterruptedException {
        server.answer(101, true);
        server.remoteConfigCode.set(remoteConfigCode);
        server.remoteConfigAnswer.set(remoteConfigAnswer);
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
        GzipStandInRequest after = server.await(withMarker("after_download"));

        GzipStandInRequest download = server.await(withMethod("rc"));
        Assert.assertTrue(download.compressed);
        Assert.assertEquals(120, new org.json.JSONArray(download.params.get("keys")).length());
        return after.compressed;
    }

    /**
     * A configuration that sends to the given server without request cooldowns or health check.
     */
    private static Config config(GzipStandInServer server, Config.Feature... features) {
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
    private static Predicate<GzipStandInRequest> withMarker(String marker) {
        return r -> marker.equals(r.params.get("marker"));
    }

    /**
     * Matches an immediate request of the given method.
     */
    private static Predicate<GzipStandInRequest> withMethod(String method) {
        return r -> method.equals(r.params.get("method"));
    }

    /**
     * Whether each request came compressed.
     */
    private static List<Boolean> compressedFlags(List<GzipStandInRequest> requests) {
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
     * Reads the gzip flag of a settings answer given as text.
     */
    private static boolean gzipFlagOf(String answer) {
        return ModuleConfiguration.extractGzipFlag(new JSONObject(answer));
    }

    /**
     * Decodes gzip bytes into text.
     */
    private static String gunzipText(byte[] compressed) throws IOException {
        return new String(GzipStandInRequest.gunzip(compressed), StandardCharsets.UTF_8);
    }

    // endregion
}
