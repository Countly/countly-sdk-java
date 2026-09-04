package ly.count.sdk.java.ui;

/**
 * Handed into the page as a JavaScript member so a widget can reach Java. Must be public, and its
 * methods must be public, for the JavaFX web engine to call them.
 */
public class WidgetJsBridge {

    /**
     * The JavaScript member name the page posts to.
     */
    static final String MEMBER_NAME = "countlyJavaBridge";

    /**
     * What the page reports about its card, whenever it changes.
     */
    interface CardObserver {

        /**
         * @param width the width of the card the page has drawn
         * @param height its height
         * @param overflow how much taller the content is than the room it has, 0 when it fits
         */
        void onObservedCard(int width, int height, int overflow);
    }

    /**
     * Installs both halves of the page's side of the conversation.
     * <p>
     * The first is the {@code cly_widget_command} messages a widget posts - {@code resize_me},
     * {@code close}, a link - forwarded as they arrive.
     * <p>
     * The second watches the card. A widget page announces its size for some steps and not others,
     * and waiting on those announcements meant a step that was never announced kept the previous
     * step's card: an NPS comment step sat 55 pixels short, with a scrollbar, until something else
     * happened to disturb it. Watching from Java instead meant polling on a timer, which was both
     * slow to react and wasteful. So the page is asked to report its own card, which it knows
     * immediately: a {@code ResizeObserver} where there is one, a {@code MutationObserver} for the
     * step swaps that replace elements, and a cheap interval as a backstop. All three funnel through
     * one function that crosses into Java only when the numbers actually changed.
     */
    static final String INSTALL_SCRIPT =
        "(function(){if(window.__clyBridgeInstalled){return 'already installed';}"
            + "window.__clyBridgeInstalled=true;"
            + "var bridge=window." + MEMBER_NAME + ";"
            + "window.addEventListener('message',function(ev){try{"
            + "var d=typeof ev.data==='string'?JSON.parse(ev.data):ev.data;"
            + "if(d&&d.cly_widget_command){bridge.post(JSON.stringify(d));}"
            + "}catch(e){}});"
            + "var last='';var watched=null;var ro=null;"
            + "function measure(){try{"
            + "var el=document.querySelector('" + FxSurfaces.WIDGET_MARKERS + "');"
            + "if(!el){return;}"
            // The card element does not exist when this is installed - the bridge goes in as soon as
            // there is a document, which is the only way to catch the page's first message - so the
            // observer is attached the first time the card is actually found.
            + "if(ro&&watched!==el){try{if(watched){ro.unobserve(watched);}ro.observe(el);watched=el;}catch(e){}}"
            + "var r=el.getBoundingClientRect();"
            + "var w=Math.round(r.width);var h=Math.round(r.height);"
            + "if(w<40||h<40){return;}"
            + "var d=document.documentElement;var b=document.body;"
            + "var need=Math.max(d.scrollHeight||0,b?b.scrollHeight||0:0);"
            + "var have=Math.max(d.clientHeight||0,window.innerHeight||0);"
            + "var over=Math.max(0,need-have);"
            + "var key=w+'x'+h+':'+over;if(key===last){return;}last=key;"
            + "bridge.cardChanged(w+','+h+','+over);"
            + "}catch(e){}}"
            // Reported per frame, deliberately. A step swap animates its height and this engine's
            // ResizeObserver fires on every frame of that animation, so the card's window follows
            // the content as it grows: the animation and the window move together, which is what
            // reads as smooth. Coalescing these into one resize at the end was measurably worse -
            // the content animates inside a stale window and then the window snaps.
            + "if(window.ResizeObserver){try{ro=new ResizeObserver(measure);"
            + "ro.observe(document.documentElement);}catch(e){ro=null;}}"
            + "if(window.MutationObserver){try{new MutationObserver(measure).observe("
            + "document.documentElement,{childList:true,subtree:true,attributes:true,"
            + "attributeFilter:['style','class']});}catch(e){}}"
            // The end of an animation, so a card that settled on a size a frame after the last
            // observer callback is not left a few pixels out.
            + "document.addEventListener('transitionend',measure,true);"
            + "document.addEventListener('animationend',measure,true);"
            // A backstop for a step that changes without animating, mutating or resizing anything
            // these observers are watching.
            + "setInterval(measure,500);measure();"
            + "return 'installed';})();";

    private final WidgetWebHost.Listener listener;
    private final CardObserver observer;

    WidgetJsBridge(WidgetWebHost.Listener listener, CardObserver observer) {
        this.listener = listener;
        this.observer = observer;
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

    /**
     * Called from JavaScript, whenever the card the page draws changes size or stops fitting.
     *
     * @param report the card as {@code "width,height,overflow"}
     */
    public void cardChanged(String report) {
        if (observer == null || report == null) {
            return;
        }
        try {
            // "width,height,overflow". A String rather than three numbers: this engine binds a
            // String parameter reliably, which post proves, while a three-argument numeric method
            // silently failed to resolve on a customer's machine and the page's report went nowhere.
            String[] parts = report.split(",");
            if (parts.length != 3) {
                return;
            }
            observer.onObservedCard(Integer.parseInt(parts[0].trim()),
                Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()));
        } catch (Throwable t) {
            UiLog.w("[WidgetJsBridge] cardChanged, could not read [" + report + "], [" + t + "]");
        }
    }
}
