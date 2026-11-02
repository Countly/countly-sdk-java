package ly.count.sdk.java.internal;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
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

/**
 * The events the SDK records for its own features, views and feedback widget results, are recorded
 * under the consent of their feature, without the events consent, which custom events still need.
 */
@RunWith(JUnit4.class)
public class ScenarioInternalEventConsentTests {
    private static final long WAIT_BUDGET_MS = 60_000;

    private EventServer server;

    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
    }

    @After
    public void afterTest() {
        Countly.instance().halt();
        if (server != null) {
            server.stop();
        }
    }

    // region scenarios

    /**
     * The views consent alone is enough to record views and send them.
     * <p>
     * Verifies against a local server, with consent required and only the views consent given, that a
     * started and stopped view reach the server as two view events in one request, both with the view
     * ID and the first with the visit and start keys, while a custom event recorded in between is
     * ignored and the events feature is not tracked.
     */
    @Test
    public void viewsConsentAlone_viewsAreRecordedAndSent_customEventsAreNot() throws Exception {
        server = new EventServer();
        Countly.instance().init(serverConfig().enableFeatures(Config.Feature.Events, Config.Feature.Views)
            .setRequiresConsent(true).setEventQueueSizeToSend(2));
        Countly.onConsent(Config.Feature.Views);

        String viewId = Countly.instance().views().startView("home");
        Countly.instance().events().recordEvent("custom_without_consent");
        Countly.instance().views().stopViewWithID(viewId);

        List<JSONObject> events = server.awaitEvents(2);
        Assert.assertEquals(Arrays.asList(ModuleViews.KEY_VIEW_EVENT, ModuleViews.KEY_VIEW_EVENT), keys(events));
        Assert.assertEquals(1, server.eventRequests().size());
        TestUtils.validateRequiredParams(server.eventRequests().get(0));
        JSONObject start = events.get(0).getJSONObject("segmentation");
        Assert.assertEquals(viewId, events.get(0).getString("id"));
        Assert.assertEquals("home", start.getString(ModuleViews.KEY_NAME));
        Assert.assertEquals(ModuleViews.KEY_VISIT_VALUE, start.getString(ModuleViews.KEY_VISIT));
        Assert.assertEquals(ModuleViews.KEY_START_VALUE, start.getString(ModuleViews.KEY_START));
        JSONObject end = events.get(1).getJSONObject("segmentation");
        Assert.assertEquals(viewId, events.get(1).getString("id"));
        Assert.assertEquals("home", end.getString(ModuleViews.KEY_NAME));
        Assert.assertFalse(end.has(ModuleViews.KEY_VISIT));
        Assert.assertFalse(Countly.isTracking(Config.Feature.Events));
        Assert.assertTrue(Countly.isTracking(Config.Feature.Views));
        TestUtils.validateEQSize(0);
    }

    /**
     * The feedback consent alone is enough to record a feedback widget result and send it.
     * <p>
     * Verifies against a local server, with consent required and only the feedback consent given,
     * that a reported NPS result reaches the server as one NPS event with the widget ID, the platform,
     * the application version and the answers, and that the events feature is not tracked.
     */
    @Test
    public void feedbackConsentAlone_aWidgetResultIsRecordedAndSent() throws Exception {
        server = new EventServer();
        Countly.instance().init(serverConfig().enableFeatures(Config.Feature.Events, Config.Feature.Feedback)
            .setRequiresConsent(true).setEventQueueSizeToSend(1));
        Countly.onConsent(Config.Feature.Feedback);

        Countly.instance().feedback().reportFeedbackWidgetManually(widget(FeedbackWidgetType.nps, "nps_widget"), null, TestUtils.map("rating", 7, "comment", "fine"));

        List<JSONObject> events = server.awaitEvents(1);
        Assert.assertEquals(Collections.singletonList(FeedbackWidgetType.nps.eventKey), keys(events));
        TestUtils.validateRequiredParams(server.eventRequests().get(0));
        JSONObject segmentation = events.get(0).getJSONObject("segmentation");
        Assert.assertEquals("nps_widget", segmentation.getString("widget_id"));
        Assert.assertEquals(WidgetUrlBuilder.PLATFORM, segmentation.getString("platform"));
        Assert.assertEquals(TestUtils.APPLICATION_VERSION, segmentation.getString("app_version"));
        Assert.assertEquals(7, segmentation.getInt("rating"));
        Assert.assertEquals("fine", segmentation.getString("comment"));
        Assert.assertEquals(5, segmentation.length());
        Assert.assertFalse(Countly.isTracking(Config.Feature.Events));
    }

    /**
     * A feedback widget result needs the feedback consent, which the events consent does not stand in
     * for.
     * <p>
     * Verifies that with only the events consent given a reported result is ignored, and that once
     * the feedback consent is given the same result is recorded.
     */
    @Test
    public void eventsConsentAlone_doesNotRecordAWidgetResult() {
        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.Events, Config.Feature.Feedback)
            .setRequiresConsent(true).setEventQueueSizeToSend(100));
        Countly.onConsent(Config.Feature.Events);
        CountlyFeedbackWidget nps = widget(FeedbackWidgetType.nps, "nps_widget");

        Countly.instance().feedback().reportFeedbackWidgetManually(nps, null, null);
        TestUtils.validateEQSize(0);

        Countly.onConsent(Config.Feature.Feedback);
        Countly.instance().feedback().reportFeedbackWidgetManually(nps, null, null);
        Assert.assertEquals(Collections.singletonList(FeedbackWidgetType.nps.eventKey), TestUtils.keysInEQ());
    }

    /**
     * Without a consent requirement, views and feedback widget results are recorded when only their
     * own features are enabled, while custom events stay off with the events feature.
     * <p>
     * Verifies that a view started before init is ignored, that after init the events feature is not
     * tracked and its interface ignores calls, and that a view and a rating result are queued in the
     * order they were recorded.
     */
    @Test
    public void withoutConsentRequired_viewsAndWidgetResultsAreRecorded_withoutTheEventsFeature() {
        Assert.assertNull(Countly.instance().views().startView("before_init"));
        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.Views, Config.Feature.Feedback)
            .setEventQueueSizeToSend(100));

        Assert.assertFalse(Countly.isTracking(Config.Feature.Events));
        Assert.assertSame(SDKCore.dormantEvents, Countly.instance().events());
        Countly.instance().events().recordEvent("custom_without_the_feature");
        Assert.assertFalse(Countly.instance().events().startEvent("timed_without_the_feature"));

        Assert.assertNotNull(Countly.instance().views().startView("home"));
        Countly.instance().feedback().reportFeedbackWidgetManually(widget(FeedbackWidgetType.rating, "rating_widget"), null, TestUtils.map("rating", 4));

        List<EventImpl> queued = TestUtils.getCurrentEQ();
        Assert.assertEquals(Arrays.asList(ModuleViews.KEY_VIEW_EVENT, FeedbackWidgetType.rating.eventKey), TestUtils.keysInEQ());
        Assert.assertEquals("home", queued.get(0).segmentation.get(ModuleViews.KEY_NAME));
        Assert.assertEquals("rating_widget", queued.get(1).segmentation.get("widget_id"));
        Assert.assertEquals(4, queued.get(1).segmentation.get("rating"));
    }

    /**
     * Removing the events consent drops the custom and timed events not sent yet, and nothing the
     * views feature recorded, which keeps recording.
     * <p>
     * Verifies that after the removal only the view event is left in the queue and no timed event is
     * left, that an events interface kept from before ignores calls while a new view is recorded, and
     * that once the events consent is given again a custom event is queued after the views without a
     * previous event ID pointing at a dropped event.
     */
    @Test
    public void removingTheEventsConsent_dropsCustomAndTimedEvents_andKeepsTheViews() {
        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.Events, Config.Feature.Views)
            .setRequiresConsent(true).setEventQueueSizeToSend(100));
        Countly.onConsent(Config.Feature.Events, Config.Feature.Views);
        ModuleEvents.Events kept = Countly.instance().events();
        kept.recordEvent("custom_before_removal");
        Assert.assertTrue(kept.startEvent("timed_before_removal"));
        Assert.assertNotNull(Countly.instance().views().startView("home"));
        Assert.assertEquals(Arrays.asList("custom_before_removal", ModuleViews.KEY_VIEW_EVENT), TestUtils.keysInEQ());

        Countly.onConsentRemoval(Config.Feature.Events);

        Assert.assertEquals(Collections.singletonList(ModuleViews.KEY_VIEW_EVENT), TestUtils.keysInEQ());
        Assert.assertTrue(SDKCore.instance.module(ModuleEvents.class).timedEvents.isEmpty());
        Assert.assertFalse(Countly.isTracking(Config.Feature.Events));
        Assert.assertSame(SDKCore.dormantEvents, Countly.instance().events());
        kept.recordEvent("ignored_after_removal");
        Assert.assertFalse(kept.endEvent("timed_before_removal"));
        Assert.assertNotNull(Countly.instance().views().startView("after_removal"));
        Assert.assertEquals(Arrays.asList(ModuleViews.KEY_VIEW_EVENT, ModuleViews.KEY_VIEW_EVENT), TestUtils.keysInEQ());

        Countly.onConsent(Config.Feature.Events);
        kept.recordEvent("after_consent_again");
        Assert.assertEquals(Arrays.asList(ModuleViews.KEY_VIEW_EVENT, ModuleViews.KEY_VIEW_EVENT, "after_consent_again"), TestUtils.keysInEQ());
        Assert.assertNull(TestUtils.getCurrentEQ().get(2).peid);
    }

    /**
     * Removing every consent drops every event not sent yet, the views and feedback widget results
     * included, as their own consent goes with it.
     * <p>
     * Verifies that after a custom event, a view and an NPS result are queued with every consent
     * given, removing every consent leaves nothing in the event queue and queues no request.
     */
    @Test
    public void removingEveryConsent_dropsTheQueuedViewsAndWidgetResultsToo() {
        Countly.instance().init(TestUtils.getBaseConfig()
            .enableFeatures(Config.Feature.Events, Config.Feature.Views, Config.Feature.Feedback)
            .setRequiresConsent(true).setEventQueueSizeToSend(100));
        Countly.onConsent(Config.Feature.Events, Config.Feature.Views, Config.Feature.Feedback);
        Countly.instance().events().recordEvent("custom_before_opt_out");
        Assert.assertNotNull(Countly.instance().views().startView("home"));
        Countly.instance().feedback().reportFeedbackWidgetManually(widget(FeedbackWidgetType.nps, "nps_widget"), null, null);
        Assert.assertEquals(Arrays.asList("custom_before_opt_out", ModuleViews.KEY_VIEW_EVENT, FeedbackWidgetType.nps.eventKey), TestUtils.keysInEQ());

        Countly.onConsentRemoval(Config.Feature.values());

        TestUtils.validateEQSize(0);
        Assert.assertEquals(0, TestUtils.getCurrentRQ().length);
    }

    /**
     * Custom events left in the queue by an earlier run wait for the events consent, while the views
     * recorded meanwhile are sent.
     * <p>
     * Verifies against a local server that, after a restart with only the views consent given, the
     * request the first view triggers carries that view alone while the custom event of the earlier
     * run stays queued, and that once the events consent is given the next request carries that event
     * and a new one, in the order they were recorded.
     */
    @Test
    public void customEventsOfAnEarlierRun_waitForTheEventsConsent_whileViewsAreSent() throws Exception {
        server = new EventServer();
        Countly.instance().init(serverConfig().enableFeatures(Config.Feature.Events, Config.Feature.Views)
            .setRequiresConsent(true).setEventQueueSizeToSend(100));
        Countly.onConsent(Config.Feature.Events);
        Countly.instance().events().recordEvent("from_an_earlier_run");
        Countly.instance().stop();
        Assert.assertEquals(Collections.singletonList("from_an_earlier_run"), TestUtils.keysInEQ());

        Countly.instance().init(serverConfig().enableFeatures(Config.Feature.Events, Config.Feature.Views)
            .setRequiresConsent(true).setEventQueueSizeToSend(1));
        Countly.onConsent(Config.Feature.Views);
        Assert.assertNotNull(Countly.instance().views().startView("home"));

        Assert.assertEquals(Collections.singletonList(ModuleViews.KEY_VIEW_EVENT), keys(server.awaitEvents(1)));
        Assert.assertEquals(Collections.singletonList("from_an_earlier_run"), TestUtils.keysInEQ());

        Countly.onConsent(Config.Feature.Events);
        Countly.instance().events().recordEvent("after_the_consent");

        List<JSONObject> events = server.awaitEvents(3);
        Assert.assertEquals(Arrays.asList(ModuleViews.KEY_VIEW_EVENT, "from_an_earlier_run", "after_the_consent"), keys(events));
        Assert.assertEquals(2, server.eventRequests().size());
        TestUtils.validateEQSize(0);
    }

    /**
     * Custom events waiting for the events consent do not count toward the event queue threshold, so
     * the views still go in batches.
     * <p>
     * Verifies that, after a restart with a threshold below the number of custom events left by the
     * earlier run and only the views consent given, the first view event stays queued and the second
     * one sends both in one request, while the custom events stay queued.
     */
    @Test
    public void customEventsWaitingForTheEventsConsent_doNotCountTowardTheThreshold() {
        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.Events, Config.Feature.Views)
            .setRequiresConsent(true).setEventQueueSizeToSend(100));
        Countly.onConsent(Config.Feature.Events);
        Countly.instance().events().recordEvent("custom_1");
        Countly.instance().events().recordEvent("custom_2");
        Countly.instance().events().recordEvent("custom_3");
        Countly.instance().stop();

        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.Events, Config.Feature.Views)
            .setRequiresConsent(true).setEventQueueSizeToSend(2));
        Countly.onConsent(Config.Feature.Views);
        String viewId = Countly.instance().views().startView("home");
        Assert.assertEquals(0, TestUtils.getCurrentRQ().length);

        Countly.instance().views().stopViewWithID(viewId);

        Map<String, String>[] requests = TestUtils.getCurrentRQ();
        Assert.assertEquals(1, requests.length);
        TestUtils.validateRequiredParams(requests[0]);
        Assert.assertEquals(Arrays.asList(ModuleViews.KEY_VIEW_EVENT, ModuleViews.KEY_VIEW_EVENT), keys(eventsOf(requests[0])));
        Assert.assertEquals(Arrays.asList("custom_1", "custom_2", "custom_3"), TestUtils.keysInEQ());
    }

    /**
     * Timed events stored by an earlier run through the deprecated session API stay in that store
     * while the events feature does not run, so nothing can end them into custom events.
     * <p>
     * Verifies, with only the views consent given, that restoring a stored timed event keeps it in the
     * deprecated store and out of the events module, and that a device ID change without merge, which
     * ends the timed events of the module, records no custom event.
     */
    @Test
    public void timedEventsOfAnEarlierRun_stayInTheDeprecatedStore_whileTheEventsFeatureDoesNotRun() throws IOException {
        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.Events, Config.Feature.Views)
            .setRequiresConsent(true).setEventQueueSizeToSend(100));
        Countly.onConsent(Config.Feature.Views);
        Log logger = SDKCore.instance.config.getLogger();
        TimedEvents stored = new TimedEvents();

        Assert.assertTrue(stored.restore(storedTimedEvent("timed_of_an_earlier_run", logger), logger));

        Assert.assertEquals(1, stored.size());
        Assert.assertTrue(SDKCore.instance.module(ModuleEvents.class).timedEvents.isEmpty());
        Countly.instance().deviceId().changeWithoutMerge("another_device_id");
        Assert.assertEquals(Collections.emptyList(), allQueuedEventKeys());
    }

    /**
     * A custom event that reaches the events module another way than through the events interface,
     * as a timed event the deprecated API handed over, is not recorded while the events feature does
     * not run.
     * <p>
     * Verifies, with only the views consent given, that a custom event recorded straight into the
     * module is dropped, and that a device ID change without merge drops the timed event it ends while
     * it records the start and the end of the running view.
     */
    @Test
    public void customEventsReachingTheModuleAnotherWay_areNotRecorded_whileTheEventsFeatureDoesNotRun() {
        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.Events, Config.Feature.Views)
            .setRequiresConsent(true).setEventQueueSizeToSend(100));
        Countly.onConsent(Config.Feature.Views);
        ModuleEvents module = SDKCore.instance.module(ModuleEvents.class);

        module.recordEventInternal("custom_from_inside", 1, null, null, null, null);
        TestUtils.validateEQSize(0);

        module.timedEvents.put("timed_from_inside", new EventImpl(event -> {
        }, "timed_from_inside", SDKCore.instance.config.getLogger()));
        Assert.assertNotNull(Countly.instance().views().startView("home"));
        Countly.instance().deviceId().changeWithoutMerge("another_device_id");

        Assert.assertTrue(module.timedEvents.isEmpty());
        Assert.assertEquals(Arrays.asList(ModuleViews.KEY_VIEW_EVENT, ModuleViews.KEY_VIEW_EVENT), allQueuedEventKeys());
    }

    /**
     * Saving user properties sends the views queued before them first, without the events consent as
     * with it, so the server gets the views before the properties change.
     * <p>
     * Verifies that with only the views and user profiles consents given, saving a property queues
     * the request with the view before the one with the property, and empties the event queue.
     */
    @Test
    public void savingUserProperties_queuesTheViewsRecordedBeforeThemFirst() {
        Countly.instance().init(TestUtils.getBaseConfig()
            .enableFeatures(Config.Feature.Events, Config.Feature.Views, Config.Feature.UserProfiles)
            .setRequiresConsent(true).setEventQueueSizeToSend(100));
        Countly.onConsent(Config.Feature.Views, Config.Feature.UserProfiles);
        Assert.assertNotNull(Countly.instance().views().startView("home"));

        Countly.instance().userProfile().setProperty("plan", "pro");
        Countly.instance().userProfile().save();

        Map<String, String>[] requests = TestUtils.getCurrentRQ();
        Assert.assertEquals(2, requests.length);
        TestUtils.validateRequiredParams(requests[0]);
        Assert.assertEquals(Collections.singletonList(ModuleViews.KEY_VIEW_EVENT), keys(eventsOf(requests[0])));
        Assert.assertFalse(requests[0].containsKey("user_details"));
        Assert.assertTrue(requests[1].get("user_details").contains("\"plan\":\"pro\""));
        TestUtils.validateEQSize(0);
    }

    /**
     * A view the server does not accept is kept and sent again.
     * <p>
     * Verifies against a local server that answers the first request with an error, with only the
     * views feature enabled, that the request with the first view arrives again before the one with
     * the second view, and that the request queue then drains.
     */
    @Test
    public void aViewTheServerDoesNotAccept_isSentAgain() throws Exception {
        server = new EventServer();
        server.failTheNextAnswer();
        Countly.instance().init(serverConfig().enableFeatures(Config.Feature.Views).setEventQueueSizeToSend(1));

        Assert.assertNotNull(Countly.instance().views().startView("first"));
        server.awaitEvents(1);
        Assert.assertNotNull(Countly.instance().views().startView("second"));

        List<JSONObject> events = server.awaitEvents(3);
        Assert.assertEquals(Arrays.asList("first", "first", "second"), viewNames(events));
        Assert.assertTrue("the request queue never drained", TestUtils.waitFor(WAIT_BUDGET_MS, () -> TestUtils.getCurrentRQ().length == 0));
    }

    /**
     * Content events are custom events, so they still need the events feature.
     * <p>
     * Verifies that with the content feature enabled and the events feature not, the events a content
     * block reports are dropped.
     */
    @Test
    public void contentEvents_stillNeedTheEventsFeature() {
        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.Content).setEventQueueSizeToSend(100));

        SDKCore.instance.module(ModuleContent.class).recordContentEventsInternal("[{\"key\":\"[CLY]_content_shown\",\"sg\":{\"id\":\"content_1\"}}]");

        TestUtils.validateEQSize(0);
        Assert.assertEquals(0, TestUtils.getCurrentRQ().length);
    }

    // endregion
    // region helpers

    /**
     * A configuration pointing at {@link #server}, without SDK behavior settings requests.
     *
     * @return the configuration
     */
    private Config serverConfig() {
        File directory = TestUtils.getTestSDirectory();
        TestUtils.checkSdkStorageRootDirectoryExist(directory);
        return new Config("http://127.0.0.1:" + server.port(), TestUtils.SERVER_APP_KEY, directory)
            .setApplicationVersion(TestUtils.APPLICATION_VERSION)
            .setCustomDeviceId(TestUtils.DEVICE_ID)
            .setNetworkRequestCooldown(0)
            .setNetworkImportantRequestCooldown(0)
            .disableSdkBehaviorSettingsUpdates();
    }

    /**
     * A feedback widget as the widget list of the server describes it.
     *
     * @param type the type of the widget
     * @param id the ID of the widget
     * @return the widget
     */
    private static CountlyFeedbackWidget widget(FeedbackWidgetType type, String id) {
        CountlyFeedbackWidget widget = new CountlyFeedbackWidget();
        widget.type = type;
        widget.widgetId = id;
        widget.name = id + "_name";
        widget.tags = new String[] {};
        return widget;
    }

    /**
     * The keys of the given events, in order.
     *
     * @param events the events
     * @return the keys
     */
    private static List<String> keys(List<JSONObject> events) {
        List<String> keys = new ArrayList<>();
        for (JSONObject event : events) {
            keys.add(event.getString("key"));
        }
        return keys;
    }

    /**
     * The events a request carries, in order.
     *
     * @param request the parameters of the request
     * @return the events, none when the request carries no events parameter
     */
    private static List<JSONObject> eventsOf(Map<String, String> request) {
        List<JSONObject> events = new ArrayList<>();
        String parameter = request == null ? null : request.get("events");
        if (parameter != null) {
            JSONArray array = new JSONArray(parameter);
            for (int i = 0; i < array.length(); i++) {
                events.add(array.getJSONObject(i));
            }
        }
        return events;
    }

    /**
     * The keys of every event waiting to be sent: those in the event queue, then those in the queued
     * requests.
     *
     * @return the keys
     */
    private static List<String> allQueuedEventKeys() {
        List<String> keys = new ArrayList<>(TestUtils.keysInEQ());
        for (Map<String, String> request : TestUtils.getCurrentRQ()) {
            keys.addAll(keys(eventsOf(request)));
        }
        return keys;
    }

    /**
     * One timed event as the deprecated timed events store writes it.
     *
     * @param key the key of the timed event
     * @param logger the logger the event serializes with
     * @return the stored bytes
     * @throws IOException when the bytes cannot be written
     */
    private static byte[] storedTimedEvent(String key, Log logger) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream stream = new ObjectOutputStream(bytes)) {
            stream.writeInt(1);
            stream.writeUTF(key);
            stream.writeUTF(new EventImpl(key, 1, null, null, null, logger, null, null, null, null).toJSON(logger));
        }
        return bytes.toByteArray();
    }

    /**
     * The view names of the given view events, in order.
     *
     * @param events the view events
     * @return the names
     */
    private static List<String> viewNames(List<JSONObject> events) {
        List<String> names = new ArrayList<>();
        for (JSONObject event : events) {
            names.add(event.getJSONObject("segmentation").getString(ModuleViews.KEY_NAME));
        }
        return names;
    }

    /**
     * A local HTTP server standing in for the Countly server: it records every request in the order
     * it arrives and accepts it, except the one a test asks it to answer with an error.
     */
    private static final class EventServer {
        private final HttpServer http;
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final List<Map<String, String>> received = new CopyOnWriteArrayList<>();
        private final AtomicBoolean failNextAnswer = new AtomicBoolean();

        /**
         * Starts the server on a free loopback port.
         *
         * @throws IOException when the server cannot start
         */
        EventServer() throws IOException {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.createContext("/", this::handle);
            http.setExecutor(executor);
            http.start();
        }

        /**
         * Answers the next request with a server error.
         */
        void failTheNextAnswer() {
            failNextAnswer.set(true);
        }

        /**
         * Records a request and answers it.
         *
         * @param exchange the request and its response
         * @throws IOException when the response cannot be written
         */
        private void handle(HttpExchange exchange) throws IOException {
            String query = exchange.getRequestURI().getRawQuery();
            String body = new String(Utils.readStream(exchange.getRequestBody(), null), StandardCharsets.UTF_8);
            received.add(new Params(query != null && !query.isEmpty() ? query : body).map());

            boolean fail = failNextAnswer.getAndSet(false);
            byte[] response = (fail ? "{\"error\":\"not accepted\"}" : "{\"result\":\"Success\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(fail ? 500 : 200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        }

        /**
         * The requests that carried events, in the order they arrived.
         *
         * @return the parameters of each request
         */
        List<Map<String, String>> eventRequests() {
            List<Map<String, String>> withEvents = new ArrayList<>();
            for (Map<String, String> request : received) {
                if (request.containsKey("events")) {
                    withEvents.add(request);
                }
            }
            return withEvents;
        }

        /**
         * Waits until the requests carried at least the given number of events.
         *
         * @param count how many events to wait for
         * @return every event the requests carried, in the order they arrived
         * @throws InterruptedException when the wait is interrupted
         */
        List<JSONObject> awaitEvents(int count) throws InterruptedException {
            Assert.assertTrue("expected " + count + " events, the server got: " + received, TestUtils.waitFor(WAIT_BUDGET_MS, () -> events().size() >= count));
            return events();
        }

        /**
         * Every event the requests carried, in the order they arrived.
         *
         * @return the events
         */
        private List<JSONObject> events() {
            List<JSONObject> events = new ArrayList<>();
            for (Map<String, String> request : received) {
                events.addAll(eventsOf(request));
            }
            return events;
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
         * Stops the server and its handler thread.
         */
        void stop() {
            http.stop(0);
            executor.shutdownNow();
        }

    }

    // endregion
}
