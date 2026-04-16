package ly.count.sdk.java.internal;

import java.io.File;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for SDK initialization ordering and recovery scenarios.
 *
 * These tests verify that the SDK initializes correctly when leftover
 * crash files or session files exist from a previous run. This covers
 * the fix for GitHub issue #263 (NPE in SDKCore.recover() when a crash
 * file exists) and related initialization ordering concerns.
 */
@RunWith(JUnit4.class)
public class ScenarioInitRecoveryTests {

    /** Time to wait for async storage/networking operations to settle */
    private static final int ASYNC_SETTLE_MS = 200;

    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
    }

    @After
    public void stop() {
        Countly.instance().halt();
    }

    // ── Test helpers ────────────────────────────────────────────────────

    private JSONObject createCrashData(String error, boolean nonfatal) {
        JSONObject data = new JSONObject();
        data.put("_error", error);
        data.put("_nonfatal", nonfatal);
        return data;
    }

    private long plantCrashFile(JSONObject crashData) {
        long crashId = TimeUtils.uniqueTimestampMs();
        TestUtils.writeToFile("crash_" + crashId, crashData.toString());
        return crashId;
    }

    private long plantCrashFileAndInit(Config config) throws InterruptedException {
        long crashId = plantCrashFile(createCrashData("java.lang.RuntimeException: test", false));
        Countly.instance().init(config);
        Thread.sleep(ASYNC_SETTLE_MS);
        return crashId;
    }

    private void assertCrashFileRemoved(long crashId) {
        File crashFile = new File(TestUtils.getTestSDirectory(), "[CLY]_crash_" + crashId);
        Assert.assertFalse("Crash file " + crashId + " should be removed after recovery", crashFile.exists());
    }

    private void assertCrashFileExists(long crashId) {
        File crashFile = new File(TestUtils.getTestSDirectory(), "[CLY]_crash_" + crashId);
        Assert.assertTrue("Crash file " + crashId + " should still exist", crashFile.exists());
    }

    private void assertMinRequestFiles(int expectedMin) {
        File testDir = TestUtils.getTestSDirectory();
        File[] requestFiles = testDir.listFiles((dir, name) -> name.startsWith("[CLY]_request_"));
        Assert.assertNotNull("Request file listing should not be null", requestFiles);
        Assert.assertTrue("Expected at least " + expectedMin + " request file(s), found " + requestFiles.length,
            requestFiles.length >= expectedMin);
    }

    private void withNullNetworking(Runnable action) {
        Networking saved = SDKCore.instance.networking;
        SDKCore.instance.networking = null;
        try {
            action.run();
        } finally {
            SDKCore.instance.networking = saved;
        }
    }

    // ── Crash recovery tests ────────────────────────────────────────────

    /**
     * "init_withExistingCrashFile"
     * Primary regression test for issue #263.
     * Crash file should be converted into a request and removed from disk.
     */
    @Test
    public void init_withExistingCrashFile() throws InterruptedException {
        JSONObject crashData = createCrashData(
            "java.lang.RuntimeException: test crash\n\tat com.test.App.main(App.java:10)", false);
        crashData.put("_os", "Java");
        crashData.put("_os_version", "17");
        crashData.put("_device", "TestDevice");

        long crashId = plantCrashFile(crashData);
        assertCrashFileExists(crashId);

        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.CrashReporting));
        Thread.sleep(ASYNC_SETTLE_MS);

        assertCrashFileRemoved(crashId);
        assertMinRequestFiles(1);
    }

    /**
     * "init_withExistingCrashFile_noFeatureEnabled"
     * Crash file recovery happens at SDKCore level regardless of feature flags.
     * The crash file should still be processed and removed.
     */
    @Test
    public void init_withExistingCrashFile_noFeatureEnabled() throws InterruptedException {
        long crashId = plantCrashFile(createCrashData("java.lang.NullPointerException: test", true));

        Countly.instance().init(TestUtils.getBaseConfig());
        Thread.sleep(ASYNC_SETTLE_MS);

        Assert.assertTrue("SDK should be initialized", Countly.isInitialized());
        assertCrashFileRemoved(crashId);
        assertMinRequestFiles(1);
    }

    /**
     * "init_withMultipleCrashFiles"
     * All crash files should be converted to requests and removed.
     */
    @Test
    public void init_withMultipleCrashFiles() throws InterruptedException {
        long[] crashIds = new long[3];
        for (int i = 0; i < 3; i++) {
            crashIds[i] = plantCrashFile(createCrashData("Exception #" + i, i % 2 == 0));
        }

        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.CrashReporting));
        Thread.sleep(ASYNC_SETTLE_MS);

        for (int i = 0; i < 3; i++) {
            assertCrashFileRemoved(crashIds[i]);
        }
        assertMinRequestFiles(3);
    }

    // ── Session recovery tests ──────────────────────────────────────────

    /**
     * "init_withExistingSessionFile"
     * Session recovery calls session.end() -> onSignal(Ping) -> networking.check().
     * Uses stop() (not halt()) to preserve session files on disk.
     */
    @Test
    public void init_withExistingSessionFile() throws InterruptedException {
        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.Sessions));
        Countly.instance().session().begin();
        Thread.sleep(ASYNC_SETTLE_MS);

        // stop() preserves files; halt() would delete them
        Countly.instance().stop();

        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.Sessions));
        Thread.sleep(ASYNC_SETTLE_MS);

        Assert.assertTrue("SDK should be initialized after session recovery", Countly.isInitialized());
    }

    /**
     * "init_withCrashAndSessionFiles"
     * Both crash files and session files present simultaneously.
     * Verifies recover() processes both crash loop and session loop without interference.
     */
    @Test
    public void init_withCrashAndSessionFiles() throws InterruptedException {
        // First init: create a session file
        Countly.instance().init(TestUtils.getBaseConfig()
            .enableFeatures(Config.Feature.Sessions, Config.Feature.CrashReporting));
        Countly.instance().session().begin();
        Thread.sleep(ASYNC_SETTLE_MS);
        Countly.instance().stop();

        // Plant a crash file on top of the leftover session file
        long crashId = plantCrashFile(createCrashData("java.lang.RuntimeException: dual recovery", false));

        // Re-init should recover both
        Countly.instance().init(TestUtils.getBaseConfig()
            .enableFeatures(Config.Feature.Sessions, Config.Feature.CrashReporting));
        Thread.sleep(ASYNC_SETTLE_MS);

        Assert.assertTrue("SDK should initialize with both crash and session files", Countly.isInitialized());
        assertCrashFileRemoved(crashId);
    }

    // ── Corrupt/empty file resilience ───────────────────────────────────

    /**
     * "init_withCorruptCrashFile"
     * Corrupt crash file should not block initialization.
     * processCrash returns false when Storage.read fails, so the file is NOT removed
     * (the remove only happens after successful push). The SDK should still init.
     */
    @Test
    public void init_withCorruptCrashFile() throws InterruptedException {
        long crashId = System.currentTimeMillis() - 1000;
        TestUtils.writeToFile("crash_" + crashId, "this is not valid json {{{");

        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.CrashReporting));
        Thread.sleep(ASYNC_SETTLE_MS);

        Assert.assertTrue("SDK should be initialized even with corrupt crash file", Countly.isInitialized());
        // Corrupt file is NOT cleaned up by processCrash (it returns false at the null check)
        // This is expected — the file will be retried on next init
    }

    /**
     * "init_emptyCrashFile"
     * Empty crash file should not cause initialization failure.
     */
    @Test
    public void init_emptyCrashFile() throws InterruptedException {
        long crashId = System.currentTimeMillis() - 1000;
        TestUtils.writeToFile("crash_" + crashId, "");

        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.CrashReporting));
        Thread.sleep(ASYNC_SETTLE_MS);

        Assert.assertTrue("SDK should initialize with empty crash file", Countly.isInitialized());
    }

    // ── Feature combination tests ───────────────────────────────────────

    /**
     * "init_withCrashFile_remoteConfigEnabled"
     * Exercises both crash recovery and ModuleRemoteConfig.initFinished()
     * needing networking to be ready.
     */
    @Test
    public void init_withCrashFile_remoteConfigEnabled() throws InterruptedException {
        long crashId = plantCrashFileAndInit(TestUtils.getBaseConfig()
            .enableFeatures(Config.Feature.CrashReporting, Config.Feature.RemoteConfig));

        Assert.assertTrue("SDK should initialize with crash file + remote config", Countly.isInitialized());
        assertCrashFileRemoved(crashId);
        assertMinRequestFiles(1);
    }

    /**
     * "init_withCrashFile_locationEnabled"
     * ModuleLocation.initFinished() -> sendLocation() -> onSignal(Ping) -> networking.check().
     */
    @Test
    public void init_withCrashFile_locationEnabled() throws InterruptedException {
        long crashId = plantCrashFileAndInit(TestUtils.getBaseConfig()
            .enableFeatures(Config.Feature.CrashReporting, Config.Feature.Location)
            .setLocation("US", "New York", "40.7128,-74.0060", null));

        Assert.assertTrue("SDK should initialize with crash file + location", Countly.isInitialized());
        assertCrashFileRemoved(crashId);
        assertMinRequestFiles(1);
    }

    /**
     * "init_withCrashFile_allFeaturesEnabled"
     * Stress test: all module initFinished() paths exercised simultaneously.
     */
    @Test
    public void init_withCrashFile_allFeaturesEnabled() throws InterruptedException {
        long crashId = plantCrashFileAndInit(TestUtils.getBaseConfig()
            .enableFeatures(
                Config.Feature.CrashReporting,
                Config.Feature.Events,
                Config.Feature.Sessions,
                Config.Feature.Views,
                Config.Feature.Location,
                Config.Feature.RemoteConfig,
                Config.Feature.Feedback
            ));

        Assert.assertTrue("SDK should initialize with crash file + all features", Countly.isInitialized());
        assertCrashFileRemoved(crashId);
        assertMinRequestFiles(1);
    }

    // ── Networking null-safety tests ────────────────────────────────────

    /**
     * "init_networkingNullSafety_onSignalDID"
     * Validates the null guard in SDKCore.onSignal(config, id) for DID signal.
     */
    @Test
    public void init_networkingNullSafety_onSignalDID() {
        Countly.instance().init(TestUtils.getBaseConfig());

        withNullNetworking(() ->
            SDKCore.instance.onSignal(SDKCore.instance.config, SDKCore.Signal.DID.getIndex()));

        Assert.assertTrue("SDK should remain functional", Countly.isInitialized());
    }

    /**
     * "init_networkingNullSafety_onSignalPing"
     * Validates the null guard in SDKCore.onSignal(config, id, param) for Ping signal.
     */
    @Test
    public void init_networkingNullSafety_onSignalPing() {
        Countly.instance().init(TestUtils.getBaseConfig());

        withNullNetworking(() ->
            SDKCore.instance.onSignal(SDKCore.instance.config, SDKCore.Signal.Ping.getIndex(), null));

        Assert.assertTrue("SDK should remain functional", Countly.isInitialized());
    }

    /**
     * "init_networkingNullSafety_processCrash"
     * Plants a crash file AFTER init, nulls networking, then triggers the crash signal.
     * This exercises the null guard inside processCrash() directly.
     */
    @Test
    public void init_networkingNullSafety_processCrash() throws InterruptedException {
        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.CrashReporting));
        Thread.sleep(ASYNC_SETTLE_MS);

        // Plant crash after init so it hasn't been processed yet
        long crashId = plantCrashFile(createCrashData("java.lang.RuntimeException: post-init crash", false));

        withNullNetworking(() ->
            SDKCore.instance.onSignal(SDKCore.instance.config, SDKCore.Signal.Crash.getIndex(), String.valueOf(crashId)));

        Assert.assertTrue("SDK should remain functional after processCrash with null networking", Countly.isInitialized());
    }

    // ── Regression tests ────────────────────────────────────────────────

    /**
     * "init_repeatInit_withCrashFile"
     * Verifies crash file is removed on first init so it doesn't permanently block startup.
     * This was the user-visible symptom of issue #263.
     */
    @Test
    public void init_repeatInit_withCrashFile() throws InterruptedException {
        long crashId = plantCrashFileAndInit(
            TestUtils.getBaseConfig().enableFeatures(Config.Feature.CrashReporting));

        Assert.assertTrue("First init should succeed", Countly.isInitialized());
        assertCrashFileRemoved(crashId);

        Countly.instance().halt();

        // Second init — no crash file to recover
        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.CrashReporting));
        Thread.sleep(ASYNC_SETTLE_MS);
        Assert.assertTrue("Second init should succeed without leftover crash files", Countly.isInitialized());
    }
}
