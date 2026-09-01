package ly.count.sdk.java.ui;

/**
 * Handed into the page as a JavaScript member so a widget's {@code postMessage} payloads can reach
 * Java. Must be public, and its method must be public, for the JavaFX web engine to call it.
 */
public class WidgetJsBridge {

    /**
     * The JavaScript member name the page posts to.
     */
    static final String MEMBER_NAME = "countlyJavaBridge";

    /**
     * Forwards every {@code cly_widget_command} message the page receives to Java. Guarded so a
     * reload does not install a second listener.
     */
    static final String INSTALL_SCRIPT =
        "(function(){if(window.__clyBridgeInstalled){return;}window.__clyBridgeInstalled=true;"
            + "window.addEventListener('message',function(ev){try{"
            + "var d=typeof ev.data==='string'?JSON.parse(ev.data):ev.data;"
            + "if(d&&d.cly_widget_command){window." + MEMBER_NAME + ".post(JSON.stringify(d));}"
            + "}catch(e){}});})();";

    private final WidgetWebHost.Listener listener;

    WidgetJsBridge(WidgetWebHost.Listener listener) {
        this.listener = listener;
    }

    /**
     * Called from JavaScript.
     *
     * @param json the payload the widget posted
     */
    public void post(String json) {
        if (listener == null) {
            return;
        }
        try {
            listener.onWidgetMessage(json);
        } catch (Throwable t) {
            UiLog.e("[WidgetJsBridge] post, failed to handle a widget message, [" + t + "]");
        }
    }
}
