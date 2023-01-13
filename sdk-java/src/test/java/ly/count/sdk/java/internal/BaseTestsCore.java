package ly.count.sdk.java.internal;


import ly.count.sdk.java.Countly;
import org.junit.After;
import org.junit.Before;
import org.mockito.Mockito;
import org.powermock.reflect.Whitebox;

import java.util.List;

import ly.count.sdk.java.Config;

import static org.mockito.Mockito.mock;

public class BaseTestsCore {
    protected static String SERVER = "http://www.serverurl.com";
    protected static String APP_KEY = "1234";

    protected CtxCore ctx;
    protected InternalConfig config = null;
    protected Module dummy = null;
    protected Utils utils = null;

    protected SDKCore sdk = null;


    public Config config() {
        return new Config(SERVER, APP_KEY).setLoggingLevel(Config.LoggingLevel.DEBUG);
    }

    protected Config defaultConfig() throws Exception {
        return config();
    }

    protected Config defaultConfigWithLogsForConfigTests() throws Exception {
        InternalConfig config = new InternalConfig(defaultConfig());
        new Log(config);
        return config;
    }

    @Before
    public void setUp() throws Exception {
        Log L = new Log(this.config == null ? new InternalConfig(config == null ? defaultConfig() : config) : this.config);
        ctx = new CtxCore(this.sdk, this.config == null ? new InternalConfig(defaultConfig()) : this.config, L, null);
        utils = new Utils();
        Utils.reflectiveSetField(Utils.class, "utils", utils);
    }

    protected void setUpApplication(Config config) throws Exception {
        setUpSDK(config == null ? defaultConfig() : config, false);
    }

    private void setUpSDK(Config config, boolean limited) throws Exception {
        Log L = new Log(this.config == null ? new InternalConfig(config == null ? defaultConfig() : config) : this.config);
        this.sdk = new SDK();
        this.ctx = new CtxCore(this.sdk, this.config, L, null);
        this.sdk.init(ctx, L);
        this.config = this.sdk.config();

    }

    private Object getContext() {
        return new Object();
    }

    @After
    public void tearDown() throws Exception  {
        if (this.sdk != null && ctx != null) {
            this.sdk.stop(ctx, true);
            this.sdk = null;
        }
        Utils.reflectiveSetField(SDKInterface.class, "testDummyModule", null);
    }

}
