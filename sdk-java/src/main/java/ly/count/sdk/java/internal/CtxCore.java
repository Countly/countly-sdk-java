package ly.count.sdk.java.internal;

import java.io.File;

import ly.count.sdk.java.internal.InternalConfig;
import ly.count.sdk.java.internal.Log;

/**
 * {@link CtxCore} implementation
 */
public class CtxCore {
    // private static final Log.Module L = Log.module("[CtxImpl]");

    Log L = null;
    private SDKCore sdk;
    private InternalConfig config;
    private File directory;
    private String view;//todo not sure about the usefulness of this

    public CtxCore(SDKCore sdk, InternalConfig config, Log logger, File directory) {
        this.sdk = sdk;
        this.config = config;
        this.L = logger;
        this.directory = directory;
    }

    public CtxCore(SDKCore sdk, InternalConfig config, File directory, String view) {
        this.sdk = sdk;
        this.config = config;
        this.directory = directory;
        this.view = view;
    }

    public File getContext() {
        return directory;
    }

    public InternalConfig getConfig() {
        return config;
    }

    public SDKCore getSDK() {
        return sdk;
    }

    public Log getLogger() {
        return L;
    }

    public String getView() {
        return view;
    }

}
