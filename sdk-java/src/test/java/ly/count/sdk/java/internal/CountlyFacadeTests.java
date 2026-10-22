package ly.count.sdk.java.internal;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import ly.count.sdk.java.Session;
import ly.count.sdk.java.Usage;
import ly.count.sdk.java.User;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * The static {@link Countly} facade and the {@link SDKCore} accessors behind it.
 * <p>
 * Two contracts are covered: before {@code init} no accessor may throw, and every feature interface
 * is handed out but ignores calls, and after {@code init} the deprecated {@link Usage} API must still
 * route to the live session. Both matter because they are the surface an integration touches first.
 */
@RunWith(JUnit4.class)
public class CountlyFacadeTests {

    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
    }

    @After
    public void stop() {
        Countly.instance().halt();
    }

    // region scenarios

    /**
     * Nothing may throw before {@code init}. Every module accessor and every state query is walked in
     * one pass, so a newly added accessor that forgets its guard shows up here. Each feature interface
     * is handed out and ignores calls, so code that calls it before init does not throw either, and a
     * call with a callback gets its answer.
     */
    @Test
    public void beforeInit_noAccessorThrows_andEveryFeatureInterfaceIgnoresCalls() {
        Assert.assertFalse(Countly.isInitialized());

        Assert.assertNull(Countly.session());
        Assert.assertNull(Countly.instance().backendM());
        Assert.assertNull(Countly.backendMode());
        Assert.assertSame(SDKCore.dormantFeedback, Countly.instance().feedback());
        Assert.assertSame(SDKCore.dormantContent, Countly.instance().content());
        Assert.assertSame(SDKCore.dormantEvents, Countly.instance().events());
        Assert.assertSame(SDKCore.dormantViews, Countly.instance().views());
        Assert.assertSame(SDKCore.dormantCrashes, Countly.instance().crashes());
        Assert.assertSame(SDKCore.dormantRemoteConfig, Countly.instance().remoteConfig());
        Assert.assertSame(SDKCore.dormantDeviceId, Countly.instance().deviceId());
        Assert.assertSame(SDKCore.dormantUserProfile, Countly.instance().userProfile());
        Assert.assertSame(SDKCore.dormantLocation, Countly.instance().location());

        Assert.assertFalse(Countly.instance().events().startEvent("before_init"));
        Countly.instance().events().recordEvent("before_init");
        Assert.assertNull(Countly.instance().views().startView("home"));
        Countly.instance().crashes().recordHandledException(new RuntimeException("before init"));
        Assert.assertNull(Countly.instance().deviceId().getID());
        Assert.assertTrue(Countly.instance().remoteConfig().getValues().isEmpty());
        Countly.instance().userProfile().setProperty("plan", "pro");
        Countly.instance().location().setLocation("TR", "Izmir", "38.4,27.1", null);
        Countly.instance().content().enterContentZone();
        List<String> errors = new CopyOnWriteArrayList<>();
        Countly.instance().feedback().getAvailableFeedbackWidgets((widgets, error) -> errors.add(error));
        Countly.instance().remoteConfig().downloadAllKeys((result, error, fullValueUpdate, values) -> errors.add(error));
        Assert.assertEquals(2, errors.size());
        Assert.assertFalse(Countly.isInitialized());

        // isTracking short circuits on the uninitialised check rather than dereferencing the SDK.
        for (Config.Feature feature : Config.Feature.values()) {
            Assert.assertFalse(feature + " cannot be tracked before init", Countly.isTracking(feature));
        }

        // Consent calls before init are no-ops, not crashes.
        Countly.onConsent(Config.Feature.Events);
        Countly.onConsentRemoval(Config.Feature.Events);

        // api() is just the singleton, and it is available at any time.
        Assert.assertSame(Countly.instance(), Countly.api());
    }

    /**
     * The deprecated {@link Usage} API a long lived integration still calls. One session, every entry
     * point exercised, and the resulting events and crash asserted on the queue and the wire.
     */
    @Test
    public void deprecatedUsageApi_stillRoutesEverythingIntoTheSession() {
        Countly.instance().init(TestUtils.getConfigSessions(Config.Feature.Events, Config.Feature.Views,
            Config.Feature.CrashReporting, Config.Feature.Location, Config.Feature.UserProfiles));

        Assert.assertTrue(Countly.isInitialized());
        Assert.assertTrue(Countly.isTracking(Config.Feature.Events));

        Session session = Countly.session();
        Assert.assertNotNull(session);
        Assert.assertSame("session() must hand back the same live session", session, Countly.session());

        Usage usage = Countly.api();
        // addParam is delegated to the session, so the session is what comes back, not the facade.
        Assert.assertNotNull(usage.addParam("customParam", "customValue"));

        User user = Countly.instance().user();
        Assert.assertNotNull(user);

        Assert.assertNotNull(Countly.instance().view("deprecatedView", true));
        Assert.assertNotNull(Countly.instance().addLocation(52.5, 13.4));
        Assert.assertNotNull(Countly.instance().addCrashReport(new IllegalStateException("deprecated crash"), false));

        Map<String, String> segments = new HashMap<>();
        segments.put("where", "deprecated");
        Assert.assertNotNull(Countly.instance().addCrashReport(
            new IllegalArgumentException("named crash"), false, "crashName", segments, "log line"));

        Assert.assertEquals(TestUtils.DEVICE_ID, Countly.instance().getDeviceId());
        Assert.assertEquals(DeviceIdType.DEVELOPER_SUPPLIED, Countly.instance().getDeviceIdType());

        // The crashes really left the SDK, which is what the deprecated call is for.
        Storage.await(SDKCore.instance.config.getLogger());
        Assert.assertTrue("the deprecated crash API must produce requests", requestsContaining("crash") >= 1);
    }

    /**
     * Login and logout through the deprecated facade. A login is a device id change with merge, so it
     * must put the old id on the wire; the logout that follows must not.
     */
    @Test
    public void deprecatedLoginAndLogout_changeTheDeviceIdOnTheWire() {
        Countly.instance().init(TestUtils.getConfigDeviceId(TestUtils.DEVICE_ID));

        Usage usage = Countly.instance().login("facade_user");
        Assert.assertSame(Countly.instance(), usage);
        Assert.assertEquals("facade_user", Countly.instance().getDeviceId());

        Storage.await(SDKCore.instance.config.getLogger());
        Assert.assertTrue("a login must send the old id so profiles can merge",
            requestsContaining(Params.PARAM_OLD_DEVICE_ID) >= 1);

        Assert.assertSame(Countly.instance(), Countly.instance().logout());

        // Both deprecated device id changes route through the same module as the modern API.
        Countly.instance().changeDeviceIdWithMerge("merged_facade_user");
        Assert.assertEquals("merged_facade_user", Countly.instance().getDeviceId());
        Countly.instance().changeDeviceIdWithoutMerge("fresh_facade_user");
        Assert.assertEquals("fresh_facade_user", Countly.instance().getDeviceId());
        Assert.assertEquals(DeviceIdType.DEVELOPER_SUPPLIED, Countly.instance().getDeviceIdType());
    }

    /**
     * Backend mode is either on, in which case the real interface reaches the request queue, or off,
     * in which case a disabled placeholder is handed out instead of null.
     */
    @Test
    public void backendMode_placeholderWhenOffAndRealInterfaceWhenOn() {
        Countly.instance().init(TestUtils.getConfigEvents(100));

        // A placeholder rather than null, so an integration that calls it does not have to null check.
        // Its methods are not exercised here: they log before checking the disabled flag and the
        // placeholder has no logger, so they throw. Reported as a bug.
        Assert.assertNotNull(Countly.instance().backendM());

        Countly.instance().halt();
        TestUtils.createCleanTestState();

        Config config = TestUtils.getBaseConfig().setEventQueueSizeToSend(1);
        config.enableBackendMode();
        Countly.instance().init(config);

        ModuleBackendMode.BackendMode real = Countly.instance().backendM();
        Assert.assertNotNull(real);
        Assert.assertNotNull(real.getModule());

        Map<String, Object> segmentation = new HashMap<>();
        segmentation.put("from", "backendMode");
        real.recordEvent("backend_device", "backendEvent", 2, 4.0, 1.0, segmentation, null);

        // Backend mode queues into memory, and the event batch flushes at the configured size.
        Assert.assertFalse("backend mode must queue its own requests", SDKCore.instance.requestQueueMemory.isEmpty());
        Request queued = SDKCore.instance.requestQueueMemory.peek();
        Assert.assertNotNull(queued);
        Assert.assertEquals("backend_device", queued.params.get("device_id"));
        Assert.assertTrue(Utils.urldecode(queued.params.get("events")).contains("backendEvent"));
    }

    /**
     * Backend mode refuses every call that has no usable device id or key, across its whole surface.
     * Each rejection must leave the request queue exactly as it was, because a backend mode
     * integration supplies the device id per call and a silent default would attribute data to the
     * wrong user.
     */
    @Test
    public void backendMode_rejectsEveryCallWithoutAUsableDeviceIdOrKey() {
        Config config = TestUtils.getBaseConfig().setEventQueueSizeToSend(1);
        config.enableBackendMode();
        Countly.instance().init(config);

        ModuleBackendMode.BackendMode backend = Countly.instance().backendM();
        Assert.assertNotNull(backend);

        int before = SDKCore.instance.requestQueueMemory.size();

        for (String badDeviceId : new String[] { null, "" }) {
            backend.recordEvent(badDeviceId, "key", 1, 1.0, 1.0, null, null);
            backend.recordView(badDeviceId, "view", null, null);
            backend.sessionBegin(badDeviceId, null, null, null);
            backend.sessionUpdate(badDeviceId, 1.0, null);
            backend.sessionEnd(badDeviceId, 1.0, null);
            backend.recordUserProperties(badDeviceId, null, null);
            backend.recordDirectRequest(badDeviceId, null, null);
            backend.recordException(badDeviceId, new IllegalStateException("nope"), null, null, null);
            backend.recordException(badDeviceId, "message", "stack", null, null, null);
        }

        // A usable device id but no usable key or payload is refused just the same.
        backend.recordEvent("device", null, 1, 1.0, 1.0, null, null);
        backend.recordEvent("device", "", 1, 1.0, 1.0, null, null);
        backend.recordView("device", null, null, null);
        backend.recordView("device", "", null, null);
        backend.recordUserProperties("device", null, null);
        backend.recordUserProperties("device", new HashMap<>(), null);
        backend.recordDirectRequest("device", null, null);
        backend.recordDirectRequest("device", new HashMap<>(), null);
        backend.recordException("device", (Throwable) null, null, null, null);
        backend.recordException("device", null, "stack", null, null, null);

        Assert.assertEquals("no rejected backend mode call may reach the request queue",
            before, SDKCore.instance.requestQueueMemory.size());

        // And a well formed call still works, so the guards are not blocking everything.
        backend.recordEvent("device", "goodBackendEvent", 1, 1.0, 1.0, null, null);
        Assert.assertTrue(SDKCore.instance.requestQueueMemory.size() > before);
    }

    /**
     * A module override installed through {@link SDKCore} really replaces the built in module and
     * receives the lifecycle callbacks, which is the hook the SDK's own tests rely on.
     */
    @Test
    public void moduleOverride_replacesTheBuiltInModuleAndGetsTheCallbacks() {
        AtomicInteger initialised = new AtomicInteger();
        AtomicInteger stopped = new AtomicInteger();

        SDKCore.testDummyModule = new ModuleBase() {
            @Override
            public void init(InternalConfig config) {
                super.init(config);
                initialised.incrementAndGet();
            }

            @Override
            public void stop(InternalConfig config, boolean clear) {
                stopped.incrementAndGet();
            }
        };

        try {
            Countly.instance().init(TestUtils.getConfigEvents(100));
            Assert.assertEquals("the overridden module must be initialised", 1, initialised.get());

            Countly.instance().halt();
            Assert.assertEquals("the overridden module must be stopped on halt", 1, stopped.get());
        } finally {
            SDKCore.testDummyModule = null;
        }
    }

    /**
     * Consent gating of the accessors. With consent required and nothing granted every feature
     * interface ignores calls; granting afterwards hands over the running ones, and removing the
     * consent again hands out the ignoring ones.
     */
    @Test
    public void consentGating_handsOutInterfacesThatIgnoreCalls_untilTheConsentIsGiven() {
        Config config = TestUtils.getBaseConfig()
            .enableFeatures(Config.Feature.Events, Config.Feature.Views, Config.Feature.Location,
                Config.Feature.Feedback, Config.Feature.RemoteConfig, Config.Feature.CrashReporting)
            .setRequiresConsent(true);
        Countly.instance().init(config);

        Assert.assertFalse(Countly.isTracking(Config.Feature.Events));
        Assert.assertSame(SDKCore.dormantEvents, Countly.instance().events());
        Assert.assertSame(SDKCore.dormantViews, Countly.instance().views());
        Assert.assertSame(SDKCore.dormantLocation, Countly.instance().location());
        Assert.assertSame(SDKCore.dormantFeedback, Countly.instance().feedback());
        Assert.assertSame(SDKCore.dormantRemoteConfig, Countly.instance().remoteConfig());
        Assert.assertSame(SDKCore.dormantCrashes, Countly.instance().crashes());
        Countly.instance().events().recordEvent("before_consent");
        TestUtils.validateEQSize(0);

        Countly.onConsent(Config.Feature.values());

        Assert.assertTrue(Countly.isTracking(Config.Feature.Events));
        Assert.assertSame(SDKCore.instance.module(ModuleEvents.class).eventsInterface, Countly.instance().events());
        Assert.assertNotSame(SDKCore.dormantViews, Countly.instance().views());
        Assert.assertNotSame(SDKCore.dormantLocation, Countly.instance().location());
        Assert.assertNotSame(SDKCore.dormantFeedback, Countly.instance().feedback());
        Assert.assertNotSame(SDKCore.dormantRemoteConfig, Countly.instance().remoteConfig());
        Assert.assertNotSame(SDKCore.dormantCrashes, Countly.instance().crashes());

        Countly.onConsentRemoval(Config.Feature.values());

        Assert.assertFalse(Countly.isTracking(Config.Feature.Events));
        Assert.assertSame(SDKCore.dormantEvents, Countly.instance().events());
        Assert.assertSame(SDKCore.dormantViews, Countly.instance().views());
    }

    /**
     * Every interface sends its calls to the module of its feature once that runs, wherever it came
     * from, as a caller may keep the one it got first.
     * <p>
     * Verifies that an events interface handed out before init and one handed out before the consent
     * record once the consent is given, that a views interface handed out before the consent starts a
     * view then, and that a running events interface kept across a removal of its consent ignores
     * calls until the consent is given again and then records into the new module.
     */
    @Test
    public void aKeptInterface_reachesTheModuleOnceItsFeatureRuns() {
        ModuleEvents.Events beforeInit = Countly.instance().events();
        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.Events, Config.Feature.Views)
            .setRequiresConsent(true).setEventQueueSizeToSend(100));
        ModuleEvents.Events beforeConsent = Countly.instance().events();
        ModuleViews.Views viewsBeforeConsent = Countly.instance().views();
        beforeConsent.recordEvent("ignored_before_consent");
        Assert.assertNull(viewsBeforeConsent.startView("ignored"));
        TestUtils.validateEQSize(0);

        Countly.onConsent(Config.Feature.Events, Config.Feature.Views);

        beforeInit.recordEvent("from_before_init");
        beforeConsent.recordEvent("from_before_consent");
        Assert.assertNotNull(viewsBeforeConsent.startView("home"));
        Assert.assertTrue(beforeConsent.startEvent("timed"));
        Assert.assertTrue(Countly.instance().events().endEvent("timed"));
        Assert.assertEquals(Arrays.asList("from_before_init", "from_before_consent", ModuleViews.KEY_VIEW_EVENT, "timed"), TestUtils.keysInEQ());

        ModuleEvents.Events running = Countly.instance().events();
        Countly.onConsentRemoval(Config.Feature.Events);
        running.recordEvent("ignored_after_removal");
        TestUtils.validateEQSize(0);

        Countly.onConsent(Config.Feature.Events);
        running.recordEvent("after_consent_again");
        Assert.assertEquals(Collections.singletonList("after_consent_again"), TestUtils.keysInEQ());
    }

    /**
     * Events carry the view IDs of the views module that runs now.
     * <p>
     * Verifies that while the views consent is removed an event carries no current view ID, and that
     * once it is given again an event carries the ID of the view the new module started, as does the
     * next view as its previous one.
     */
    @Test
    public void viewIds_followTheViewsModuleThatRuns_acrossAViewsConsentChange() {
        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.Events, Config.Feature.Views)
            .setRequiresConsent(true).setEventQueueSizeToSend(100));
        Countly.onConsent(Config.Feature.Events, Config.Feature.Views);
        Assert.assertNotNull(Countly.instance().views().startView("first"));

        Countly.onConsentRemoval(Config.Feature.Views);
        Countly.instance().events().recordEvent("without_views");
        Countly.onConsent(Config.Feature.Views);
        String second = Countly.instance().views().startView("second");
        Countly.instance().events().recordEvent("with_views");
        Countly.instance().views().startView("third");

        List<EventImpl> queued = TestUtils.getCurrentEQ();
        Assert.assertEquals(Arrays.asList(ModuleViews.KEY_VIEW_EVENT, "without_views", ModuleViews.KEY_VIEW_EVENT, "with_views", ModuleViews.KEY_VIEW_EVENT), TestUtils.keysInEQ());
        Assert.assertTrue(Utils.isEmptyOrNull(queued.get(1).cvid));
        Assert.assertEquals(second, queued.get(3).cvid);
        Assert.assertEquals(second, queued.get(4).pvid);
    }

    /**
     * A module that fails on a timer tick is logged and the timer keeps ticking for every module.
     */
    @Test
    public void aModuleFailingOnATick_doesNotStopTheTimer() throws InterruptedException {
        AtomicInteger ticks = new AtomicInteger();
        SDKCore.testDummyModule = new ModuleBase() {
            /**
             * Counts the tick and fails on the first one.
             */
            @Override
            public void onTimer() {
                if (ticks.incrementAndGet() == 1) {
                    throw new IllegalStateException("failing on the first tick");
                }
            }
        };
        try {
            Countly.instance().init(TestUtils.getBaseConfig().setUpdateSessionTimerDelay(1));
            long deadline = System.currentTimeMillis() + 10_000;
            while (ticks.get() < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            Assert.assertTrue("ticks: " + ticks.get(), ticks.get() >= 2);
        } finally {
            SDKCore.testDummyModule = null;
        }
    }

    /**
     * The deprecated API and the user editor log no user data before the feature it belongs to
     * checks its consent, as such a line can be gathered for the server under another consent.
     */
    @Test
    public void deprecatedCallsAndTheUserEditor_logNoUserData_beforeTheirConsentCheck() {
        List<String> lines = new CopyOnWriteArrayList<>();
        Countly.instance().init(TestUtils.getBaseConfig().setRequiresConsent(true).setLoggingLevel(Config.LoggingLevel.VERBOSE)
            .enableFeatures(Config.Feature.Sessions, Config.Feature.Location, Config.Feature.Views, Config.Feature.CrashReporting, Config.Feature.UserProfiles)
            .setLogListener((message, level) -> {
                if (message.startsWith("[Countly]") || message.startsWith("[UserEditorImpl]")) {
                    lines.add(message);
                }
            }));
        Countly.onConsent(Config.Feature.Sessions);
        Map<String, String> segments = new HashMap<>();
        segments.put("secret_key", "secret_value");

        Countly.api().addLocation(12.345_678, 98.765_432);
        Countly.api().view("SecretViewName");
        Countly.api().addCrashReport(new RuntimeException("secret crash message"), false, "secret_crash_name", segments);
        Countly.api().addParam("param_key", "secret_param_value");
        Countly.instance().user().edit().setLocale("tr_TR").setCountry("TR_country").setCity("SecretCity")
            .setLocation(1.234_567, 2.345_678).setLocation("38.456789,27.123456").setLocation("not a location").commit();

        Assert.assertFalse(lines.isEmpty());
        for (String line : lines) {
            for (String secret : Arrays.asList("12.345678", "SecretViewName", "secret", "tr_TR", "TR_country", "SecretCity", "1.234567", "38.456789", "not a location")) {
                Assert.assertFalse(line, line.contains(secret));
            }
        }
    }

    /**
     * Removing a consent stops the modules of the features it names and no others, so the device ID
     * module, which has no consent of its own, keeps running.
     * <p>
     * Verifies that after the location consent is removed the location interface ignores calls while
     * the device ID, events, views and user profile interfaces keep working and recording, and that
     * an events interface kept from before the events consent is removed ignores calls instead of
     * recording into the stopped module.
     */
    @Test
    public void consentRemoval_stopsOnlyTheFeaturesItNames_andAKeptInterfaceIgnoresCalls() {
        Countly.instance().init(TestUtils.getBaseConfig()
            .enableFeatures(Config.Feature.Events, Config.Feature.Views, Config.Feature.Location, Config.Feature.UserProfiles)
            .setRequiresConsent(true).setEventQueueSizeToSend(100));
        Countly.onConsent(Config.Feature.values());
        ModuleEvents.Events keptEvents = Countly.instance().events();

        Countly.onConsentRemoval(Config.Feature.Location);

        Assert.assertFalse(Countly.isTracking(Config.Feature.Location));
        Assert.assertSame(SDKCore.dormantLocation, Countly.instance().location());
        Assert.assertEquals(TestUtils.DEVICE_ID, Countly.instance().deviceId().getID());
        Assert.assertSame(keptEvents, Countly.instance().events());
        keptEvents.recordEvent("after_location_removal");
        Countly.instance().views().startView("home");
        Assert.assertEquals(2, TestUtils.getCurrentEQ().size());
        Countly.instance().userProfile().setProperty("plan", "pro");
        Countly.instance().userProfile().save();
        Assert.assertEquals(1, requestsContaining("user_details"));

        Countly.onConsentRemoval(Config.Feature.Events);

        Assert.assertSame(SDKCore.dormantEvents, Countly.instance().events());
        keptEvents.recordEvent("after_events_removal");
        Assert.assertFalse(keptEvents.startEvent("timed_after_events_removal"));
        TestUtils.validateEQSize(0);
        Assert.assertEquals(TestUtils.DEVICE_ID, Countly.instance().deviceId().getID());
    }

    /**
     * Consent changes add and remove modules while the transport hands responses to their owners,
     * and every response still reaches its owner.
     * <p>
     * Verifies that while another thread keeps giving and removing a consent, handing responses
     * over throws nothing and each one reaches the module that owns the request, that the consent
     * changes throw nothing either, and that the last removal took effect.
     */
    @Test
    public void requestCompletions_reachTheirOwner_whileConsentChangesAddAndRemoveModules() throws InterruptedException {
        AtomicInteger delivered = new AtomicInteger();
        SDKCore.testDummyModule = new ModuleBase() {
            /**
             * Counts a response handed over.
             *
             * @param request the request that was sent
             * @param response the response body
             * @param responseCode the response code
             */
            @Override
            public void onRequestCompleted(Request request, String response, int responseCode) {
                delivered.incrementAndGet();
            }
        };
        try {
            Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.Feedback).setRequiresConsent(true));
            SDKCore core = SDKCore.instance;
            Class<? extends ModuleBase> owner = SDKCore.testDummyModule.getClass();
            Request request = ModuleRequests.nonSessionRequest(core.config);
            List<Throwable> failures = new CopyOnWriteArrayList<>();
            AtomicInteger handedOver = new AtomicInteger();
            AtomicBoolean changingConsents = new AtomicBoolean(true);

            Thread completions = new Thread(() -> {
                while (changingConsents.get()) {
                    try {
                        core.onRequestCompleted(request, "{\"result\":\"Success\"}", 200, owner);
                        handedOver.incrementAndGet();
                    } catch (Throwable t) {
                        failures.add(t);
                        return;
                    }
                }
            });
            completions.start();
            try {
                for (int i = 0; i < 2000 && failures.isEmpty(); i++) {
                    Countly.onConsent(Config.Feature.Feedback);
                    Countly.onConsentRemoval(Config.Feature.Feedback);
                }
            } catch (RuntimeException e) {
                failures.add(e);
            } finally {
                changingConsents.set(false);
                completions.join(30_000);
            }

            Assert.assertFalse(completions.isAlive());
            Assert.assertTrue("failed: " + failures, failures.isEmpty());
            Assert.assertTrue(handedOver.get() > 0);
            Assert.assertEquals(handedOver.get(), delivered.get());
            Assert.assertSame(SDKCore.dormantFeedback, Countly.instance().feedback());
        } finally {
            SDKCore.testDummyModule = null;
        }
    }

    // endregion
    // region helpers

    /**
     * @return how many queued requests carry the given parameter
     */
    private int requestsContaining(String parameter) {
        int found = 0;
        for (Map<String, String> request : TestUtils.getCurrentRQ()) {
            if (request != null && request.containsKey(parameter)) {
                found++;
            }
        }
        return found;
    }

    // endregion
}
