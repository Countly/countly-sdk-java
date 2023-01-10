package ly.count.sdk.java.internal;

import java.io.File;

import ly.count.sdk.java.internal.InternalConfig;
import ly.count.sdk.java.internal.Log;

/**
 * {@link CtxCore} implementation
 */
public class CtxImpl implements CtxCore {
   // private static final Log.Module L = Log.module("[CtxImpl]");

    Log L = null;
    private SDKLifecycle sdk;
    private InternalConfig config;
    private File directory;
    private String view;//todo not sure about the usefulness of this

    private boolean expired = false;

    public CtxImpl(SDKLifecycle sdk, InternalConfig config, Log logger, File directory) {
        this.sdk = sdk;
        this.config = config;
        this.L = logger;
        this.directory = directory;
    }

    public CtxImpl(SDKLifecycle sdk, InternalConfig config, File directory, String view) {
        this.sdk = sdk;
        this.config = config;
        this.directory = directory;
        this.view = view;
    }

    @Override
    public File getContext() {
        if (expired) {
            L.w("[CtxImpl] Ctx is expired");
        }
        return directory;
    }

    @Override
    public InternalConfig getConfig() {
        return config;
    }

    @Override
    public SDKLifecycle getSDK() {
        return sdk;
    }

    @Override
    public boolean isExpired() {
        return expired;
    }

    @Override
    public Log getLogger() {
        return L;
    }

    public String getView() {
        return view;
    }

    public void expire() {
        config = null;
        directory = null;
        view = null;
    }
}
