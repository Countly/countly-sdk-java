package ly.count.sdk.java.internal;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import ly.count.sdk.java.Crash;
import ly.count.sdk.java.CrashProcessor;
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
 * The SDK internal limits of the SDK behavior settings, {@code lkl}, {@code lvs}, {@code lsv},
 * {@code ltlpt} and {@code ltl}, with the breadcrumb limit {@code lbc}, observed through the public
 * API: what custom events, timed events, views, user properties, crashes and feedback widget results
 * send once the settings set limits, how a later response changes them, and that nothing is cut
 * while the settings set none.
 * <p>
 * Settings requests go through a {@link ModuleConfigurationTests.ServerConfigResponder}, which answers
 * on the calling thread, so the response of the fetch made at init applies before init returns and a
 * later one is pushed by fetching the way the refresh timer does. Networking is off through the
 * settings wherever they are fetched, so every stored request stays on disk to be read.
 */
@RunWith(JUnit4.class)
public class ModuleConfigurationLimitsTests {

    private ModuleConfigurationTests.ServerConfigResponder server;
    private final List<String> warnings = new CopyOnWriteArrayList<>();

    /**
     * Starts every test from empty storage, without a test module, with a fresh responder and with
     * the crash processor passing crashes through.
     */
    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
        SDKCore.testDummyModule = null;
        server = new ModuleConfigurationTests.ServerConfigResponder();
        warnings.clear();
        DumpingCrashProcessor.replaceWithDump = false;
        DumpingCrashProcessor.framesPerThread = 5;
    }

    /**
     * Stops the SDK and clears its data.
     */
    @After
    public void afterTest() {
        Countly.instance().halt();
        DumpingCrashProcessor.replaceWithDump = false;
    }

    // region scenarios

    /**
     * {@code lkl}, {@code lvs} and {@code lsv} cut custom events after the listing filters and the
     * journey trigger match, which see the full key, while an internal event only gets the string
     * values of its widget result cut.
     * <p>
     * Verifies on the wire that a journey trigger listed by its full key sends the queue at once, that
     * its key is cut to 5 characters, that a segmentation key blacklisted by its full name and an
     * entry of an unsupported type are removed before the entry limit counts while a key blacklisted
     * only by its truncated form is kept, that of two keys cut to the same key the one that sorts last
     * keeps its value, that string values are cut to 6 characters, that past 2 entries the keys that
     * sort last are dropped and that the caller's map is left as it was; that an event whose truncated
     * key is whitelisted but whose full
     * key is not is dropped; that a timed event is cut the same way; and that a feedback widget
     * result keeps every key and entry and the values the SDK adds, only its own strings being cut.
     */
    @Test
    public void customEvents_areCutAfterTheFiltersAndTheJourneyTriggerSawTheFullKey_andInternalEventsOnlyGetWidgetValuesCut() {
        server.respondWith(smallLimits()
            .eventFilterList(names("purchase_completed", "timed_checkout", "check"), true)
            .segmentationFilterList(names("internal_notes", "payme"), false)
            .journeyTriggerEvents(names("purchase_completed")));
        init(configWithWarnings().enableFeatures(Config.Feature.Events, Config.Feature.Feedback).setEventQueueSizeToSend(100));

        Map<String, Object> segmentation = new HashMap<>();
        segmentation.put("payment_method", "credit_card");
        segmentation.put("category_a", "books");
        segmentation.put("category_b", "movies");
        segmentation.put("internal_notes", "removed_by_the_filter");
        segmentation.put("aa_unsupported", new Object());
        segmentation.put("zz_note", "dropped_by_the_entry_limit");
        Map<String, Object> callers = Collections.unmodifiableMap(new HashMap<>(segmentation));
        Countly.instance().events().recordEvent("purchase_completed", callers, 2, 9.99);
        TestUtils.letTheClockCatchUp();

        List<EventImpl> sent = sentEvents();
        Assert.assertEquals(keysOf(sent).toString(), 1, sent.size());
        assertEvent(sent.get(0), "purch", TestUtils.map("categ", "movies", "payme", "credit"));
        Assert.assertEquals(2, sent.get(0).count);
        Assert.assertEquals(9.99, sent.get(0).sum, 0.0);
        Assert.assertEquals(segmentation, callers);
        Assert.assertEquals(1, warningsContaining("[ModuleEvents] recordEventInternal, the key [purchase_completed] is longer than the key length limit of [5]"));
        Assert.assertEquals(1, warningsContaining("its value replaces that of [categ]"));
        Assert.assertEquals(1, warningsContaining("the segmentation has [3] entries, over the limit of [2]"));

        Countly.instance().events().recordEvent("checkout_started");
        Assert.assertEquals(1, warningsContaining("is filtered out by the event filter"));

        Countly.instance().events().startEvent("timed_checkout");
        Countly.instance().events().endEvent("timed_checkout", TestUtils.map("duration_bucket", "long_running", "attempt", 2), 1, null);

        CountlyFeedbackWidget widget = new CountlyFeedbackWidget();
        widget.widgetId = "rating_widget_identifier";
        widget.type = FeedbackWidgetType.rating;
        widget.name = "rating";
        widget.tags = new String[0];
        Map<String, Object> widgetResult = new HashMap<>();
        widgetResult.put("rating", 4);
        widgetResult.put("comment", "a comment longer than six characters");
        widgetResult.put("email", "user@example.com");
        widgetResult.put("contactMe", true);
        Countly.instance().feedback().reportFeedbackWidgetManually(widget, null, widgetResult);

        List<EventImpl> queued = TestUtils.getCurrentEQ();
        Assert.assertEquals(keysOf(queued).toString(), 2, queued.size());
        assertEvent(queued.get(0), "timed", TestUtils.map("attem", 2, "durat", "long_r"));
        assertEvent(queued.get(1), FeedbackWidgetType.rating.eventKey, TestUtils.map("platform", WidgetUrlBuilder.PLATFORM,
            "app_version", TestUtils.APPLICATION_VERSION, "widget_id", "rating_widget_identifier",
            "rating", 4, "comment", "a comm", "email", "user@e", "contactMe", true));
        Assert.assertEquals(1, TestUtils.getCurrentRQ().length);
    }

    /**
     * {@code lkl}, {@code lvs} and {@code lsv} cut the name and the segmentation of views, while the
     * keys the SDK adds are neither cut nor counted.
     * <p>
     * Verifies on the wire that the name is cut to 5 characters on the start and the end event, that
     * the global, the added and the given segmentation are merged and then cut, that a key cut to a
     * reserved key is removed so an end event never carries {@code visit}, that an entry of an
     * unsupported type takes no place under the entry limit, that past 2 entries the keys that sort
     * last are dropped while {@code name}, {@code visit}, {@code start} and
     * {@code segment} stay with their values uncut, that a journey trigger view matches the name as
     * sent, and that a view started under a long name is still stopped by that name.
     */
    @Test
    public void views_nameAndSegmentationAreCut_whileTheKeysTheSdkAddsAreNeitherCutNorCounted() {
        server.respondWith(smallLimits().journeyTriggerViews(names("Setti")));
        init(configWithWarnings().enableFeatures(Config.Feature.Events, Config.Feature.Views).setEventQueueSizeToSend(100));
        String platform = SDKCore.instance.config.getSdkPlatform();
        Countly.instance().views().setGlobalViewSegmentation(TestUtils.map("global_key", "global_value"));

        String viewId = Countly.instance().views().startView("SettingsScreen", TestUtils.map("visitor_type", "returning", "aa_first", 1));
        TestUtils.letTheClockCatchUp();
        Countly.instance().views().addSegmentationToViewWithID(viewId, TestUtils.map("added_key", "added_value", "bb_second", 2));
        Countly.instance().views().stopViewWithID(viewId, TestUtils.map("visitor_type", "new_visitor", "cc_third", "third_value", "aa_unsupported", new int[] { 1 }));
        TestUtils.letTheClockCatchUp();

        List<EventImpl> sent = sentEvents();
        Assert.assertEquals(keysOf(sent).toString(), 2, sent.size());
        assertEvent(sent.get(0), ModuleViews.KEY_VIEW_EVENT, TestUtils.map("aa_fi", 1, "globa", "global",
            ModuleViews.KEY_NAME, "Setti", ModuleViews.KEY_VISIT, "1", ModuleViews.KEY_START, "1", ModuleViews.KEY_SEGMENT, platform));
        assertEvent(sent.get(1), ModuleViews.KEY_VIEW_EVENT, TestUtils.map("added", "added_", "bb_se", 2,
            ModuleViews.KEY_NAME, "Setti", ModuleViews.KEY_SEGMENT, platform));
        Assert.assertEquals(viewId, sent.get(1).id);
        Assert.assertEquals(2, warningsContaining("[ModuleViews] createViewEventSegmentation, a key truncated to the reserved key [visit] is removed"));

        Countly.instance().views().startView("Home");
        Countly.instance().views().startView("ProfileScreen");
        Countly.instance().views().stopViewWithName("ProfileScreen");

        List<EventImpl> queued = TestUtils.getCurrentEQ();
        Assert.assertEquals(keysOf(queued).toString(), 3, queued.size());
        assertEvent(queued.get(0), ModuleViews.KEY_VIEW_EVENT, TestUtils.map("globa", "global",
            ModuleViews.KEY_NAME, "Home", ModuleViews.KEY_VISIT, "1", ModuleViews.KEY_SEGMENT, platform));
        Assert.assertEquals("Profi", queued.get(1).segmentation.get(ModuleViews.KEY_NAME));
        Assert.assertEquals("1", queued.get(1).segmentation.get(ModuleViews.KEY_VISIT));
        assertEvent(queued.get(2), ModuleViews.KEY_VIEW_EVENT, TestUtils.map("globa", "global",
            ModuleViews.KEY_NAME, "Profi", ModuleViews.KEY_SEGMENT, platform));
        Assert.assertEquals(2, TestUtils.getCurrentRQ().length);
    }

    /**
     * {@code lkl}, {@code lvs} and {@code lsv} cut custom user properties, sets and modifications,
     * while predefined properties only get their string values cut and the picture is never cut. The
     * limits in effect when the request is built apply.
     * <p>
     * Verifies on the wire that predefined keys stay whole, that the name, email and organization
     * are cut to 6 characters while the picture URL, the gender and the birth year are sent as they
     * are; that custom keys are cut to 5 characters, the key that sorts last winning a collision,
     * string values cut to 6, and past 2 sets the keys that sort last are dropped; that modification
     * keys and string operands are cut the same way and never count against the entry limit; and
     * that a property set before a response lowered the key length limit is cut by the new limit.
     */
    @Test
    public void userProperties_customKeysAndValuesAreCutForSetsAndModifications_predefinedOnlyTheirValuesAndNeverThePicture() {
        server.respondWith(smallLimits());
        init(configWithWarnings());

        String pictureUrl = "https://example.com/pictures/avatar.png";
        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put(PredefinedUserPropertyKeys.NAME, "Johnathan Doe");
        batch.put(PredefinedUserPropertyKeys.EMAIL, "johnathan@example.com");
        batch.put(PredefinedUserPropertyKeys.ORGANIZATION, "Countly Ltd");
        batch.put(PredefinedUserPropertyKeys.PICTURE_PATH, pictureUrl);
        batch.put(PredefinedUserPropertyKeys.GENDER, "M");
        batch.put(PredefinedUserPropertyKeys.BIRTH_YEAR, 1990);
        batch.put("favourite_colour", "turquoise");
        batch.put("favourite_food", "spaghetti");
        batch.put("aaa_first", 1);
        batch.put("zzz_last", "dropped");
        Countly.instance().userProfile().setProperties(batch);
        Countly.instance().userProfile().increment("login_count_total");
        Countly.instance().userProfile().push("visited_pages", "homepage_long");
        Countly.instance().userProfile().setOnce("first_seen_at", "2024-01-01");
        Countly.instance().userProfile().save();
        TestUtils.letTheClockCatchUp();

        JSONObject details = userDetails(0);
        Assert.assertEquals("Johnat", details.getString(PredefinedUserPropertyKeys.NAME));
        Assert.assertEquals("johnat", details.getString(PredefinedUserPropertyKeys.EMAIL));
        Assert.assertEquals("Countl", details.getString(PredefinedUserPropertyKeys.ORGANIZATION));
        Assert.assertEquals(pictureUrl, details.getString(PredefinedUserPropertyKeys.PICTURE));
        Assert.assertEquals("M", details.getString(PredefinedUserPropertyKeys.GENDER));
        Assert.assertEquals(1990, details.getInt(PredefinedUserPropertyKeys.BIRTH_YEAR));
        JSONObject custom = details.getJSONObject(ModuleUserProfile.CUSTOM_KEY);
        Assert.assertEquals(names("aaa_f", "favou", "login", "visit", "first"), custom.keySet());
        Assert.assertEquals(1, custom.getInt("aaa_f"));
        Assert.assertEquals("spaghe", custom.getString("favou"));
        Assert.assertEquals(1, custom.getJSONObject("login").getInt("$inc"));
        Assert.assertEquals("homepa", custom.getJSONObject("visit").getString("$push"));
        Assert.assertEquals("2024-0", custom.getJSONObject("first").getString("$setOnce"));
        Assert.assertEquals(1, warningsContaining("[ModuleUserProfile] perform, the segmentation has [3] entries, over the limit of [2]"));

        Countly.instance().userProfile().setProperty("abc_property", "value");
        Countly.instance().userProfile().incrementBy("xyz_counter", 3);
        push(new ServerConfigBuilder().keyLengthLimit(3));
        Countly.instance().userProfile().save();
        TestUtils.letTheClockCatchUp();

        custom = userDetails(1).getJSONObject(ModuleUserProfile.CUSTOM_KEY);
        Assert.assertEquals(names("abc", "xyz"), custom.keySet());
        Assert.assertEquals("value", custom.getString("abc"));
        Assert.assertEquals(3, custom.getJSONObject("xyz").getInt("$inc"));
    }

    /**
     * {@code ltl} cuts every stack trace line, {@code ltlpt} the lines of each thread of a trace dump,
     * {@code lkl}, {@code lvs} and {@code lsv} the custom segments, and {@code lvs} and {@code lbc}
     * the breadcrumbs, including what a crash processor adds.
     * <p>
     * Verifies on the wire that a handled exception keeps every line of its stack trace, each cut to
     * 20 characters, that its segments are cut to 5 character keys and 6 character values with the 2
     * keys that sort first kept, and that only the 2 newest breadcrumbs are sent, cut to 6 characters;
     * and that when a crash processor replaces the stack trace with a dump of two threads and sets its
     * own segments and breadcrumbs, each thread keeps its 3 top lines, the lines are cut to 20
     * characters and the segments and breadcrumbs it set are cut as well.
     */
    @Test
    public void crashes_stackTraceLinesSegmentsAndBreadcrumbsAreCut_includingWhatACrashProcessorAdds() {
        server.respondWith(smallLimits().breadcrumbLimit(2));
        init(configWithWarnings().enableFeatures(Config.Feature.CrashReporting).setCrashProcessorClass(DumpingCrashProcessor.class));

        Countly.instance().crashes().addCrashBreadcrumb("first_breadcrumb");
        Countly.instance().crashes().addCrashBreadcrumb("second_breadcrumb");
        Countly.instance().crashes().addCrashBreadcrumb("third_breadcrumb");
        Exception exception = new Exception("a message longer than twenty characters");
        Countly.instance().crashes().recordHandledException(exception, TestUtils.map("screen_name", "checkout_page", "retry_count", 3, "zz_last", "value"));
        TestUtils.letTheClockCatchUp();

        DumpingCrashProcessor.replaceWithDump = true;
        Countly.instance().crashes().recordUnhandledException(new RuntimeException("dumped"));
        TestUtils.letTheClockCatchUp();

        List<JSONObject> crashes = crashRequests();
        Assert.assertEquals(2, crashes.size());

        JSONObject handled = crashes.get(0);
        String fullStackTrace = stackTraceOf(exception);
        Assert.assertTrue(lineCount(fullStackTrace) > 3);
        Assert.assertEquals(truncateLines(fullStackTrace, 20), handled.getString("_error"));
        Assert.assertEquals(lineCount(fullStackTrace), lineCount(handled.getString("_error")));
        Assert.assertTrue(handled.getBoolean("_nonfatal"));
        Assert.assertEquals("second\nthird_", handled.getString("_logs"));
        JSONObject segments = handled.getJSONObject("_custom");
        Assert.assertEquals(names("retry", "scree"), segments.keySet());
        Assert.assertEquals(3, segments.getInt("retry"));
        Assert.assertEquals("checko", segments.getString("scree"));

        JSONObject dumped = crashes.get(1);
        String frameLine = "\tat ly.count.Example\n";
        String expectedDump = "Thread [main]:" + System.lineSeparator() + frameLine + frameLine + frameLine + "\n\n"
            + "Thread [worker-threa\n" + frameLine + frameLine + frameLine + "\n\n";
        Assert.assertEquals("anr", dumped.getString("_type"));
        Assert.assertFalse(dumped.getBoolean("_nonfatal"));
        Assert.assertEquals(expectedDump, dumped.getString("_error"));
        segments = dumped.getJSONObject("_custom");
        Assert.assertEquals(names("anoth", "proce"), segments.keySet());
        Assert.assertEquals("anothe", segments.getString("anoth"));
        Assert.assertEquals("proces", segments.getString("proce"));
        Assert.assertEquals("proces", dumped.getString("_logs"));
        Assert.assertEquals(2, warningsContaining("[ModuleCrash] onCrash, the stack trace of the thread"));
    }

    /**
     * A response that changes a limit applies from the next call, lowering or raising it.
     * <p>
     * Verifies that an event recorded before any limit is sent whole, that after a response brings
     * key and value limits the same call is cut by them, that a later response lowering the key
     * length and adding an entry limit is merged over the value limit it leaves out, that one raising
     * every limit sends the event whole again from the caller's untouched map, and that breadcrumbs
     * and stack trace lines follow the limits in effect when each breadcrumb is added and when the
     * crash is recorded.
     */
    @Test
    public void aResponseChangingTheLimits_appliesFromTheNextCall() {
        server.respondWith(new ServerConfigBuilder().networking(false));
        init(configWithWarnings().enableFeatures(Config.Feature.Events, Config.Feature.CrashReporting).setEventQueueSizeToSend(100));
        Map<String, Object> segmentation = TestUtils.map("segment_key", "segment_value", "second", 2);

        Countly.instance().events().recordEvent("event_key", segmentation);
        push(new ServerConfigBuilder().keyLengthLimit(8).valueSizeLimit(4));
        Countly.instance().events().recordEvent("event_key", segmentation);
        push(new ServerConfigBuilder().keyLengthLimit(3).segmentationValuesLimit(1));
        Assert.assertEquals(4, provider().getMaxValueSize());
        Countly.instance().events().recordEvent("event_key", segmentation);
        push(new ServerConfigBuilder().keyLengthLimit(64).valueSizeLimit(64).segmentationValuesLimit(64));
        Countly.instance().events().recordEvent("event_key", segmentation);

        List<EventImpl> queued = TestUtils.getCurrentEQ();
        Assert.assertEquals(keysOf(queued).toString(), 4, queued.size());
        assertEvent(queued.get(0), "event_key", segmentation);
        assertEvent(queued.get(1), "event_ke", TestUtils.map("segment_", "segm", "second", 2));
        assertEvent(queued.get(2), "eve", TestUtils.map("sec", 2));
        assertEvent(queued.get(3), "event_key", segmentation);

        push(new ServerConfigBuilder().valueSizeLimit(4));
        Countly.instance().crashes().addCrashBreadcrumb("alpha_breadcrumb");
        push(new ServerConfigBuilder().breadcrumbLimit(1).traceLengthLimit(12));
        Countly.instance().crashes().addCrashBreadcrumb("omega_breadcrumb");
        Exception exception = new Exception("recorded after the change");
        Countly.instance().crashes().recordHandledException(exception);
        TestUtils.letTheClockCatchUp();

        List<JSONObject> crashes = crashRequests();
        Assert.assertEquals(1, crashes.size());
        Assert.assertEquals("omeg", crashes.get(0).getString("_logs"));
        Assert.assertEquals(truncateLines(stackTraceOf(exception), 12), crashes.get(0).getString("_error"));
    }

    /**
     * With a live response that sets no limit, nothing any feature sends is cut, however long or wide.
     * <p>
     * Verifies that the provider reports no key, value, entry, lines per thread and line length limit
     * and the developer breadcrumb limit, and then the same as
     * {@link #assertNothingIsCut()}.
     */
    @Test
    public void withSettingsThatSetNoLimit_nothingIsCut() {
        server.respondWith(new ServerConfigBuilder().networking(false));
        init(configWithWarnings().enableFeatures(Config.Feature.Events, Config.Feature.Views, Config.Feature.CrashReporting, Config.Feature.Feedback)
            .setEventQueueSizeToSend(1000).setCrashProcessorClass(DumpingCrashProcessor.class));

        ConfigurationProvider provider = provider();
        Assert.assertEquals(ModuleConfiguration.NO_LIMIT, provider.getMaxKeyLength());
        Assert.assertEquals(ModuleConfiguration.NO_LIMIT, provider.getMaxValueSize());
        Assert.assertEquals(ModuleConfiguration.NO_LIMIT, provider.getMaxSegmentationValues());
        Assert.assertEquals(ModuleConfiguration.NO_LIMIT, provider.getMaxStackTraceLinesPerThread());
        Assert.assertEquals(ModuleConfiguration.NO_LIMIT, provider.getMaxStackTraceLineLength());
        Assert.assertEquals(100, provider.getMaxBreadcrumbCount());

        assertNothingIsCut();
    }

    /**
     * Without any SDK behavior settings, the settings requests being disabled as for an upgrading app
     * that never received any, nothing any feature sends is cut, however long or wide.
     * <p>
     * Verifies the same as {@link #assertNothingIsCut()}.
     */
    @Test
    public void withoutSettings_nothingIsCut() {
        Countly.instance().init(TestUtils.getBaseConfig().setLogListener(this::keepWarning)
            .enableFeatures(Config.Feature.Events, Config.Feature.Views, Config.Feature.CrashReporting, Config.Feature.Feedback)
            .setEventQueueSizeToSend(1000).setCrashProcessorClass(DumpingCrashProcessor.class));

        assertNothingIsCut();
    }

    // endregion
    // region helpers

    /**
     * Records a 200 character event key with 150 segments of 200 character keys and 300 character
     * values as an event, a timed event and a view with its end event, reports a feedback widget
     * result with a 300 character comment, sets 150 such custom user properties with a 300 character
     * name and a modification, and records a crash with that segmentation, a 300 character message
     * and breadcrumb and a crash processor dump of 40 lines per thread. Asserts that every key, value,
     * entry and stack trace line is sent whole and that no limit warning was logged.
     */
    private void assertNothingIsCut() {
        String longKey = repeat("key_", 50);
        String longValue = repeat("value_", 50);
        Map<String, Object> wide = new HashMap<>();
        for (int i = 0; i < 150; i++) {
            wide.put(longKey + i, longValue + i);
        }

        Countly.instance().events().recordEvent(longKey, wide);
        Countly.instance().events().startEvent(longKey + "timed");
        Countly.instance().events().endEvent(longKey + "timed", wide, 1, null);
        String viewId = Countly.instance().views().startView(longKey, wide);
        Countly.instance().views().stopViewWithID(viewId, wide);
        CountlyFeedbackWidget widget = new CountlyFeedbackWidget();
        widget.widgetId = "rating_widget_identifier";
        widget.type = FeedbackWidgetType.rating;
        widget.name = "rating";
        widget.tags = new String[0];
        Countly.instance().feedback().reportFeedbackWidgetManually(widget, null, TestUtils.map("rating", 5, "comment", longValue));

        List<EventImpl> queued = TestUtils.getCurrentEQ();
        Assert.assertEquals(keysOf(queued).toString(), 5, queued.size());
        assertEvent(queued.get(0), longKey, wide);
        assertEvent(queued.get(1), longKey + "timed", wide);
        Map<String, Object> viewStart = new HashMap<>(queued.get(2).segmentation);
        Assert.assertEquals(longKey, viewStart.remove(ModuleViews.KEY_NAME));
        Assert.assertEquals("1", viewStart.remove(ModuleViews.KEY_VISIT));
        Assert.assertEquals("1", viewStart.remove(ModuleViews.KEY_START));
        Assert.assertNotNull(viewStart.remove(ModuleViews.KEY_SEGMENT));
        Assert.assertEquals(wide, viewStart);
        Map<String, Object> viewEnd = new HashMap<>(queued.get(3).segmentation);
        Assert.assertEquals(longKey, viewEnd.remove(ModuleViews.KEY_NAME));
        Assert.assertNotNull(viewEnd.remove(ModuleViews.KEY_SEGMENT));
        Assert.assertEquals(wide, viewEnd);
        Assert.assertEquals(longValue, queued.get(4).segmentation.get("comment"));

        Map<String, Object> properties = new HashMap<>(wide);
        properties.put(PredefinedUserPropertyKeys.NAME, longValue);
        Countly.instance().userProfile().setProperties(properties);
        Countly.instance().userProfile().push(longKey + "list", longValue);
        Countly.instance().userProfile().save();
        TestUtils.letTheClockCatchUp();

        JSONObject details = null;
        for (Map<String, String> request : TestUtils.getCurrentRQ()) {
            if (request.containsKey("user_details")) {
                details = new JSONObject(request.get("user_details"));
            }
        }
        Assert.assertNotNull(details);
        Assert.assertEquals(longValue, details.getString(PredefinedUserPropertyKeys.NAME));
        JSONObject custom = details.getJSONObject(ModuleUserProfile.CUSTOM_KEY);
        Assert.assertEquals(151, custom.length());
        for (Map.Entry<String, Object> entry : wide.entrySet()) {
            Assert.assertEquals(entry.getValue(), custom.getString(entry.getKey()));
        }
        Assert.assertEquals(longValue, custom.getJSONObject(longKey + "list").getString("$push"));

        Countly.instance().crashes().addCrashBreadcrumb(longValue);
        Exception exception = new Exception(longValue);
        Countly.instance().crashes().recordHandledException(exception, wide);
        TestUtils.letTheClockCatchUp();
        DumpingCrashProcessor.replaceWithDump = true;
        DumpingCrashProcessor.framesPerThread = 40;
        Countly.instance().crashes().recordHandledException(new Exception("dumped"));
        TestUtils.letTheClockCatchUp();

        List<JSONObject> crashes = crashRequests();
        Assert.assertEquals(2, crashes.size());
        Assert.assertEquals(stackTraceOf(exception), crashes.get(0).getString("_error"));
        Assert.assertEquals(longValue, crashes.get(0).getString("_logs"));
        JSONObject segments = crashes.get(0).getJSONObject("_custom");
        Assert.assertEquals(wide.size(), segments.length());
        for (Map.Entry<String, Object> entry : wide.entrySet()) {
            Assert.assertEquals(entry.getValue(), segments.getString(entry.getKey()));
        }
        String dump = crashes.get(1).getString("_error");
        Assert.assertEquals(80, dump.split("\tat ly.count.Example.method", -1).length - 1);
        Assert.assertTrue(dump.contains("Thread [worker-thread]:\n"));
        Assert.assertEquals(3, crashes.get(1).getJSONObject("_custom").length());
        Assert.assertEquals("processor_breadcrumb", crashes.get(1).getString("_logs"));

        Assert.assertEquals(0, warningsContaining("limit of ["));
        Assert.assertEquals(0, warningsContaining("reserved key"));
    }

    /**
     * The limits most scenarios run with: keys of 5 characters, values of 6, 2 segmentation entries
     * and 3 stack trace lines per thread of 20 characters each, with networking off.
     *
     * @return the settings
     */
    private static ServerConfigBuilder smallLimits() {
        return new ServerConfigBuilder().keyLengthLimit(5).valueSizeLimit(6).segmentationValuesLimit(2)
            .traceLinesLimit(3).traceLengthLimit(20).networking(false);
    }

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
        return TestUtils.getConfigSdkBehaviorSettings().setLogListener(this::keepWarning);
    }

    /**
     * A log listener that keeps every warning.
     *
     * @param message the log line
     * @param level its level
     */
    private void keepWarning(String message, Config.LoggingLevel level) {
        if (level == Config.LoggingLevel.WARN) {
            warnings.add(message);
        }
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
     * The crash of every stored crash request, in queue order.
     *
     * @return the crashes
     */
    private static List<JSONObject> crashRequests() {
        List<JSONObject> crashes = new ArrayList<>();
        for (Map<String, String> request : TestUtils.getCurrentRQ()) {
            if (request.containsKey("crash")) {
                crashes.add(new JSONObject(request.get("crash")));
            }
        }
        return crashes;
    }

    /**
     * The stack trace of a throwable as the SDK prints it.
     *
     * @param throwable the throwable
     * @return the stack trace
     */
    private static String stackTraceOf(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    /**
     * Cuts every line of a text to a length, keeping its line breaks.
     *
     * @param text the text
     * @param maxLength the length
     * @return the cut text
     */
    private static String truncateLines(String text, int maxLength) {
        StringBuilder truncated = new StringBuilder();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String carriageReturn = "";
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
                carriageReturn = "\r";
            }
            truncated.append(line.length() > maxLength ? line.substring(0, maxLength) : line).append(carriageReturn);
            if (i < lines.length - 1) {
                truncated.append('\n');
            }
        }
        return truncated.toString();
    }

    /**
     * The number of line breaks of a text.
     *
     * @param text the text
     * @return the number of lines that end with a line break
     */
    private static int lineCount(String text) {
        return text.split("\n", -1).length - 1;
    }

    /**
     * A text repeated.
     *
     * @param text the text
     * @param times how many times
     * @return the repeated text
     */
    private static String repeat(String text, int times) {
        StringBuilder repeated = new StringBuilder(text.length() * times);
        for (int i = 0; i < times; i++) {
            repeated.append(text);
        }
        return repeated.toString();
    }

    /**
     * A crash processor that, when asked to, replaces the stack trace with a dump of a main and a
     * worker thread and sets its own segments and breadcrumbs, as a customer's processor may.
     */
    public static class DumpingCrashProcessor implements CrashProcessor {
        static volatile boolean replaceWithDump = false;
        static volatile int framesPerThread = 5;
        private static final Thread MAIN_THREAD = new Thread(() -> {
        }, "main");
        private static final Thread WORKER_THREAD = new Thread(() -> {
        }, "worker-thread");

        /**
         * Replaces the stack trace, the segments and the breadcrumbs when asked to.
         *
         * @param crash the crash
         * @return the same crash, never vetoed
         */
        @Override
        public Crash process(Crash crash) {
            if (replaceWithDump) {
                Map<Thread, StackTraceElement[]> traces = new LinkedHashMap<>();
                traces.put(MAIN_THREAD, frames(framesPerThread));
                traces.put(WORKER_THREAD, frames(framesPerThread));
                crash.addTraces(MAIN_THREAD, traces);
                Map<String, String> segments = new HashMap<>();
                segments.put("processor_segment", "processor_value");
                segments.put("another_segment", "another_value");
                segments.put("zzz", "z");
                crash.setSegments(segments);
                crash.setLogs(new String[] { "processor_breadcrumb" });
            }
            return crash;
        }

        /**
         * Stack trace lines of a made up class.
         *
         * @param count how many
         * @return the lines
         */
        private static StackTraceElement[] frames(int count) {
            StackTraceElement[] frames = new StackTraceElement[count];
            for (int i = 0; i < count; i++) {
                frames[i] = new StackTraceElement("ly.count.Example", "method" + i, "Example.java", i + 1);
            }
            return frames;
        }
    }

    // endregion
}
