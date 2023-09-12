package ly.count.sdk.java.internal;

import java.io.File;

/**
 * {@link CtxCore} implementation
 */
public class CtxCore {
    Log L = null;
    private SDKCore sdk;
    private InternalConfig config;
    private File directory;

    public CtxCore(SDKCore sdk, InternalConfig config, Log logger, File directory) {
        this.sdk = sdk;
        this.config = config;
        this.L = logger;
        this.directory = directory;
    }

    public File getContext() {
        return directory;
    }

    public InternalConfig getConfig() {
        return config;
    }

    protected void setConfig(InternalConfig config) {
        this.config = config;
    }

    public SDKCore getSDK() {
        return sdk;
    }

    public Log getLogger() {
        return L;
    }
}
