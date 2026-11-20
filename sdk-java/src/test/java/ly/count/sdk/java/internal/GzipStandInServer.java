package ly.count.sdk.java.internal;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.json.JSONObject;
import org.junit.Assert;

/**
 * A Countly server stand-in on loopback for the request compression tests, which records every
 * request. It answers the SDK behavior settings with the answer set by {@link #answer(int, boolean)},
 * remote config with {@link #remoteConfigCode} and {@link #remoteConfigAnswer}, and anything else
 * with success; it can refuse or drop compressed requests and hold its settings answer until a
 * latch opens.
 */
final class GzipStandInServer {

    final HttpServer http;
    final List<GzipStandInRequest> received = new CopyOnWriteArrayList<>();
    final AtomicReference<String> settingsAnswer = new AtomicReference<>("{}");
    final AtomicReference<String> remoteConfigAnswer = new AtomicReference<>("{}");
    final AtomicInteger remoteConfigCode = new AtomicInteger(200);
    final AtomicInteger refuseCompressed = new AtomicInteger(0);
    final AtomicBoolean dropCompressed = new AtomicBoolean(false);
    final AtomicReference<CountDownLatch> holdSettings = new AtomicReference<>(null);

    /**
     * Starts the server on a free loopback port.
     *
     * @throws IOException when the server cannot start
     */
    GzipStandInServer() throws IOException {
        http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        http.createContext("/", this::handle);
        http.setExecutor(Executors.newCachedThreadPool());
        http.start();
    }

    /**
     * Sets the settings answer: an {@code lkl} value that tells it apart, with "gzip": true when
     * the server reads compressed bodies.
     *
     * @param lkl the key length limit of the answer
     * @param gzip whether the answer says that the server reads compressed bodies
     */
    void answer(int lkl, boolean gzip) {
        JSONObject answer = new JSONObject().put("v", 1).put("t", 1_700_000_000_000L).put("c", new JSONObject().put("lkl", lkl));
        if (gzip) {
            answer.put("gzip", true);
        }
        settingsAnswer.set(answer.toString());
    }

    /**
     * Records a request and answers it.
     *
     * @param exchange the exchange the request arrived in
     * @throws IOException when the request cannot be read or answered
     */
    private void handle(HttpExchange exchange) throws IOException {
        GzipStandInRequest request = new GzipStandInRequest(exchange);
        received.add(request);
        String method = request.params.get("method");
        int code = 200;
        String response = "{\"result\":\"Success\"}";
        if ("sc".equals(method)) {
            CountDownLatch hold = holdSettings.get();
            if (hold != null) {
                try {
                    hold.await(15, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            response = settingsAnswer.get();
        } else if ("rc".equals(method)) {
            code = remoteConfigCode.get();
            response = remoteConfigAnswer.get();
        }
        if (request.compressed && dropCompressed.get()) {
            exchange.close();
            return;
        }
        if (request.compressed && refuseCompressed.getAndUpdate(left -> left > 0 ? left - 1 : 0) > 0) {
            code = 400;
            response = "{\"result\":\"Invalid request body\"}";
        }
        byte[] out = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, out.length == 0 ? -1 : out.length);
        try (OutputStream stream = exchange.getResponseBody()) {
            stream.write(out);
        }
        request.answered.set(true);
    }

    /**
     * The URL the SDK reaches this server at.
     *
     * @return the server URL
     */
    String url() {
        return "http://127.0.0.1:" + http.getAddress().getPort();
    }

    /**
     * How many received requests match.
     *
     * @param filter the requests to count
     * @return the number of matching requests
     */
    int count(Predicate<GzipStandInRequest> filter) {
        return (int) received.stream().filter(filter).count();
    }

    /**
     * Every received request that matches, in arrival order.
     *
     * @param filter the requests to return
     * @return the matching requests
     */
    List<GzipStandInRequest> all(Predicate<GzipStandInRequest> filter) {
        return received.stream().filter(filter).collect(Collectors.toList());
    }

    /**
     * Waits for the first matching request and returns it.
     *
     * @param filter the request to wait for
     * @return the first matching request
     * @throws InterruptedException when the wait is interrupted
     */
    GzipStandInRequest await(Predicate<GzipStandInRequest> filter) throws InterruptedException {
        Assert.assertTrue("no matching request arrived at " + url() + ", it received " + received.size(), TestUtils.waitFor(15_000, () -> count(filter) > 0));
        return received.stream().filter(filter).findFirst().orElse(null);
    }

    /**
     * Opens a held settings answer and stops the server.
     */
    void stop() {
        CountDownLatch hold = holdSettings.get();
        if (hold != null) {
            hold.countDown();
        }
        http.stop(0);
    }
}
