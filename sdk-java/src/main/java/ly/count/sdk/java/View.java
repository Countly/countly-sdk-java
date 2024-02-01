package ly.count.sdk.java;
import ly.count.sdk.java.internal.ModuleViews;

import ly.count.sdk.java.internal.ModuleViews;

/**
 * Contract interface for Views functionality
 */

public interface View {

    /**
     * Start view
     *
     * @param firstView true if this is the first view in the session
     * @deprecated use {@link ModuleViews.Views#startView(String)} instead via {@link Countly#views()}
     */
    void start(boolean firstView);

    /**
     * Stop view
     *
     * @param lastView true if this is the last view in the session
     * @deprecated use {@link ModuleViews.Views#stopViewWithName(String)} or {@link ModuleViews.Views#stopViewWithID(String)} instead via {@link Countly#views()}
     */
    void stop(boolean lastView);
}