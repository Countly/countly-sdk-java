package ly.count.sdk.java.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * The {@link Config} builder: its fluent chain, its validation, and the enums a customer's
 * configuration is expressed in.
 * <p>
 * {@code ConfigTests} covers the defaults and the storage plumbing. This covers the rest of the
 * surface, and where a setting is observable from outside the SDK it is asserted on the wire rather
 * than on the field it was written to.
 */
@RunWith(JUnit4.class)
public class ConfigSurfaceTests {

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
     * The whole fluent chain in one pass, with values that must all be accepted, and then the
     * getters confirming each one landed. Proves the builder really is chainable and that nothing
     * silently overwrites a neighbouring setting.
     */
    @Test
    public void fluentChain_acceptsEverySaneValueAndReadsItBack() {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Chain", "yes");

        Config config = TestUtils.getBaseConfig()
            .setApplicationName("ChainedApp")
            .setUpdateSessionTimerDelay(20)
            .setEventQueueSizeToSend(9)
            .setRequestQueueMaxSize(321)
            .setMaxBreadcrumbCount(12)
            .setNetworkConnectTimeout(11)
            .setNetworkReadTimeout(12)
            .setNetworkRequestCooldown(1300)
            .setNetworkImportantRequestCooldown(7)
            .setLoggingLevel(Config.LoggingLevel.WARN)
            .setSdkPlatform("test-platform")
            .addCustomNetworkRequestHeaders(headers)
            .enableParameterTamperingProtection("chain-salt")
            .disableUnhandledCrashReporting()
            .enableFeatures(Config.Feature.Events, Config.Feature.Views);

        // setApplicationName / getApplicationName are documented deprecated no-ops.
        Assert.assertEquals("", config.getApplicationName());
        Assert.assertEquals(20, config.getSendUpdateEachSeconds());
        Assert.assertEquals(9, config.getEventsBufferSize());
        Assert.assertEquals(321, config.getRequestQueueMaxSize());
        Assert.assertEquals(11, config.getNetworkConnectionTimeout());
        Assert.assertEquals(12, config.getNetworkReadTimeout());
        Assert.assertEquals(1300, config.getNetworkRequestCooldown());
        Assert.assertEquals(7, config.getNetworkImportantRequestCooldown());
        Assert.assertEquals(Config.LoggingLevel.WARN, config.getLoggingLevel());
        Assert.assertEquals("chain-salt", config.getParameterTamperingProtectionSalt());
        Assert.assertEquals("yes", config.getCustomNetworkRequestHeaders().get("X-Chain"));

        Assert.assertTrue(config.isFeatureEnabled(Config.Feature.Events));
        Assert.assertTrue(config.isFeatureEnabled(Config.Feature.Views));
        Assert.assertFalse(config.isFeatureEnabled(Config.Feature.Location));
        Assert.assertTrue(config.isFeatureEnabled(Config.Feature.Events.getIndex()));

        Set<Config.Feature> enabled = config.getFeatures();
        Assert.assertEquals(2, enabled.size());
        Assert.assertTrue(enabled.contains(Config.Feature.Events));
        Assert.assertTrue(enabled.contains(Config.Feature.Views));
        Assert.assertEquals(Config.Feature.Events.getIndex() | Config.Feature.Views.getIndex(), config.getFeaturesMap());

        // The platform really reaches the server, which is the only reason it is configurable.
        Countly.instance().init(config);
        Storage.await(SDKCore.instance.config.getLogger());
        Assert.assertEquals("test-platform", SDKCore.instance.config.getSdkPlatform());
        // These two are only readable off the internal config, which is what the modules see.
        Assert.assertEquals(12, SDKCore.instance.config.getMaxBreadcrumbCount());
        Assert.assertFalse(SDKCore.instance.config.isUnhandledCrashReportingEnabled());
    }

    /**
     * Every out of range value the network and session setters must refuse, walked in one loop. Each
     * rejection has to leave the previous good value in place, because silently accepting a nonsense
     * timeout would strand a customer's requests.
     */
    @Test
    public void outOfRangeValues_areRefusedAndLeaveTheGoodValueInPlace() {
        Config config = TestUtils.getBaseConfig()
            .setNetworkConnectTimeout(10)
            .setNetworkReadTimeout(10)
            .setNetworkRequestCooldown(100)
            .setNetworkImportantRequestCooldown(5);

        // connect timeout and read timeout accept 1..300 only
        for (int bad : new int[] { 0, -1, 301, Integer.MAX_VALUE }) {
            config.setNetworkConnectTimeout(bad);
            Assert.assertEquals("connect timeout must reject " + bad, 10, config.getNetworkConnectionTimeout());
            config.setNetworkReadTimeout(bad);
            Assert.assertEquals("read timeout must reject " + bad, 10, config.getNetworkReadTimeout());
        }

        // request cooldown accepts 0..30000
        for (int bad : new int[] { -1, 30001 }) {
            config.setNetworkRequestCooldown(bad);
            Assert.assertEquals("request cooldown must reject " + bad, 100, config.getNetworkRequestCooldown());
        }

        // important request cooldown accepts 0..30
        for (int bad : new int[] { -1, 31 }) {
            config.setNetworkImportantRequestCooldown(bad);
            Assert.assertEquals("important cooldown must reject " + bad, 5, config.getNetworkImportantRequestCooldown());
        }

        // The deprecated setters are aliases of the current ones, so each pair must move together
        // rather than quietly maintaining two different values.
        config.setSendUpdateEachSeconds(45);
        Assert.assertEquals(45, config.getSendUpdateEachSeconds());
        config.setUpdateSessionTimerDelay(60);
        Assert.assertEquals(60, config.getSendUpdateEachSeconds());
        config.setUpdateSessionTimerDelay(-1);
        Assert.assertEquals("a negative session timer delay must be refused", 60, config.getSendUpdateEachSeconds());

        config.setEventsBufferSize(17);
        Assert.assertEquals(17, config.getEventsBufferSize());
        config.setEventQueueSizeToSend(23);
        Assert.assertEquals(23, config.getEventsBufferSize());
        config.setEventQueueSizeToSend(-5);
        Assert.assertEquals("a negative event queue size must be refused", 23, config.getEventsBufferSize());

        // The boundary values themselves are valid.
        Assert.assertEquals(300, config.setNetworkConnectTimeout(300).getNetworkConnectionTimeout());
        Assert.assertEquals(1, config.setNetworkReadTimeout(1).getNetworkReadTimeout());
        Assert.assertEquals(0, config.setNetworkRequestCooldown(0).getNetworkRequestCooldown());
        Assert.assertEquals(30000, config.setNetworkRequestCooldown(30000).getNetworkRequestCooldown());
        Assert.assertEquals(30, config.setNetworkImportantRequestCooldown(30).getNetworkImportantRequestCooldown());
    }

    /**
     * Enabling, disabling and replacing the feature set, including the null inputs a caller can
     * stumble into. The feature bitmask must never be corrupted by a bad argument.
     */
    @Test
    public void featureSet_isBuiltAndUnbuiltWithoutBeingCorrupted() {
        Config config = TestUtils.getBaseConfig();

        config.enableFeatures(Config.Feature.Events, Config.Feature.Views, Config.Feature.Location);
        Assert.assertEquals(3, config.getFeatures().size());

        config.disableFeatures(Config.Feature.Views);
        Assert.assertFalse(config.isFeatureEnabled(Config.Feature.Views));
        Assert.assertTrue(config.isFeatureEnabled(Config.Feature.Events));
        Assert.assertTrue(config.isFeatureEnabled(Config.Feature.Location));

        // setFeatures replaces rather than adds.
        config.setFeatures(Config.Feature.Feedback);
        Assert.assertEquals(1, config.getFeatures().size());
        Assert.assertTrue(config.isFeatureEnabled(Config.Feature.Feedback));
        Assert.assertFalse(config.isFeatureEnabled(Config.Feature.Events));

        // Null arrays and null elements are ignored, not fatal, and leave the mask untouched.
        int before = config.getFeaturesMap();
        config.enableFeatures((Config.Feature[]) null);
        config.disableFeatures((Config.Feature[]) null);
        config.enableFeatures(Config.Feature.Events, null);
        config.disableFeatures(Config.Feature.Events, null);
        Assert.assertEquals(before, config.getFeaturesMap());

        // setFeatures with nothing at all clears the mask.
        config.setFeatures();
        Assert.assertEquals(0, config.getFeaturesMap());
        config.setFeatures((Config.Feature[]) null);
        Assert.assertEquals(0, config.getFeaturesMap());
        config.setFeatures(Config.Feature.Events, null);
        Assert.assertEquals(Config.Feature.Events.getIndex(), config.getFeaturesMap());
    }

    /**
     * The device id strategy, which decides whether the SDK invents an id or uses the customer's.
     * A custom strategy without an id must fall back rather than leave the SDK with no id at all.
     */
    @Test
    public void deviceIdStrategy_switchesAndFallsBackWhenNoIdIsGiven() {
        Config config = TestUtils.getBaseConfig();

        Assert.assertEquals(Config.DeviceIdStrategy.CUSTOM_ID, config.getDeviceIdStrategyEnum());

        config.setDeviceIdStrategy(Config.DeviceIdStrategy.UUID);
        Assert.assertEquals(Config.DeviceIdStrategy.UUID, config.getDeviceIdStrategyEnum());

        config.setDeviceIdStrategy(Config.DeviceIdStrategy.CUSTOM_ID, "chosen_id");
        Assert.assertEquals(Config.DeviceIdStrategy.CUSTOM_ID, config.getDeviceIdStrategyEnum());
        Assert.assertEquals("chosen_id", config.getCustomDeviceId());

        // Custom strategy with no usable id drops back to the generated strategy.
        for (String bad : new String[] { null, "" }) {
            config.setDeviceIdStrategy(Config.DeviceIdStrategy.CUSTOM_ID, bad);
            Assert.assertEquals(Config.DeviceIdStrategy.UUID, config.getDeviceIdStrategyEnum());
            Assert.assertNull(config.getCustomDeviceId());
        }

        // A null strategy is ignored rather than clearing the current one.
        config.setDeviceIdStrategy(Config.DeviceIdStrategy.CUSTOM_ID, "kept_id");
        config.setDeviceIdStrategy(null);
        Assert.assertEquals(Config.DeviceIdStrategy.CUSTOM_ID, config.getDeviceIdStrategyEnum());
        Assert.assertEquals("kept_id", config.getCustomDeviceId());
    }

    /**
     * The enums a configuration is expressed in. Index round trips must be exact, because they are
     * what the consent bitmask and the persisted device id strategy are stored as.
     */
    @Test
    public void configEnums_roundTripThroughTheirIndexes() {
        for (Config.Feature feature : Config.Feature.values()) {
            Assert.assertSame("Feature." + feature + " must round trip", feature, Config.Feature.byIndex(feature.getIndex()));
        }
        Assert.assertNull("an unknown feature index has no feature", Config.Feature.byIndex(0));
        Assert.assertNull(Config.Feature.byIndex(1 << 30));

        for (Config.DeviceIdStrategy strategy : Config.DeviceIdStrategy.values()) {
            Assert.assertSame(strategy, Config.DeviceIdStrategy.fromIndex(strategy.getIndex()));
        }
        Assert.assertNull(Config.DeviceIdStrategy.fromIndex(7));

        // Logging levels are ordered, and "prints" is what the logger gates on.
        Assert.assertEquals(0, Config.LoggingLevel.VERBOSE.getLevel());
        Assert.assertEquals(5, Config.LoggingLevel.OFF.getLevel());
        Assert.assertTrue(Config.LoggingLevel.VERBOSE.prints(Config.LoggingLevel.ERROR));
        Assert.assertFalse(Config.LoggingLevel.ERROR.prints(Config.LoggingLevel.VERBOSE));
    }

    /**
     * The device id holder, which storage and the request queue compare instances of. Equality must
     * take both the id and the strategy into account, or a strategy change would go unnoticed.
     */
    @Test
    public void deviceIdHolder_comparesIdAndStrategy() {
        Config.DID custom = new Config.DID(Config.DID.STRATEGY_CUSTOM, "same_id");
        Config.DID sameAgain = new Config.DID(Config.DID.STRATEGY_CUSTOM, "same_id");
        Config.DID differentStrategy = new Config.DID(Config.DID.STRATEGY_UUID, "same_id");
        Config.DID differentId = new Config.DID(Config.DID.STRATEGY_CUSTOM, "other_id");

        Assert.assertEquals(custom, sameAgain);
        Assert.assertEquals(custom.hashCode(), sameAgain.hashCode());
        Assert.assertNotEquals(custom, differentStrategy);
        Assert.assertNotEquals(custom, differentId);
        Assert.assertNotEquals(custom, "not a did");
        Assert.assertTrue(custom.toString().contains("same_id"));
    }

    /**
     * A log listener installed through the config really receives what the SDK logs, which is the
     * only way an integrator can pipe SDK output into their own logging.
     */
    @Test
    public void logListener_receivesWhatTheSdkLogs() {
        StringBuilder captured = new StringBuilder();

        Config config = TestUtils.getBaseConfig()
            .enableFeatures(Config.Feature.Events)
            .setLoggingLevel(Config.LoggingLevel.VERBOSE)
            .setLogListener((message, level) -> captured.append('[').append(level).append(']').append(message).append('\n'));

        Assert.assertNotNull(config.getLogListener());

        Countly.instance().init(config);
        Countly.instance().events().recordEvent("loggedEvent");

        Assert.assertTrue("the listener must have received SDK output", captured.length() > 0);
        Assert.assertTrue(captured.toString().contains("loggedEvent"));
    }

    /**
     * The setters that are kept only for source compatibility. Each is documented as doing nothing,
     * and each must be verified to actually do nothing: a half removed setting that silently ignores
     * its argument while a getter still reports something plausible is worse than a compile error.
     */
    @Test
    public void deprecatedSetters_areRealNoOpsWithConstantGetters() {
        Config config = TestUtils.getBaseConfig();

        Assert.assertSame(config, config.setDeviceIdFallbackAllowed(false));
        Assert.assertTrue("the fallback flag is a constant now", config.isDeviceIdFallbackAllowed());

        Assert.assertSame(config, config.setLoggingTag("MyTag"));
        Assert.assertEquals("Countly", config.getLoggingTag());

        Assert.assertSame(config, config.enableTestMode());
        Assert.assertFalse("test mode cannot be turned on any more", config.isTestModeEnabled());
        Assert.assertSame(config, config.disableTestMode());
        Assert.assertFalse(config.isTestModeEnabled());

        Assert.assertSame(config, config.setApplicationName("Ignored"));
        Assert.assertEquals("", config.getApplicationName());

        Assert.assertSame(config, config.setCrashReportingANRCheckingPeriod(99));
        Assert.assertEquals("the ANR period is a constant now", 5, config.getCrashReportingANRCheckingPeriod());
        Assert.assertSame(config, config.disableANRCrashReporting());
        Assert.assertEquals(5, config.getCrashReportingANRCheckingPeriod());

        // The SDK identity is fixed, and it is what goes on the wire, so these must not move it.
        Assert.assertSame(config, config.setSdkName("not-my-sdk"));
        Assert.assertSame(config, config.setSdkVersion("0.0.0"));
        Assert.assertEquals(TestUtils.SDK_NAME, config.getSdkName());
        Assert.assertEquals(TestUtils.SDK_VERSION, config.getSdkVersion());

        // Module overrides are never recorded, so nothing can be looked back up. The setter itself
        // is protected and unreachable from here, which is part of why the map stays empty.
        Assert.assertNull(config.getModuleOverride(Config.Feature.Events));
        Assert.assertNull(config.getModuleOverride(7));

        // And the identity really does reach the server unchanged.
        Countly.instance().init(config.enableFeatures(Config.Feature.Events).setEventQueueSizeToSend(1));
        Countly.instance().events().recordEvent("identityEvent");
        Storage.await(SDKCore.instance.config.getLogger());
        Map<String, String>[] queue = TestUtils.getCurrentRQ();
        Assert.assertTrue(queue.length > 0);
        TestUtils.validateSdkIdentityParams(queue[0]);
        Assert.assertEquals(TestUtils.SDK_NAME, queue[0].get("sdk_name"));
    }

    /**
     * The pin accumulators. Both kinds start empty, accumulate, deduplicate, and refuse an empty pin
     * so a typo cannot silently disable pinning.
     */
    @Test
    public void pinAccumulators_collectDeduplicateAndRefuseEmptyPins() {
        Config config = TestUtils.getBaseConfig();

        Assert.assertNull(config.getPublicKeyPins());
        Assert.assertNull(config.getCertificatePins());

        config.addPublicKeyPin("key-one").addPublicKeyPin("key-two").addPublicKeyPin("key-one");
        config.addCertificatePin("cert-one").addCertificatePin("cert-two");

        Assert.assertEquals(2, config.getPublicKeyPins().size());
        Assert.assertTrue(config.getPublicKeyPins().contains("key-one"));
        Assert.assertEquals(2, config.getCertificatePins().size());

        for (String bad : new String[] { null, "" }) {
            config.addPublicKeyPin(bad);
            config.addCertificatePin(bad);
        }
        Assert.assertEquals("an empty pin must not be stored", 2, config.getPublicKeyPins().size());
        Assert.assertEquals(2, config.getCertificatePins().size());
    }

    // endregion
}
