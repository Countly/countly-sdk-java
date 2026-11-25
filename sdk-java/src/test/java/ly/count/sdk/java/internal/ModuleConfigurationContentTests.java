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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
     * {@code ecz} decides only whether the SDK enters the content zone at init.
     * <p>
     * Verifies that on a first start the first answer decides, and the zone is entered once a display
     * is registered; that a later answer turning the setting off does not leave the zone; that after
     * the application left the zone a later answer turning it on does not enter it; and that at the
     * next init the stored setting enters the zone once a display is registered.
     */
    @Test
    public void contentZoneEnabled_decidesOnlyTheEntryAtInit() throws InterruptedException {
        server.settings.respondWith(new ServerConfigBuilder().contentZone(true));
        init(contentConfig());
        holdTheZoneTimer();

        awaitLogs("it is entered once a content display is registered", 1);
        assertZoneIsInactive(0);
        Countly.instance().content().setContentDisplay(display);
        assertZoneFetches(1);

        push(new ServerConfigBuilder().contentZone(false));
        assertZoneFetches(2);

        Countly.instance().content().exitContentZone();
        assertZoneIsInactive(2);
        push(new ServerConfigBuilder().contentZone(true));
        assertZoneIsInactive(2);
        Countly.instance().stop();

        server.settings.pending();
        init(contentConfig());
        holdTheZoneTimer();
        assertZoneIsInactive(2);
        Countly.instance().content().setContentDisplay(display);
        assertZoneFetches(3);
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
     * The settings request asks for the content triggers with {@code jt=1}, and they are stored with
     * the settings, unless {@code disableAutoContentTriggers} is set.
     */
    @Test
    public void contentTriggers_areAskedForAndStored_unlessTurnedOff() {
        server.settings.respondWith(new ServerConfigBuilder().topLevelKey("jt", triggers("e", "purchase")));
        init(contentConfig());

        Assert.assertEquals("1", server.settings.requests.get(0).get("jt"));
        Assert.assertTrue(provider().getContentTriggers().keys.contains("purchase"));
        Assert.assertTrue(SDKCore.instance.config.storageProvider.getServerConfig().contains("\"jt\""));
        Countly.instance().halt();

        TestUtils.createCleanTestState();
        server = new SettingsAndContentServer();
        server.settings.respondWith(new ServerConfigBuilder().topLevelKey("jt", triggers("e", "purchase")));
        Config config = contentConfig();
        config.content.disableAutoContentTriggers();
        init(config);

        Assert.assertNull(server.settings.requests.get(0).get("jt"));
        Assert.assertNull(provider().getContentTriggers());
    }

    /**
     * A listed event, while the content zone is wanted, sends the queued requests and then asks for
     * content, up to three times while there is none; the same event asks again only after its gap.
     * <p>
     * Verifies against a local server that every recorded event goes out with the trigger, that the
     * request queue is empty when the first content request goes out, that three content requests
     * follow, and that the same event inside its gap starts no round.
     */
    @Test
    public void contentTriggers_aListedEventSendsTheQueueThenAsksForContent() throws IOException, InterruptedException {
        startQueueServer();
        server.settings.respondWith(new ServerConfigBuilder().topLevelKey("jt", triggers("e", "purchase")));
        init(networkedContentConfig());
        holdTheZoneTimer();
        Countly.instance().content().setContentDisplay(display);
        Countly.instance().content().enterContentZone();

        Countly.instance().events().recordEvent("regular");
        Countly.instance().events().recordEvent("purchase");
        awaitContentRequests(3);
        Assert.assertEquals("[\"regular\",\"purchase\"]", eventKeys(queueServer.eventRequests().get(0)).toString());
        Assert.assertEquals(Integer.valueOf(0), server.queuedRequestsAtFetch.get(0));

        Countly.instance().events().recordEvent("purchase");
        awaitLogs("started a round less than [10] seconds ago", 1);
        Thread.sleep(1500);
        Assert.assertEquals(3, server.contentFetches.size());
    }

    /**
     * Content triggers apply only while the content zone is wanted, and an answer with content
     * triggers, even an empty one, makes the SDK ignore the journey trigger events.
     */
    @Test
    public void contentTriggers_needTheContentZone_andHideTheJourneyTriggerEvents() throws IOException, InterruptedException {
        startQueueServer();
        server.settings.respondWith(new ServerConfigBuilder().journeyTriggerEvents(names("legacy")).topLevelKey("jt", triggers("e", "purchase")));
        init(networkedContentConfig());
        holdTheZoneTimer();
        Countly.instance().content().setContentDisplay(display);

        Countly.instance().events().recordEvent("purchase");
        Countly.instance().events().recordEvent("legacy");
        Thread.sleep(2500);
        Assert.assertEquals(0, server.contentFetches.size());

        push(new ServerConfigBuilder().journeyTriggerEvents(names("legacy")).topLevelKey("jt", new JSONObject()));
        Countly.instance().content().enterContentZone();
        int zoneFetches = server.contentFetches.size();
        Countly.instance().events().recordEvent("legacy");
        Thread.sleep(2500);
        Assert.assertEquals(zoneFetches, server.contentFetches.size());
    }

    /**
     * Without content triggers, the journey trigger events and views ask for content directly, also
     * outside the content zone and while {@code rcz} is off: an event of the app whose key is listed,
     * and a listed view when it starts and again when it ends, each with its own gap.
     */
    @Test
    public void journeyTriggers_askForContentDirectly_forEventsOfTheAppAndBothEventsOfAView() throws IOException, InterruptedException {
        startQueueServer();
        server.settings.respondWith(new ServerConfigBuilder().refreshContentZone(false)
            .journeyTriggerEvents(names("purchase", "[CLY]_star_rating"))
            .journeyTriggerViews(names("checkout")));
        init(networkedContentConfig());
        holdTheZoneTimer();
        Countly.instance().content().setContentDisplay(display);

        Countly.instance().events().recordEvent("[CLY]_star_rating");
        Thread.sleep(2000);
        Assert.assertEquals(0, server.contentFetches.size());

        Countly.instance().events().recordEvent("purchase");
        awaitContentRequests(3);

        Countly.instance().views().startView("checkout");
        awaitContentRequests(6);
        Countly.instance().views().stopViewWithName("checkout");
        awaitContentRequests(9);
    }

    /**
     * What was recorded before the first settings answer of the run is checked again when it
     * arrives, and a listed one starts a round; a session, a user property and a handled exception
     * are triggers too.
     */
    @Test
    public void contentTriggers_earlyTriggerAndTheOtherTriggerKinds() throws IOException, InterruptedException {
        startQueueServer();
        server.settings.pending();
        init(networkedContentConfig().enableFeatures(Config.Feature.CrashReporting, Config.Feature.UserProfiles));
        holdTheZoneTimer();
        Countly.instance().content().setContentDisplay(display);
        Countly.instance().content().enterContentZone();

        Countly.instance().events().recordEvent("purchase");
        Thread.sleep(1000);
        Assert.assertEquals(0, server.contentFetches.size());
        JSONObject listed = triggers("e", "purchase", "[CLY]_crash").put("p", new JSONArray().put("custom.plan"));
        push(new ServerConfigBuilder().topLevelKey("jt", listed));
        awaitContentRequests(3);

        Countly.instance().userProfile().setProperty("plan", "pro");
        Countly.instance().userProfile().save();
        awaitContentRequests(6);

        Countly.instance().crashes().recordHandledException(new IllegalStateException("content trigger"));
        awaitContentRequests(9);
    }

    /**
     * A settings change turning {@code ecz} on that arrives while the SDK stops starts no zone timer.
     * <p>
     * Verifies, with the change handed over right after the content module left its zone during
     * {@code halt()}, while its display is still registered, that the stopping SDK dispatches the
     * change to no module, and that the content module, told directly, enters no zone, so no zone
     * timer outlives the SDK.
     */
    @Test
    public void contentZoneEnabled_arrivingWhileTheSdkStops_startsNoZoneTimer() {
        AtomicInteger dispatchedChanges = new AtomicInteger();
        SDKCore.testDummyModule = new ModuleBase() {
            /**
             * Counts a dispatched settings change.
             *
             * @param config configuration of the running SDK
             */
            @Override
            protected void onSdkConfigurationChanged(InternalConfig config) {
                dispatchedChanges.incrementAndGet();
            }
        };
        AtomicReference<Runnable> onZoneLeft = new AtomicReference<>();
        server.settings.respondWith(new ServerConfigBuilder());
        init(TestUtils.getConfigSdkBehaviorSettings()
            .enableFeatures(Config.Feature.Content, Config.Feature.Events)
            .setLogListener((message, level) -> {
                Runnable hook = message != null && message.startsWith("[ModuleContent] exitContentZoneInternal, left the content zone") ? onZoneLeft.getAndSet(null) : null;
                if (hook != null) {
                    hook.run();
                }
            }));
        Countly.instance().content().setContentDisplay(display);
        ModuleContent content = contentModule();
        ModuleConfiguration configuration = SDKCore.instance.module(ModuleConfiguration.class);
        InternalConfig config = SDKCore.instance.config;
        Assert.assertNull(content.contentTimer);
        int dispatchedBeforeStop = dispatchedChanges.get();

        onZoneLeft.set(() -> {
            configuration.currentVContentZone = true;
            config.sdk.onSdkConfigurationChanged(config);
            content.onSdkConfigurationChanged(config);
        });
        try {
            Countly.instance().halt();
            Assert.assertNull("the change was never handed over", onZoneLeft.get());
            Assert.assertEquals(dispatchedBeforeStop, dispatchedChanges.get());
            Assert.assertNull(content.contentTimer);
        } finally {
            SDKCore.testDummyModule = null;
            CountlyTimer leaked = content.contentTimer;
            if (leaked != null) {
                leaked.stopTimer(false);
            }
        }
    }

    // endregion
    // region helpers

    /**
     * The settings of the journey trigger scenario: two trigger events, one trigger view, a queue
     * that waits for 100 events and networking off, so every request stays on disk. Each response
     * replaces the stored settings, so every response of the scenario starts from these.
     *
     * @return a builder holding those settings
     */
    /**
     * @param list the list of the content triggers, "e", "v" or "p"
     * @param names the names in it
     * @return content triggers with that one list
     */
    private static JSONObject triggers(String list, String... names) {
        JSONArray array = new JSONArray();
        for (String name : names) {
            array.put(name);
        }
        return new JSONObject().put(list, array);
    }

    /**
     * Waits until the content requests reached the expected count, then that no more follow at once.
     *
     * @param expected the expected count of content requests
     */
    private void awaitContentRequests(int expected) throws InterruptedException {
        Assert.assertTrue("only [" + server.contentFetches.size() + "] content requests, logs: " + logs, waitFor(() -> server.contentFetches.size() >= expected));
        Thread.sleep(300);
        Assert.assertEquals(expected, server.contentFetches.size());
    }

    /**
     * Stands in for the settings and the content endpoints of the server. Settings requests go to a
     * {@link ModuleConfigurationTests.ServerConfigResponder}; content fetches are recorded and answered
     * on the calling thread with {@link #contentResponse}, unless networking is off, where the SDK's own
     * request maker sends nothing.
     */
    private static final class SettingsAndContentServer implements ImmediateRequestGenerator {
        final ModuleConfigurationTests.ServerConfigResponder settings = new ModuleConfigurationTests.ServerConfigResponder();
        final List<String> contentFetches = new CopyOnWriteArrayList<>();
        final List<Integer> queuedRequestsAtFetch = new CopyOnWriteArrayList<>();
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
                queuedRequestsAtFetch.add(queuedRequestCount());
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
