package ly.count.sdk.java.internal;

import java.io.File;

/**
 * {@link CtxCore} implementation
 */
public class CtxCore {
    private InternalConfig config;

    public CtxCore(InternalConfig config) {
        this.config = config;
    }

    public File getSdkStorageRootDirectory() {
        return config.getSdkStorageRootDirectory();
    }

    public InternalConfig getConfig() {
        return config;
    }

    protected void setConfig(InternalConfig config) {
        this.config = config;
    }

    public SDKCore getSDK() {
        return config.sdk;
    }

    public Log getLogger() {
        return config.getLogger();
    }
}
