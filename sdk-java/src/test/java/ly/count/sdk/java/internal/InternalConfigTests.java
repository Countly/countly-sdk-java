package ly.count.sdk.java.internal;

import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * {@link InternalConfig}, the copy of the customer's {@link Config} that the modules actually read.
 * <p>
 * Its job is to carry every configured value across unchanged and to hold the device id list, so the
 * two things worth proving are that the copy is faithful and that the device id bookkeeping cannot
 * lose the current id.
 */
@RunWith(JUnit4.class)
public class InternalConfigTests {

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
     * The copy constructor must carry the customer's settings across verbatim. A value that failed to
     * copy would leave the modules running on a default nobody asked for.
     */
    @Test
    public void copyConstructor_carriesTheCustomerSettingsAcross() {
        Config source = TestUtils.getBaseConfig()
            .enableFeatures(Config.Feature.Events, Config.Feature.Views)
            .setEventQueueSizeToSend(13)
            .setUpdateSessionTimerDelay(44)
            .setNetworkConnectTimeout(19)
            .setLoggingLevel(Config.LoggingLevel.INFO)
            .enableParameterTamperingProtection("copy-salt");

        InternalConfig copy = new InternalConfig(source);

        Assert.assertEquals(source.getServerURL().toString(), copy.getServerURL().toString());
        Assert.assertEquals(source.getServerAppKey(), copy.getServerAppKey());
        Assert.assertEquals(source.getApplicationVersion(), copy.getApplicationVersion());
        Assert.assertEquals(13, copy.getEventsBufferSize());
        Assert.assertEquals(44, copy.getSendUpdateEachSeconds());
        Assert.assertEquals(19, copy.getNetworkConnectionTimeout());
        Assert.assertEquals(Config.LoggingLevel.INFO, copy.getLoggingLevel());
        Assert.assertEquals("copy-salt", copy.getParameterTamperingProtectionSalt());
        Assert.assertEquals(source.getFeaturesMap(), copy.getFeatures1());
        Assert.assertTrue(copy.isFeatureEnabled(Config.Feature.Events));

        // Nothing was configured to override, so the override set is empty rather than null.
        Assert.assertNotNull(copy.getModuleOverrides());
        Assert.assertTrue(copy.getModuleOverrides().isEmpty());

        // Networking defaults to in-process and is switchable.
        Assert.assertTrue(copy.isDefaultNetworking());
        copy.setDefaultNetworking(false);
        Assert.assertFalse(copy.isDefaultNetworking());
        copy.setDefaultNetworking(true);
        Assert.assertTrue(copy.isDefaultNetworking());
    }

    /**
     * The device id list. Setting a new id must hand back the one it replaced so the caller can put
     * it on the wire as the old id, and removing must work by instance and by id string alike.
     */
    @Test
    public void deviceIdBookkeeping_replacesAndRemovesById() {
        InternalConfig config = new InternalConfig(TestUtils.getBaseConfig());

        Assert.assertNull("a fresh config has no device id", config.getDeviceId());

        Config.DID first = new Config.DID(Config.DID.STRATEGY_CUSTOM, "first_id");
        Assert.assertNull("the first set replaces nothing", config.setDeviceId(first));
        Assert.assertEquals(first, config.getDeviceId());

        Config.DID second = new Config.DID(Config.DID.STRATEGY_CUSTOM, "second_id");
        Assert.assertEquals("setting a new id must return the previous one", first, config.setDeviceId(second));
        Assert.assertEquals(second, config.getDeviceId());

        // Removing by a matching id string finds and drops it.
        Assert.assertTrue(config.removeDeviceId("second_id"));
        Assert.assertNull(config.getDeviceId());

        // Removing something that is not there is false, not an exception.
        Assert.assertFalse(config.removeDeviceId("never_stored"));
        Assert.assertFalse(config.removeDeviceId(first));

        // And removing by instance works too.
        config.setDeviceId(first);
        Assert.assertTrue(config.removeDeviceId(first));
        Assert.assertNull(config.getDeviceId());
    }

    /**
     * The two constructors that exist only to be refused. Both would leave the SDK without a usable
     * server URL, so each must fail loudly at construction rather than later on the first request.
     */
    @Test
    public void theUnusableConstructors_refuseToBuild() {
        try {
            new InternalConfig("http://count.ly", "a key");
            Assert.fail("the url and app key constructor must not be usable");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("should not be used"));
        }

        // The no-argument one builds, but only with placeholder values that are not a real server.
        InternalConfig placeholder = new InternalConfig();
        Assert.assertEquals("http://count.ly", placeholder.getServerURL().toString());
        Assert.assertEquals("not a key", placeholder.getServerAppKey());
    }

    /**
     * The logger the modules use comes off the internal config, and it must be the one built from the
     * customer's configured level rather than a default.
     */
    @Test
    public void theModuleLogger_comesFromTheConfiguredLevel() {
        StringBuilder captured = new StringBuilder();

        Countly.instance().init(TestUtils.getBaseConfig()
            .enableFeatures(Config.Feature.Events)
            .setLoggingLevel(Config.LoggingLevel.VERBOSE)
            .setLogListener((message, level) -> captured.append(message).append('\n')));

        InternalConfig config = SDKCore.instance.config;
        Assert.assertNotNull(config.getLogger());
        Assert.assertSame("the SDK must expose itself on its own config", SDKCore.instance, config.sdk);
        Assert.assertNotNull(config.storageProvider);

        config.getLogger().i("[InternalConfigTests] a message through the module logger");
        Assert.assertTrue(captured.toString().contains("a message through the module logger"));
    }

    // endregion
}
