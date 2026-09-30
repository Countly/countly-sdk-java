package ly.count.sdk.java.internal;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static ly.count.sdk.java.internal.ServerConfigBuilder.names;

/**
 * SDK behavior settings, the server side configuration served by {@code /o/sdk?method=sc}: how
 * {@link ModuleConfiguration} validates, merges, stores and resolves them, and how the SDK applies
 * the values it owns at init and when a response changes them.
 * <p>
 * The settings request goes through a {@link ServerConfigResponder} injected on the
 * {@link InternalConfig} before init. It answers on the calling thread, so the response of the fetch
 * made at init is applied before init returns, and a later response is pushed by calling
 * {@link ModuleConfiguration#fetchConfigFromServer(InternalConfig)} the way the refresh timer does.
 */
@RunWith(JUnit4.class)
public class ModuleConfigurationTests {

    private static final Set<String> TOP_LEVEL_KEYS = names("v", "t", "c", "lg", "ct");

    private ServerConfigResponder server;

    /**
     * Starts every test from empty storage, without a test module and with a fresh responder.
     */
    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
        SDKCore.testDummyModule = null;
        server = new ServerConfigResponder();
    }

    /**
     * Stops the SDK, clearing its data, and removes the test module.
     */
    @After
    public void afterTest() {
        Countly.instance().halt();
        SDKCore.testDummyModule = null;
    }

    // region scenarios

    /**
     * The type table of every settings key, run through the validation a response goes through.
     * <p>
     * Verifies that each of the 42 keys keeps every valid value and loses every value of the wrong
     * type or range, that unknown keys are dropped, and that a decimal, which the JSON parser hands
     * over as a {@link BigDecimal}, is still accepted as {@code bom_rqp}.
     */
    @Test
    public void removeUnsupportedKeys_keepsEveryValidValueAndDropsTheRest() throws IllegalAccessException {
        server.failing();
        init(TestUtils.getConfigSdkBehaviorSettings());
        ModuleConfiguration module = module();

        Map<String, List<String>> valid = new LinkedHashMap<>();
        Map<String, List<String>> invalid = new LinkedHashMap<>();
        for (String key : Arrays.asList("tracking", "networking", "st", "vt", "cet", "ecz", "crt", "ast", "avt", "acr", "lt", "rcz", "bom", "log", "cr")) {
            valid.put(key, Arrays.asList("true", "false"));
            invalid.put(key, Arrays.asList("1", "0", "\"true\"", "null", "{}", "[]"));
        }
        for (String key : Arrays.asList("scui", "rqs", "eqs", "sui", "lkl", "lvs", "lsv", "lbc", "ltlpt", "ltl", "upcl", "bom_at", "bom_ra", "bom_d")) {
            valid.put(key, Arrays.asList("1", "7", "2147483647"));
            invalid.put(key, Arrays.asList("0", "-5", "1.5", "5.0", "\"5\"", "true", "3000000000", "null"));
        }
        valid.put("dort", Arrays.asList("0", "1", "72"));
        invalid.put("dort", Arrays.asList("-1", "0.5", "\"1\"", "false"));
        valid.put("czi", Arrays.asList("16", "300"));
        invalid.put("czi", Arrays.asList("15", "0", "-16", "16.0", "\"16\""));
        valid.put("bom_rqp", Arrays.asList("0.5", "0.01", "0.99", "1e-1"));
        invalid.put("bom_rqp", Arrays.asList("0", "1", "0.0", "1.0", "-0.5", "1.5", "\"0.5\"", "true", "null"));
        for (String key : Arrays.asList("eb", "ew", "sb", "sw", "upb", "upw", "jte", "jtv")) {
            valid.put(key, Arrays.asList("[]", "[\"a\",\"b\"]"));
            invalid.put(key, Arrays.asList("\"a\"", "{}", "1", "true", "null"));
        }
        for (String key : Arrays.asList("esb", "esw")) {
            valid.put(key, Arrays.asList("{}", "{\"purchase\":[\"card\"]}"));
            invalid.put(key, Arrays.asList("[]", "\"a\"", "1", "null"));
        }

        Set<String> settingsKeys = new HashSet<>(settingsKeysOfTheModule());
        Assert.assertEquals("the table covers every settings key the module declares", settingsKeys, valid.keySet());
        Assert.assertEquals(42, settingsKeys.size());

        for (Map.Entry<String, List<String>> entry : valid.entrySet()) {
            for (String literal : entry.getValue()) {
                assertKeptByValidation(module, entry.getKey(), literal, true);
            }
        }
        for (Map.Entry<String, List<String>> entry : invalid.entrySet()) {
            for (String literal : entry.getValue()) {
                assertKeptByValidation(module, entry.getKey(), literal, false);
            }
        }
        for (String unknownKey : Arrays.asList("heartbeat", "TRACKING", "someFutureFeature", "")) {
            assertKeptByValidation(module, unknownKey, "true", false);
        }

        JSONObject settings = validated(module, "{\"bom_rqp\":0.25,\"tracking\":\"no\",\"eqs\":3}");
        Assert.assertTrue(settings.get("bom_rqp") instanceof BigDecimal);
        Assert.assertEquals(2, settings.length());

        push(new JSONObject().put("v", 1).put("t", 1).put("c", new JSONObject("{\"bom_rqp\":0.25}")).toString());
        Assert.assertEquals(0.25, provider().getBOMRQPercentage(), 0.0);
    }

    /**
     * Every settings key at once, one key of each blacklist and whitelist pair, at a valid value that
     * differs from the default, then every key at an invalid value.
     * <p>
     * Verifies that each value reaches its provider getter and the store, that the request queue size
     * is reported as coming from the settings, and that a response made only of invalid values changes
     * nothing, neither the resolved values nor the stored settings.
     */
    @Test
    public void allSettings_resolveFromAResponse_andInvalidValuesNeverOverrideThem() throws IllegalAccessException {
        AtomicInteger changes = installCountingModule().changes;
        ServerConfigBuilder allKeys = new ServerConfigBuilder().allKeysAtNonDefaultValues();
        server.respondWith(allKeys);
        init(TestUtils.getConfigSdkBehaviorSettings());

        allKeys.validateAgainst(provider());
        Assert.assertTrue(provider().isRequestQueueMaxSizeFromBehaviorSettings());
        Assert.assertEquals(1, changes.get());
        JSONObject storedSettings = storedConfig().getJSONObject("c");
        Assert.assertEquals(allKeys.config.keySet(), storedSettings.keySet());

        ServerConfigBuilder allInvalid = new ServerConfigBuilder();
        for (String key : settingsKeysOfTheModule()) {
            allInvalid.set(key, key.equals("tracking") ? -1 : "not a valid value");
        }
        push(allInvalid);

        allKeys.validateAgainst(provider());
        Assert.assertEquals(1, changes.get());
        Assert.assertTrue(storedSettings.similar(storedConfig().getJSONObject("c")));
    }

    /**
     * The envelope of a response: {@code v}, {@code t} and an object {@code c} are required, any
     * other top level key is allowed.
     * <p>
     * Verifies that a response missing one of the three, or with a {@code c} that is not an object,
     * is rejected without touching the store, that an empty {@code c} is accepted, that extra top
     * level keys are accepted quietly and never stored, so neither {@code lg} nor {@code ct} ever is,
     * and that a rejected response after an accepted one keeps the merged settings.
     */
    @Test
    public void envelope_requiresVersionTimestampAndSettingsObject_andOnlyThoseAreStored() {
        List<String> warnings = new CopyOnWriteArrayList<>();
        server.failing();
        init(TestUtils.getConfigSdkBehaviorSettings().setLogListener((message, level) -> {
            if (level == Config.LoggingLevel.WARN || level == Config.LoggingLevel.ERROR) {
                warnings.add(message);
            }
        }));
        Assert.assertNull(storedConfig());

        for (String rejected : Arrays.asList("{}", "{'t':2,'c':{'tracking':false}}", "{'v':1,'c':{'tracking':false}}", "{'v':1,'t':2}",
            "{'v':1,'t':2,'c':123}", "{'v':1,'t':2,'c':false}", "{'v':1,'t':2,'c':'fdf'}", "{'v':1,'t':2,'c':[]}")) {
            push(rejected);
            Assert.assertNull("stored after " + rejected, storedConfig());
            Assert.assertTrue(provider().getTrackingEnabled());
        }

        push("{'v':1,'t':2,'c':{}}");
        Assert.assertTrue(new JSONObject("{'v':1,'t':2,'c':{}}").similar(storedConfig()));

        warnings.clear();
        push(new ServerConfigBuilder().version(2).timestamp(3).tracking(false)
            .logGatheringOn("gather_id", "ew", 20)
            .connectionTest(1)
            .topLevelKey("someFutureFeature", "a value this SDK has never heard of"));

        Assert.assertFalse(provider().getTrackingEnabled());
        Assert.assertEquals(ConfigurationProvider.LogGatheringState.GATHERING, provider().getLogGatheringState());
        JSONObject stored = storedConfig();
        Assert.assertEquals(names("v", "t", "c"), stored.keySet());
        Assert.assertEquals(2, stored.getInt("v"));
        Assert.assertEquals(3, stored.getLong("t"));
        Assert.assertFalse(stored.getJSONObject("c").getBoolean("tracking"));
        for (String warning : warnings) {
            Assert.assertFalse("warned about a top level key: " + warning, warning.contains("someFutureFeature") || warning.contains("[lg]") || warning.contains("[ct]"));
        }

        push("{'t':4,'c':{'tracking':true}}");
        Assert.assertFalse(provider().getTrackingEnabled());
        Assert.assertTrue(stored.similar(storedConfig()));
    }

    /**
     * The layers of a value: developer configuration, then the provided settings while nothing is
     * stored, then the stored settings, then each server response.
     * <p>
     * Verifies each layer overriding the one below it, that the provided settings are stored and
     * ignored once something is stored, that a response merges into the stored settings instead of
     * replacing them, and that the merge survives {@code stop()} and a new init.
     */
    @Test
    public void precedence_developerThenProvidedThenStoredThenServer_andTheMergeSurvivesARestart() {
        server.pending();
        init(developerConfig());

        Assert.assertEquals(7, provider().getEventQueueSizeThreshold());
        Assert.assertEquals(50, provider().getRequestQueueMaxSize());
        Assert.assertFalse(provider().isRequestQueueMaxSizeFromBehaviorSettings());
        Assert.assertEquals(30, provider().getSessionUpdateInterval());
        Assert.assertEquals(40, provider().getMaxBreadcrumbCount());
        Assert.assertEquals(ModuleConfiguration.NO_LIMIT, provider().getMaxKeyLength());
        Assert.assertEquals(ModuleConfiguration.NO_LIMIT, provider().getUserPropertyCacheLimit());
        Assert.assertTrue(provider().getLoggingEnabled());
        Assert.assertNull(storedConfig());
        Countly.instance().halt();

        init(developerConfig().setSdkBehaviorSettings(new ServerConfigBuilder().eventQueueSize(11).requestQueueSize(22).build()));

        Assert.assertEquals(11, provider().getEventQueueSizeThreshold());
        Assert.assertEquals(22, provider().getRequestQueueMaxSize());
        Assert.assertTrue(provider().isRequestQueueMaxSizeFromBehaviorSettings());
        Assert.assertEquals(30, provider().getSessionUpdateInterval());
        Assert.assertEquals(2, storedConfig().getJSONObject("c").length());

        push(new ServerConfigBuilder().eventQueueSize(33).breadcrumbLimit(5).logging(false));

        Assert.assertEquals(33, provider().getEventQueueSizeThreshold());
        Assert.assertEquals(22, provider().getRequestQueueMaxSize());
        Assert.assertEquals(5, provider().getMaxBreadcrumbCount());
        Assert.assertEquals(30, provider().getSessionUpdateInterval());
        Assert.assertFalse(provider().getLoggingEnabled());
        JSONObject storedSettings = storedConfig().getJSONObject("c");
        Assert.assertEquals(names("eqs", "rqs", "lbc", "log"), storedSettings.keySet());
        Countly.instance().stop();

        server.failing();
        init(developerConfig().setSdkBehaviorSettings(new ServerConfigBuilder().eventQueueSize(99).sessionTracking(false).build()));

        Assert.assertEquals(33, provider().getEventQueueSizeThreshold());
        Assert.assertEquals(22, provider().getRequestQueueMaxSize());
        Assert.assertEquals(5, provider().getMaxBreadcrumbCount());
        Assert.assertFalse(provider().getLoggingEnabled());
        Assert.assertTrue(provider().getSessionTrackingEnabled());
        Assert.assertTrue(storedSettings.similar(storedConfig().getJSONObject("c")));
    }

    /**
     * The settings request itself.
     * <p>
     * Verifies that init sends exactly one request, to {@code /o/sdk?} with {@code method=sc} and the
     * parameters every request carries, that it goes out even while the settings forbid networking,
     * that the configuration module is initialized once and sits at its fixed index ahead of the
     * feature modules, and that with settings requests disabled nothing is requested while the
     * provided settings still apply.
     */
    @Test
    public void fetch_requestsTheSettingsAtInitWithTheRequiredParams_andNeverWhenDisabled() {
        List<String> initLines = new CopyOnWriteArrayList<>();
        server.respondWith(new ServerConfigBuilder().networking(false));
        init(TestUtils.getConfigSdkBehaviorSettings().setLogListener((message, level) -> {
            if (message.startsWith("[ModuleConfiguration] init,")) {
                initLines.add(message);
            }
        }));

        Assert.assertEquals(1, server.requests.size());
        Assert.assertEquals("/o/sdk?", server.endpoints.get(0));
        Map<String, String> params = server.requests.get(0);
        Assert.assertEquals("sc", params.get("method"));
        TestUtils.validateRequiredParams(params);
        Assert.assertEquals(TestUtils.SDK_NAME, params.get("sdk_name"));
        Assert.assertEquals(TestUtils.SDK_VERSION, params.get("sdk_version"));
        Assert.assertTrue(server.networkingFlags.get(0));

        Assert.assertEquals(1, initLines.size());
        Assert.assertSame(module(), SDKCore.instance.modules.get(-1));
        Assert.assertSame(module(), SDKCore.instance.config.getConfigurationProvider());
        Assert.assertEquals(4L * 60 * 60, module().serverConfigUpdateTimer.getTimerDelaySeconds());

        Assert.assertFalse(provider().getNetworkingEnabled());
        push(new ServerConfigBuilder().networking(true));
        Assert.assertEquals(2, server.requests.size());
        Assert.assertTrue(server.networkingFlags.get(1));
        Assert.assertTrue(provider().getNetworkingEnabled());
        Countly.instance().halt();

        server = new ServerConfigResponder();
        init(TestUtils.getConfigSdkBehaviorSettings()
            .disableSdkBehaviorSettingsUpdates()
            .setSdkBehaviorSettings(new ServerConfigBuilder().tracking(false).build()));

        Assert.assertTrue(server.requests.isEmpty());
        Assert.assertFalse(provider().getTrackingEnabled());
        Assert.assertNull(module().serverConfigUpdateTimer);
        Assert.assertEquals(ConfigurationProvider.LogGatheringState.NOT_GATHERING, provider().getLogGatheringState());
    }

    /**
     * Backend mode keeps the settings inert.
     * <p>
     * Verifies that with stored and provided settings present, backend mode requests nothing, serves
     * the developer configuration, starts no refresh timer, decides against log gathering and leaves
     * the stored settings as they are for a later run without backend mode.
     */
    @Test
    public void backendMode_keepsTheSettingsInert() {
        server.respondWith(new ServerConfigBuilder().tracking(false).eventQueueSize(3).requestQueueSize(5));
        init(TestUtils.getConfigSdkBehaviorSettings());
        Assert.assertFalse(provider().getTrackingEnabled());
        JSONObject storedBefore = storedConfig();
        Countly.instance().stop();

        server = new ServerConfigResponder().respondWith(new ServerConfigBuilder().networking(false));
        init(TestUtils.getConfigSdkBehaviorSettings()
            .enableBackendMode()
            .setEventQueueSizeToSend(9)
            .setRequestQueueMaxSize(40)
            .setSdkBehaviorSettings(new ServerConfigBuilder().networking(false).build()));

        Assert.assertTrue(server.requests.isEmpty());
        Assert.assertTrue(provider().getTrackingEnabled());
        Assert.assertTrue(provider().getNetworkingEnabled());
        Assert.assertTrue(SDKCore.instance.config.getNetworkingEnabled());
        Assert.assertEquals(9, provider().getEventQueueSizeThreshold());
        Assert.assertEquals(40, provider().getRequestQueueMaxSize());
        Assert.assertFalse(provider().isRequestQueueMaxSizeFromBehaviorSettings());
        Assert.assertEquals(ConfigurationProvider.LogGatheringState.NOT_GATHERING, provider().getLogGatheringState());
        Assert.assertNull(module().serverConfigUpdateTimer);
        Assert.assertTrue(storedBefore.similar(storedConfig()));
    }

    /**
     * The consent requirement is applied when the SDK initializes, and only then.
     * <p>
     * Verifies that a {@code cr} arriving while the SDK runs is stored but leaves the running SDK
     * without consent gating, that the stored value is in place before the modules are built on the
     * next init, so the feature modules wait for consent, and that removing consent stops feature
     * modules but never the configuration module, which keeps applying responses.
     */
    @Test
    public void consentRequirement_appliesAtInitOnly_andConsentRemovalNeverStopsTheConfigurationModule() {
        server.respondWith(new ServerConfigBuilder().consentRequired(true));
        init(TestUtils.getConfigSdkBehaviorSettings().enableFeatures(Config.Feature.Events));

        Assert.assertTrue(provider().getConsentRequired());
        Assert.assertFalse(SDKCore.instance.config.requiresConsent());
        Assert.assertTrue(Countly.isTracking(Config.Feature.Events));
        Assert.assertNotNull(Countly.instance().events());
        Assert.assertTrue(storedConfig().getJSONObject("c").getBoolean("cr"));
        Countly.instance().stop();

        server.pending();
        init(TestUtils.getConfigSdkBehaviorSettings().enableFeatures(Config.Feature.Events));

        Assert.assertTrue(SDKCore.instance.config.requiresConsent());
        Assert.assertFalse(Countly.isTracking(Config.Feature.Events));
        Assert.assertNull(Countly.instance().events());

        Countly.onConsent(Config.Feature.Events);
        Assert.assertTrue(Countly.isTracking(Config.Feature.Events));

        ModuleConfiguration configurationModule = module();
        CountlyTimer refreshTimer = configurationModule.serverConfigUpdateTimer;
        Assert.assertNotNull(refreshTimer);

        Countly.onConsentRemoval(Config.Feature.Events);

        Assert.assertFalse(Countly.isTracking(Config.Feature.Events));
        Assert.assertSame(configurationModule, SDKCore.instance.modules.get(-1));
        Assert.assertSame(configurationModule, SDKCore.instance.config.getConfigurationProvider());
        Assert.assertTrue(configurationModule.isActive());
        Assert.assertSame(refreshTimer, configurationModule.serverConfigUpdateTimer);

        push(new ServerConfigBuilder().consentRequired(false).tracking(false));
        Assert.assertFalse(provider().getTrackingEnabled());
        Assert.assertFalse(provider().getConsentRequired());
        Assert.assertTrue(SDKCore.instance.config.requiresConsent());
    }

    /**
     * The global timer follows the session update interval, and the refresh timer follows the
     * update interval of the settings.
     * <p>
     * Verifies that a stored {@code sui} starts the global timer at init, which then really ticks every
     * second, that a changed {@code sui} restarts the timer and notifies every module once, that a
     * response changing nothing restarts nothing and notifies nobody, that another change notifies the
     * modules without touching the timer, and that a changed {@code scui} restarts the refresh timer.
     */
    @Test
    public void timers_followTheResolvedIntervals_andChangesAreDispatchedOnce() throws InterruptedException {
        CountingModule counter = installCountingModule();
        AtomicInteger changes = counter.changes;
        AtomicInteger ticks = counter.ticks;

        server.respondWith(new ServerConfigBuilder().sessionUpdateInterval(1));
        init(TestUtils.getConfigSdkBehaviorSettings());
        Assert.assertEquals(1, SDKCore.instance.countlyTimer.getTimerDelaySeconds());
        Assert.assertEquals(1, changes.get());
        Countly.instance().stop();

        changes.set(0);
        ticks.set(0);
        server.pending();
        init(TestUtils.getConfigSdkBehaviorSettings());

        CountlyTimer timerAtInit = SDKCore.instance.countlyTimer;
        Assert.assertEquals(1, timerAtInit.getTimerDelaySeconds());
        Assert.assertEquals(0, changes.get());
        Assert.assertTrue("the global timer did not tick every second", waitFor(10_000, () -> ticks.get() >= 2));

        ServerConfigBuilder slower = new ServerConfigBuilder().sessionUpdateInterval(3);
        push(slower);
        CountlyTimer restarted = SDKCore.instance.countlyTimer;
        Assert.assertNotSame(timerAtInit, restarted);
        Assert.assertEquals(3, restarted.getTimerDelaySeconds());
        Assert.assertEquals(1, changes.get());

        push(slower);
        Assert.assertSame(restarted, SDKCore.instance.countlyTimer);
        Assert.assertEquals(1, changes.get());

        push(new ServerConfigBuilder().sessionUpdateInterval(3).eventQueueSize(4));
        Assert.assertSame(restarted, SDKCore.instance.countlyTimer);
        Assert.assertEquals(2, changes.get());

        CountlyTimer refreshTimer = module().serverConfigUpdateTimer;
        Assert.assertEquals(4L * 60 * 60, refreshTimer.getTimerDelaySeconds());
        push(new ServerConfigBuilder().serverConfigUpdateInterval(2));
        Assert.assertNotSame(refreshTimer, module().serverConfigUpdateTimer);
        Assert.assertEquals(2L * 60 * 60, module().serverConfigUpdateTimer.getTimerDelaySeconds());
        Assert.assertEquals(3, changes.get());
    }

    /**
     * {@code cet} covers custom events only, {@code tracking} covers every event.
     * <p>
     * Verifies on the wire that with custom event tracking off a custom event is dropped while a view
     * started through the views interface and an internal event still reach the queue, flushed at the
     * event queue size the settings resolved, that tracking off then drops internal events too, and
     * that custom events are recorded again once both are back on. Networking is off throughout, so
     * the request stays on disk to be read.
     */
    @Test
    public void customEventTracking_off_dropsCustomEventsOnly_whileTrackingGatesEveryEvent() {
        server.respondWith(new ServerConfigBuilder().customEventTracking(false).eventQueueSize(2).networking(false));
        init(TestUtils.getConfigSdkBehaviorSettings().enableFeatures(Config.Feature.Events, Config.Feature.Views).setEventQueueSizeToSend(100));

        Countly.instance().events().recordEvent("custom_event");
        TestUtils.validateEQSize(0);

        Countly.instance().views().startView("home");
        TestUtils.validateEQSize(1);
        Assert.assertEquals(ModuleViews.KEY_VIEW_EVENT, TestUtils.getCurrentEQ().get(0).key);
        Assert.assertEquals(0, TestUtils.getCurrentRQ().length);

        Countly.instance().events().recordEvent("[CLY]_star_rating");
        Assert.assertEquals(1, TestUtils.getCurrentRQ().length);
        List<EventImpl> sent = TestUtils.readEventsFromRequest(0, TestUtils.DEVICE_ID);
        Assert.assertEquals(2, sent.size());
        Assert.assertEquals(ModuleViews.KEY_VIEW_EVENT, sent.get(0).key);
        Assert.assertEquals("[CLY]_star_rating", sent.get(1).key);
        TestUtils.validateEQSize(0);

        push(new ServerConfigBuilder().tracking(false));
        Countly.instance().events().recordEvent("[CLY]_star_rating");
        Countly.instance().views().startView("settings");
        TestUtils.validateEQSize(0);
        Assert.assertEquals(1, TestUtils.getCurrentRQ().length);

        push(new ServerConfigBuilder().tracking(true).customEventTracking(true));
        Countly.instance().events().recordEvent("custom_event");
        TestUtils.validateEQSize(1);
        Assert.assertEquals("custom_event", TestUtils.getCurrentEQ().get(0).key);
    }

    /**
     * Listing filters and journey trigger sets are published as immutable snapshots.
     * <p>
     * Verifies every filter family and both journey sets as parsed, that a reader's snapshot never
     * changes under it, that a response with any whitelist drops every stored blacklist and the
     * reverse, that a response with neither keeps the stored filters, and that they survive a restart.
     */
    @Test
    public void listingFilters_arePublishedAsSnapshots_andOneKindReplacesTheStoredOtherKind() {
        Map<String, Set<String>> perEvent = new LinkedHashMap<>();
        perEvent.put("purchase", names("card"));
        server.respondWith(new ServerConfigBuilder()
            .eventFilterList(names("blocked_a", "blocked_b"), false)
            .segmentationFilterList(names("secret"), false)
            .eventSegmentationFilterMap(perEvent, false)
            .userPropertyFilterList(names("plan"), true)
            .journeyTriggerEvents(names("checkout"))
            .journeyTriggerViews(names("paywall")));
        init(TestUtils.getConfigSdkBehaviorSettings());

        ConfigurationProvider.FilterList<Set<String>> events = provider().getEventFilterList();
        assertFilter(events, false, names("blocked_a", "blocked_b"));
        assertFilter(provider().getSegmentationFilterList(), false, names("secret"));
        assertFilter(provider().getUserPropertyFilterList(), true, names("plan"));
        Assert.assertFalse(provider().getEventSegmentationFilterList().isWhitelist());
        Assert.assertEquals(perEvent, provider().getEventSegmentationFilterList().getFilterList());
        Assert.assertEquals(names("checkout"), provider().getJourneyTriggerEvents());
        Assert.assertEquals(names("paywall"), provider().getJourneyTriggerViews());
        assertUnmodifiable(events.getFilterList());
        assertUnmodifiable(provider().getEventSegmentationFilterList().getFilterList().get("purchase"));
        assertUnmodifiable(provider().getJourneyTriggerEvents());
        try {
            provider().getEventSegmentationFilterList().getFilterList().put("other", names("x"));
            Assert.fail("the event segmentation filter can be changed by a reader");
        } catch (UnsupportedOperationException expected) {
            Assert.assertEquals(1, provider().getEventSegmentationFilterList().getFilterList().size());
        }

        push(new ServerConfigBuilder().eventFilterList(names("allowed_event"), true));

        assertFilter(events, false, names("blocked_a", "blocked_b"));
        assertFilter(provider().getEventFilterList(), true, names("allowed_event"));
        assertFilter(provider().getSegmentationFilterList(), false, names());
        assertFilter(provider().getUserPropertyFilterList(), true, names("plan"));
        Assert.assertTrue(provider().getEventSegmentationFilterList().getFilterList().isEmpty());
        Assert.assertEquals(names("checkout"), provider().getJourneyTriggerEvents());
        Assert.assertEquals(names("ew", "upw", "jte", "jtv"), storedConfig().getJSONObject("c").keySet());

        push(new ServerConfigBuilder().tracking(false));
        assertFilter(provider().getEventFilterList(), true, names("allowed_event"));

        push(new ServerConfigBuilder().segmentationFilterList(names("secret"), false));
        assertFilter(provider().getEventFilterList(), false, names());
        assertFilter(provider().getUserPropertyFilterList(), false, names());
        assertFilter(provider().getSegmentationFilterList(), false, names("secret"));
        Assert.assertEquals(names("jte", "jtv", "tracking", "sb"), storedConfig().getJSONObject("c").keySet());
        Countly.instance().stop();

        server.pending();
        init(TestUtils.getConfigSdkBehaviorSettings());
        assertFilter(provider().getSegmentationFilterList(), false, names("secret"));
        Assert.assertEquals(names("checkout"), provider().getJourneyTriggerEvents());
        Assert.assertEquals(names("paywall"), provider().getJourneyTriggerViews());
        Assert.assertFalse(provider().getTrackingEnabled());
    }

    /**
     * The {@code ct} connection test flag of a live response.
     * <p>
     * Verifies the truthiness table of the flag and that it is removed from the response, then through
     * live fetches that a truthy flag reaches the registered listener once, after the settings of the
     * same response were applied, with the latency measured from just before the request to its
     * response, that a falsy flag does not, that a response whose settings are rejected can still ask,
     * and that the flag is never stored.
     */
    @Test
    public void connectionTestFlag_isReadByValue_strippedFromTheResponse_andHandedOnWithTheFetchLatency() {
        Object[][] table = {
            { "true", true }, { "false", false }, { "1", true }, { "0", false }, { "0.0", false }, { "2.5", true }, { "-1", true },
            { "\"1\"", true }, { "\"0\"", false }, { "\"false\"", false }, { "\"FALSE\"", false }, { "\" false \"", false },
            { "\"\"", false }, { "\"   \"", false }, { "\"yes\"", true }, { "\"true\"", true }, { "null", false },
            { "{}", true }, { "[]", true }, { "{\"a\":1}", true }
        };
        for (Object[] row : table) {
            JSONObject response = new JSONObject("{\"v\":1,\"t\":1,\"c\":{},\"ct\":" + row[0] + "}");
            Assert.assertEquals("ct = " + row[0], row[1], ModuleConfiguration.extractConnectionTestFlag(response));
            Assert.assertFalse("ct = " + row[0] + " was left in the response", response.has("ct"));
        }
        Assert.assertFalse(ModuleConfiguration.extractConnectionTestFlag(new JSONObject("{\"v\":1,\"t\":1,\"c\":{}}")));
        Assert.assertFalse(ModuleConfiguration.extractConnectionTestFlag(null));

        server.failing();
        init(TestUtils.getConfigSdkBehaviorSettings());
        List<Long> latencies = new CopyOnWriteArrayList<>();
        List<Boolean> trackingWhenNotified = new CopyOnWriteArrayList<>();
        module().setConnectionTestListener(fetchLatencyMs -> {
            latencies.add(fetchLatencyMs);
            trackingWhenNotified.add(provider().getTrackingEnabled());
        });

        server.delayedBy(80);
        push(new ServerConfigBuilder().tracking(false).connectionTest(1));
        Assert.assertEquals(1, latencies.size());
        Assert.assertTrue("latency " + latencies.get(0), latencies.get(0) >= 70);
        Assert.assertEquals(Arrays.asList(false), trackingWhenNotified);

        server.delayedBy(0);
        push(new ServerConfigBuilder().connectionTest(0));
        push(new ServerConfigBuilder().connectionTest("false"));
        push(new ServerConfigBuilder());
        Assert.assertEquals(1, latencies.size());

        push("{\"ct\":true}");
        Assert.assertEquals(2, latencies.size());
        Assert.assertFalse(storedConfig().has("ct"));

        module().setConnectionTestListener(null);
        push(new ServerConfigBuilder().connectionTest(1).tracking(true));
        Assert.assertTrue(provider().getTrackingEnabled());
        Assert.assertEquals(2, latencies.size());
    }

    /**
     * The {@code lg} directive of a live response turns log gathering on and off.
     * <p>
     * Verifies that a directive with a gather id arms gathering with its levels and batch size and is
     * never stored, that {@code "e":false} and a response without any directive both decide off and
     * drop the gather id, and that a failed fetch after a decision changes nothing.
     */
    @Test
    public void logGatheringDirective_enabledThenDisabled_isAppliedBothWays() {
        server.respondWith(new ServerConfigBuilder().logGatheringOn("gather_id_3", "ewd", 40));
        init(TestUtils.getConfigSdkBehaviorSettings());

        assertGathering("gather_id_3", "ewd", 40);
        Assert.assertFalse(storedConfig().has("lg"));

        push(new ServerConfigBuilder().logGatheringOff());
        assertNotGathering();

        push(new ServerConfigBuilder().logGatheringOn("gather_id_3", "ewd", 40));
        assertGathering("gather_id_3", "ewd", 40);

        push(new ServerConfigBuilder().tracking(false));
        assertNotGathering();
        Assert.assertFalse(provider().getTrackingEnabled());

        push(new ServerConfigBuilder().logGatheringOn("gather_id_4", "e", 20));
        server.failing();
        module().fetchConfigFromServer(SDKCore.instance.config);
        assertGathering("gather_id_4", "e", 20);
    }

    /**
     * A directive that enables gathering without a usable gather id, or with {@code e} that is not a
     * boolean {@code true}, decides off. A working directive is applied between the cases, so no
     * assertion passes because the state was already off.
     */
    @Test
    public void logGatheringDirective_withoutUsableGatherId_doesNotEnableGathering() {
        server.respondWith(new ServerConfigBuilder().logGatheringOn("gather_id_4", "ew", 20));
        init(TestUtils.getConfigSdkBehaviorSettings());
        assertGathering("gather_id_4", "ew", 20);

        List<ServerConfigBuilder> unusable = Arrays.asList(
            new ServerConfigBuilder().logGatheringDirective(true, null, "ew", 20),
            new ServerConfigBuilder().logGatheringDirective(true, "   ", "ew", 20),
            new ServerConfigBuilder().logGatheringDirective(true, 42, "ew", 20),
            new ServerConfigBuilder().logGatheringDirective("true", "gather_id_4", "ew", 20),
            new ServerConfigBuilder().logGatheringDirective(1, "gather_id_4", "ew", 20),
            new ServerConfigBuilder().topLevelKey("lg", "on"));
        for (ServerConfigBuilder directive : unusable) {
            push(directive);
            assertNotGathering();

            push(new ServerConfigBuilder().logGatheringOn("gather_id_4", "ew", 20));
            assertGathering("gather_id_4", "ew", 20);
        }

        push(new ServerConfigBuilder().logGatheringOn("  gather_id_5  ", "ew", 20));
        assertGathering("gather_id_5", "ew", 20);
    }

    /**
     * The levels and the batch size of a directive are sanitized: unknown level characters go, the
     * rest is lower cased and deduplicated in the order sent, nothing usable falls back to every level,
     * and the batch size is clamped into [10, 500] or falls back to 100 when it is not a number.
     */
    @Test
    public void logGatheringDirective_levelsAndBatchSize_areSanitized() {
        server.respondWith(new ServerConfigBuilder().logGatheringOn("gather_id_5", "xyz!", 100));
        init(TestUtils.getConfigSdkBehaviorSettings());
        assertGathering("gather_id_5", ModuleConfiguration.logGatheringAllLevels, 100);

        Object[][] steps = {
            { "ew", 100, "ew", 100 },
            { null, 100, ModuleConfiguration.logGatheringAllLevels, 100 },
            { "ew", 100, "ew", 100 },
            { 5, 100, ModuleConfiguration.logGatheringAllLevels, 100 },
            { "EWID", 100, "ewid", 100 },
            { "wweeww", 100, "we", 100 },
            { "vq d", 100, "vd", 100 },
            { "ew", 1, "ew", ModuleConfiguration.logGatheringMinBatchSize },
            { "ew", 9999, "ew", ModuleConfiguration.logGatheringMaxBufferedLines },
            { "ew", 250, "ew", 250 },
            { "ew", "250", "ew", ModuleConfiguration.logGatheringDefaultBatchSize },
            { "ew", 250, "ew", 250 },
            { "ew", null, "ew", ModuleConfiguration.logGatheringDefaultBatchSize },
            { "ew", 37.9, "ew", 37 }
        };
        for (Object[] step : steps) {
            push(new ServerConfigBuilder().logGatheringDirective(true, "gather_id_5", step[0], step[1]));
            assertGathering("gather_id_5", (String) step[2], (Integer) step[3]);
        }
    }

    /**
     * Only a live response decides log gathering.
     * <p>
     * Verifies that a stored directive, even an armed one, leaves the state undecided and is dropped
     * from the stored copy, that a stored configuration without one also stays undecided, that a
     * failed fetch decides off while undecided, and that disabled settings requests decide off at
     * init even with an armed directive in the provided settings.
     */
    @Test
    public void logGathering_isUndecidedUntilALiveResponse_failedFetchAndDisabledRequestsDecideOff() {
        TestUtils.writeToFile(SDKStorage.JSON_FILE_NAME, new JSONObject()
            .put("sc", new ServerConfigBuilder().tracking(false).logGatheringOn("stored_gather_id", "ewidv", 40).build()).toString());
        server.pending();
        init(TestUtils.getConfigSdkBehaviorSettings());

        Assert.assertFalse(provider().getTrackingEnabled());
        Assert.assertEquals(ConfigurationProvider.LogGatheringState.UNDECIDED, provider().getLogGatheringState());
        Assert.assertNull(provider().getLogGatheringId());
        Assert.assertEquals(ModuleConfiguration.logGatheringAllLevels, provider().getLogGatheringLevels());
        Assert.assertEquals(ModuleConfiguration.logGatheringDefaultBatchSize, provider().getLogGatheringBatchSize());
        Assert.assertFalse(storedConfig().has("lg"));
        Countly.instance().stop();

        init(TestUtils.getConfigSdkBehaviorSettings());
        Assert.assertEquals(ConfigurationProvider.LogGatheringState.UNDECIDED, provider().getLogGatheringState());
        Countly.instance().stop();

        server.failing();
        init(TestUtils.getConfigSdkBehaviorSettings());
        assertNotGathering();
        Countly.instance().halt();

        server = new ServerConfigResponder();
        init(TestUtils.getConfigSdkBehaviorSettings()
            .disableSdkBehaviorSettingsUpdates()
            .setSdkBehaviorSettings(new ServerConfigBuilder().logGatheringOn("provided_gather_id", "ew", 20).build()));
        assertNotGathering();
        Assert.assertTrue(server.requests.isEmpty());
    }

    /**
     * A queue held back by {@code networking: false} drains as soon as a response allows networking
     * again, without waiting for another request or for the global timer.
     * <p>
     * Verifies against a local server that nothing is sent while networking is off and that both held
     * requests arrive once it is back on.
     */
    @Test
    public void networking_turningBackOn_drainsTheRequestsItHeldBack() throws Exception {
        AtomicInteger received = new AtomicInteger();
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        httpServer.createContext("/", exchange -> {
            if (exchange.getRequestURI().getPath().startsWith("/i")) {
                received.incrementAndGet();
            }
            byte[] body = "{\"result\":\"Success\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        httpServer.start();

        try {
            Config config = new Config("http://localhost:" + httpServer.getAddress().getPort(), TestUtils.SERVER_APP_KEY, TestUtils.getTestSDirectory())
                .setApplicationVersion(TestUtils.APPLICATION_VERSION)
                .setCustomDeviceId(TestUtils.DEVICE_ID)
                .enableFeatures(Config.Feature.Events)
                .setEventQueueSizeToSend(1)
                .setSdkBehaviorSettings(new ServerConfigBuilder().networking(false).build());
            server.pending();
            init(config);

            Countly.instance().events().recordEvent("held_1");
            Countly.instance().events().recordEvent("held_2");
            Assert.assertEquals(2, TestUtils.getCurrentRQ().length);
            //a queue that ignored the switch would have sent the first request well within this
            Thread.sleep(1000);
            Assert.assertEquals(0, received.get());
            Assert.assertEquals(2, TestUtils.getCurrentRQ().length);

            push(new ServerConfigBuilder().networking(true));

            Assert.assertTrue("the held requests never drained", waitFor(30_000, () -> received.get() >= 2 && TestUtils.getCurrentRQ().length == 0));
            Assert.assertEquals(2, received.get());
        } finally {
            Countly.instance().halt();
            httpServer.stop(0);
        }
    }

    // endregion
    // region helpers

    /**
     * Stands in for the settings endpoint of the server: records every immediate request the SDK
     * makes and answers it on the calling thread with the response set at that moment.
     */
    static final class ServerConfigResponder implements ImmediateRequestGenerator {
        final List<Map<String, String>> requests = new CopyOnWriteArrayList<>();
        final List<String> endpoints = new CopyOnWriteArrayList<>();
        final List<Boolean> networkingFlags = new CopyOnWriteArrayList<>();
        private volatile String response = null;
        private volatile boolean answering = true;
        private volatile long delayMs = 0;

        /**
         * Answers every request with the given payload.
         *
         * @param builder the payload
         * @return this responder
         */
        ServerConfigResponder respondWith(ServerConfigBuilder builder) {
            return respondWith(builder.build());
        }

        /**
         * Answers every request with the given JSON text.
         *
         * @param json the response
         * @return this responder
         */
        ServerConfigResponder respondWith(String json) {
            response = json;
            answering = true;
            return this;
        }

        /**
         * Answers every request as a failed one.
         *
         * @return this responder
         */
        ServerConfigResponder failing() {
            response = null;
            answering = true;
            return this;
        }

        /**
         * Never answers, as a server that has not answered yet.
         *
         * @return this responder
         */
        ServerConfigResponder pending() {
            answering = false;
            return this;
        }

        /**
         * Waits before answering.
         *
         * @param delayMs how long, in milliseconds
         * @return this responder
         */
        ServerConfigResponder delayedBy(long delayMs) {
            this.delayMs = delayMs;
            return this;
        }

        /**
         * A request maker that records the request and answers it.
         *
         * @return the request maker
         */
        @Override
        public ImmediateRequestI createImmediateRequestMaker() {
            return (requestData, customEndpoint, transport, requestShouldBeDelayed, networkingIsEnabled, callback, log) -> {
                requests.add(parseParams(requestData));
                endpoints.add(customEndpoint);
                networkingFlags.add(networkingIsEnabled);
                if (!answering) {
                    return;
                }
                if (delayMs > 0) {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                String current = response;
                callback.callback(current == null ? null : new JSONObject(current));
            };
        }

        /**
         * Decodes url encoded request parameters.
         *
         * @param requestData the parameters
         * @return the parameters by name
         */
        private static Map<String, String> parseParams(String requestData) {
            Map<String, String> params = new HashMap<>();
            for (String pair : requestData.split("&")) {
                String[] keyValue = pair.split("=", 2);
                params.put(Utils.urldecode(keyValue[0]), keyValue.length > 1 ? Utils.urldecode(keyValue[1]) : "");
            }
            return params;
        }
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
        push(builder.build());
    }

    /**
     * Answers the next settings request with the given JSON text and makes the SDK fetch.
     *
     * @param json the response
     */
    private void push(String json) {
        server.respondWith(json);
        module().fetchConfigFromServer(SDKCore.instance.config);
    }

    /**
     * The configuration module of the running SDK.
     *
     * @return the module
     */
    private static ModuleConfiguration module() {
        return SDKCore.instance.module(ModuleConfiguration.class);
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
     * The settings as stored on disk.
     *
     * @return the stored settings, {@code null} when nothing is stored
     */
    private static JSONObject storedConfig() {
        String stored = TestUtils.readJsonFile(SDKStorage.JSON_FILE_NAME).optString("sc", null);
        return stored == null ? null : new JSONObject(stored);
    }

    /**
     * A developer configuration that sets every value the precedence test follows.
     *
     * @return the configuration
     */
    private static Config developerConfig() {
        return TestUtils.getConfigSdkBehaviorSettings()
            .setEventQueueSizeToSend(7)
            .setRequestQueueMaxSize(50)
            .setUpdateSessionTimerDelay(30)
            .setMaxBreadcrumbCount(40)
            .setLoggingLevel(Config.LoggingLevel.ERROR);
    }

    /**
     * A test module that counts the ticks of the global timer and the configuration changes the SDK
     * dispatches to every module.
     */
    private static final class CountingModule extends ModuleBase {
        final AtomicInteger changes = new AtomicInteger();
        final AtomicInteger ticks = new AtomicInteger();

        /**
         * Counts a tick of the global timer.
         */
        @Override
        protected void onTimer() {
            ticks.incrementAndGet();
        }

        /**
         * Counts a dispatched configuration change.
         *
         * @param config configuration of the running SDK
         */
        @Override
        protected void onSdkConfigurationChanged(InternalConfig config) {
            changes.incrementAndGet();
        }
    }

    /**
     * Installs a {@link CountingModule} for the next init.
     *
     * @return the module
     */
    private static CountingModule installCountingModule() {
        CountingModule module = new CountingModule();
        SDKCore.testDummyModule = module;
        return module;
    }

    /**
     * Every settings key the module declares, read off its {@code keyR} constants.
     *
     * @return the settings keys, without the top level keys
     */
    private static List<String> settingsKeysOfTheModule() throws IllegalAccessException {
        List<String> keys = new ArrayList<>();
        for (Field field : ModuleConfiguration.class.getDeclaredFields()) {
            if (field.getName().startsWith("keyR") && Modifier.isStatic(field.getModifiers())) {
                String key = (String) field.get(null);
                if (!TOP_LEVEL_KEYS.contains(key)) {
                    keys.add(key);
                }
            }
        }
        return keys;
    }

    /**
     * Runs a settings object through the validation of a response.
     *
     * @param module the module that validates
     * @param settings the settings object, as JSON text
     * @return the settings left after validation
     */
    private static JSONObject validated(ModuleConfiguration module, String settings) {
        JSONObject envelope = new JSONObject("{\"v\":1,\"t\":1,\"c\":" + settings + "}");
        Assert.assertTrue(module.validateServerConfig(envelope));
        return envelope.getJSONObject("c");
    }

    /**
     * Asserts whether validation keeps one setting.
     *
     * @param module the module that validates
     * @param key the settings key
     * @param literal the value, as JSON text
     * @param kept whether the setting must be kept
     */
    private static void assertKeptByValidation(ModuleConfiguration module, String key, String literal, boolean kept) {
        JSONObject settings = validated(module, "{\"" + key + "\":" + literal + "}");
        Assert.assertEquals("[" + key + "] = " + literal, kept, settings.has(key));
    }

    /**
     * Asserts the kind and the names of a filter.
     *
     * @param filter the filter
     * @param isWhitelist the expected kind
     * @param expected the expected names
     */
    private static void assertFilter(ConfigurationProvider.FilterList<Set<String>> filter, boolean isWhitelist, Set<String> expected) {
        Assert.assertEquals(isWhitelist, filter.isWhitelist());
        Assert.assertEquals(expected, filter.getFilterList());
    }

    /**
     * Asserts that a reader cannot change a collection.
     *
     * @param collection the collection
     */
    private static void assertUnmodifiable(Collection<String> collection) {
        try {
            collection.add("added_by_a_reader");
            Assert.fail("a reader can change " + collection);
        } catch (UnsupportedOperationException expected) {
            Assert.assertFalse(collection.contains("added_by_a_reader"));
        }
    }

    /**
     * Asserts that log gathering is on with the given directive.
     *
     * @param gatherId the gather id
     * @param levels the levels
     * @param batchSize the batch size
     */
    private static void assertGathering(String gatherId, String levels, int batchSize) {
        Assert.assertEquals(ConfigurationProvider.LogGatheringState.GATHERING, provider().getLogGatheringState());
        Assert.assertEquals(gatherId, provider().getLogGatheringId());
        Assert.assertEquals(levels, provider().getLogGatheringLevels());
        Assert.assertEquals(batchSize, provider().getLogGatheringBatchSize());
    }

    /**
     * Asserts that log gathering was decided off and nothing of a previous directive is left.
     */
    private static void assertNotGathering() {
        Assert.assertEquals(ConfigurationProvider.LogGatheringState.NOT_GATHERING, provider().getLogGatheringState());
        Assert.assertNull(provider().getLogGatheringId());
        Assert.assertEquals(ModuleConfiguration.logGatheringAllLevels, provider().getLogGatheringLevels());
        Assert.assertEquals(ModuleConfiguration.logGatheringDefaultBatchSize, provider().getLogGatheringBatchSize());
    }

    /**
     * Polls until the condition holds or the budget runs out.
     *
     * @param timeoutMs the budget
     * @param condition the condition
     * @return whether the condition holds
     */
    private static boolean waitFor(long timeoutMs, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }

    // endregion
}
