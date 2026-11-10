package ly.count.sdk.java.internal;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Changing the server URL and the app key while the SDK runs, and the request queue calls, against
 * two real HTTP servers on loopback standing in for an old and a new Countly server. The assertions
 * are on what each server received.
 */
@RunWith(JUnit4.class)
public class ServerUrlChangeTests {

    private static final String NEW_APP_KEY = "NEW_APP_KEY";

    private static final String PUBLIC_KEY_PEM =
        "-----BEGIN PUBLIC KEY-----\n"
            + "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAoEAoIix1JRqGjxxZlVLc\n"
            + "EYuoQE5QvdClJjdFxtkqnTxHim0Qlvh8tA0EoiFgDdhCvp2HLk3w8EloR5Op2JQK\n"
            + "kyTEqPk5OY7ftg39WVsGIAP0CxwoyCymGgmP2gRimjV1wa+58Ng3f5MktTKAVWX9\n"
            + "1/ki9khQ2XVIXUv5wq3Yx0VyCZa80W7bifXqfDnLmkhOA8jFy7effR/pkLk5j0HS\n"
            + "9g3zYPFmid1gJHxF2/j7oZ9NOG7KBfKuB3Aml8R3Y9Xg/3RAbbh7jVwlYj8CJ1gC\n"
            + "TU7V9bCRX+DFjX8XcCP7hVM8yHadS63dsnKPPlUtUbVQouNKqWA+ubhEpQsrxEmv\n"
            + "jQIDAQAB\n"
            + "-----END PUBLIC KEY-----\n";

    private Server serverA;
    private Server serverB;

    /**
     * Starts every test without SDK files and with two new servers.
     */
    @Before
    public void beforeTest() throws IOException {
        TestUtils.createCleanTestState();
        serverA = new Server();
        serverB = new Server();
    }

    /**
     * Stops the SDK, deletes its data and stops both servers.
     */
    @After
    public void afterTest() {
        Countly.instance().halt();
        serverA.stop();
        serverB.stop();
    }

    /**
     * A request that failed at the old server is retried at the new one, given with trailing "/"
     * characters, which are removed, so the path is not doubled. Later requests go there too, with
     * the same app key, and changing back to the old URL brings the traffic back.
     */
    @Test
    public void changeServerUrl_queuedAndLaterRequestsGoToTheNewServer() throws Exception {
        serverA.responseCode = 503;
        Countly.instance().init(config(serverA, Config.Feature.Events).setEventQueueSizeToSend(1));
        Countly.instance().events().recordEvent("queued_while_down");
        serverA.await(withEvent("queued_while_down"));

        Countly.instance().changeServerUrl(serverB.url() + "///");

        Assert.assertEquals(serverB.url(), SDKCore.instance.config.getServerURL().toString());
        Received retried = serverB.await(withEvent("queued_while_down"));
        Assert.assertEquals("/i", retried.path);
        Assert.assertEquals(TestUtils.SERVER_APP_KEY, retried.params.get("app_key"));
        Assert.assertEquals(TestUtils.DEVICE_ID, retried.params.get("device_id"));

        Countly.instance().events().recordEvent("after_change");
        Received later = serverB.await(withEvent("after_change"));
        Assert.assertEquals("/i", later.path);
        Assert.assertEquals(TestUtils.SERVER_APP_KEY, later.params.get("app_key"));
        Assert.assertEquals(1, serverA.count(withEvent("queued_while_down")));
        Assert.assertEquals(0, serverA.count(withEvent("after_change")));

        serverA.responseCode = 200;
        Countly.instance().changeServerUrl(serverA.url());
        Countly.instance().events().recordEvent("after_revert");
        serverA.await(withEvent("after_revert"));
        Assert.assertEquals(0, serverB.count(withEvent("after_revert")));
        Assert.assertTrue("the request queue must drain", TestUtils.waitFor(10_000, () -> queuedRequestCount() == 0));
    }

    /**
     * An invalid URL is ignored with log code e102, and the SDK keeps sending to the current server.
     */
    @Test
    public void changeServerUrl_invalidUrlsAreIgnored() throws Exception {
        Countly.instance().init(config(serverA, Config.Feature.Events).setEventQueueSizeToSend(1));

        String[] invalid = { null, "", "not a url", "ftp://x", "http://", "http:///no_host", "http://exa mple.com" };
        for (String url : invalid) {
            Countly.instance().changeServerUrl(url);
        }

        Assert.assertEquals(serverA.url(), SDKCore.instance.config.getServerURL().toString());
        Assert.assertEquals(invalid.length, logCode("e102"));
        Countly.instance().events().recordEvent("still_on_a");
        Received received = serverA.await(withEvent("still_on_a"));
        Assert.assertEquals(TestUtils.SERVER_APP_KEY, received.params.get("app_key"));
        Assert.assertTrue(serverB.received.isEmpty());
    }

    /**
     * While a public key pin is set, only an https URL is accepted.
     */
    @Test
    public void changeServerUrl_withPins_acceptsHttpsOnly() {
        Countly.instance().init(config(serverA).addPublicKeyPin(PUBLIC_KEY_PEM));

        Countly.instance().changeServerUrl(serverB.url());
        Assert.assertEquals(serverA.url(), SDKCore.instance.config.getServerURL().toString());
        Assert.assertEquals(1, logCode("e102"));

        Countly.instance().changeServerUrl(TestUtils.SERVER_URL + "/");
        Assert.assertEquals(TestUtils.SERVER_URL, SDKCore.instance.config.getServerURL().toString());
        Assert.assertEquals(1, logCode("e102"));
    }

    /**
     * Right after a URL change the new server is asked for the SDK behavior settings and, as remote
     * config downloads automatically, for remote config, with the same app key. Immediate requests
     * made later, as the feedback widget list, go to the new server too.
     */
    @Test
    public void changeServerUrl_catchesUpTheNewServer() throws Exception {
        Config config = config(serverA, Config.Feature.RemoteConfig, Config.Feature.Feedback).enableRemoteConfigAutomaticTriggers();
        Countly.instance().init(config);
        serverA.await(withMethod("sc"));
        serverA.await(withMethod("rc"));

        Countly.instance().changeServerUrl(serverB.url());

        Received settings = serverB.await(withMethod("sc"));
        Received remoteConfig = serverB.await(withMethod("rc"));
        Assert.assertEquals("/o/sdk", settings.path);
        Assert.assertEquals("/o/sdk", remoteConfig.path);
        Assert.assertEquals(TestUtils.SERVER_APP_KEY, settings.params.get("app_key"));
        Assert.assertEquals(TestUtils.SERVER_APP_KEY, remoteConfig.params.get("app_key"));
        Assert.assertEquals(TestUtils.DEVICE_ID, remoteConfig.params.get("device_id"));

        Countly.instance().feedback().getAvailableFeedbackWidgets((widgets, error) -> {
        });
        serverB.await(withMethod("feedback"));
        Assert.assertEquals(0, serverA.count(withMethod("feedback")));
        Assert.assertEquals(1, serverA.count(withMethod("sc")));
        Assert.assertEquals(1, serverA.count(withMethod("rc")));
    }

    /**
     * Right after an app key change the server is asked for the SDK behavior settings and remote
     * config of the new app. The remote config values of the old app are cleared first, so they are
     * gone even when the new download fails.
     */
    @Test
    public void changeAppKey_catchesUpTheNewApp_andClearsTheRemoteConfigOfTheOldApp() throws Exception {
        Countly.instance().init(config(serverA, Config.Feature.RemoteConfig).enableRemoteConfigAutomaticTriggers());
        serverA.await(withMethod("rc"));
        Assert.assertTrue(TestUtils.waitFor(5_000, () -> Countly.instance().remoteConfig().getValues().containsKey("color")));

        serverA.failRemoteConfig = true;
        Countly.instance().changeAppKey(NEW_APP_KEY);

        Received settings = serverA.await(withMethod("sc").and(r -> NEW_APP_KEY.equals(r.params.get("app_key"))));
        Received remoteConfig = serverA.await(withMethod("rc").and(r -> NEW_APP_KEY.equals(r.params.get("app_key"))));
        Assert.assertEquals(TestUtils.DEVICE_ID, settings.params.get("device_id"));
        Assert.assertEquals(TestUtils.DEVICE_ID, remoteConfig.params.get("device_id"));
        Assert.assertTrue(Countly.instance().remoteConfig().getValues().isEmpty());
    }

    /**
     * The queued events are packed into a request and the request queue is sent right away: first
     * the event that waited in the event queue, then, once the server is back, the request that
     * failed, without waiting for the timer.
     */
    @Test
    public void attemptToSendStoredRequests_sendsTheEventQueueAndTheRequestQueueNow() throws Exception {
        serverA.responseCode = 503;
        Countly.instance().init(config(serverA, Config.Feature.Events).setEventQueueSizeToSend(10));
        Countly.instance().events().recordEvent("waiting_in_the_event_queue");
        Assert.assertEquals(1, TestUtils.getCurrentEQ().size());

        Countly.instance().attemptToSendStoredRequests();
        serverA.await(withEvent("waiting_in_the_event_queue"));
        Assert.assertEquals(0, TestUtils.getCurrentEQ().size());
        Assert.assertEquals(1, queuedRequestCount());

        serverA.responseCode = 200;
        Countly.instance().attemptToSendStoredRequests();
        Assert.assertTrue(TestUtils.waitFor(5_000, () -> serverA.count(withEvent("waiting_in_the_event_queue")) == 2));
        Assert.assertTrue(TestUtils.waitFor(5_000, () -> queuedRequestCount() == 0));
    }

    /**
     * The queue calls never lose a request that is not sent: the request being sent while the queue
     * is flushed finishes, the flushed ones never arrive, and the one queued behind it before it
     * finished is sent next, not removed in its place.
     */
    @Test
    public void flushQueues_whileARequestIsBeingSent() throws Exception {
        serverA.gate = new CountDownLatch(1);
        Countly.instance().init(config(serverA, Config.Feature.Events).setEventQueueSizeToSend(1));
        Countly.instance().events().recordEvent("being_sent");
        serverA.await(withEvent("being_sent"));
        Countly.instance().events().recordEvent("flushed_1");
        Countly.instance().events().recordEvent("flushed_2");
        Assert.assertTrue(TestUtils.waitFor(5_000, () -> queuedRequestCount() == 3));

        Countly.instance().flushQueues();
        Assert.assertEquals(0, queuedRequestCount());
        Countly.instance().events().recordEvent("after_flush");
        Assert.assertEquals(1, queuedRequestCount());
        serverA.gate.countDown();

        serverA.await(withEvent("after_flush"));
        Assert.assertTrue(TestUtils.waitFor(5_000, () -> queuedRequestCount() == 0));
        Assert.assertEquals(0, serverA.count(withEvent("flushed_1")));
        Assert.assertEquals(0, serverA.count(withEvent("flushed_2")));
        Assert.assertEquals(1, serverA.count(withEvent("being_sent")));
    }

    // region helpers

    /**
     * A configuration that sends to the given server without request cooldowns or health check.
     */
    private Config config(Server server, Config.Feature... features) {
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
     * How often the running health check counter recorded a log code.
     */
    private static int logCode(String code) {
        return SDKCore.instance.healthCheckCounter.snapshot().getJSONObject("lc").optInt(code, 0);
    }

    /**
     * How many requests are stored, once the pending storage tasks ran.
     */
    private static int queuedRequestCount() {
        Storage.await(null);
        File[] files = TestUtils.getTestSDirectory().listFiles((dir, name) -> name.startsWith("[CLY]_request_"));
        return files == null ? 0 : files.length;
    }

    /**
     * Matches a request that carries an event with the given key.
     */
    private static Predicate<Received> withEvent(String key) {
        return r -> r.params.containsKey("events") && r.params.get("events").contains("\"" + key + "\"");
    }

    /**
     * Matches an immediate request of the given method.
     */
    private static Predicate<Received> withMethod(String method) {
        return r -> method.equals(r.params.get("method"));
    }

    /**
     * One request as a server received it, with its parameters decoded.
     */
    private static final class Received {
        final String path;
        final Map<String, String> params;

        /**
         * A request with its path and decoded parameters.
         */
        Received(String path, Map<String, String> params) {
            this.path = path;
            this.params = params;
        }
    }

    /**
     * A Countly server stand-in on loopback that records every request. It answers remote config
     * with one value, the SDK behavior settings with none, and anything else with success, unless
     * told to fail; it can hold every event request until a gate opens.
     */
    private static final class Server {
        final HttpServer http;
        final List<Received> received = new CopyOnWriteArrayList<>();
        volatile int responseCode = 200;
        volatile boolean failRemoteConfig = false;
        volatile CountDownLatch gate = null;

        /**
         * Starts the server on a free loopback port.
         */
        Server() throws IOException {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.createContext("/", exchange -> {
                String query = exchange.getRequestURI().getRawQuery();
                String body = new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8);
                Map<String, String> params = TestUtils.parseQueryParams(Utils.isNotEmpty(query) ? query : body);
                params.replaceAll((key, value) -> Utils.urldecode(value));
                received.add(new Received(exchange.getRequestURI().getPath(), params));

                CountDownLatch waitFor = gate;
                if (waitFor != null && params.containsKey("events")) {
                    try {
                        waitFor.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                int code = responseCode;
                String response = "{\"result\":\"Success\"}";
                if ("rc".equals(params.get("method"))) {
                    code = failRemoteConfig ? 500 : code;
                    response = "{\"color\":\"red\"}";
                } else if ("sc".equals(params.get("method"))) {
                    response = "{}";
                } else if ("feedback".equals(params.get("method"))) {
                    response = "{\"result\":[]}";
                }
                byte[] out = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(code, out.length);
                try (OutputStream stream = exchange.getResponseBody()) {
                    stream.write(out);
                }
            });
            http.setExecutor(Executors.newSingleThreadExecutor());
            http.start();
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
         * Waits for the first matching request and returns it.
         */
        Received await(Predicate<Received> filter) throws InterruptedException {
            Assert.assertTrue("no matching request arrived at " + url() + ", it received " + received.size(), TestUtils.waitFor(15_000, () -> count(filter) > 0));
            return received.stream().filter(filter).findFirst().orElse(null);
        }

        /**
         * Opens the gate and stops the server.
         */
        void stop() {
            CountDownLatch waitFor = gate;
            if (waitFor != null) {
                waitFor.countDown();
            }
            http.stop(0);
        }

        /**
         * Reads a request body.
         */
        private static byte[] readAll(InputStream stream) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    // endregion
}
