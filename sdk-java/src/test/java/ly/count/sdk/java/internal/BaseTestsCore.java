package ly.count.sdk.java.internal;


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

    public class CtxImpl implements CtxCore {
        private SDKCore sdk;
        private Object ctx;
        private InternalConfig config;

        private Log L = null;

        public CtxImpl(SDKCore sdk, InternalConfig config, Object ctx, Log logger) {
            this.sdk = sdk;
            this.config = config;
            this.ctx = ctx;
            this.L = logger;
        }

        @Override
        public Object getContext() {
            return ctx;
        }

        @Override
        public InternalConfig getConfig() {
            return config;
        }

        @Override
        public SDKCore getSDK() {
            return sdk;
        }

        @Override
        public boolean isExpired() {
            return false;
        }

        @Override
        public Log getLogger() {
            return null;
        }
    }

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
        ctx = new CtxImpl(this.sdk, this.config == null ? new InternalConfig(defaultConfig()) : this.config, getContext(), L);
        utils = Mockito.spy(new Utils());
        Utils.reflectiveSetField(Utils.class, "utils", utils);
    }

    protected void setUpApplication(Config config) throws Exception {
        setUpSDK(config == null ? defaultConfig() : config, false);
    }

    private void setUpSDK(Config config, boolean limited) throws Exception {
        Log L = new Log(this.config == null ? new InternalConfig(config == null ? defaultConfig() : config) : this.config);
        this.dummy = mock(ModuleBase.class);
        Utils.reflectiveSetField(SDKInterface.class, "testDummyModule", dummy);
        this.sdk = mock(SDKCore.class);
        this.sdk.init(new CtxImpl(this.sdk, new InternalConfig(defaultConfig()), getContext(), L));
        this.config = this.sdk.config();
        this.ctx = new CtxImpl(this.sdk, this.config, getContext(), L);
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
