package ly.count.sdk.java.internal;

import junit.framework.Assert;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import ly.count.sdk.java.internal.BaseTestsCore;
import ly.count.sdk.java.internal.InternalConfig;

@RunWith(JUnit4.class)
public class ConfigTests extends BaseTestsCore {
    private InternalConfig internalConfig;
    private String serverUrl = "http://www.serverurl.com";
    private String serverAppKey = "1234";

    @Before
    public void setUp() throws Exception {
        internalConfig = (InternalConfig)defaultConfigWithLogsForConfigTests();
    }

    @Test
    public void sdkName_default(){
        Assert.assertEquals("java-native", internalConfig.getSdkName());
    }

    @Test
    public void sdkVersion_default(){
        Assert.assertEquals("20.11.2", internalConfig.getSdkVersion());
    }
}
