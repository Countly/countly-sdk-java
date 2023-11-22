package ly.count.sdk.java.internal;

import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class MultiThreadingTest {
    @After
    public void stop() {
        Countly.instance().halt();
    }

    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
    }

    @Test
    public void multiThread() {
        Countly.instance().init(getAllConfig());
    }

    private Config getAllConfig() {
        Config config = TestUtils.getBaseConfig();
        config.enableFeatures(Config.Feature.Events, Config.Feature.Sessions, Config.Feature.Location, Config.Feature.CrashReporting, Config.Feature.Feedback, Config.Feature.UserProfiles, Config.Feature.Views, Config.Feature.RemoteConfig);
        config.enableRemoteConfigValueCaching().setRequiresConsent(false).enableRemoteConfigAutomaticTriggers().enrollABOnRCDownload();
        return config.setUpdateSessionTimerDelay(1);
    }
}
