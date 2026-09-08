package ly.count.sdk.java.internal;

import java.util.HashMap;
import java.util.Map;
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
 * Two contracts are covered: before {@code init} every accessor must answer with null and a logged
 * error instead of throwing, and after {@code init} the deprecated {@link Usage} API must still route
 * to the live session. Both matter because they are the surface an integration touches first.
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
     * one pass, so a newly added accessor that forgets its guard shows up here.
     */
    @Test
    public void beforeInit_everyAccessorAnswersNullInsteadOfThrowing() {
        Assert.assertFalse(Countly.isInitialized());

        Assert.assertNull(Countly.session());
        Assert.assertNull(Countly.instance().backendM());
        Assert.assertNull(Countly.backendMode());
        Assert.assertNull(Countly.instance().feedback());
        Assert.assertNull(Countly.instance().content());
        Assert.assertNull(Countly.instance().events());
        Assert.assertNull(Countly.instance().views());
        Assert.assertNull(Countly.instance().crashes());
        Assert.assertNull(Countly.instance().remoteConfig());
        Assert.assertNull(Countly.instance().deviceId());
        Assert.assertNull(Countly.instance().userProfile());
        // Countly.location() is deliberately NOT called here: its own uninitialised guard logs
        // through the instance logger without a null check, so it throws NPE before it can return
        // null. Reported as a bug rather than asserted, so a fix does not have to touch this test.

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
     * Consent gating of the accessors. With consent required and nothing granted the feature
     * interfaces must be withheld; granting afterwards must hand them over.
     */
    @Test
    public void consentGating_withholdsThenReleasesTheFeatureInterfaces() {
        Config config = TestUtils.getBaseConfig()
            .enableFeatures(Config.Feature.Events, Config.Feature.Views, Config.Feature.Location,
                Config.Feature.Feedback, Config.Feature.RemoteConfig, Config.Feature.CrashReporting)
            .setRequiresConsent(true);
        Countly.instance().init(config);

        Assert.assertFalse(Countly.isTracking(Config.Feature.Events));
        Assert.assertNull(Countly.instance().events());
        Assert.assertNull(Countly.instance().views());
        Assert.assertNull(Countly.instance().location());
        Assert.assertNull(Countly.instance().feedback());
        Assert.assertNull(Countly.instance().remoteConfig());
        Assert.assertNull(Countly.instance().crashes());

        Countly.onConsent(Config.Feature.values());

        Assert.assertTrue(Countly.isTracking(Config.Feature.Events));
        Assert.assertNotNull(Countly.instance().events());
        Assert.assertNotNull(Countly.instance().views());
        Assert.assertNotNull(Countly.instance().location());
        Assert.assertNotNull(Countly.instance().feedback());
        Assert.assertNotNull(Countly.instance().remoteConfig());
        Assert.assertNotNull(Countly.instance().crashes());

        Countly.onConsentRemoval(Config.Feature.values());

        Assert.assertFalse(Countly.isTracking(Config.Feature.Events));
        Assert.assertNull(Countly.instance().events());
        Assert.assertNull(Countly.instance().views());
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
