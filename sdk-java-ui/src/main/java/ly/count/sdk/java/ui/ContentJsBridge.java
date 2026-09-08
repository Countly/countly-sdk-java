package ly.count.sdk.java.ui;

import java.util.function.Consumer;
import ly.count.sdk.java.internal.WidgetAction;

/**
 * Handed into a content page so a survey served through the content queue can reach Java.
 * <p>
 * A content block signals by navigating to the action URL, which the display intercepts. A survey
 * or NPS template hosted by the content queue is a different page: it signals its size and its close
 * the way every widget template does, with {@code window.parent.postMessage}, and never navigates.
 * Without this, a survey inside a content block could not resize or close itself.
 * <p>
 * Must be public, with public methods, for the JavaFX web engine to call it - and it has to be kept
 * in a field by whoever installs it, because the engine only holds it weakly.
 */
public class ContentJsBridge {

    static final String MEMBER_NAME = "countlyContentBridge";

    /** Forwards every {@code cly_widget_command} message the page receives. Guarded against reinstall. */
    static final String INSTALL_SCRIPT =
        "(function(){if(window.__clyContentBridgeInstalled){return 'already installed';}"
            + "window.__clyContentBridgeInstalled=true;"
            + "var bridge=window." + MEMBER_NAME + ";"
            + "window.addEventListener('message',function(ev){try{"
            + "var d=typeof ev.data==='string'?JSON.parse(ev.data):ev.data;"
            + "if(d&&d.cly_widget_command){bridge.post(JSON.stringify(d));}"
            + "}catch(e){}});"
            + "return 'installed';})();";

    private final Consumer<WidgetAction> onAction;

    ContentJsBridge(Consumer<WidgetAction> onAction) {
        this.onAction = onAction;
    }

    /**
     * Called from JavaScript.
     *
     * @param json the payload the page posted
     */
    public void post(String json) {
        if (onAction == null) {
            return;
        }
        try {
            WidgetAction action = WidgetMessageParser.parse(json);
            if (action != null) {
                onAction.accept(action);
            }
        } catch (Throwable t) {
            UiLog.e("[ContentJsBridge] post, failed to handle a page message, [" + t + "]");
        }
    }
}
