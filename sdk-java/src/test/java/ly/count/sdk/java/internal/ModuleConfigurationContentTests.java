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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
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
 * The content settings and the journey triggers of the SDK behavior settings: {@code ecz},
 * {@code czi}, {@code rcz}, {@code jte} and {@code jtv}, observed through the public API.
 * <p>
 * Settings requests and content fetches go through a {@link SettingsAndContentServer}, which answers
 * on the calling thread. The zone timer's first tick is held an hour away, so the zone fetches only
 * when a test ticks it by hand, and a tick that fetches proves the zone is active. The journey
 * scenarios send the event requests through the real networking stack to a local HTTP server, since
 * the content zone is refreshed only after the server answers the request carrying the trigger.
 */
@RunWith(JUnit4.class)
public class ModuleConfigurationContentTests {

    private static final String CONTENT_ENDPOINT = "/o/sdk/content?";
    private static final String NO_CONTENT_RESPONSE = "{\"jsonArray\":[{\"result\":\"No content block found!\"}]}";
    private static final String CONTENT_RESPONSE = "{\"html\":\"https://content.count.ly/block-1\",\"geo\":{"
        + "\"p\":{\"x\":10,\"y\":20,\"w\":300,\"h\":400},\"l\":{\"x\":30,\"y\":40,\"w\":500,\"h\":600}}}";
    private static final long WAIT_BUDGET_MS = 30_000;

    private SettingsAndContentServer server;
    private QueueServer queueServer;
    private FakeDisplay display;
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
        display = new FakeDisplay();
        logs.clear();
    }

    /**
     * Stops the SDK, clearing its data, and the local HTTP server when one was started.
     */
    @After
    public void afterTest() {
        Countly.instance().halt();
        if (queueServer != null) {
            queueServer.http.stop(0);
        }
    }

    // region scenarios

    /**
     * {@code ecz} enters the content zone once a display is registered, and turning it off leaves
     * only a zone it entered.
     * <p>
     * Verifies that the response of the fetch made at init, applied before the content module
     * finished its init, enters nothing while no display is registered; that registering one enters
     * the zone, which a refresh keeps as the settings' zone; that turning the setting off leaves that
     * zone and turning it on enters again; that a zone the application entered, or claimed by entering
     * while the settings' zone ran, survives the setting turning off; that a response which leaves the
     * setting as it is changes nothing, so a zone the application left stays left; that registering a
     * display again enters; and that the stored setting enters again after a restart.
     */
    @Test
    public void contentZoneEnabled_entersOnceADisplayIsRegistered_andTurningItOffLeavesOnlyTheZoneItEntered() {
        server.settings.respondWith(new ServerConfigBuilder().contentZone(true));
        init(contentConfig());
        holdTheZoneTimer();

        Assert.assertTrue(provider().getContentZoneEnabled());
        Assert.assertEquals(2, logsContaining("it is entered once a content display is registered"));
        assertZoneIsInactive(0);

        Countly.instance().content().setContentDisplay(display);
        assertZoneFetches(1);
        Countly.instance().content().refreshContentZone();
        assertZoneFetches(2);

        push(new ServerConfigBuilder().contentZone(false));
        assertZoneIsInactive(2);
        push(new ServerConfigBuilder().contentZone(true));
        assertZoneFetches(3);

        Countly.instance().content().enterContentZone();
        push(new ServerConfigBuilder().contentZone(false));
        assertZoneFetches(4);
        push(new ServerConfigBuilder().eventQueueSize(5));
        assertZoneFetches(5);
        Countly.instance().content().exitContentZone();
        assertZoneIsInactive(5);

        Countly.instance().content().enterContentZone();
        push(new ServerConfigBuilder().contentZone(true));
        push(new ServerConfigBuilder().contentZone(false));
        assertZoneFetches(6);
        Countly.instance().content().exitContentZone();

        push(new ServerConfigBuilder().contentZone(true));
        assertZoneFetches(7);
        Countly.instance().content().exitContentZone();
        push(new ServerConfigBuilder().contentZone(true).eventQueueSize(6));
        assertZoneIsInactive(7);

        Countly.instance().content().setContentDisplay(display);
        assertZoneFetches(8);
        Countly.instance().stop();

        server.settings.pending();
        init(contentConfig());
        holdTheZoneTimer();
        assertZoneIsInactive(8);
        Countly.instance().content().setContentDisplay(display);
        assertZoneFetches(9);
    }

    /**
     * {@code czi} is the fetch interval of a zone, read when the zone is entered.
     * <p>
     * Verifies that without the setting the developer's interval is used, that the setting overrides
     * it, that a response changing it restarts the timer of the active zone at the new interval while
     * the zone keeps fetching, that a response repeating it keeps the running timer, and that a change
     * while no zone is active starts nothing and applies when a zone is entered.
     */
    @Test
    public void contentZoneInterval_isReadWhenTheZoneIsEntered_andAChangeRestartsTheTimerOfTheActiveZone() {
        server.settings.pending();
        init(contentConfig().content.setZoneTimerInterval(20));
        holdTheZoneTimer();
        Countly.instance().content().setContentDisplay(display);
        Countly.instance().content().enterContentZone();
        Assert.assertEquals(20, zoneTimer().getTimerDelaySeconds());
        Countly.instance().halt();

        server.settings.respondWith(new ServerConfigBuilder().contentZoneInterval(60));
        init(contentConfig().content.setZoneTimerInterval(20));
        holdTheZoneTimer();
        Assert.assertEquals(60, provider().getContentZoneTimerInterval());
        Countly.instance().content().setContentDisplay(display);
        Countly.instance().content().enterContentZone();
        CountlyTimer atEnter = zoneTimer();
        Assert.assertEquals(60, atEnter.getTimerDelaySeconds());
        assertZoneFetches(1);

        push(new ServerConfigBuilder().contentZoneInterval(90));
        CountlyTimer restarted = zoneTimer();
        Assert.assertNotSame(atEnter, restarted);
        Assert.assertEquals(90, restarted.getTimerDelaySeconds());
        assertZoneFetches(2);

        push(new ServerConfigBuilder().contentZoneInterval(90).eventQueueSize(3));
        Assert.assertSame(restarted, zoneTimer());

        Countly.instance().content().exitContentZone();
        push(new ServerConfigBuilder().contentZoneInterval(120));
        Assert.assertNull(zoneTimer());
        Countly.instance().content().enterContentZone();
        Assert.assertEquals(120, zoneTimer().getTimerDelaySeconds());
        assertZoneFetches(3);
    }

    /**
     * {@code jte} and {@code jtv} send the whole event queue as soon as a trigger is recorded.
     * <p>
     * Verifies on disk, with networking off, that events that are not triggers wait for the queue
     * threshold, an internal event listed in {@code jte} included, that a custom trigger sends them all
     * with it, that each later trigger gets its own request and each such request waits for its
     * response, that both the start and the end of a trigger view send the queue, and that a trigger
     * filtered out by {@code eb} or a trigger view while view tracking is off sends nothing.
     */
    @Test
    public void journeyTriggers_sendTheWholeEventQueueRightAway_eachTriggerInItsOwnRequest() {
        server.settings.respondWith(new ServerConfigBuilder()
            .journeyTriggerEvents(names("purchase", "[CLY]_star_rating"))
            .journeyTriggerViews(names("checkout"))
            .eventQueueSize(100)
            .networking(false));
        init(contentConfig().enableFeatures(Config.Feature.Views));

        Countly.instance().events().recordEvent("regular");
        Countly.instance().events().recordEvent("[CLY]_star_rating");
        Countly.instance().views().startView("home");
        Assert.assertEquals(0, TestUtils.getCurrentRQ().length);
        TestUtils.validateEQSize(3);

        Countly.instance().events().recordEvent("purchase", TestUtils.map("item", "book"));
        TestUtils.letTheClockCatchUp();
        TestUtils.validateEQSize(0);
        Assert.assertEquals(1, TestUtils.getCurrentRQ().length);
        List<EventImpl> sent = TestUtils.readEventsFromRequest(0, TestUtils.DEVICE_ID);
        Assert.assertEquals(4, sent.size());
        Assert.assertEquals("regular", sent.get(0).key);
        Assert.assertEquals("[CLY]_star_rating", sent.get(1).key);
        Assert.assertEquals(ModuleViews.KEY_VIEW_EVENT, sent.get(2).key);
        Assert.assertEquals("purchase", sent.get(3).key);
        Assert.assertEquals("book", sent.get(3).segmentation.get("item"));

        Countly.instance().events().recordEvent("purchase");
        TestUtils.letTheClockCatchUp();
        Assert.assertEquals(2, TestUtils.getCurrentRQ().length);
        Assert.assertEquals(1, TestUtils.readEventsFromRequest(1, TestUtils.DEVICE_ID).size());

        Countly.instance().views().startView("checkout");
        TestUtils.letTheClockCatchUp();
        Countly.instance().views().stopViewWithName("checkout");
        TestUtils.letTheClockCatchUp();
        Assert.assertEquals(4, TestUtils.getCurrentRQ().length);
        List<EventImpl> checkoutStart = TestUtils.readEventsFromRequest(2, TestUtils.DEVICE_ID);
        List<EventImpl> checkoutEnd = TestUtils.readEventsFromRequest(3, TestUtils.DEVICE_ID);
        Assert.assertEquals("checkout", checkoutStart.get(0).segmentation.get(ModuleViews.KEY_NAME));
        Assert.assertEquals("1", checkoutStart.get(0).segmentation.get(ModuleViews.KEY_VISIT));
        Assert.assertEquals("checkout", checkoutEnd.get(0).segmentation.get(ModuleViews.KEY_NAME));
        Assert.assertNull(checkoutEnd.get(0).segmentation.get(ModuleViews.KEY_VISIT));
        Assert.assertEquals(storedRequestIds(), SDKCore.instance.module(ModuleEvents.class).journeyTriggerRequestIds);

        Countly.instance().views().startView("profile");
        push(new ServerConfigBuilder().eventFilterList(names("purchase"), false));
        Countly.instance().events().recordEvent("purchase");
        push(new ServerConfigBuilder().viewTracking(false));
        Assert.assertNull(Countly.instance().views().startView("checkout"));
        Assert.assertEquals(4, TestUtils.getCurrentRQ().length);
        TestUtils.validateEQSize(1);
        Assert.assertEquals(4, SDKCore.instance.module(ModuleEvents.class).journeyTriggerRequestIds.size());
    }

    /**
     * A {@code jte} trigger refreshes the content zone once the server accepts the request carrying it.
     * <p>
     * Verifies against a local server that the zone is entered only after the request carrying the
     * trigger and the events queued before it was accepted; that while a content block is on screen
     * the refresh is ignored; that a rejected request refreshes nothing, and neither does its retry
     * once accepted; and that an attempt that got no response settles it the same way.
     */
    @Test
    public void journeyTriggerEvent_refreshesTheZoneOnlyWhenTheServerAcceptsItsRequest() throws Exception {
        startQueueServer();
        server.settings.respondWith(new ServerConfigBuilder().journeyTriggerEvents(names("purchase")));
        init(networkedContentConfig());
        holdTheZoneTimer();
        Countly.instance().content().setContentDisplay(display);
        assertZoneIsInactive(0);

        Countly.instance().events().recordEvent("regular");
        TestUtils.validateEQSize(1);
        Countly.instance().events().recordEvent("purchase");
        awaitZoneFetch(1);
        Assert.assertEquals(1, queueServer.eventRequests().size());
        Assert.assertEquals("[\"regular\",\"purchase\"]", eventKeys(queueServer.eventRequests().get(0)).toString());
        awaitEmptyQueue();

        server.contentResponse = CONTENT_RESPONSE;
        assertZoneFetches(2);
        Assert.assertEquals(1, display.presented.size());
        Countly.instance().events().recordEvent("purchase");
        awaitLogs("a content block is on screen, ignoring the call", 1);
        display.lastCallback.onClosed(new HashMap<>());
        Countly.instance().content().exitContentZone();
        server.contentResponse = NO_CONTENT_RESPONSE;
        awaitEmptyQueue();
        assertZoneIsInactive(2);

        queueServer.mode = QueueServer.REJECT;
        Countly.instance().events().recordEvent("purchase");
        awaitLogs("carrying a journey trigger failed with code [500]", 1);
        Assert.assertEquals(1, queuedRequestCount());
        assertZoneIsInactive(2);
        queueServer.mode = QueueServer.ACCEPT;
        awaitEmptyQueue();
        assertZoneIsInactive(2);

        queueServer.mode = QueueServer.DROP;
        Countly.instance().events().recordEvent("purchase");
        awaitLogs("carrying a journey trigger failed with code [" + Transport.NO_RESPONSE_CODE + "]", 1);
        assertZoneIsInactive(2);
        queueServer.mode = QueueServer.ACCEPT;
        awaitEmptyQueue();
        assertZoneIsInactive(2);
        Assert.assertEquals(2, logsContaining("carrying a journey trigger, refreshing the content zone"));
        Assert.assertTrue(SDKCore.instance.module(ModuleEvents.class).journeyTriggerRequestIds.isEmpty());
    }

    /**
     * A {@code jtv} trigger refreshes the zone like an event does, it never triggers while view
     * tracking is off, and {@code rcz} off ignores every refresh.
     * <p>
     * Verifies against a local server that with refreshing forbidden the application's refresh is
     * ignored before it sends the queued events and the refresh of an accepted trigger is ignored too;
     * that with view tracking off a trigger view is not recorded, so nothing is sent; and that with
     * both allowed a trigger view sends the queue and enters the zone once the server accepted it.
     */
    @Test
    public void journeyTriggerView_refreshesLikeAnEvent_neverWhileViewTrackingIsOff_andRczOffIgnoresEveryRefresh() throws Exception {
        startQueueServer();
        server.settings.respondWith(new ServerConfigBuilder()
            .journeyTriggerViews(names("checkout"))
            .journeyTriggerEvents(names("purchase"))
            .refreshContentZone(false));
        init(networkedContentConfig());
        holdTheZoneTimer();
        Countly.instance().content().setContentDisplay(display);

        Countly.instance().views().startView("home");
        Countly.instance().content().refreshContentZone();
        TestUtils.validateEQSize(1);
        Assert.assertEquals(1, logsContaining("refreshing the content zone is disabled by the SDK behavior settings"));
        assertZoneIsInactive(0);

        Countly.instance().events().recordEvent("purchase");
        awaitLogs("refreshing the content zone is disabled by the SDK behavior settings", 2);
        awaitEmptyQueue();
        assertZoneIsInactive(0);
        Assert.assertEquals("[\"[CLY]_view\",\"purchase\"]", eventKeys(queueServer.eventRequests().get(0)).toString());

        push(new ServerConfigBuilder().refreshContentZone(true).viewTracking(false));
        Assert.assertNull(Countly.instance().views().startView("checkout"));
        TestUtils.validateEQSize(0);
        Assert.assertEquals(0, queuedRequestCount());
        assertZoneIsInactive(0);

        push(new ServerConfigBuilder().viewTracking(true));
        Countly.instance().views().startView("checkout");
        awaitZoneFetch(1);
        List<Map<String, String>> eventRequests = queueServer.eventRequests();
        Assert.assertEquals(2, eventRequests.size());
        JSONObject view = new JSONArray(eventRequests.get(1).get("events")).getJSONObject(0);
        Assert.assertEquals(ModuleViews.KEY_VIEW_EVENT, view.getString("key"));
        Assert.assertEquals("checkout", view.getJSONObject("segmentation").getString(ModuleViews.KEY_NAME));
        awaitEmptyQueue();
    }

    // endregion
    // region helpers

    /**
     * Stands in for the settings and the content endpoints of the server. Settings requests go to a
     * {@link ModuleConfigurationTests.ServerConfigResponder}; content fetches are recorded and answered
     * on the calling thread with {@link #contentResponse}, unless networking is off, where the SDK's own
     * request maker sends nothing.
     */
    private static final class SettingsAndContentServer implements ImmediateRequestGenerator {
        final ModuleConfigurationTests.ServerConfigResponder settings = new ModuleConfigurationTests.ServerConfigResponder();
        final List<String> contentFetches = new CopyOnWriteArrayList<>();
        volatile String contentResponse = NO_CONTENT_RESPONSE;

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
                callback.callback(new JSONObject(contentResponse));
            };
        }
    }

    /**
     * A local HTTP server standing in for the request queue endpoint {@code /i}: it records the
     * parameters of every request and accepts it, rejects it with a 500, or drops the connection
     * without any response.
     */
    private static final class QueueServer {
        static final int ACCEPT = 0;
        static final int REJECT = 1;
        static final int DROP = 2;

        final HttpServer http;
        final List<Map<String, String>> received = new CopyOnWriteArrayList<>();
        volatile int mode = ACCEPT;

        /**
         * Starts the server on a free loopback port.
         *
         * @throws IOException when the server cannot start
         */
        QueueServer() throws IOException {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.createContext("/", this::handle);
            http.setExecutor(Executors.newSingleThreadExecutor());
            http.start();
        }

        /**
         * Records a request and answers it as {@link #mode} says.
         *
         * @param exchange the request and its response
         * @throws IOException when the response cannot be written
         */
        private void handle(HttpExchange exchange) throws IOException {
            String query = exchange.getRequestURI().getRawQuery();
            String body = new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8);
            received.add(parseParams(query != null && !query.isEmpty() ? query : body));

            int currentMode = mode;
            if (currentMode == DROP) {
                exchange.close();
                return;
            }

            byte[] response = (currentMode == ACCEPT ? "{\"result\":\"Success\"}" : "{\"result\":\"Server error\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(currentMode == ACCEPT ? 200 : 500, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        }

        /**
         * The received requests that carry events, in the order they arrived.
         *
         * @return the parameters of each request
         */
        List<Map<String, String>> eventRequests() {
            List<Map<String, String>> requests = new ArrayList<>();
            for (Map<String, String> params : received) {
                if (params.containsKey("events")) {
                    requests.add(params);
                }
            }
            return requests;
        }

        /**
         * The port the server listens on.
         *
         * @return the port
         */
        int port() {
            return http.getAddress().getPort();
        }
    }

    /**
     * A display that records what it was asked to show and lets a test close it.
     */
    private static final class FakeDisplay implements ContentDisplay {
        final List<ContentData> presented = new CopyOnWriteArrayList<>();
        volatile ContentCloseCallback lastCallback;

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
         * Records the content and keeps the callback that closes it.
         *
         * @param content the content to show
         * @param onClosed called when the content closes
         */
        @Override
        public void present(ContentData content, ContentCloseCallback onClosed) {
            presented.add(content);
            lastCallback = onClosed;
        }
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
     * The settings test configuration with content and events, keeping every log line.
     *
     * @return the configuration
     */
    private Config contentConfig() {
        return TestUtils.getConfigSdkBehaviorSettings()
            .enableFeatures(Config.Feature.Content, Config.Feature.Events)
            .setLogListener((message, level) -> logs.add(message));
    }

    /**
     * A configuration pointing at {@link #queueServer}, with content, events and views, an event
     * queue that only a trigger flushes, no request cooldowns, and every log line kept.
     *
     * @return the configuration
     */
    private Config networkedContentConfig() {
        File directory = TestUtils.getTestSDirectory();
        TestUtils.checkSdkStorageRootDirectoryExist(directory);
        return new Config("http://127.0.0.1:" + queueServer.port(), TestUtils.SERVER_APP_KEY, directory)
            .setApplicationVersion(TestUtils.APPLICATION_VERSION)
            .setCustomDeviceId(TestUtils.DEVICE_ID)
            .enableFeatures(Config.Feature.Content, Config.Feature.Events, Config.Feature.Views)
            .setEventQueueSizeToSend(100)
            .setNetworkRequestCooldown(0)
            .setNetworkImportantRequestCooldown(0)
            .setLogListener((message, level) -> logs.add(message));
    }

    /**
     * Starts {@link #queueServer}, accepting every request.
     *
     * @throws IOException when the server cannot start
     */
    private void startQueueServer() throws IOException {
        queueServer = new QueueServer();
    }

    /**
     * Holds the first tick of every zone timer an hour away, so the zone fetches only when a test
     * ticks it by hand.
     */
    private static void holdTheZoneTimer() {
        contentModule().startedAtForTests(System.currentTimeMillis() + 60L * 60L * 1000L);
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
     * The timer of the active zone.
     *
     * @return the timer, {@code null} while no zone is active
     */
    private static CountlyTimer zoneTimer() {
        return contentModule().contentTimer;
    }

    /**
     * Ticks the zone once by hand and asserts the total number of content fetches after the tick.
     *
     * @param expectedFetches the fetches expected so far
     */
    private void assertZoneFetches(int expectedFetches) {
        contentModule().onZoneTimerTick();
        Assert.assertEquals(expectedFetches, server.contentFetches.size());
    }

    /**
     * Ticks the zone once by hand and asserts that it fetched nothing, as no zone is active.
     *
     * @param fetchesSoFar the fetches made so far
     */
    private void assertZoneIsInactive(int fetchesSoFar) {
        assertZoneFetches(fetchesSoFar);
    }

    /**
     * Ticks the zone by hand until it fetches, which it does once a refresh entered it.
     *
     * @param expectedFetches the fetches expected once it fetched
     */
    private void awaitZoneFetch(int expectedFetches) throws InterruptedException {
        boolean fetched = waitFor(() -> {
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
        Assert.assertTrue("never logged [" + text + "] " + count + " times", waitFor(() -> logsContaining(text) >= count));
        Assert.assertEquals(count, logsContaining(text));
    }

    /**
     * Waits until every stored request was sent and removed.
     */
    private void awaitEmptyQueue() throws InterruptedException {
        Assert.assertTrue("the request queue never drained", waitFor(() -> queuedRequestCount() == 0));
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
     * Counts the stored requests without reading them, as the network loop can remove one while it
     * is read.
     *
     * @return the number of stored requests
     */
    private static int queuedRequestCount() {
        File[] files = TestUtils.getTestSDirectory().listFiles((dir, name) -> name.startsWith("[CLY]_request_"));
        return files == null ? 0 : files.length;
    }

    /**
     * The storage IDs of the stored requests.
     *
     * @return the IDs
     */
    private static Set<Long> storedRequestIds() {
        Set<Long> ids = new HashSet<>();
        File[] files = TestUtils.getTestSDirectory().listFiles((dir, name) -> name.startsWith("[CLY]_request_"));
        if (files != null) {
            for (File file : files) {
                ids.add(Long.parseLong(file.getName().substring("[CLY]_request_".length())));
            }
        }
        return ids;
    }

    /**
     * The keys of the events a request carries, in order.
     *
     * @param params the parameters of the request
     * @return the keys as a JSON array
     */
    private static JSONArray eventKeys(Map<String, String> params) {
        JSONArray events = new JSONArray(params.get("events"));
        JSONArray keys = new JSONArray();
        for (int i = 0; i < events.length(); i++) {
            keys.put(events.getJSONObject(i).getString("key"));
        }
        return keys;
    }

    /**
     * Polls until the condition holds or {@link #WAIT_BUDGET_MS} runs out, nudging the send loop on
     * every round, as it only picks up a request when something is pushed.
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
            SDKCore core = SDKCore.instance;
            if (core != null && core.networking != null) {
                core.networking.check(core.config);
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
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
