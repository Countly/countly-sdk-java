package ly.count.sdk.java.internal;

import org.junit.After;
import org.junit.Before;
import org.mockito.Mockito;
import org.powermock.reflect.Whitebox;

import java.io.File;
import java.util.List;

import ly.count.sdk.java.Config;

import static org.mockito.Mockito.mock;

public class BaseTestsCore {
    protected static String SERVER = "http://www.serverurl.com";
    protected static String APP_KEY = "1234";

    protected CtxCore ctx;
    protected InternalConfig config = null;
    protected ModuleBase dummy = null;

    protected SDKCore sdk = null;

    public class CtxImpl extends CtxCore {
        private SDKCore sdk;
        private Object ctx;
        private InternalConfig config;

        private Log L = null;

        public CtxImpl(SDKCore sdk, InternalConfig config, Object ctx, Log logger) {
            super(sdk, config, logger, null);
            this.sdk = sdk;
            this.config = config;
            this.ctx = ctx;
            this.L = logger;
        }

        @Override
        public File getContext() {
            return null;
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
        new Log(config.getLoggingLevel(), config.getLogListener());
        return config;
    }

    @Before
    public void setUp() throws Exception {
        Log L = new Log(Config.LoggingLevel.VERBOSE, null);
        ctx = new CtxImpl(this.sdk, this.config == null ? new InternalConfig(defaultConfig()) : this.config, getContext(), L);
    }

    protected void setUpApplication(Config config) throws Exception {
        setUpSDK(config == null ? defaultConfig() : config, false);
    }

    private void setUpSDK(Config config, boolean limited) throws Exception {
        Log L = new Log(Config.LoggingLevel.VERBOSE, null);

        this.dummy = mock(ModuleBase.class);
        Utils.reflectiveSetField(SDKCore.class, "testDummyModule", dummy, L);
        this.sdk = mock(SDKCore.class);
        this.sdk.init(new CtxImpl(this.sdk, new InternalConfig(defaultConfig()), getContext(), L));
        this.config = this.sdk.config();
        this.ctx = new CtxImpl(this.sdk, this.config, getContext(), L);
    }

    protected <T extends ModuleBase> T module(Class<T> cls, boolean mock) {
        T module = sdk.module(cls);

        if (module == null) {
            return null;
        }

        if (mock) {
            List<ModuleBase> list = Whitebox.getInternalState(sdk, "modules");
            list.remove(module);
            module = Mockito.spy(module);
            list.add(1, module);
        }

        return module;
    }

    private Object getContext() {
        return new Object();
    }

    @After
    public void tearDown() throws Exception {
        if (this.sdk != null && ctx != null) {
            this.sdk.stop(ctx, true);
            this.sdk = null;
        }
        Utils.reflectiveSetField(SDKCore.class, "testDummyModule", null, null);
    }
}
