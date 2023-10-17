package ly.count.sdk.java.internal;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import ly.count.sdk.java.Config;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ConfigTests {
    private InternalConfig internalConfig;

    @Test
    public void testServerUrlAndAppKey() throws Exception {
        internalConfig = new InternalConfig(TestUtils.getBaseConfig());

        URL url = new URL(TestUtils.SERVER_URL);
        Assert.assertEquals(TestUtils.SERVER_APP_KEY, internalConfig.getServerAppKey());
        Assert.assertEquals(url, internalConfig.getServerURL());
    }

    @Test
    public void testRequestMethod() {
        internalConfig = new InternalConfig(TestUtils.getBaseConfig());

        Assert.assertFalse(internalConfig.isHTTPPostForced());

        internalConfig.enableUsePOST();
        Assert.assertTrue(internalConfig.isHTTPPostForced());

        internalConfig.setUsePOST(false);
        Assert.assertFalse(internalConfig.isHTTPPostForced());

        internalConfig.setUsePOST(true);
        Assert.assertTrue(internalConfig.isHTTPPostForced());
    }

    @Test
    public void testLoggingTag() {
        internalConfig = new InternalConfig(TestUtils.getBaseConfig());

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
        internalConfig = new InternalConfig(TestUtils.getBaseConfig().setLoggingLevel(Config.LoggingLevel.DEBUG));

        Assert.assertEquals(Config.LoggingLevel.DEBUG, internalConfig.getLoggingLevel());

        internalConfig.setLoggingLevel(Config.LoggingLevel.INFO);
        Assert.assertEquals(Config.LoggingLevel.INFO, internalConfig.getLoggingLevel());
    }

    @Test
    public void testSDKName() {
        internalConfig = new InternalConfig(TestUtils.getBaseConfig());

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
        internalConfig = new InternalConfig(TestUtils.getBaseConfig());

        Assert.assertEquals(TestUtils.SDK_VERSION, internalConfig.getSdkVersion());

        internalConfig.setSdkVersion(null);
        Assert.assertEquals(TestUtils.SDK_VERSION, internalConfig.getSdkVersion());

        internalConfig.setSdkVersion("");
        Assert.assertEquals(TestUtils.SDK_VERSION, internalConfig.getSdkVersion());

        internalConfig.setSdkVersion("new-version");
        Assert.assertEquals(TestUtils.SDK_VERSION, internalConfig.getSdkVersion());
    }

    @Test
    public void testSendUpdateEachSeconds() {
        internalConfig = new InternalConfig(TestUtils.getBaseConfig());

        Assert.assertEquals(60, internalConfig.getSendUpdateEachSeconds());

        internalConfig.disableUpdateRequests();
        Assert.assertEquals(0, internalConfig.getSendUpdateEachSeconds());

        internalConfig.setUpdateSessionTimerDelay(123);
        Assert.assertEquals(123, internalConfig.getSendUpdateEachSeconds());
    }

    @Test
    public void testEventBufferSize() {
        internalConfig = new InternalConfig(TestUtils.getBaseConfig());

        Assert.assertEquals(10, internalConfig.getEventsBufferSize());

        internalConfig.setEventQueueSizeToSend(60);
        Assert.assertEquals(60, internalConfig.getEventsBufferSize());
    }

    @Test
    public void metricOverride() {
        internalConfig = new InternalConfig(TestUtils.getBaseConfig());

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
