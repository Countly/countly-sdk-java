package ly.count.sdk.java.internal;

import java.io.File;
import java.util.Map;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Regression tests for defects found while raising coverage. Each one is a public API that either
 * crashed or silently did the wrong thing under a perfectly ordinary configuration.
 */
@RunWith(JUnit4.class)
public class CoreRobustnessTests {

    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
    }

    @After
    public void stop() {
        Countly.instance().halt();
    }

    /**
     * The crash name given to "addCrashReport" has to reach the request.
     * <p>
     * The guard applying it was inverted, so a real name was dropped and an empty one was sent as
     * an empty "_name", which made that argument of the public API dead.
     */
    @Test
    public void crashName_isAppliedWhenGivenAndOmittedWhenNot() {
        Countly.instance().init(TestUtils.getConfigSessions(Config.Feature.CrashReporting));
        Countly.session().begin();

        Countly.session().addCrashReport(new IllegalStateException("boom"), false, "PaymentFailure", null);

        String named = findCrashRequest();
        Assert.assertNotNull("a crash request should have been queued", named);
        Assert.assertTrue("the crash name must reach the wire, got: " + named,
            named.contains("PaymentFailure"));

        // An empty name is not a name: it must not be sent as an empty _name.
        Countly.instance().halt();
        TestUtils.createCleanTestState();
        Countly.instance().init(TestUtils.getConfigSessions(Config.Feature.CrashReporting));
        Countly.session().begin();
        Countly.session().addCrashReport(new IllegalStateException("boom"), false, "", null);

        String unnamed = findCrashRequest();
        Assert.assertNotNull(unnamed);
        Assert.assertFalse("an empty name must not be sent, got: " + unnamed, unnamed.contains("_name"));
    }

    /**
     * A null config is refused by both init entry points instead of throwing. The guard existed but
     * was unreachable, because the config was dereferenced one line above it.
     */
    @Test
    public void init_withANullConfig_isRefusedWithoutCrashing() {
        Countly.instance().init(null);
        Assert.assertFalse(Countly.isInitialized());

        Countly.init(TestUtils.getTestSDirectory(), null);
        Assert.assertFalse(Countly.isInitialized());

        // Still usable with a real config afterwards.
        Countly.instance().init(TestUtils.getConfigEvents(2));
        Assert.assertTrue(Countly.isInitialized());
    }

    /**
     * Feature accessors for features that were never enabled return null rather than throwing.
     * <p>
     * They dereferenced a module that was never built, and consent is not the gate that stops them:
     * with consent not required, the consent check passes and the null module was used anyway.
     */
    @Test
    public void accessors_forFeaturesThatWereNeverEnabled_returnNull() {
        Countly.instance().init(TestUtils.getConfigEvents(2));

        Assert.assertNull(Countly.instance().feedback());
        Assert.assertNull(Countly.instance().crashes());
        Assert.assertNull(Countly.instance().remoteConfig());
        Assert.assertNull(Countly.instance().content());

        // The feature that IS enabled still works, so the guards did not break the happy path.
        Assert.assertNotNull(Countly.instance().events());
    }

    /**
     * Accessors used before init log and return null. "location" logged through a logger that does
     * not exist yet, unlike every sibling accessor.
     */
    @Test
    public void accessors_beforeInit_returnNullWithoutCrashing() {
        Countly.instance().halt();

        Assert.assertNull(Countly.instance().location());
        Assert.assertNull(Countly.instance().feedback());
        Assert.assertNull(Countly.instance().content());
        Assert.assertNull(Countly.instance().remoteConfig());
        Assert.assertNull(Countly.instance().backendM());
    }

    /**
     * The placeholder handed out when backend mode is off is inert: every call is a no-op, nothing
     * is queued, and nothing throws. It used to log before checking it was disabled, through a
     * logger it never had.
     */
    @Test
    public void backendModePlaceholder_isInertInsteadOfCrashing() {
        Countly.instance().init(TestUtils.getConfigEvents(2));

        ModuleBackendMode.BackendMode backendMode = Countly.instance().backendM();
        Assert.assertNotNull("a dummy is expected, not null", backendMode);

        int before = TestUtils.getCurrentRQ().length;

        backendMode.recordEvent("device", "key", 1, 0.0, 0.0, null, null);
        backendMode.recordView("device", "view", null, null);
        backendMode.sessionBegin("device", null, null, null);
        backendMode.sessionUpdate("device", 1.0, null);
        backendMode.sessionEnd("device", 1.0, null);
        backendMode.recordException("device", new IllegalStateException("boom"), null, null, null);
        backendMode.recordUserProperties("device", null, null);
        backendMode.recordDirectRequest("device", null, null);

        Assert.assertEquals("an inert placeholder must not queue anything", before, TestUtils.getCurrentRQ().length);
    }

    /**
     * A pin that is not valid base64 is reported and skipped, rather than taking init down with it.
     */
    @Test
    public void aMalformedPin_doesNotBreakInit() {
        Config config = TestUtils.getConfigEvents(2)
            .addPublicKeyPin("this is definitely not base64 !!!")
            .addCertificatePin("neither is this !!!");

        Countly.instance().init(config);

        Assert.assertTrue("a bad pin must be reported, not fatal", Countly.isInitialized());
    }

    /**
     * A storable that cannot serialise itself reports failure and leaves no file behind. It used to
     * write a zero length file that later read back as a corrupt storable.
     */
    @Test
    public void aStorableThatCannotSerialise_leavesNoFileBehind() {
        Countly.instance().init(TestUtils.getConfigEvents(2));
        InternalConfig config = SDKCore.instance.config;

        UnserialisableStorable storable = new UnserialisableStorable();
        Assert.assertFalse("a failed write must report failure", Storage.push(config, storable));

        File[] litter = TestUtils.getTestSDirectory().listFiles((dir, name) -> name.contains(UnserialisableStorable.PREFIX));
        Assert.assertNotNull(litter);
        Assert.assertEquals("no file should have been created, found: " + litter.length, 0, litter.length);
    }

    /**
     * The queued crash request, or null when none was queued.
     *
     * @return the value of the "crash" parameter
     */
    private static String findCrashRequest() {
        for (Map<String, String> request : TestUtils.getCurrentRQ()) {
            if (request.containsKey("crash")) {
                return request.get("crash");
            }
        }
        return null;
    }

    private static class UnserialisableStorable implements Storable {

        static final String PREFIX = "unserialisable";

        @Override
        public Long storageId() {
            return 12345L;
        }

        @Override
        public String storagePrefix() {
            return PREFIX;
        }

        @Override
        public void setId(Long id) {
        }

        @Override
        public byte[] store(Log L) {
            return null;
        }

        @Override
        public boolean restore(byte[] data, Log L) {
            return false;
        }
    }
}
