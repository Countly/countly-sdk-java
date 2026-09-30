package ly.count.sdk.java.internal;

import com.sun.net.httpserver.HttpExchange;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static ly.count.sdk.java.internal.ServerConfigBuilder.names;

/**
 * The network resilience settings of the SDK behavior settings, observed through the public API: the
 * backoff mechanism, {@code bom}, {@code bom_at}, {@code bom_rqp}, {@code bom_ra} and {@code bom_d},
 * and the request drop age, {@code dort}.
 * <p>
 * Settings requests go through a {@link ModuleConfigurationTests.ServerConfigResponder}, which answers
 * on the calling thread. Queued requests go through the real networking stack to a local HTTP server
 * that can hold each answer back and records when every request arrived and when its answer started,
 * so the gap a backoff leaves between two requests can be measured. Backoff settings are set to
 * seconds, never left at their defaults, and no wait relies on the 60 second SDK timer.
 */
@RunWith(JUnit4.class)
public class ModuleConfigurationNetworkTests {

    private static final long SLOW_ANSWER_MS = 1_200;
    //longer than the wait budget, so a backoff that should not happen fails the wait instead of slowing it
    private static final int BACKOFF_LONGER_THAN_THE_BUDGET_SECONDS = 60;
    private static final long WAIT_BUDGET_MS = 30_000;
    private static final long POLL_MS = 50;
    private static final long TIMER_SLACK_MS = 50;
    private static final long LATE_RESUME_MS = 5_000;
    private static final long MINUTE_MS = 60L * 1000L;
    private static final long HOUR_MS = 60L * MINUTE_MS;

    private static final String BACKOFF_STARTED = "holding the request queue back for [";
    private static final String BACKOFF_ENDED = "the backoff is over";
    private static final String NOT_BACKING_OFF = "not backing off";
    private static final String DROPPED_UNSENT = "dropping it without sending";
    private static final String CONTENT_ENDPOINT = "/o/sdk/content?";
    private static final String NO_CONTENT_RESPONSE = "{\"jsonArray\":[{\"result\":\"No content block found!\"}]}";

    private SettingsAndContentServer server;
    private QueueServer queueServer;
    private final List<String> logs = new CopyOnWriteArrayList<>();

    /**
     * Starts every test from empty storage, without a test module and with fresh fakes.
     */
    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
        SDKCore.testDummyModule = null;
        server = new SettingsAndContentServer();
        queueServer = null;
        logs.clear();
    }

    /**
     * Stops the SDK, clearing its data, and the local HTTP server when one was started.
     */
    @After
    public void afterTest() {
        Countly.instance().halt();
        if (queueServer != null) {
            queueServer.stop();
        }
    }

    // region scenarios

    /**
     * A request the server answers slowly while few requests are queued holds the queue back for
     * {@code bom_d}, then the queue drains on its own.
     * <p>
     * Verifies against a local server that after an answer slower than {@code bom_at}, with two
     * requests left, the next request arrives only once {@code bom_d} has passed since the server
     * started answering; that meanwhile a newly recorded request and nudges of the send loop send
     * nothing and the queue stays on disk as it was; that the backoff ends without any nudge; and
     * that the fast answers after it hold nothing back.
     */
    @Test
    public void backoff_slowAnswerWithFewRequestsQueued_holdsTheQueueBackForTheDuration_thenItDrainsOnItsOwn() throws Exception {
        startQueueServer();
        queueServer.holdTheNextAnswers(SLOW_ANSWER_MS);
        server.settings.respondWith(new ServerConfigBuilder().backoffAcceptedTimeout(1).backoffDuration(3));
        init(networkedConfig());

        recordEvents("first", "second", "third");
        awaitLogs(BACKOFF_STARTED, 1);
        Assert.assertEquals(1, logsContaining("and [2] requests remain queued, " + BACKOFF_STARTED + "3] seconds"));
        Assert.assertEquals(1, queueServer.received.size());

        Countly.instance().events().recordEvent("during_backoff");
        TestUtils.letTheClockCatchUp();
        Map<String, String>[] held = TestUtils.getCurrentRQ();
        Assert.assertEquals(3, held.length);
        Assert.assertEquals("[\"second\"]", eventKeys(held[0]).toString());
        Assert.assertEquals("[\"third\"]", eventKeys(held[1]).toString());
        Assert.assertEquals("[\"during_backoff\"]", eventKeys(held[2]).toString());

        nudgeUntil(queueServer.received.get(0).answeredAtNs + 2_000L * 1_000_000L);
        Assert.assertEquals(1, queueServer.received.size());
        Assert.assertEquals(3, queuedRequestCount());
        Assert.assertEquals(0, logsContaining(BACKOFF_ENDED));

        Assert.assertTrue("the queue never drained after the backoff, logs: " + logs, waitFor(() -> queueServer.received.size() == 4 && queuedRequestCount() == 0));
        long gapMs = gapBeforeRequestMs(1);
        Assert.assertTrue("the next request came [" + gapMs + "] ms after the slow answer", gapMs >= 3_000 - TIMER_SLACK_MS);
        Assert.assertTrue("the queue resumed [" + gapMs + "] ms after the slow answer", gapMs < 3_000 + LATE_RESUME_MS);
        Assert.assertEquals(Arrays.asList("first", "second", "third", "during_backoff"), receivedEventKeys());
        Assert.assertEquals(1, logsContaining(BACKOFF_STARTED));
        Assert.assertEquals(1, logsContaining(BACKOFF_ENDED));
        Assert.assertEquals(0, logsContaining(NOT_BACKING_OFF));
    }

    /**
     * The backoff needs a slow answer and the requests still queued within {@code bom_rqp} of the
     * request queue size.
     * <p>
     * Verifies against a local server, with {@code rqs} 4 and {@code bom_rqp} 0.25, so at most one
     * request left, that a slow answer with two requests left holds nothing back, that the next slow
     * answer, with one request left, holds the queue back for {@code bom_d}, and that the fast answer
     * to the last request holds nothing back.
     */
    @Test
    public void backoff_needsTheQueueWithinItsShareOfTheQueueSize_andASlowAnswer() throws Exception {
        startQueueServer();
        queueServer.holdTheNextAnswers(SLOW_ANSWER_MS, SLOW_ANSWER_MS);
        server.settings.respondWith(new ServerConfigBuilder().requestQueueSize(4).backoffRequestQueuePercentage(0.25)
            .backoffAcceptedTimeout(1).backoffDuration(2));
        init(networkedConfig());

        recordEvents("a", "b", "c");
        Assert.assertTrue("the queue never drained, logs: " + logs, waitFor(() -> queueServer.received.size() == 3 && queuedRequestCount() == 0));

        Assert.assertEquals(Arrays.asList("a", "b", "c"), receivedEventKeys());
        Assert.assertEquals(1, logsContaining("but [2] requests remain queued, more than [1.0], " + NOT_BACKING_OFF));
        Assert.assertEquals(1, logsContaining("and [1] requests remain queued, " + BACKOFF_STARTED + "2] seconds"));
        Assert.assertEquals(1, logsContaining(BACKOFF_STARTED));
        Assert.assertEquals(1, logsContaining(NOT_BACKING_OFF));
        long gapAfterFirstMs = gapBeforeRequestMs(1);
        long gapAfterSecondMs = gapBeforeRequestMs(2);
        Assert.assertTrue("the second request waited [" + gapAfterFirstMs + "] ms", gapAfterFirstMs < 2_000);
        Assert.assertTrue("the third request waited only [" + gapAfterSecondMs + "] ms", gapAfterSecondMs >= 2_000 - TIMER_SLACK_MS);
    }

    /**
     * The backoff never holds the queue back after a request older than {@code bom_ra}, nor while the
     * server turns it off with {@code bom}.
     * <p>
     * Verifies against a local server, with a {@code bom_d} longer than the wait budget so that a
     * backoff fails the wait, that two stored requests recorded two hours ago are both sent although
     * each is answered slowly, each slow answer logged as coming too late to back off; and, after a
     * restart with {@code bom} false from the server, that two new requests answered slowly are sent
     * one after the other without any backoff.
     */
    @Test
    public void backoff_neverAfterARequestOlderThanTheRequestAge_norWhileTheServerTurnsItOff() throws Exception {
        startQueueServer();
        long twoHoursAgo = System.currentTimeMillis() - 2 * HOUR_MS;
        storeRequest(twoHoursAgo, "old_1", twoHoursAgo);
        storeRequest(twoHoursAgo + 1, "old_2", twoHoursAgo + 1);
        queueServer.holdTheNextAnswers(SLOW_ANSWER_MS, SLOW_ANSWER_MS);
        server.settings.respondWith(new ServerConfigBuilder().backoffAcceptedTimeout(1).backoffRequestAge(1)
            .backoffDuration(BACKOFF_LONGER_THAN_THE_BUDGET_SECONDS));
        init(networkedConfig());

        Assert.assertTrue("the old requests never drained, logs: " + logs, waitFor(() -> queueServer.received.size() == 2 && queuedRequestCount() == 0));
        Assert.assertEquals(Arrays.asList("old_1", "old_2"), receivedParams("marker"));
        Assert.assertEquals(2, logsContaining("but the request is older than [1] hours, " + NOT_BACKING_OFF));
        Assert.assertEquals(0, logsContaining(BACKOFF_STARTED));
        Countly.instance().halt();

        queueServer.received.clear();
        logs.clear();
        queueServer.holdTheNextAnswers(SLOW_ANSWER_MS, SLOW_ANSWER_MS);
        server.settings.respondWith(new ServerConfigBuilder().backoffMechanism(false).backoffAcceptedTimeout(1)
            .backoffDuration(BACKOFF_LONGER_THAN_THE_BUDGET_SECONDS));
        init(networkedConfig());
        Assert.assertFalse(provider().getBOMEnabled());

        recordEvents("x", "y");
        Assert.assertTrue("the new requests never drained, logs: " + logs, waitFor(() -> queueServer.received.size() == 2 && queuedRequestCount() == 0));
        Assert.assertEquals(Arrays.asList("x", "y"), receivedEventKeys());
        Assert.assertEquals(0, logsContaining(BACKOFF_STARTED));
        Assert.assertEquals(0, logsContaining(NOT_BACKING_OFF));
    }

    /**
     * {@link Config#disableBackoffMechanism()} keeps the backoff off while the settings server stays
     * silent, and a {@code bom} true from the server turns it back on.
     * <p>
     * Verifies against a local server, with the settings server never answering and the provided
     * settings setting {@code bom_at} and a {@code bom_d} longer than the wait budget, that slow
     * answers hold nothing back; then that once a response sets {@code bom} true with a short
     * {@code bom_d}, the next slow answer holds the queue back for that long.
     */
    @Test
    public void backoff_offByTheDeveloper_staysOffWithASilentServer_andTheServerTurnsItBackOn() throws Exception {
        startQueueServer();
        queueServer.holdTheNextAnswers(SLOW_ANSWER_MS, SLOW_ANSWER_MS, SLOW_ANSWER_MS);
        server.settings.pending();
        init(networkedConfig().disableBackoffMechanism().setSdkBehaviorSettings(new ServerConfigBuilder()
            .backoffAcceptedTimeout(1).backoffDuration(BACKOFF_LONGER_THAN_THE_BUDGET_SECONDS).build()));
        Assert.assertFalse(provider().getBOMEnabled());
        Assert.assertEquals(1, provider().getBOMAcceptedTimeoutSeconds());

        recordEvents("a", "b");
        Assert.assertTrue("the queue never drained, logs: " + logs, waitFor(() -> queueServer.received.size() == 2 && queuedRequestCount() == 0));
        Assert.assertEquals(0, logsContaining(BACKOFF_STARTED));

        push(new ServerConfigBuilder().backoffMechanism(true).backoffDuration(2));
        Assert.assertTrue(provider().getBOMEnabled());
        Assert.assertEquals(2, provider().getBOMDuration());

        recordEvents("c", "d");
        Assert.assertTrue("the queue never drained after the backoff, logs: " + logs, waitFor(() -> queueServer.received.size() == 4 && queuedRequestCount() == 0));
        Assert.assertEquals(Arrays.asList("a", "b", "c", "d"), receivedEventKeys());
        Assert.assertEquals(1, logsContaining("and [1] requests remain queued, " + BACKOFF_STARTED + "2] seconds"));
        long gapMs = gapBeforeRequestMs(3);
        Assert.assertTrue("the last request waited only [" + gapMs + "] ms", gapMs >= 2_000 - TIMER_SLACK_MS);
    }

    /**
     * Backend mode never holds its request queue back and never drops a request for its age, whatever
     * the resolved values say.
     * <p>
     * Verifies against a local server, with the settings inert in backend mode and the resolved
     * backoff and drop age values forced to ones that would back off and drop, that three requests,
     * the first two answered slowly and the first recorded three hours ago, are all sent in order with
     * their own timestamps, that nothing backs off or is dropped, and that no settings request is made.
     */
    @Test
    public void backendMode_neverHoldsTheQueueBack_norDropsARequestForItsAge() throws Exception {
        startQueueServer();
        queueServer.holdTheNextAnswers(SLOW_ANSWER_MS, SLOW_ANSWER_MS);
        init(networkedConfig().enableBackendMode());
        ModuleConfiguration module = SDKCore.instance.module(ModuleConfiguration.class);
        module.currentVBOMAcceptedTimeoutSeconds = 1;
        module.currentVBOMDuration = BACKOFF_LONGER_THAN_THE_BUDGET_SECONDS;
        module.currentVDropAgeHours = 1;
        Assert.assertTrue(provider().getBOMEnabled());

        long threeHoursAgo = System.currentTimeMillis() - 3 * HOUR_MS;
        Countly.instance().backendM().sessionBegin("device_old", null, null, threeHoursAgo);
        Countly.instance().backendM().sessionBegin("device_fresh", null, null, null);
        Countly.instance().backendM().sessionBegin("device_last", null, null, null);

        Assert.assertTrue("the memory queue never drained, logs: " + logs, waitFor(() -> queueServer.received.size() == 3 && memoryQueueSize() == 0));
        Assert.assertEquals(Arrays.asList("device_old", "device_fresh", "device_last"), receivedParams("device_id"));
        Assert.assertEquals(String.valueOf(threeHoursAgo), queueServer.received.get(0).params.get("timestamp"));
        Assert.assertEquals(0, logsContaining(BACKOFF_STARTED));
        Assert.assertEquals(0, logsContaining(NOT_BACKING_OFF));
        Assert.assertEquals(0, logsContaining(DROPPED_UNSENT));
        Assert.assertTrue(server.settings.requests.isEmpty());
    }

    /**
     * Stopping the SDK while the queue is held back ends the backoff for good.
     * <p>
     * Verifies against a local server that once the SDK stopped during a backoff, nothing is sent
     * after the backoff would have ended, the held request stays on disk, the backoff thread is gone
     * and the backoff never reports its end, and that the next init sends the held request.
     */
    @Test
    public void backoff_stopWhileHeldBack_sendsNothingAfterwards_leavesNoThread_andTheNextInitSendsTheHeldRequest() throws Exception {
        startQueueServer();
        queueServer.holdTheNextAnswers(SLOW_ANSWER_MS);
        server.settings.respondWith(new ServerConfigBuilder().backoffAcceptedTimeout(1).backoffDuration(2));
        Set<Thread> threadsBefore = backoffThreads();
        init(networkedConfig());

        recordEvents("first", "held");
        awaitLogs(BACKOFF_STARTED, 1);
        Set<Thread> threads = backoffThreads();
        threads.removeAll(threadsBefore);
        Assert.assertEquals(1, threads.size());
        Assert.assertEquals(1, queuedRequestCount());

        Countly.instance().stop();
        Assert.assertTrue("the backoff thread outlived stop()", waitFor(() -> noneAlive(threads)));
        sleepUntil(queueServer.received.get(0).answeredAtNs + 3_500L * 1_000_000L);
        Assert.assertEquals(1, queueServer.received.size());
        Assert.assertEquals(1, queuedRequestCount());
        Assert.assertEquals(0, logsContaining(BACKOFF_ENDED));

        init(networkedConfig());
        Assert.assertTrue("the held request was never sent, logs: " + logs, waitFor(() -> queueServer.received.size() == 2 && queuedRequestCount() == 0));
        Assert.assertEquals(Arrays.asList("first", "held"), receivedEventKeys());
        Assert.assertEquals(1, logsContaining(BACKOFF_STARTED));
    }

    /**
     * {@code dort} drops the stored requests older than it without sending them and sends the others,
     * and {@code dort} 0 drops nothing.
     * <p>
     * Verifies against a local server that the age of a request comes from its {@code timestamp}
     * parameter, so an old storage ID with a fresh timestamp is sent and a fresh storage ID with an
     * old timestamp is dropped, and from its storage ID when it has no timestamp; that each drop is
     * logged with the request it dropped; and that with {@code dort} 0 the same old requests are all
     * sent in order.
     */
    @Test
    public void requestDropAge_dropsTheStoredRequestsOlderThanIt_unsent_andZeroDropsNothing() throws Exception {
        startQueueServer();
        long now = System.currentTimeMillis();
        long threeHoursAgo = now - 3 * HOUR_MS;
        storeRequest(threeHoursAgo, "old_timestamp", threeHoursAgo);
        storeRequest(threeHoursAgo + 1, "fresh_timestamp_old_id", now);
        storeRequest(threeHoursAgo + 2, "no_timestamp_old_id", null);
        storeRequest(now - 2 * MINUTE_MS, "old_timestamp_fresh_id", now - 2 * HOUR_MS);
        storeRequest(now - MINUTE_MS, "within_the_drop_age", now - 30 * MINUTE_MS);
        server.settings.failing();
        init(networkedConfig().setSdkBehaviorSettings(new ServerConfigBuilder().dropOldRequestTime(1).build()));
        Assert.assertEquals(1, provider().getRequestDropAgeHours());

        Assert.assertTrue("the queue never drained, logs: " + logs, waitNudging(() -> queuedRequestCount() == 0 && queueServer.received.size() == 2));
        Assert.assertEquals(Arrays.asList("fresh_timestamp_old_id", "within_the_drop_age"), receivedParams("marker"));
        Assert.assertEquals(3, logsContaining(DROPPED_UNSENT));
        for (long droppedId : new long[] { threeHoursAgo, threeHoursAgo + 2, now - 2 * MINUTE_MS }) {
            Assert.assertEquals(1, logsContaining("request [" + droppedId + "] is older than the request drop age of [1] hours"));
        }

        TestUtils.createCleanTestState();
        queueServer.received.clear();
        logs.clear();
        storeRequest(threeHoursAgo, "old_timestamp", threeHoursAgo);
        storeRequest(threeHoursAgo + 2, "no_timestamp_old_id", null);
        storeRequest(now - 2 * MINUTE_MS, "old_timestamp_fresh_id", now - 2 * HOUR_MS);
        init(networkedConfig().setSdkBehaviorSettings(new ServerConfigBuilder().dropOldRequestTime(0).build()));
        Assert.assertEquals(0, provider().getRequestDropAgeHours());

        Assert.assertTrue("the queue never drained, logs: " + logs, waitNudging(() -> queuedRequestCount() == 0 && queueServer.received.size() == 3));
        Assert.assertEquals(Arrays.asList("old_timestamp", "no_timestamp_old_id", "old_timestamp_fresh_id"), receivedParams("marker"));
        Assert.assertEquals(0, logsContaining(DROPPED_UNSENT));
    }

    /**
     * A journey trigger whose request is dropped for its age is settled, while a later trigger still
     * refreshes the content zone.
     * <p>
     * Verifies against a local server that a trigger request recorded while networking is off and
     * aged past {@code dort} on disk is dropped unsent once networking is back on, that its owner is
     * told with {@link Transport#NO_RESPONSE_CODE}, which leaves no pending trigger and the zone
     * inactive, and that a fresh trigger is then sent, accepted and enters the zone.
     */
    @Test
    public void requestDropAge_settlesTheJourneyTriggerOfADroppedRequest_andALaterTriggerStillRefreshes() throws Exception {
        startQueueServer();
        server.settings.failing();
        init(networkedConfig().enableFeatures(Config.Feature.Content).setEventQueueSizeToSend(100)
            .setSdkBehaviorSettings(new ServerConfigBuilder().networking(false).journeyTriggerEvents(names("purchase")).dropOldRequestTime(1).build()));
        holdTheZoneTimer();
        Countly.instance().content().setContentDisplay(new FakeDisplay());
        assertZoneIsInactive(0);

        Countly.instance().events().recordEvent("purchase");
        TestUtils.letTheClockCatchUp();
        Assert.assertEquals(1, TestUtils.getCurrentRQ().length);
        Set<Long> pending = SDKCore.instance.module(ModuleEvents.class).journeyTriggerRequestIds;
        Assert.assertEquals(storedRequestIds(), pending);
        Assert.assertEquals(1, pending.size());
        ageStoredRequests(2 * HOUR_MS);

        push(new ServerConfigBuilder().networking(true));
        Assert.assertTrue("the aged request was never dropped, logs: " + logs, waitNudging(() -> queuedRequestCount() == 0));
        Assert.assertEquals(1, logsContaining(DROPPED_UNSENT));
        Assert.assertEquals(1, logsContaining("carrying a journey trigger failed with code [" + Transport.NO_RESPONSE_CODE + "], the content zone is not refreshed"));
        Assert.assertTrue(pending.isEmpty());
        Assert.assertTrue(queueServer.received.isEmpty());
        assertZoneIsInactive(0);

        Countly.instance().events().recordEvent("purchase");
        awaitZoneFetch(1);
        Assert.assertEquals(Collections.singletonList("purchase"), receivedEventKeys());
        Assert.assertTrue(pending.isEmpty());
        Assert.assertEquals(1, logsContaining(DROPPED_UNSENT));
    }

    /**
     * The request queue trim settles the journey triggers of the requests it drops unread.
     * <p>
     * Verifies on disk, with networking off and {@code rqs} 2, that each journey trigger waits for
     * its own request, and that a third trigger, whose request drops the oldest one, leaves waiting
     * only the triggers of the two requests still queued.
     */
    @Test
    public void requestQueueTrim_settlesTheJourneyTriggersOfTheRequestsItDrops() {
        server.settings.respondWith(new ServerConfigBuilder().networking(false).requestQueueSize(2).journeyTriggerEvents(names("purchase")));
        init(TestUtils.getConfigSdkBehaviorSettings()
            .enableFeatures(Config.Feature.Events)
            .setEventQueueSizeToSend(100)
            .setLogListener((message, level) -> logs.add(message)));
        Set<Long> pending = SDKCore.instance.module(ModuleEvents.class).journeyTriggerRequestIds;

        recordEvents("purchase", "purchase");
        Assert.assertEquals(2, TestUtils.getCurrentRQ().length);
        Set<Long> firstTwo = storedRequestIds();
        Assert.assertEquals(firstTwo, pending);
        long oldest = Collections.min(firstTwo);

        recordEvents("purchase");
        Assert.assertEquals(2, TestUtils.getCurrentRQ().length);
        Set<Long> lastTwo = storedRequestIds();
        Assert.assertFalse(lastTwo.contains(oldest));
        Assert.assertEquals(lastTwo, pending);
        Assert.assertEquals(1, logsContaining("the request [" + oldest + "] carrying a journey trigger was dropped from the queue without being sent"));
    }

    // endregion
    // region helpers

    /**
     * Stands in for the settings and the content endpoints of the server. Settings requests go to a
     * {@link ModuleConfigurationTests.ServerConfigResponder}; content fetches are recorded and
     * answered on the calling thread with no content, unless networking is off.
     */
    private static final class SettingsAndContentServer implements ImmediateRequestGenerator {
        final ModuleConfigurationTests.ServerConfigResponder settings = new ModuleConfigurationTests.ServerConfigResponder();
        final List<String> contentFetches = new CopyOnWriteArrayList<>();

        /**
         * A request maker that routes by endpoint.
         *
         * @return the request maker
         */
        @Override
        public ImmediateRequestI createImmediateRequestMaker() {
            ImmediateRequestI settingsRequestMaker = settings.createImmediateRequestMaker();
            return (requestData, customEndpoint, transport, requestShouldBeDelayed, networkingIsEnabled, callback, log) -> {
                if (!CONTENT_ENDPOINT.equals(customEndpoint)) {
                    settingsRequestMaker.doWork(requestData, customEndpoint, transport, requestShouldBeDelayed, networkingIsEnabled, callback, log);
                    return;
                }
                if (!networkingIsEnabled) {
                    callback.callback(null);
                    return;
                }
                contentFetches.add(requestData);
                callback.callback(new JSONObject(NO_CONTENT_RESPONSE));
            };
        }
    }

    /**
     * One request as the local server saw it.
     */
    private static final class Received {
        final Map<String, String> params;
        final long receivedAtNs;
        volatile long answeredAtNs;

        /**
         * Records a request as it arrives.
         *
         * @param params the decoded request parameters
         * @param receivedAtNs {@link System#nanoTime()} when it arrived
         */
        Received(Map<String, String> params, long receivedAtNs) {
            this.params = params;
            this.receivedAtNs = receivedAtNs;
        }
    }

    /**
     * A local HTTP server standing in for the request queue endpoint: it accepts every request, holds
     * back the answers a test asks it to, and records each request with the moment it arrived and the
     * moment its answer started. One handler thread, so requests are recorded in the order they came.
     */
    private static final class QueueServer {
        final HttpServer http;
        final List<Received> received = new CopyOnWriteArrayList<>();
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final Queue<Long> answerDelaysMs = new ConcurrentLinkedQueue<>();

        /**
         * Starts the server on a free loopback port.
         *
         * @throws IOException when the server cannot start
         */
        QueueServer() throws IOException {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.createContext("/", this::handle);
            http.setExecutor(executor);
            http.start();
        }

        /**
         * Holds back the answers to the next requests, one delay per request in arrival order. Any
         * request after those is answered right away.
         *
         * @param delaysMs how long to hold each answer back, in milliseconds
         */
        void holdTheNextAnswers(long... delaysMs) {
            for (long delayMs : delaysMs) {
                answerDelaysMs.add(delayMs);
            }
        }

        /**
         * Records a request, holds its answer back when asked to, then accepts it.
         *
         * @param exchange the request and its response
         * @throws IOException when the response cannot be written
         */
        private void handle(HttpExchange exchange) throws IOException {
            String query = exchange.getRequestURI().getRawQuery();
            String body = new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8);
            Received request = new Received(parseParams(query != null && !query.isEmpty() ? query : body), System.nanoTime());
            received.add(request);

            Long delayMs = answerDelaysMs.poll();
            if (delayMs != null && delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            byte[] response = "{\"result\":\"Success\"}".getBytes(StandardCharsets.UTF_8);
            request.answeredAtNs = System.nanoTime();
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        }

        /**
         * The port the server listens on.
         *
         * @return the port
         */
        int port() {
            return http.getAddress().getPort();
        }

        /**
         * Stops the server and its handler thread, even while it holds an answer back.
         */
        void stop() {
            http.stop(0);
            executor.shutdownNow();
        }
    }

    /**
     * A display that shows nothing, so the content zone can be entered.
     */
    private static final class FakeDisplay implements ContentDisplay {
        /**
         * A landscape screen.
         *
         * @return the screen
         */
        @Override
        public ContentScreen getScreen() {
            return new ContentScreen(1600, 900);
        }

        /**
         * Shows nothing.
         *
         * @param content the content to show
         * @param onClosed called when the content closes
         */
        @Override
        public void present(ContentData content, ContentCloseCallback onClosed) {
        }
    }

    /**
     * Starts {@link #queueServer}.
     *
     * @throws IOException when the server cannot start
     */
    private void startQueueServer() throws IOException {
        queueServer = new QueueServer();
    }

    /**
     * Initializes the SDK with {@link #server} answering its settings requests and content fetches.
     *
     * @param config the configuration
     */
    private void init(Config config) {
        InternalConfig internalConfig = new InternalConfig(config);
        internalConfig.immediateRequestGenerator = server;
        Countly.instance().init(internalConfig);
    }

    /**
     * Answers the next settings request with the given payload and makes the SDK fetch, as the
     * refresh timer does.
     *
     * @param builder the payload
     */
    private void push(ServerConfigBuilder builder) {
        server.settings.respondWith(builder);
        SDKCore.instance.module(ModuleConfiguration.class).fetchConfigFromServer(SDKCore.instance.config);
    }

    /**
     * The settings in effect in the running SDK.
     *
     * @return the provider
     */
    private static ConfigurationProvider provider() {
        return SDKCore.instance.config.getConfigurationProvider();
    }

    /**
     * A configuration pointing at {@link #queueServer}, with events sent one per request, no request
     * cooldowns, and every log line kept.
     *
     * @return the configuration
     */
    private Config networkedConfig() {
        File directory = TestUtils.getTestSDirectory();
        TestUtils.checkSdkStorageRootDirectoryExist(directory);
        return new Config("http://127.0.0.1:" + queueServer.port(), TestUtils.SERVER_APP_KEY, directory)
            .setApplicationVersion(TestUtils.APPLICATION_VERSION)
            .setCustomDeviceId(TestUtils.DEVICE_ID)
            .enableFeatures(Config.Feature.Events)
            .setEventQueueSizeToSend(1)
            .setNetworkRequestCooldown(0)
            .setNetworkImportantRequestCooldown(0)
            .setLogListener((message, level) -> logs.add(message));
    }

    /**
     * Records events one after another, letting the clock move on after each, so every request gets
     * a storage ID of its own.
     *
     * @param keys the event keys
     */
    private static void recordEvents(String... keys) {
        for (String key : keys) {
            Countly.instance().events().recordEvent(key);
            TestUtils.letTheClockCatchUp();
        }
    }

    /**
     * Stores a request file before init, as an earlier run would have left it.
     *
     * @param id the storage ID, which names the file
     * @param marker a parameter that tells the requests apart on the server
     * @param timestamp the {@code timestamp} parameter, {@code null} for none
     * @throws IOException when the file cannot be written
     */
    private static void storeRequest(long id, String marker, Long timestamp) throws IOException {
        Request request = new Request(id);
        request.params = new Params("app_key", TestUtils.SERVER_APP_KEY, "device_id", TestUtils.DEVICE_ID, "marker", marker);
        if (timestamp != null) {
            request.params.add("timestamp", timestamp);
        }
        File directory = TestUtils.getTestSDirectory();
        TestUtils.checkSdkStorageRootDirectoryExist(directory);
        Files.write(new File(directory, "[CLY]_request_" + id).toPath(), request.store(new Log(Config.LoggingLevel.OFF, null)));
    }

    /**
     * Moves the {@code timestamp} parameter of every stored request back by the given time, as if it
     * had been recorded that much earlier, keeping its storage ID.
     *
     * @param byMs how far back, in milliseconds
     * @throws IOException when a file cannot be rewritten
     */
    private static void ageStoredRequests(long byMs) throws IOException {
        Pattern timestamp = Pattern.compile("(^|&)timestamp=(\\d+)");
        for (File file : requestFiles()) {
            String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            Matcher matcher = timestamp.matcher(content);
            Assert.assertTrue("no timestamp in " + file.getName(), matcher.find());
            long aged = Long.parseLong(matcher.group(2)) - byMs;
            String agedContent = content.substring(0, matcher.start(2)) + aged + content.substring(matcher.end(2));
            Files.write(file.toPath(), agedContent.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * The stored request files.
     *
     * @return the files, never {@code null}
     */
    private static File[] requestFiles() {
        File[] files = TestUtils.getTestSDirectory().listFiles((dir, name) -> name.startsWith("[CLY]_request_"));
        return files == null ? new File[0] : files;
    }

    /**
     * Counts the stored requests without reading them, as the network loop can remove one while it
     * is read.
     *
     * @return the number of stored requests
     */
    private static int queuedRequestCount() {
        return requestFiles().length;
    }

    /**
     * The storage IDs of the stored requests.
     *
     * @return the IDs
     */
    private static Set<Long> storedRequestIds() {
        Set<Long> ids = new HashSet<>();
        for (File file : requestFiles()) {
            ids.add(Long.parseLong(file.getName().substring("[CLY]_request_".length())));
        }
        return ids;
    }

    /**
     * How many requests the memory queue of backend mode holds.
     *
     * @return the number of requests
     */
    private static int memoryQueueSize() {
        SDKCore core = SDKCore.instance;
        synchronized (core.lockBRQStorage) {
            return core.requestQueueMemory.size();
        }
    }

    /**
     * The key of the event each request the server received carried, in the order they arrived.
     *
     * @return the event keys
     */
    private List<String> receivedEventKeys() {
        List<String> keys = new ArrayList<>();
        for (Received request : queueServer.received) {
            JSONArray requestKeys = eventKeys(request.params);
            for (int i = 0; i < requestKeys.length(); i++) {
                keys.add(requestKeys.getString(i));
            }
        }
        return keys;
    }

    /**
     * One parameter of each request the server received, in the order they arrived.
     *
     * @param key the parameter
     * @return its values
     */
    private List<String> receivedParams(String key) {
        List<String> values = new ArrayList<>();
        for (Received request : queueServer.received) {
            values.add(request.params.get(key));
        }
        return values;
    }

    /**
     * How long the server had to wait for a request after it started answering the one before it.
     *
     * @param index the position of the request, from 1
     * @return the gap in milliseconds
     */
    private long gapBeforeRequestMs(int index) {
        Received previous = queueServer.received.get(index - 1);
        Received next = queueServer.received.get(index);
        return (next.receivedAtNs - previous.answeredAtNs) / 1_000_000L;
    }

    /**
     * The keys of the events a request carries, in order.
     *
     * @param params the parameters of the request
     * @return the keys as a JSON array, empty when the request carries no events
     */
    private static JSONArray eventKeys(Map<String, String> params) {
        JSONArray keys = new JSONArray();
        String events = params.get("events");
        if (events == null) {
            return keys;
        }
        JSONArray eventArray = new JSONArray(events);
        for (int i = 0; i < eventArray.length(); i++) {
            keys.put(eventArray.getJSONObject(i).getString("key"));
        }
        return keys;
    }

    /**
     * The live backoff threads.
     *
     * @return the threads
     */
    private static Set<Thread> backoffThreads() {
        Set<Thread> threads = new HashSet<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (DefaultNetworking.BACKOFF_THREAD_NAME.equals(thread.getName()) && thread.isAlive()) {
                threads.add(thread);
            }
        }
        return threads;
    }

    /**
     * Whether every given thread has ended.
     *
     * @param threads the threads
     * @return {@code true} when none is alive
     */
    private static boolean noneAlive(Set<Thread> threads) {
        for (Thread thread : threads) {
            if (thread.isAlive()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Holds the first tick of every zone timer an hour away, so the zone fetches only when a test
     * ticks it by hand.
     */
    private static void holdTheZoneTimer() {
        contentModule().startedAtForTests(System.currentTimeMillis() + HOUR_MS);
    }

    /**
     * The content module of the running SDK.
     *
     * @return the module
     */
    private static ModuleContent contentModule() {
        return SDKCore.instance.module(ModuleContent.class);
    }

    /**
     * Ticks the zone once by hand and asserts that it fetched nothing, as no zone is active.
     *
     * @param fetchesSoFar the content fetches made so far
     */
    private void assertZoneIsInactive(int fetchesSoFar) {
        contentModule().onZoneTimerTick();
        Assert.assertEquals(fetchesSoFar, server.contentFetches.size());
    }

    /**
     * Ticks the zone by hand until it fetches, which it does once a refresh entered it.
     *
     * @param expectedFetches the content fetches expected once it fetched
     */
    private void awaitZoneFetch(int expectedFetches) throws InterruptedException {
        boolean fetched = waitNudging(() -> {
            contentModule().onZoneTimerTick();
            return server.contentFetches.size() >= expectedFetches;
        });
        Assert.assertTrue("the content zone never fetched, logs: " + logs, fetched);
        Assert.assertEquals(expectedFetches, server.contentFetches.size());
    }

    /**
     * Waits until the log holds a number of lines containing a text.
     *
     * @param text the text
     * @param count how many lines
     */
    private void awaitLogs(String text, int count) throws InterruptedException {
        Assert.assertTrue("never logged [" + text + "] " + count + " times, logs: " + logs, waitFor(() -> logsContaining(text) >= count));
        Assert.assertEquals(count, logsContaining(text));
    }

    /**
     * How many log lines so far contain a text.
     *
     * @param text the text
     * @return the number of lines
     */
    private int logsContaining(String text) {
        int count = 0;
        for (String line : logs) {
            if (line.contains(text)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Makes the send loop look at the queue, as a newly stored request does.
     */
    private static void nudge() {
        SDKCore core = SDKCore.instance;
        if (core != null && core.networking != null && core.config != null) {
            core.networking.check(core.config);
        }
    }

    /**
     * Nudges the send loop every {@link #POLL_MS} until the given moment.
     *
     * @param deadlineNs the moment, as {@link System#nanoTime()}
     */
    private static void nudgeUntil(long deadlineNs) throws InterruptedException {
        while (System.nanoTime() < deadlineNs) {
            nudge();
            Thread.sleep(POLL_MS);
        }
    }

    /**
     * Sleeps until the given moment.
     *
     * @param deadlineNs the moment, as {@link System#nanoTime()}
     */
    private static void sleepUntil(long deadlineNs) throws InterruptedException {
        long remainingMs = (deadlineNs - System.nanoTime()) / 1_000_000L;
        if (remainingMs > 0) {
            Thread.sleep(remainingMs);
        }
    }

    /**
     * Polls until the condition holds or {@link #WAIT_BUDGET_MS} runs out, without nudging the send
     * loop, so the SDK has to drain its queue on its own.
     *
     * @param condition the condition
     * @return whether the condition holds
     */
    private static boolean waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + WAIT_BUDGET_MS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(POLL_MS);
        }
        return condition.getAsBoolean();
    }

    /**
     * Polls like {@link #waitFor(BooleanSupplier)}, nudging the send loop on every round.
     *
     * @param condition the condition
     * @return whether the condition holds
     */
    private static boolean waitNudging(BooleanSupplier condition) throws InterruptedException {
        return waitFor(() -> {
            nudge();
            return condition.getAsBoolean();
        });
    }

    /**
     * Decodes url encoded request parameters.
     *
     * @param data the parameters
     * @return the parameters by name
     */
    private static Map<String, String> parseParams(String data) {
        Map<String, String> params = new HashMap<>();
        for (String pair : data.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            String[] keyValue = pair.split("=", 2);
            params.put(Utils.urldecode(keyValue[0]), keyValue.length > 1 ? Utils.urldecode(keyValue[1]) : "");
        }
        return params;
    }

    /**
     * Reads a stream to its end.
     *
     * @param stream the stream
     * @return the bytes
     * @throws IOException when the stream cannot be read
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

    // endregion
}
