package ly.count.sdk.java.internal;

import java.util.HashMap;
import java.util.Map;

import ly.count.sdk.java.Config;
import ly.count.sdk.java.Session;
import ly.count.sdk.java.View;

/**
 * Views support
 */

public class ModuleViews extends ModuleBase {
    private Map<Integer, View> views = null;

    @Override
    public void init(InternalConfig config, Log logger) {
        super.init(config, logger);
        views = new HashMap<>();
    }

    /**
     * When new {@code Activity} started, starts new {@link View} with name
     * set as {@code Activity} class name.
     */
    @Override
    public void onActivityStarted(CtxCore ctx) {
        Session session = SDKCore.instance.getSession();
        if (session != null && SDKCore.enabled(Config.Feature.Views) && ctx.getConfig().isAutoViewsTrackingEnabled()) {
            Class cls = ctx.getContext().getClass();
            views.put(ctx.getContext().hashCode(), session.view(cls.getSimpleName()));
        }
    }

    /**
     * When {@code Activity} stopped, stops previously started {@link View}.
     */
    @Override
    public void onActivityStopped(CtxCore ctx) {
        Session session = SDKCore.instance.getSession();
        if (session != null && SDKCore.enabled(Config.Feature.Views) && ctx.getConfig().isAutoViewsTrackingEnabled()) {
            int cls = ctx.getContext().hashCode();
            if (views.containsKey(cls)) {
                views.remove(cls).stop(false);
            }
        }
    }
}
