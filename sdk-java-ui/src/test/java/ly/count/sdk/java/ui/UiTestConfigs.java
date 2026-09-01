package ly.count.sdk.java.ui;

import java.io.File;
import ly.count.sdk.java.Config;
import org.junit.Assert;

/**
 * Shared SDK configurations for the UI tests. One home for these, so a change to how the tests
 * reach a server does not have to be made in three places.
 */
final class UiTestConfigs {

    private UiTestConfigs() {
    }

    /**
     * @return a config pointing at a port that refuses instantly, so no test ever waits on a
     *     network timeout
     */
    static Config refusedServer() {
        return configFor("http://localhost:1");
    }

    /**
     * @param serverUrl the server the SDK should talk to
     * @return a config pointing at it
     */
    static Config configFor(String serverUrl) {
        File storage = new File(System.getProperty("java.io.tmpdir"), "countly-ui-tests");
        if (!storage.exists()) {
            Assert.assertTrue(storage.mkdirs());
        }
        // No application version on purpose: it is unset in plenty of real integrations, and that is
        // what used to make reportFeedbackWidgetManually throw.
        return new Config(serverUrl, "UI_TEST_APP_KEY", storage)
            .enableFeatures(Config.Feature.Content, Config.Feature.Events, Config.Feature.Feedback)
            .setLoggingLevel(Config.LoggingLevel.DEBUG)
            .setCustomDeviceId("ui-test-device");
    }
}
