package ly.count.sdk.java.internal;

import ly.count.sdk.java.Config;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

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
