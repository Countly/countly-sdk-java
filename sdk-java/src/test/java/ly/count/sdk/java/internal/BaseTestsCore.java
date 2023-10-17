package ly.count.sdk.java.internal;

import java.io.File;
import java.util.List;
import ly.count.sdk.java.Config;
import org.junit.After;
import org.junit.Before;
import org.mockito.Mockito;
import org.powermock.reflect.Whitebox;

import static org.mockito.Mockito.mock;

public class BaseTestsCore {
    protected static String SERVER = "https://www.serverurl.com";
    protected static String APP_KEY = "1234";

    protected InternalConfig config = null;
    protected ModuleBase dummy = null;

    protected SDKCore sdk = null;

    public class CtxImpl extends CtxCore {
        private SDKCore sdk;
        private Object ctx;
        private InternalConfig config;

        private Log L = null;

        public CtxImpl(InternalConfig config) {
            super(config);
            this.config = config;
        }

        @Override
        public File getSdkStorageRootDirectory() {
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
        return new Config(SERVER, APP_KEY, TestUtils.getTestSDirectory()).setLoggingLevel(Config.LoggingLevel.DEBUG);
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

        //ctx = new CtxImpl(this.sdk, this.config == null ? new InternalConfig(defaultConfig()) : this.config, getSdkStorageRootDirectory(), L);
    }

    protected void setUpApplication(Config config) throws Exception {
        setUpSDK(config == null ? defaultConfig() : config);
    }

    private void setUpSDK(Config config) throws Exception {
        Log L = new Log(Config.LoggingLevel.VERBOSE, null);

        this.dummy = mock(ModuleBase.class);
        this.sdk = mock(SDKCore.class);
        this.sdk.init(new InternalConfig(defaultConfig()));
        this.config = this.sdk.config();
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

    @After
    public void tearDown() throws Exception {
        if (this.sdk != null) {
            this.sdk.halt();
            this.sdk = null;
        }
    }
}
