package ly.count.sdk.java.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import ly.count.sdk.java.PredefinedUserPropertyKeys;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static ly.count.sdk.java.internal.ServerConfigBuilder.names;

/**
 * The listing filters and the user property cache limit of the SDK behavior settings, observed
 * through the public API: which custom events, segmentation keys and custom user properties reach
 * the event queue and the request queue, while internal events and predefined user properties pass.
 * <p>
 * Settings requests go through a {@link ModuleConfigurationTests.ServerConfigResponder}, which answers
 * on the calling thread, so the response of the fetch made at init applies before init returns and a
 * later one is pushed by fetching the way the refresh timer does. Networking is off through the
 * settings, so every stored request stays on disk to be read.
 */
@RunWith(JUnit4.class)
public class ModuleConfigurationFiltersTests {

    private ModuleConfigurationTests.ServerConfigResponder server;
    private final List<String> warnings = new CopyOnWriteArrayList<>();

    /**
     * Starts every test from empty storage, without a test module and with a fresh responder.
     */
    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
        SDKCore.testDummyModule = null;
        server = new ModuleConfigurationTests.ServerConfigResponder();
        warnings.clear();
    }

    /**
     * Stops the SDK and clears its data.
     */
    @After
    public void afterTest() {
        Countly.instance().halt();
    }

    // region scenarios

    /**
     * {@code eb} and then {@code ew} decide which custom events are recorded, while internal events
     * always pass.
     * <p>
     * Verifies that with a blacklist, listed keys, spaces and dashes included, are dropped with one
     * warning each, timed events too, while an unlisted key, a view and an internal event listed in
     * the blacklist are recorded; that a response bringing a whitelist replaces the blacklist, so only
     * its keys are recorded, a view still is and a key the blacklist dropped is dropped again; and that
     * an empty whitelist allows every key.
     */
    @Test
    public void eventFilter_blacklistThenWhitelist_decideCustomEventsOnly_andAnEmptyListAllowsEverything() {
        server.respondWith(new ServerConfigBuilder()
            .eventFilterList(names("blocked_event", "event with spaces", "event-with-dashes", ModuleViews.KEY_VIEW_EVENT, "[CLY]_star_rating"), false)
            .networking(false));
        init(configWithWarnings().enableFeatures(Config.Feature.Events, Config.Feature.Views).setEventQueueSizeToSend(100));

        Countly.instance().events().recordEvent("blocked_event");
        Countly.instance().events().recordEvent("event with spaces");
        Countly.instance().events().recordEvent("event-with-dashes", TestUtils.map("colour", "red"));
        Countly.instance().events().startEvent("blocked_event");
        Countly.instance().events().endEvent("blocked_event");
        TestUtils.validateEQSize(0);
        Assert.assertEquals(4, warningsContaining("is filtered out by the event filter"));

        Countly.instance().events().recordEvent("allowed_event");
        Countly.instance().views().startView("home");
        Countly.instance().events().recordEvent("[CLY]_star_rating");
        Assert.assertEquals(Arrays.asList("allowed_event", ModuleViews.KEY_VIEW_EVENT, "[CLY]_star_rating"), keysInEQ());

        push(new ServerConfigBuilder().eventFilterList(names("allowed_event", "another_allowed"), true));
        Countly.instance().events().recordEvent("allowed_event");
        Countly.instance().events().recordEvent("not_in_whitelist");
        Countly.instance().events().recordEvent("another_allowed", 2);
        Countly.instance().events().recordEvent("blocked_event");
        Countly.instance().views().stopViewWithName("home");
        Assert.assertEquals(Arrays.asList("allowed_event", ModuleViews.KEY_VIEW_EVENT, "[CLY]_star_rating",
            "allowed_event", "another_allowed", ModuleViews.KEY_VIEW_EVENT), keysInEQ());
        Assert.assertEquals(6, warningsContaining("is filtered out by the event filter"));

        push(new ServerConfigBuilder().eventFilterList(names(), true));
        Countly.instance().events().recordEvent("not_in_whitelist");
        Countly.instance().events().recordEvent("blocked_event");
        List<String> keys = keysInEQ();
        Assert.assertEquals(Arrays.asList("not_in_whitelist", "blocked_event"), keys.subList(6, keys.size()));
        Assert.assertEquals(6, warningsContaining("is filtered out by the event filter"));
        Assert.assertEquals(0, TestUtils.getCurrentRQ().length);
    }

    /**
     * {@code sb} and {@code esb}, then {@code sw} and {@code esw}, remove segmentation keys of custom
     * events on a copy of the map the caller passed.
     * <p>
     * Verifies on the wire that the segmentation blacklist and then the blacklist of each event apply
     * together, that an event without rules keeps everything but the general blacklist, that the
     * caller's unmodifiable map is left as it was and can be passed again, that an internal event and
     * a view keep every key, that empty and missing segmentation still record, and that a response
     * bringing whitelists replaces both blacklists, the general whitelist applying before the one of
     * the event.
     */
    @Test
    public void segmentationFilters_generalThenPerEvent_filterACopyOfTheCallersMap_andNeverTouchInternalEvents() {
        Map<String, Set<String>> perEvent = new LinkedHashMap<>();
        perEvent.put("event1", names("blocked_for_event1"));
        perEvent.put("event2", names("key_b"));
        server.respondWith(new ServerConfigBuilder()
            .segmentationFilterList(names("general_blocked"), false)
            .eventSegmentationFilterMap(perEvent, false)
            .eventQueueSize(1)
            .networking(false));
        init(TestUtils.getConfigSdkBehaviorSettings().enableFeatures(Config.Feature.Events, Config.Feature.Views));

        Map<String, Object> callers = Collections.unmodifiableMap(TestUtils.map("general_blocked", "g", "blocked_for_event1", "e1", "key_a", "a", "key_b", "b"));
        Countly.instance().events().recordEvent("event1", callers);
        TestUtils.letTheClockCatchUp();
        Countly.instance().events().recordEvent("event2", callers);
        TestUtils.letTheClockCatchUp();
        Countly.instance().events().recordEvent("event3", callers);
        TestUtils.letTheClockCatchUp();
        Countly.instance().events().recordEvent("[CLY]_star_rating", callers);
        TestUtils.letTheClockCatchUp();
        Countly.instance().views().startView("home", TestUtils.map("general_blocked", "v"));
        TestUtils.letTheClockCatchUp();
        Countly.instance().events().recordEvent("event1", new HashMap<>());
        TestUtils.letTheClockCatchUp();
        Countly.instance().events().recordEvent("event1");
        TestUtils.letTheClockCatchUp();

        List<EventImpl> sent = sentEvents();
        Assert.assertEquals(keysOf(sent).toString(), 7, sent.size());
        assertEvent(sent.get(0), "event1", TestUtils.map("key_a", "a", "key_b", "b"));
        assertEvent(sent.get(1), "event2", TestUtils.map("blocked_for_event1", "e1", "key_a", "a"));
        assertEvent(sent.get(2), "event3", TestUtils.map("blocked_for_event1", "e1", "key_a", "a", "key_b", "b"));
        assertEvent(sent.get(3), "[CLY]_star_rating", callers);
        Assert.assertEquals(ModuleViews.KEY_VIEW_EVENT, sent.get(4).key);
        Assert.assertEquals("v", sent.get(4).segmentation.get("general_blocked"));
        Assert.assertEquals("home", sent.get(4).segmentation.get(ModuleViews.KEY_NAME));
        Assert.assertEquals("event1", sent.get(5).key);
        Assert.assertTrue(sent.get(5).segmentation == null || sent.get(5).segmentation.isEmpty());
        Assert.assertEquals("event1", sent.get(6).key);
        Assert.assertTrue(sent.get(6).segmentation == null || sent.get(6).segmentation.isEmpty());
        Assert.assertEquals(TestUtils.map("general_blocked", "g", "blocked_for_event1", "e1", "key_a", "a", "key_b", "b"), callers);

        Map<String, Set<String>> perEventWhitelist = new LinkedHashMap<>();
        perEventWhitelist.put("event1", names("key_a"));
        push(new ServerConfigBuilder()
            .segmentationFilterList(names("key_a", "key_b", "blocked_for_event1"), true)
            .eventSegmentationFilterMap(perEventWhitelist, true));
        Countly.instance().events().recordEvent("event1", callers);
        TestUtils.letTheClockCatchUp();
        Countly.instance().events().recordEvent("event2", callers);
        TestUtils.letTheClockCatchUp();
        Countly.instance().events().recordEvent("event4", TestUtils.map("key_b", "b", "other", "o"));
        TestUtils.letTheClockCatchUp();

        sent = sentEvents();
        Assert.assertEquals(keysOf(sent).toString(), 10, sent.size());
        assertEvent(sent.get(7), "event1", TestUtils.map("key_a", "a"));
        assertEvent(sent.get(8), "event2", TestUtils.map("blocked_for_event1", "e1", "key_a", "a", "key_b", "b"));
        assertEvent(sent.get(9), "event4", TestUtils.map("key_b", "b"));
        Assert.assertEquals(4, callers.size());
    }

    /**
     * {@code upb} and then {@code upw} decide which custom user properties are set and modified,
     * while predefined properties are never filtered.
     * <p>
     * Verifies on the wire that with a blacklist listing custom keys and predefined ones, the
     * predefined name and email are still sent while a listed custom key is dropped from a batch,
     * from a single set and from each of the nine modifications, with one warning per dropped call,
     * and every unlisted key keeps its set or modification; and that a response bringing a whitelist
     * keeps only its custom keys while a predefined key outside it is still sent.
     */
    @Test
    public void userPropertyFilter_appliesToCustomKeysOfEverySetAndModification_whilePredefinedKeysBypassIt() {
        server.respondWith(new ServerConfigBuilder()
            .userPropertyFilterList(names("blocked_prop", "blocked_counter", PredefinedUserPropertyKeys.NAME, PredefinedUserPropertyKeys.EMAIL), false)
            .networking(false));
        init(configWithWarnings());

        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put(PredefinedUserPropertyKeys.NAME, "John");
        batch.put(PredefinedUserPropertyKeys.EMAIL, "john@example.com");
        batch.put("blocked_prop", "value1");
        batch.put("allowed_prop", "value2");
        Countly.instance().userProfile().setProperties(Collections.unmodifiableMap(batch));
        Countly.instance().userProfile().setProperty("blocked_prop", "value3");
        modifyWithEveryOperation("blocked_counter");
        modifyWithEveryOperation("allowed_counter");
        Countly.instance().userProfile().save();
        TestUtils.letTheClockCatchUp();

        JSONObject details = userDetails(0);
        Assert.assertEquals("John", details.getString(PredefinedUserPropertyKeys.NAME));
        Assert.assertEquals("john@example.com", details.getString(PredefinedUserPropertyKeys.EMAIL));
        JSONObject custom = details.getJSONObject(ModuleUserProfile.CUSTOM_KEY);
        Assert.assertEquals(names("allowed_prop", "allowed_counter"), custom.keySet());
        Assert.assertEquals("value2", custom.getString("allowed_prop"));
        JSONObject counter = custom.getJSONObject("allowed_counter");
        Assert.assertEquals(names("$inc", "$mul", "$max", "$min", "$setOnce", "$push", "$addToSet", "$pull"), counter.keySet());
        Assert.assertEquals(5, counter.getInt("$inc"));
        Assert.assertEquals(11, warningsContaining("is filtered out by the user property filter"));

        push(new ServerConfigBuilder().userPropertyFilterList(names("plan", "visits"), true));
        Countly.instance().userProfile().setProperty("plan", "gold");
        Countly.instance().userProfile().setProperty("allowed_prop", "not_listed_now");
        Countly.instance().userProfile().setProperty(PredefinedUserPropertyKeys.USERNAME, "jdoe");
        Countly.instance().userProfile().increment("visits");
        Countly.instance().userProfile().increment("allowed_counter");
        Countly.instance().userProfile().save();
        TestUtils.letTheClockCatchUp();

        details = userDetails(1);
        Assert.assertEquals("jdoe", details.getString(PredefinedUserPropertyKeys.USERNAME));
        custom = details.getJSONObject(ModuleUserProfile.CUSTOM_KEY);
        Assert.assertEquals(names("plan", "visits"), custom.keySet());
        Assert.assertEquals("gold", custom.getString("plan"));
        Assert.assertEquals(1, custom.getJSONObject("visits").getInt("$inc"));
        Assert.assertEquals(13, warningsContaining("is filtered out by the user property filter"));
    }

    /**
     * {@code upcl} caps the pending custom properties and the keys with pending modifications apart.
     * <p>
     * Verifies that without the setting any number of custom properties is sent; that with it, a
     * batch over the limit keeps its newest keys, predefined properties are neither counted nor
     * dropped, updating a pending key does not count again, a new key drops the oldest one, and each
     * drop warns; that modifications count distinct keys, so repeating one does not count, and going
     * over the limit drops every modification of the oldest key; that the request carries exactly
     * what was kept; and that a save starts the count again.
     */
    @Test
    public void userPropertyCacheLimit_capsPendingCustomSetsAndModifiedKeysApart_droppingTheOldestFirst() {
        server.respondWith(new ServerConfigBuilder().networking(false));
        init(configWithWarnings());
        Assert.assertEquals(ModuleConfiguration.NO_LIMIT, provider().getUserPropertyCacheLimit());

        for (int i = 1; i <= 150; i++) {
            Countly.instance().userProfile().setProperty("bulk_" + i, i);
        }
        Countly.instance().userProfile().save();
        TestUtils.letTheClockCatchUp();
        Assert.assertEquals(150, userDetails(0).getJSONObject(ModuleUserProfile.CUSTOM_KEY).length());

        push(new ServerConfigBuilder().userPropertyCacheLimit(3));
        Map<String, Object> batch = new LinkedHashMap<>();
        for (int i = 1; i <= 5; i++) {
            batch.put("prop" + i, "value" + i);
        }
        batch.put(PredefinedUserPropertyKeys.NAME, "John");
        batch.put(PredefinedUserPropertyKeys.EMAIL, "john@example.com");
        batch.put(PredefinedUserPropertyKeys.USERNAME, "jdoe");
        Countly.instance().userProfile().setProperties(batch);
        Assert.assertEquals(1, warningsContaining("over the cache limit of [3]"));
        Countly.instance().userProfile().setProperty("prop3", "updated");
        Assert.assertEquals(1, warningsContaining("over the cache limit of [3]"));
        Countly.instance().userProfile().setProperty("prop6", "value6");
        Assert.assertEquals(2, warningsContaining("over the cache limit of [3]"));

        Countly.instance().userProfile().incrementBy("counter1", 1);
        Countly.instance().userProfile().incrementBy("counter2", 2);
        Countly.instance().userProfile().incrementBy("counter1", 10);
        Countly.instance().userProfile().incrementBy("counter3", 3);
        Assert.assertEquals(2, warningsContaining("over the cache limit of [3]"));
        Countly.instance().userProfile().multiply("factor", 2);
        Countly.instance().userProfile().push("list", "x");
        Assert.assertEquals(4, warningsContaining("over the cache limit of [3]"));
        Countly.instance().userProfile().save();
        TestUtils.letTheClockCatchUp();

        JSONObject details = userDetails(1);
        Assert.assertEquals("John", details.getString(PredefinedUserPropertyKeys.NAME));
        Assert.assertEquals("john@example.com", details.getString(PredefinedUserPropertyKeys.EMAIL));
        Assert.assertEquals("jdoe", details.getString(PredefinedUserPropertyKeys.USERNAME));
        JSONObject custom = details.getJSONObject(ModuleUserProfile.CUSTOM_KEY);
        Assert.assertEquals(names("prop4", "prop5", "prop6", "counter3", "factor", "list"), custom.keySet());
        Assert.assertEquals("value4", custom.getString("prop4"));
        Assert.assertEquals(3, custom.getJSONObject("counter3").getInt("$inc"));
        Assert.assertEquals(2.0, custom.getJSONObject("factor").getDouble("$mul"), 0.0);
        Assert.assertEquals("x", custom.getJSONObject("list").getString("$push"));

        Countly.instance().userProfile().setProperties(TestUtils.map("fresh1", "a", "fresh2", "b", "fresh3", "c"));
        Countly.instance().userProfile().save();
        TestUtils.letTheClockCatchUp();
        Assert.assertEquals(3, userDetails(2).getJSONObject(ModuleUserProfile.CUSTOM_KEY).length());
        Assert.assertEquals(4, warningsContaining("over the cache limit of [3]"));
    }

    // endregion
    // region helpers

    /**
     * Initializes the SDK with {@link #server} answering its settings requests.
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
        server.respondWith(builder);
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
     * The settings test configuration with a listener that keeps every warning.
     *
     * @return the configuration
     */
    private Config configWithWarnings() {
        return TestUtils.getConfigSdkBehaviorSettings().setLogListener((message, level) -> {
            if (level == Config.LoggingLevel.WARN) {
                warnings.add(message);
            }
        });
    }

    /**
     * How many warnings so far contain a text.
     *
     * @param text the text
     * @return the number of warnings
     */
    private int warningsContaining(String text) {
        int count = 0;
        for (String warning : warnings) {
            if (warning.contains(text)) {
                count++;
            }
        }
        return count;
    }

    /**
     * The keys of the events in the event queue, in queue order.
     *
     * @return the keys
     */
    private static List<String> keysInEQ() {
        List<String> keys = new ArrayList<>();
        for (EventImpl event : TestUtils.getCurrentEQ()) {
            keys.add(event.key);
        }
        return keys;
    }

    /**
     * Every event of every stored request, in queue order.
     *
     * @return the events
     */
    private static List<EventImpl> sentEvents() {
        List<EventImpl> events = new ArrayList<>();
        int requestCount = TestUtils.getCurrentRQ().length;
        for (int i = 0; i < requestCount; i++) {
            events.addAll(TestUtils.readEventsFromRequest(i, TestUtils.DEVICE_ID));
        }
        return events;
    }

    /**
     * The keys of events, in order.
     *
     * @param events the events
     * @return the keys
     */
    private static List<String> keysOf(List<EventImpl> events) {
        List<String> keys = new ArrayList<>();
        for (EventImpl event : events) {
            keys.add(event.key);
        }
        return keys;
    }

    /**
     * Asserts the key and the whole segmentation of an event.
     *
     * @param event the event
     * @param key the expected key
     * @param segmentation the expected segmentation
     */
    private static void assertEvent(EventImpl event, String key, Map<String, Object> segmentation) {
        Assert.assertEquals(key, event.key);
        Assert.assertEquals(segmentation, new HashMap<>(event.segmentation));
    }

    /**
     * The {@code user_details} object of a stored request.
     *
     * @param requestIndex the index of the request in the queue
     * @return the user details
     */
    private static JSONObject userDetails(int requestIndex) {
        Map<String, String>[] requests = TestUtils.getCurrentRQ();
        Assert.assertTrue("no request at index " + requestIndex + " in " + Arrays.toString(requests), requests.length > requestIndex);
        return new JSONObject(requests[requestIndex].get("user_details"));
    }

    /**
     * Applies each of the nine modifications of the user profile interface to one key.
     *
     * @param key the custom user property key
     */
    private static void modifyWithEveryOperation(String key) {
        ModuleUserProfile.UserProfile profile = Countly.instance().userProfile();
        profile.increment(key);
        profile.incrementBy(key, 4);
        profile.multiply(key, 3);
        profile.saveMax(key, 10);
        profile.saveMin(key, 1);
        profile.setOnce(key, "first");
        profile.push(key, "a");
        profile.pushUnique(key, "b");
        profile.pull(key, "c");
    }

    // endregion
}
