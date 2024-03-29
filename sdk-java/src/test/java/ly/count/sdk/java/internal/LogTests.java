package ly.count.sdk.java.internal;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.powermock.reflect.Whitebox;

import ly.count.sdk.java.Config;

import static ly.count.sdk.java.Config.LoggingLevel.DEBUG;
import static ly.count.sdk.java.Config.LoggingLevel.ERROR;
import static ly.count.sdk.java.Config.LoggingLevel.INFO;
import static ly.count.sdk.java.Config.LoggingLevel.OFF;
import static ly.count.sdk.java.Config.LoggingLevel.WARN;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@RunWith(JUnit4.class)
public class LogTests {
    private static final String message = "message";
    private static final Throwable exception = new IllegalStateException("IAS");
    private InternalConfig config;

    @Before
    public void setupEveryTest() {
        String serverUrl = "http://www.serverurl.com";
        String serverAppKey = "1234";
        config = new InternalConfig(new Config(serverUrl, serverAppKey));
    }

    @After
    public void cleanupEveryTests() {
        config = null;
    }

    @Test(expected = NullPointerException.class)
    public void logInit_null() {
        Log log = new Log(null, null);
    }
}
