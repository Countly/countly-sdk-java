package ly.count.sdk.java.internal;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import ly.count.sdk.java.Config;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ConfigTests extends BaseTestsCore {
    private InternalConfig internalConfig;
    private String serverUrl = "http://www.serverurl.com";
    private String serverAppKey = "1234";

    @Before
    public void setUp() throws Exception {
        internalConfig = (InternalConfig) defaultConfigWithLogsForConfigTests();
    }

    @Test
    public void testServerUrlAndAppKey() throws Exception {
        URL url = new URL(serverUrl);
        Assert.assertEquals(serverAppKey, internalConfig.getServerAppKey());
        Assert.assertEquals(url, internalConfig.getServerURL());
    }

    @Test
    public void testRequestMethod() {
        Assert.assertFalse(internalConfig.isForcePost());

        internalConfig.enableUsePOST();
        Assert.assertTrue(internalConfig.isForcePost());

        internalConfig.setUsePOST(false);
        Assert.assertFalse(internalConfig.isForcePost());

        internalConfig.setUsePOST(true);
        Assert.assertTrue(internalConfig.isForcePost());
    }

    @Test
    public void testLoggingTag() {
        Assert.assertEquals("Countly", internalConfig.getLoggingTag());

        internalConfig.setLoggingTag("");
        Assert.assertEquals("Countly", internalConfig.getLoggingTag());

        internalConfig.setLoggingTag(null);
        Assert.assertEquals("Countly", internalConfig.getLoggingTag());

        internalConfig.setLoggingTag("New Tag");
        Assert.assertEquals("Countly", internalConfig.getLoggingTag());//this should return "Countly" because the call should not be working anymore
    }

    @Test
    public void testLoggingLevel() {
        Assert.assertEquals(Config.LoggingLevel.DEBUG, internalConfig.getLoggingLevel());

        internalConfig.setLoggingLevel(Config.LoggingLevel.INFO);
        Assert.assertEquals(Config.LoggingLevel.INFO, internalConfig.getLoggingLevel());
    }

    @Test
    public void testSDKName() {
        Assert.assertEquals("java-native", internalConfig.getSdkName());

        internalConfig.setSdkName(null);
        Assert.assertEquals("java-native", internalConfig.getSdkName());

        internalConfig.setSdkName("");
        Assert.assertEquals("java-native", internalConfig.getSdkName());

        internalConfig.setSdkName("new-name");
        Assert.assertEquals("java-native", internalConfig.getSdkName());
    }

    @Test
    public void testSDKVersion() {
        String versionName = "23.8.0";
        Assert.assertEquals(versionName, internalConfig.getSdkVersion());

        internalConfig.setSdkVersion(null);
        Assert.assertEquals(versionName, internalConfig.getSdkVersion());

        internalConfig.setSdkVersion("");
        Assert.assertEquals(versionName, internalConfig.getSdkVersion());

        internalConfig.setSdkVersion("new-version");
        Assert.assertEquals(versionName, internalConfig.getSdkVersion());
    }

    @Test
    public void testSendUpdateEachSeconds() {
        Assert.assertEquals(30, internalConfig.getSendUpdateEachSeconds());

        internalConfig.disableUpdateRequests();
        Assert.assertEquals(0, internalConfig.getSendUpdateEachSeconds());

        internalConfig.setUpdateSessionTimerDelay(123);
        Assert.assertEquals(123, internalConfig.getSendUpdateEachSeconds());
    }

    @Test
    public void testEventBufferSize() {
        Assert.assertEquals(10, internalConfig.getEventsBufferSize());

        internalConfig.setEventQueueSizeToSend(60);
        Assert.assertEquals(60, internalConfig.getEventsBufferSize());
    }

    @Test
    public void metricOverride() {
        Map<String, String> initialVals = internalConfig.getMetricOverride();
        Assert.assertEquals(0, initialVals.size());

        Map<String, String> newVals = new HashMap<>();
        newVals.put("a", "1");
        newVals.put("b", "2");

        internalConfig.setMetricOverride(newVals);

        Map<String, String> postVals = internalConfig.getMetricOverride();
        Assert.assertEquals(2, initialVals.size());
        Assert.assertEquals("1", postVals.get("a"));
        Assert.assertEquals("2", postVals.get("b"));
    }
}
