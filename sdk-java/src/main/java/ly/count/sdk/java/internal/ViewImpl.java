package ly.count.sdk.java.internal;

import ly.count.sdk.java.Countly;
import ly.count.sdk.java.Session;
import ly.count.sdk.java.View;

/**
 * View implementation for Countly Views plugin
 */

class ViewImpl implements View {
    private Log L = null;
    final String name;
    final Session session;
    boolean start = false;
    boolean stop = false;

    ViewImpl(Session session, String name, Log logger) {
        this.L = logger;
        this.name = name;
        this.session = session;
    }

    @Override
    public void start(boolean firstView) {
        if (SDKCore.instance != null && SDKCore.instance.config.isBackendModeEnabled()) {
            L.w("[ViewImpl] start: Skipping view, backend mode is enabled!");
            return;
        }

        if (start) {
            L.w("[ViewImpl] start: View already started!");
            return;
        }

        start = true;
        Countly.instance().views().startAutoStoppedView(name);
    }

    @Override
    public void stop(boolean lastView) {
        if (SDKCore.instance != null && SDKCore.instance.config.isBackendModeEnabled()) {
            L.w("[ViewImpl] stop: Skipping view, backend mode is enabled!");
            return;
        }

        if (stop) {
            L.w("[ViewImpl] stop: View already stopped!");
            return;
        }
        stop = true;

        Countly.instance().views().stopViewWithName(name);
        ModuleViews viewsModule = (ModuleViews) SDKCore.instance.module(CoreFeature.Views.getIndex());
        if (viewsModule != null) {
            viewsModule.setFirstViewInternal(lastView);
        }
    }

    @Override
    public String toString() {
        return "ViewImpl{" +
            "name='" + name + '\'' +
            ", session=" + session +
            '}';
    }
}
