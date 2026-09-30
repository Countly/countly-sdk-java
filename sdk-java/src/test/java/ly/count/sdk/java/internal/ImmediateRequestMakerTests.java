package ly.count.sdk.java.internal;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
 * {@link ImmediateRequestMaker}, the "answer me now" path that feedback widgets, remote config and
 * content all use instead of the request queue.
 * <p>
 * Driven against a real loopback {@link HttpServer} so the response handling is exercised for real:
 * the other module tests replace this class with a lambda, which is why it had no coverage. The
 * distinction that matters is how each response shape is handed back, because a widget that gets a
 * null where it expected an object simply never appears.
 */
@RunWith(JUnit4.class)
public class ImmediateRequestMakerTests {

    private HttpServer server;
    private int port;
    private volatile int responseCode = 200;
    private volatile String responseBody = "{\"result\":\"ok\"}";
    private final AtomicReference<String> lastQuery = new AtomicReference<>();

    @Before
    public void beforeTest() throws IOException {
        TestUtils.createCleanTestState();
        responseCode = 200;
        responseBody = "{\"result\":\"ok\"}";
        lastQuery.set(null);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", exchange -> {
            lastQuery.set(exchange.getRequestURI().getRawQuery());
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseCode, out.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(out);
            }
        });
        server.start();
    }

    @After
    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        Countly.instance().halt();
    }

    // region scenarios

    /**
     * The three response shapes a Countly endpoint can answer with, and what each must become. A
     * bare array has to be wrapped under "jsonArray", because that is the contract the content and
     * feedback parsers are written against.
     */
    @Test
    public void responseShapes_areHandedBackInTheFormTheCallersExpect() throws Exception {
        Transport transport = transport();

        responseBody = "{\"result\":\"an object\"}";
        JSONObject object = request(transport, "method=object");
        Assert.assertNotNull(object);
        Assert.assertEquals("an object", object.getString("result"));
        Assert.assertEquals("method=object", lastQuery.get());

        // A bare array is wrapped, so callers always receive an object.
        responseBody = "[{\"one\":1},{\"two\":2}]";
        JSONObject wrapped = request(transport, "method=array");
        Assert.assertNotNull(wrapped);
        Assert.assertTrue(wrapped.has("jsonArray"));
        Assert.assertEquals(2, wrapped.getJSONArray("jsonArray").length());
        Assert.assertEquals(1, wrapped.getJSONArray("jsonArray").getJSONObject(0).getInt("one"));

        // Leading whitespace must not stop the array from being recognised.
        responseBody = "   [{\"three\":3}]";
        JSONObject padded = request(transport, "method=paddedArray");
        Assert.assertNotNull(padded);
        Assert.assertTrue(padded.has("jsonArray"));
    }

    /**
     * Every way a request can fail must hand back null rather than a half built object, so a caller
     * can tell "no answer" from "an empty answer".
     */
    @Test
    public void failureModes_allHandBackNull() throws Exception {
        Transport transport = transport();

        // A server error is not an answer.
        responseCode = 500;
        responseBody = "{\"result\":\"nope\"}";
        Assert.assertNull(request(transport, "method=serverError"));

        // Neither is a body that is not JSON at all.
        responseCode = 200;
        responseBody = "<html>not json</html>";
        Assert.assertNull(request(transport, "method=notJson"));

        // An empty body cannot be parsed either.
        responseBody = "";
        Assert.assertNull(request(transport, "method=empty"));
    }

    /**
     * With networking switched off the request must be abandoned before a socket is opened, which is
     * what stops the SDK from talking to a server a customer has told it not to use.
     */
    @Test
    public void networkingDisabled_abandonsTheRequestWithoutContactingTheServer() throws Exception {
        Transport transport = transport();

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<JSONObject> result = new AtomicReference<>();
        AtomicBoolean called = new AtomicBoolean(false);

        new ImmediateRequestMaker().doWork("method=disabled", "/o/sdk?", transport, false, false, response -> {
            called.set(true);
            result.set(response);
            done.countDown();
        }, logger());

        Assert.assertTrue("the callback must still be invoked", done.await(10, TimeUnit.SECONDS));
        Assert.assertTrue(called.get());
        Assert.assertNull("a cancelled request has no result", result.get());
        Assert.assertNull("nothing may reach the server", lastQuery.get());
    }

    /**
     * The delayed variant, used right after a device id change so the server has a chance to catch
     * up. It must still deliver, just later.
     */
    @Test
    public void delayedRequest_stillReachesTheServerAndAnswers() throws Exception {
        Transport transport = transport();
        responseBody = "{\"result\":\"delayed\"}";

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<JSONObject> result = new AtomicReference<>();

        new ImmediateRequestMaker().doWork("method=delayed", "/o/sdk?", transport, true, true, response -> {
            result.set(response);
            done.countDown();
        }, logger());

        Assert.assertTrue("a delayed request must still complete", done.await(15, TimeUnit.SECONDS));
        Assert.assertNotNull(result.get());
        Assert.assertEquals("delayed", result.get().getString("result"));
        Assert.assertEquals("method=delayed", lastQuery.get());
    }

    /**
     * A transport pointed at a port nothing is listening on must fail cleanly rather than propagating
     * the connection error out of the SDK.
     */
    @Test
    public void anUnreachableServer_failsCleanly() throws Exception {
        server.stop(0);
        server = null;

        Transport transport = transport();
        Assert.assertNull(request(transport, "method=unreachable"));
    }

    // endregion
    // region helpers

    private Log logger() {
        return new Log(Config.LoggingLevel.OFF, null);
    }

    private Transport transport() {
        Config config = new Config("http://127.0.0.1:" + port, TestUtils.SERVER_APP_KEY, TestUtils.getTestSDirectory())
            .setApplicationVersion(TestUtils.APPLICATION_VERSION)
            .setLoggingLevel(Config.LoggingLevel.OFF);
        Transport transport = new Transport();
        transport.init(TestUtils.getInternalConfigWithLogger(config));
        return transport;
    }

    /**
     * Runs one immediate request and waits for its callback.
     *
     * @param transport the transport to send through
     * @param requestData the url-encoded request parameters
     * @return whatever the maker handed the callback, possibly null
     */
    private JSONObject request(Transport transport, String requestData) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<JSONObject> result = new AtomicReference<>();

        new ImmediateRequestMaker().doWork(requestData, "/o/sdk?", transport, false, true, response -> {
            result.set(response);
            done.countDown();
        }, logger());

        Assert.assertTrue("the immediate request never completed", done.await(15, TimeUnit.SECONDS));
        return result.get();
    }

    // endregion
}
