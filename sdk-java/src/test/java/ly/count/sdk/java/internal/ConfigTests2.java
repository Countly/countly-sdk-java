package ly.count.sdk.java.internal;

import junit.framework.Assert;

import ly.count.sdk.java.Config;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.net.URL;

import ly.count.sdk.java.internal.BaseTestsCore;
import ly.count.sdk.java.internal.InternalConfig;

@RunWith(JUnit4.class)
public class ConfigTests2 extends BaseTestsCore {
    private InternalConfig internalConfig;
    private String serverUrl = "http://www.serverurl.com";
    private String serverAppKey = "1234";

    @Before
    public void setUp() throws Exception {
        internalConfig = (InternalConfig)defaultConfigWithLogsForConfigTests();
    }

    @Test
    public void setup_urlAndKey() throws Exception{
        URL url = new URL(serverUrl);
        Assert.assertEquals(serverAppKey, internalConfig.getServerAppKey());
        Assert.assertEquals(url, internalConfig.getServerURL());
    }

    @Test
    public void setUsePost_setAndDeset(){
        Assert.assertFalse(internalConfig.isUsePOST());
        internalConfig.enableUsePOST();
        Assert.assertTrue(internalConfig.isUsePOST());
        internalConfig.setUsePOST(false);
        Assert.assertFalse(internalConfig.isUsePOST());
        internalConfig.setUsePOST(true);
        Assert.assertTrue(internalConfig.isUsePOST());
    }

    @Test
    public void setLoggingTag_default(){
        Assert.assertEquals("Countly", internalConfig.getLoggingTag());
    }

    @Test
    public void setLoggingTag_null(){
        Config.LoggingLevel level = internalConfig.getLoggingLevel();
        internalConfig.setLoggingTag(null);
        Assert.assertEquals(level, internalConfig.getLoggingLevel());
    }

    @Test
    public void setLoggingTag_empty(){
        String tag = internalConfig.getLoggingTag();
        internalConfig.setLoggingTag("");
        Assert.assertEquals(tag, internalConfig.getLoggingTag());

    }

    @Test
    public void setLoggingTag_simple(){
        String tagName = "simpleName";
        internalConfig.setLoggingTag(tagName);
        Assert.assertEquals(tagName, internalConfig.getLoggingTag());
    }

    @Test
    public void setLoggingLevel_null(){
        Config.LoggingLevel level = internalConfig.getLoggingLevel();
        internalConfig.setLoggingTag(null);
        Assert.assertEquals(level, internalConfig.getLoggingLevel());
    }

    @Test
    public void sdkName_null(){
        String prvName = internalConfig.getSdkName();
        internalConfig.setSdkName(null);
        Assert.assertEquals(prvName, internalConfig.getSdkName());
    }

    @Test
    public void sdkName_empty(){
        String prvName = internalConfig.getSdkName();
        internalConfig.setSdkName("");
        Assert.assertEquals(prvName, internalConfig.getSdkName());
    }

    @Test
    public void sdkName_setting(){
        String newSdkName = "new-some-name";
        internalConfig.setSdkName(newSdkName);
        Assert.assertEquals(newSdkName, internalConfig.getSdkName());

        newSdkName = "another-name";
        internalConfig.setSdkName(newSdkName);
        Assert.assertEquals(newSdkName, internalConfig.getSdkName());
    }

    @Test
    public void sdkVersion_null(){
        String prv = internalConfig.getSdkVersion();
        internalConfig.setSdkVersion(null);
        Assert.assertEquals(prv, internalConfig.getSdkVersion());
    }

    @Test
    public void sdkVersion_empty(){
        String prv = internalConfig.getSdkVersion();
        internalConfig.setSdkVersion("");
        Assert.assertEquals(prv, internalConfig.getSdkVersion());
    }

    @Test
    public void sdkVersion_setting(){
        String versionName = "123";
        internalConfig.setSdkVersion(versionName);
        Assert.assertEquals(versionName, internalConfig.getSdkVersion());

        versionName = "asd";
        internalConfig.setSdkVersion(versionName);
        Assert.assertEquals(versionName, internalConfig.getSdkVersion());
    }

    @Test
    public void programmaticSessionsControl_default(){
        Assert.assertTrue(internalConfig.isAutoSessionsTrackingEnabled());
    }

    @Test
    public void programmaticSessionsControl_enableAndDisable(){
        Assert.assertTrue(internalConfig.isAutoSessionsTrackingEnabled());
        internalConfig.setAutoSessionsTracking(false);
        Assert.assertFalse(internalConfig.isAutoSessionsTrackingEnabled());
    }

    @Test
    public void sendUpdateEachSeconds_default(){
        Assert.assertEquals(30, internalConfig.getSendUpdateEachSeconds());
    }

    @Test
    public void sendUpdateEachSeconds_disable(){
        internalConfig.disableUpdateRequests();
        Assert.assertEquals(0, internalConfig.getSendUpdateEachSeconds());
    }

    @Test
    public void sendUpdateEachSeconds_set(){
        int secondsAmount = 123;
        internalConfig.setSendUpdateEachSeconds(secondsAmount);
        Assert.assertEquals(secondsAmount, internalConfig.getSendUpdateEachSeconds());
    }

    @Test
    public void sendUpdateEachEvents_default(){
        Assert.assertEquals(10, internalConfig.getEventsBufferSize());
    }

    @Test
    public void sendUpdateEachEvents_disable(){
        internalConfig.disableUpdateRequests();
        Assert.assertEquals(0, internalConfig.getSendUpdateEachSeconds());
    }

    @Test
    public void sendUpdateEachEvents_set(){
        int eventsAmount = 123;
        internalConfig.setEventsBufferSize(eventsAmount);
        Assert.assertEquals(eventsAmount, internalConfig.getEventsBufferSize());
    }

    @Test
    public void sdkVersion_default(){
        Assert.assertEquals("20.11.2", internalConfig.getSdkVersion());
    }

}
