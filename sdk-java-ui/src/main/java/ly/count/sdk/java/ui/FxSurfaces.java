package ly.count.sdk.java.ui;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import javafx.animation.PauseTransition;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;
import ly.count.sdk.java.internal.ContentPlacement;
import ly.count.sdk.java.internal.WidgetActionParser;

/**
 * Screen geometry and web view defaults shared by the widget and content displays.
 */
final class FxSurfaces {

    /**
     * A fallback font for the pages the SDK loads, plus emoji coverage.
     * <p>
     * Scoped to {@code html} and {@code body} only, and therefore <em>inherited</em>. That is the
     * whole trick: an inherited declaration is weaker than any direct one, so an element the page
     * styles itself keeps the page's font (the templates load Inter and Lato through @font-face and
     * those do load here), while text the page leaves unstyled inherits this instead of falling all
     * the way back to the engine's serif default. An earlier version forced this on every element,
     * which replaced the templates' own typography with Helvetica.
     * <p>
     * {@code !important} is still needed, because losing to an inherited author rule on {@code body}
     * is exactly the case that produced serif text.
     * <p>
     * The emoji families come <em>before</em> the generic {@code sans-serif}: a generic always
     * matches, so anything after it is never consulted, which is why the templates' emoji rendered
     * as nothing.
     */
    private static final String USER_CSS =
        "html,body{"
            + "font-family:\"Helvetica Neue\",Helvetica,\"Segoe UI\",Roboto,Arial,"
            + "\"Apple Color Emoji\",\"Segoe UI Emoji\",\"Noto Color Emoji\",sans-serif !important;"
            + "-webkit-font-smoothing:antialiased;}";

    private static final String USER_STYLESHEET =
        "data:text/css;charset=utf-8;base64,"
            + Base64.getEncoder().encodeToString(USER_CSS.getBytes(StandardCharsets.UTF_8));

    /**
     * How long after a page loads to look at what it actually managed to fetch. Images are still
     * arriving when the document reports itself loaded, so an immediate probe would report false
     * failures.
     */
    private static final Duration DIAGNOSTICS_DELAY = Duration.millis(1500);

    /**
     * Reports what the page could and could not fetch. Written defensively because the WebKit build
     * JavaFX bundles is old: the CSS Font Loading API may be missing entirely, and a throwing probe
     * must never take a content block down with it.
     */
    private static final String DIAGNOSTICS_SCRIPT =
        "(function(){try{"
            + "var out=[],imgs=document.getElementsByTagName('img'),failed=0,pending=0;"
            + "for(var i=0;i<imgs.length;i++){var im=imgs[i],src=(im.currentSrc||im.src||'?');"
            + "if(im.complete&&im.naturalWidth===0){failed++;out.push('IMAGE FAILED '+src);}"
            + "else if(!im.complete){pending++;out.push('image still loading '+src);}}"
            + "out.unshift('images total='+imgs.length+' failed='+failed+' pending='+pending);"
            + "out.push('stylesheets='+(document.styleSheets?document.styleSheets.length:-1));"
            + "if(document.fonts&&document.fonts.status){out.push('fonts status='+document.fonts.status+"
            + "' loaded='+(document.fonts.size!==undefined?document.fonts.size:'?'));}"
            + "else{out.push('fonts: CSS Font Loading API not available in this WebKit');}"
            + "var b=document.body;if(b){var cs=window.getComputedStyle(b);"
            + "out.push('body font='+cs.fontFamily+' size='+cs.fontSize);"
            + "out.push('body background='+cs.backgroundColor);}"
            + "var h=document.documentElement;if(h){out.push('html background='"
            + "+window.getComputedStyle(h).backgroundColor);}"
            + "try{var faces=[];for(var s=0;s<document.styleSheets.length;s++){var rs=document.styleSheets[s].cssRules;"
            + "if(!rs){continue;}for(var r=0;r<rs.length;r++){if(rs[r].type===5){faces.push(rs[r].style.fontFamily+' <- '+rs[r].style.src);}}}"
            + "out.push('@font-face: '+(faces.length?faces.join(' ;; '):'none'));}"
            + "catch(e){out.push('@font-face: unreadable ('+e+')');}"
            + "return out.join(' | ');"
            + "}catch(e){return 'diagnostics failed: '+e;}})()";

    /**
     * Keeps the WebKit stack alive once it has been started. The very first WebView in a process
     * pays for initialising all of WebKit, which is the bulk of the delay between the server saying
     * "show this" and something appearing.
     */
    private static WebView warmView;

    private static volatile boolean diagnosticsEnabled = false;
    private static boolean signalFilterInstalled = false;

    private FxSurfaces() {
    }

    /**
     * @param enabled whether to log what each page managed to fetch
     */
    static void setDiagnosticsEnabled(boolean enabled) {
        diagnosticsEnabled = enabled;
    }

    static boolean isDiagnosticsEnabled() {
        return diagnosticsEnabled;
    }

    /**
     * The containers every feedback widget and content template ships in its static HTML, so their
     * presence is what tells a real widget page apart from whatever the browser substituted for one.
     */
    static final String WIDGET_MARKERS =
        "#widget-body,.modal-content,[class*=\"survey-widget\"],[id^=\"nps-\"],"
            + "[class*=\"countly-ratings\"],[class*=\"cly-\"],.smiley-container";

    /**
     * Whether the loaded document is a widget page at all.
     * <p>
     * JavaFX reports an HTTP level failure (a refused connection, a 404, a gateway error) as a
     * SUCCEEDED load carrying an error document, so this is the only way to notice one. It asks
     * whether the widget templates' own elements are in the document, not whether anything was
     * painted: they are in the served HTML from the moment it parses, whereas painting waits on the
     * widget's own fetches, and judging by paint killed slow widgets that were perfectly fine.
     *
     * @param engine the engine whose document to inspect
     * @return {@code true} when this looks like a widget page, and when it cannot be determined
     */
    static boolean looksLikeWidgetPage(WebEngine engine) {
        try {
            Object result = engine.executeScript(
                "(function(){try{return !!(document.body&&document.querySelector('" + WIDGET_MARKERS + "'));}"
                    + "catch(e){return true;}})()");
            return !(result instanceof Boolean) || (Boolean) result;
        } catch (Throwable t) {
            // Undecidable, so it is not held against the page.
            UiLog.w("[FxSurfaces] looksLikeWidgetPage, could not inspect the page, [" + t + "]");
            return true;
        }
    }

    /**
     * Measures the rectangle the page actually painted, in CSS pixels.
     * <p>
     * Used to tell a real widget page from whatever the browser substituted when the real one could
     * not be fetched: JavaFX reports an HTTP level failure as a SUCCEEDED load carrying an error
     * document, and an error document paints no card.
     *
     * @param engine the engine to measure
     * @return {@code left,top,width,height}, or {@code null} when the page has no card element at
     *     all. Width and height may be zero when the window has not been sized yet.
     */
    static int[] measurePaintedContent(WebEngine engine) {
        try {
            Object result = engine.executeScript(
                "(function(){try{"
                    + "if(!document.body){return '';}"
                    // The widget templates' own containers, most specific first. Guessing from
                    // style does not work: every one of these sits in a transparent wrapper, so a
                    // "child of body with a background" test finds nothing on a perfectly good
                    // page, and that used to be read as a failed load.
                    + "var sel=['#widget-body','.modal-content','[class*=\"survey-widget\"]',"
                    + "'[id^=\"nps-\"]','[class*=\"countly-ratings\"]','[class*=\"cly-\"]',"
                    + "'.smiley-container'];"
                    + "var e=null;"
                    + "for(var i=0;i<sel.length&&!e;i++){var c=document.querySelectorAll(sel[i]);"
                    + "for(var j=0;j<c.length;j++){var r=c[j].getBoundingClientRect();"
                    + "if(r.width>0&&r.height>0){e=c[j];break;}}}"
                    // Anything else the page painted, biggest first: enough to say "this rendered
                    // something", which is all the caller needs.
                    + "if(!e){var best=0;var kids=document.body.querySelectorAll('*');"
                    + "for(var k=0;k<kids.length;k++){var b=kids[k].getBoundingClientRect();"
                    + "var area=b.width*b.height;if(area>best){best=area;e=kids[k];}}}"
                    + "if(!e){return '';}"
                    // Zero sizes are reported rather than rejected: the window is still 1x1 before
                    // it has been placed, so a real card measures zero here. The absence of any
                    // element at all is what says the page rendered nothing.
                    + "var b2=e.getBoundingClientRect();"
                    + "return [Math.round(b2.left),Math.round(b2.top),Math.round(b2.width),Math.round(b2.height)].join(',');"
                    + "}catch(err){return '';}})()");
            if (!(result instanceof String) || ((String) result).isEmpty()) {
                return null;
            }
            String[] parts = ((String) result).split(",");
            if (parts.length != 4) {
                return null;
            }
            return new int[] {
                Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]), Integer.parseInt(parts[3])
            };
        } catch (Throwable t) {
            UiLog.w("[FxSurfaces] measurePaintedContent, could not measure the page, [" + t + "]");
            return null;
        }
    }

    /**
     * Makes the web view's own page backdrop fully transparent, so only what the page paints is
     * visible and the application shows through everywhere else.
     * <p>
     * This is public JavaFX API as of version 20 ({@code WebView.setPageFill}). Before that the only
     * lever was a {@code com.sun.webkit} internal reached by reflection, which needed
     * {@code --add-exports} from every integrator and, on this pipeline, rendered the empty area
     * opaque black instead of see-through. That is why this module requires JavaFX 21.
     * <p>
     * The page still wins: if the document sets its own background colour, that is what shows.
     *
     * @param webView the view whose backdrop to clear
     */
    static void makePageBackgroundTransparent(WebView webView) {
        try {
            webView.setPageFill(Color.TRANSPARENT);
        } catch (Throwable t) {
            // Cosmetic only: the overlay keeps an opaque backdrop.
            UiLog.w("[FxSurfaces] makePageBackgroundTransparent, could not clear the backdrop, [" + t + "]");
        }
    }

    /**
     * The JavaFX property that chooses between the HTTP/2 loader and the older one.
     * Read once, in {@code NetworkContext}'s static initialiser, so it has to be set before the
     * first web view exists. Found by reading {@code NetworkContext}'s bytecode; it is not documented.
     */
    private static final String USE_HTTP2_LOADER = "com.sun.webkit.useHTTP2Loader";

    /**
     * Opts out of JavaFX's HTTP/2 loader before any page is loaded.
     * <p>
     * A widget or content block talks to the SDK by navigating to {@code https://countly_action_event/…},
     * a host that deliberately does not resolve: the navigation is a message, not a destination. The
     * HTTP/2 loader routes through {@code java.net.http}, whose request builder rejects that URI and
     * throws {@code IllegalArgumentException} on the application thread, before the SDK's cancel can
     * take effect. The older loader fails such a load quietly, which is what the protocol relies on
     * and what JavaFX 17 did.
     * <p>
     * Only set when the integrator has not chosen for themselves. HTTP/1.1 versus HTTP/2 makes no
     * practical difference for loading a single widget page.
     */
    private static void preferTolerantNetworkLoader() {
        if (System.getProperty(USE_HTTP2_LOADER) != null) {
            return;
        }
        try {
            System.setProperty(USE_HTTP2_LOADER, "false");
        } catch (Throwable t) {
            // A restrictive security manager: the signalling URL will then log a stack trace.
            UiLog.w("[FxSurfaces] preferTolerantNetworkLoader, could not set " + USE_HTTP2_LOADER
                + ", signalling URLs may log a stack trace, [" + t + "]");
        }
    }

    /**
     * Stops JavaFX's network stack from reporting the SDK's own signalling URLs as fatal.
     * <p>
     * A widget or content block talks to the SDK by navigating to {@code https://countly_action_event/…}.
     * That host does not resolve, which is the point: the navigation is a message, not a destination,
     * and it is cancelled as soon as it is seen. JavaFX 21 routes loads through {@code java.net.http},
     * whose builder rejects the URI outright and throws {@code IllegalArgumentException} on the
     * application thread before the cancel can take effect. JavaFX 17's older loader simply failed
     * the load quietly.
     * <p>
     * There is no navigation veto in the {@code WebEngine} API to prevent the dispatch, so the
     * exception is filtered instead: only this exact case, only on the JavaFX thread, and any other
     * uncaught exception is passed to the handler that was already installed.
     */
    static void installSignalUrlExceptionFilter() {
        if (signalFilterInstalled) {
            return;
        }
        signalFilterInstalled = true;

        Thread fxThread = Thread.currentThread();
        Thread.UncaughtExceptionHandler previous = fxThread.getUncaughtExceptionHandler();
        fxThread.setUncaughtExceptionHandler((thread, thrown) -> {
            if (isSignalUrlRejection(thrown)) {
                UiLog.d("[FxSurfaces] the network stack refused a signalling URL, which is expected: "
                    + thrown.getMessage());
                return;
            }
            if (previous != null) {
                previous.uncaughtException(thread, thrown);
            }
        });
    }

    private static boolean isSignalUrlRejection(Throwable thrown) {
        return thrown instanceof IllegalArgumentException
            && thrown.getMessage() != null
            && thrown.getMessage().contains(WidgetActionParser.ACTION_HOST);
    }

    /**
     * Starts WebKit ahead of time, so the first widget or content block does not pay for it. Cheap
     * to call more than once. Must be called on the JavaFX application thread.
     */
    static void prewarm() {
        if (warmView != null) {
            return;
        }
        // Before the first WebView: NetworkContext reads the loader choice in its static initialiser.
        preferTolerantNetworkLoader();
        try {
            long started = System.currentTimeMillis();
            warmView = new WebView();
            // The warm-up page is the fonts the last run's widget used, so the first widget of this
            // run finds them in WebKit's memory cache instead of fetching them while on screen.
            String warmupPage = WebFontPrefetch.warmupPage();
            if (warmupPage == null) {
                warmView.getEngine().load("about:blank");
            } else {
                warmView.getEngine().loadContent(warmupPage);
            }
            UiLog.d("[FxSurfaces] prewarm, started WebKit in [" + (System.currentTimeMillis() - started)
                + "] ms, prefetching [" + WebFontPrefetch.rememberedCount() + "] fonts");
        } catch (Throwable t) {
            warmView = null;
            UiLog.w("[FxSurfaces] prewarm, could not start WebKit ahead of time, [" + t + "]");
        }
    }

    /**
     * Tells the page how much room it has, which is what makes it recompute its own rectangle.
     * <p>
     * This is the half of the resize protocol the host owns. A content page cannot see the
     * application window: its viewport is the card, and the surface only ever reached the server, in
     * the fetch. Android posts the same message on every layout change
     * ({@code ContentOverlayView.notifyWebViewOfResize}), the page answers with {@code resize_me},
     * and the host adopts that rectangle. Without the message the page never asks for anything and
     * the host is left guessing the geometry.
     * <p>
     * No density conversion: JavaFX lays a page out in logical pixels, which is the unit the message
     * is defined in, so Android's divide-by-density has no counterpart here.
     *
     * @param engine the engine showing the page
     * @param surface the room the page has
     */
    static void notifyPageOfSurface(WebEngine engine, WidgetSurface surface) {
        if (engine == null || surface == null) {
            return;
        }
        try {
            engine.executeScript("window.postMessage({type: 'resize', width: " + surface.width
                + ", height: " + surface.height + "}, '*');");
            UiLog.d("[FxSurfaces] notifyPageOfSurface, told the page it has "
                + surface.width + "x" + surface.height);
        } catch (Throwable t) {
            // A page that cannot be scripted keeps the geometry the host worked out for it.
            UiLog.d("[FxSurfaces] notifyPageOfSurface, could not tell the page its size, [" + t + "]");
        }
    }

    /**
     * Fetches the given faces into the engine's memory cache now, using the hidden warm-up view.
     * <p>
     * The view belongs to the process rather than to any card, so what it starts finishes even when
     * the card that prompted it is dismissed a second later. Call on the JavaFX application thread.
     *
     * @param warmupPage the page to load, or {@code null} to do nothing
     */
    static void warmFonts(String warmupPage) {
        if (warmupPage == null) {
            return;
        }
        prewarm();
        if (warmView == null) {
            return;
        }
        try {
            warmView.getEngine().loadContent(warmupPage);
            UiLog.d("[FxSurfaces] warmFonts, fetching this run's faces into the cache");
        } catch (Throwable t) {
            // The next card pays for the fonts again, which is what happened before this existed.
            UiLog.d("[FxSurfaces] warmFonts, could not warm the faces, [" + t + "]");
        }
    }

    /**
     * Logs the user agent, which carries the bundled WebKit version, and what the page fetched.
     * Only runs when diagnostics are switched on, so a customer's page is never scripted by default.
     *
     * @param engine the engine whose page to inspect
     * @param label which display is asking
     */
    static void logPageDiagnostics(WebEngine engine, String label) {
        logPageDiagnostics(engine, label, () -> true);
    }

    /**
     * @param engine the engine whose page to inspect
     * @param label which display is asking
     * @param stillShowing whether the page is still the one that was loaded. The delayed sample is
     *     skipped when it is not: a content block the user closed quickly has already been navigated
     *     away to a blank page, and probing that reports an empty document rather than the content.
     */
    static void logPageDiagnostics(WebEngine engine, String label, BooleanSupplier stillShowing) {
        if (!diagnosticsEnabled) {
            return;
        }

        UiLog.d("[" + label + "] user agent: " + engine.getUserAgent());
        // Sampled straight away, so a page that gets dismissed a moment later is still described.
        // Images may legitimately still be arriving here; the probe reports those as pending.
        sample(engine, label, "on load");

        PauseTransition wait = new PauseTransition(DIAGNOSTICS_DELAY);
        wait.setOnFinished(event -> {
            if (!stillShowing.getAsBoolean()) {
                UiLog.d("[" + label + "] page went away before the second diagnostics sample");
                return;
            }
            sample(engine, label, "settled");
        });
        wait.play();
    }

    /**
     * Makes the engine paint a CSS background image that arrives after its box was painted.
     * <p>
     * A content card shows its picture as a {@code background-image} rather than an {@code <img>}.
     * This engine fetches and decodes that image, but does not repaint the box when it arrives: the
     * card is left with an empty slot, and no amount of invalidating the scene fills it. Handing the
     * same URL back to the style system does, so that is what this does, once per element, as soon
     * as the image is known to be loaded.
     * <p>
     * Proven by driving the live server: every card whose slot measured a single flat colour
     * measured hundreds of colours after this ran, including a 1.26MB photograph. Other platforms
     * do not need it because their engines invalidate the box themselves.
     * <p>
     * The trigger is the image's own load event, taken from the engine's memory cache, so nothing is
     * downloaded twice and nothing is polled. The observer catches the elements the page adds after
     * the first sweep, and stops after {@code OBSERVER_LIFETIME_MS}: a card is settled long before
     * then, and an observer nobody stops would outlive it.
     */
    private static final String BACKGROUND_IMAGE_REPAINT =
        "(function(){if(window.__clyBackgroundFix){return 'already installed';}"
            + "window.__clyBackgroundFix=true;var ATTEMPTS=4;"
            + "function fix(el){if(!el.style){return;}"
            + "var u=window.getComputedStyle(el).backgroundImage;"
            + "if(!u||u.indexOf('url(')!==0){return;}"
            // Already carrying the value this fix applies: nothing to do, and this is also what
            // stops the observer from reacting to its own writes.
            + "if((el.style.backgroundImage||'')===u){return;}"
            + "var tries=el.__clyBackgroundTries||0;if(tries>=ATTEMPTS){return;}"
            + "el.__clyBackgroundTries=tries+1;"
            + "var url=u.slice(4,u.length-1).replace(/['\"]/g,'');"
            + "var probe=new Image();"
            + "probe.onload=function(){el.style.backgroundImage='none';"
            + "void el.offsetHeight;el.style.backgroundImage=u;};"
            + "probe.src=url;}"
            + "function sweep(){var all=document.querySelectorAll('*');"
            + "for(var i=0;i<all.length&&i<400;i++){fix(all[i]);}}"
            + "sweep();"
            + "if(window.MutationObserver){var obs=new MutationObserver(sweep);"
            + "obs.observe(document.documentElement,{childList:true,subtree:true,"
            + "attributes:true,attributeFilter:['style','class']});"
            + "setTimeout(function(){obs.disconnect();},%1$d);}"
            // The page rebuilds its card as fonts and images settle, and a rebuild drops the value
            // this fix applied. The observer catches most of that; these sweeps catch a rebuild that
            // changes nothing the observer is watching.
            + "[400,1200,2500,5000,9000].forEach(function(ms){"
            + "if(ms<=%1$d){setTimeout(sweep,ms);}});"
            + "return 'installed';})()";

    /** How long the page is watched for elements that gain a background image. */
    private static final long OBSERVER_LIFETIME_MS = 15_000;

    /**
     * Installs the background image repaint on a page that has just loaded. Call on the JavaFX
     * application thread, after the load succeeded.
     *
     * @param engine the engine showing the page
     */
    static void repaintBackgroundImagesWhenTheyArrive(WebEngine engine) {
        try {
            Object result = engine.executeScript(
                // Locale.ROOT: a default locale with its own digits (Arabic-Indic, for one) would
                // print them into the script and make it a syntax error.
                String.format(Locale.ROOT, BACKGROUND_IMAGE_REPAINT, OBSERVER_LIFETIME_MS));
            UiLog.d("[FxSurfaces] background image repaint " + result);
        } catch (Throwable t) {
            // The card still shows its text and buttons, only the picture may be missing.
            UiLog.w("[FxSurfaces] could not install the background image repaint, [" + t + "]");
        }
    }

    /**
     * How much taller the page's content is than the room it has, in logical pixels.
     * <p>
     * A widget template caps its own card: the NPS definitions stop at 620 tall whatever the screen,
     * so a step with a comment box reports 620 and then scrolls inside itself. On a phone that is the
     * only option; on a desktop there is room, and a scrollbar in a card is a worse answer than a
     * taller card. The card is grown by this much instead, still bounded by the surface.
     *
     * @param engine the engine showing the page
     * @return the overflow, or 0 when the content fits or cannot be measured
     */
    static int measureOverflow(WebEngine engine) {
        try {
            Object result = engine.executeScript(
                "(function(){try{var d=document.documentElement;var b=document.body;"
                    + "var need=Math.max(d.scrollHeight||0,b?b.scrollHeight||0:0);"
                    + "var have=Math.max(d.clientHeight||0,window.innerHeight||0);"
                    + "return Math.max(0,need-have);}catch(e){return 0;}})()");
            if (result instanceof Number) {
                return Math.max(0, ((Number) result).intValue());
            }
        } catch (Throwable t) {
            UiLog.d("[FxSurfaces] measureOverflow, could not measure the page, [" + t + "]");
        }
        return 0;
    }

    /**
     * The page's {@code <img>} elements: where each one sits and whether the engine actually decoded
     * it. A card can be missing its picture for two very different reasons, and only this tells them
     * apart: {@code natural=0x0} means the engine never got an image, while a real natural size on a
     * box that is tiny, empty or off the card means the image is there and the layout is wrong.
     *
     * @param engine the engine whose document to inspect
     * @return one entry per image, or a short reason why there are none
     */
    static String describeImages(WebEngine engine) {
        try {
            Object result = engine.executeScript(
                "(function(){try{var out=[];var imgs=document.images;"
                    + "for(var i=0;i<imgs.length&&out.length<5;i++){var im=imgs[i];"
                    + "var st=window.getComputedStyle(im);var r=im.getBoundingClientRect();"
                    + "out.push((im.currentSrc||im.src||'no src').slice(0,90)"
                    + "+' @ '+Math.round(r.left)+','+Math.round(r.top)"
                    + "+' '+Math.round(r.width)+'x'+Math.round(r.height)"
                    + "+' natural='+im.naturalWidth+'x'+im.naturalHeight"
                    + "+' complete='+im.complete"
                    + "+' vis='+st.visibility+' disp='+st.display+' op='+st.opacity);}"
                    + "return out.length?out.join(' ;; '):'no img elements';}catch(e){return 'err '+e;}})()");
            return String.valueOf(result);
        } catch (Throwable t) {
            return "unavailable";
        }
    }

    /**
     * @return the first few CSS background image URLs the page paints with, so an image that is not
     *     an {@code <img>} element is still visible in the log
     */
    private static String backgroundImages(WebEngine engine) {
        try {
            Object result = engine.executeScript(
                "(function(){try{var out=[];var all=document.querySelectorAll('*');"
                    + "for(var i=0;i<all.length&&out.length<5;i++){"
                    + "var el=all[i];var st=window.getComputedStyle(el);"
                    + "var u=st.backgroundImage;"
                    + "if(!u||u.indexOf('url(')!==0){continue;}"
                    // Where the box actually is and whether anything is being drawn: a background on
                    // a box with no size, or one sitting outside the card, is invisible however well
                    // the image itself loaded.
                    + "var r=el.getBoundingClientRect();"
                    + "out.push(u.slice(0,90)+' @ '+Math.round(r.left)+','+Math.round(r.top)"
                    + "+' '+Math.round(r.width)+'x'+Math.round(r.height)"
                    + "+' vis='+st.visibility+' disp='+st.display+' op='+st.opacity"
                    + "+' size='+st.backgroundSize+' pos='+st.backgroundPosition);}"
                    + "return out.join(' ;; ');}catch(e){return 'err '+e;}})()");
            return String.valueOf(result);
        } catch (Throwable t) {
            return "unavailable";
        }
    }

    /**
     * @return each declared font face and whether it has actually been fetched, which is what tells
     *     "the page has not asked for its fonts yet" apart from "the fonts are here"
     */
    private static String faceStates(WebEngine engine) {
        try {
            Object result = engine.executeScript(
                "(function(){try{var f=document.fonts;if(!f||!f.forEach){return 'none';}"
                    + "var out=[];f.forEach(function(face){out.push(face.family+':'+face.status);});"
                    + "return out.join(', ');}catch(e){return 'err '+e;}})()");
            return String.valueOf(result);
        } catch (Throwable t) {
            return "unavailable";
        }
    }

    private static void sample(WebEngine engine, String label, String when) {
        try {
            Object result = engine.executeScript(DIAGNOSTICS_SCRIPT);
            UiLog.d("[" + label + "] page resources (" + when + "): " + result
                + " | images: " + describeImages(engine)
                + " | css backgrounds: " + backgroundImages(engine)
                + " | faces: " + faceStates(engine));
        } catch (Throwable t) {
            UiLog.w("[" + label + "] could not read the page diagnostics (" + when + "), [" + t + "]");
        }
    }

    /**
     * Applies the SDK's web view defaults. Called before navigating.
     *
     * @param engine the engine to configure
     */
    static void configure(WebEngine engine) {
        preferTolerantNetworkLoader();
        installSignalUrlExceptionFilter();
        engine.setJavaScriptEnabled(true);
        try {
            engine.setUserStyleSheetLocation(USER_STYLESHEET);
        } catch (Throwable t) {
            // Cosmetic only: the page still renders with the engine's own defaults.
            UiLog.w("[FxSurfaces] configure, could not apply the fallback font, [" + t + "]");
        }

        engine.getLoadWorker().exceptionProperty().addListener((observable, old, thrown) -> {
            if (thrown != null) {
                UiLog.w("[FxSurfaces] load failed, [" + thrown + "]");
            }
        });
    }

    /**
     * The work area of the screen the given window currently sits on, which is where a widget or a
     * content block belongs. Following the window matters on a multiple monitor desktop: the primary
     * screen is not necessarily the one the application is on, and the application can be dragged to
     * another monitor while a content zone is running.
     *
     * @param owner the application window to follow, may be {@code null} or not yet on screen
     * @return the surface to place on, falling back to the primary screen's work area
     */
    static WidgetSurface screenOf(Window owner) {
        Rectangle2D bounds = screenBoundsOf(owner);
        return new WidgetSurface((int) bounds.getMinX(), (int) bounds.getMinY(), (int) bounds.getWidth(), (int) bounds.getHeight());
    }

    /**
     * Whether widget cards and content blocks are laid out inside the application window instead of
     * on the screen the application is on. Both displays read this, so one setting decides both.
     * <p>
     * Inside the window by default, which is what the other desktop SDKs settled on: a card belongs
     * to the application that asked for it, and one placed against the whole screen appears over
     * whatever else the user has open. An integrator who wants the screen says so.
     */
    private static volatile boolean displayWithinApp = true;

    static void setDisplayWithinApp(boolean withinApp) {
        displayWithinApp = withinApp;
    }

    static boolean isDisplayWithinApp() {
        return displayWithinApp;
    }

    /**
     * The area a card or a content block may occupy, per the current setting.
     *
     * @param owner the application window to measure against, {@code null} for the primary screen
     * @return the application window's bounds, or the work area of the screen it is on
     */
    static WidgetSurface surfaceFor(Window owner) {
        return displayWithinApp ? boundsOf(owner) : screenOf(owner);
    }

    /**
     * @param owner the window to measure
     * @return the window's own bounds, or the primary screen's work area when it is not on screen yet
     */
    static WidgetSurface boundsOf(Window owner) {
        if (!isOnScreen(owner)) {
            return screenOf(null);
        }
        // The window's own bounds. Insetting them to spare the resize border was worse on both
        // counts: a block that fills the window left the application showing through as a stripe
        // down every side, and a card anchored to an edge no longer touched it. A covering block is
        // a modal, and there is nothing to resize while one is up.
        return new WidgetSurface((int) owner.getX(), (int) owner.getY(),
            (int) owner.getWidth(), (int) owner.getHeight());
    }

    /**
     * The corner radius applied to a block that covers the whole application window.
     * <p>
     * An overlay is a rectangular window laid over one with rounded corners, so a block that fills
     * it paints its own square corners past the application's - visible as four dark wedges. JavaFX
     * cannot read the native window's radius, so this is a sensible default an integrator can
     * correct, in the same spirit as the Swift SDK's {@code content.overlayCornerRadius}.
     */
    private static volatile double overlayCornerRadius = 10;

    static void setOverlayCornerRadius(double radius) {
        overlayCornerRadius = Math.max(0, radius);
    }

    static double getOverlayCornerRadius() {
        return overlayCornerRadius;
    }

    /**
     * Rounds off a block that covers its whole surface, and leaves any other block alone.
     * <p>
     * Only a covering block needs it: a card placed somewhere inside the window paints its own
     * corners and everything around them is already transparent, so clipping it would cut into the
     * card itself.
     *
     * @param webView the view showing the block
     * @param rect where the block was placed, screen absolute
     * @param surface the area it was placed on
     */
    static void applyOverlayCorners(WebView webView, ContentPlacement rect, WidgetSurface surface) {
        if (webView == null || rect == null || surface == null) {
            return;
        }
        boolean covers = rect.width >= surface.width && rect.height >= surface.height;
        if (!covers || overlayCornerRadius <= 0) {
            webView.setClip(null);
            return;
        }
        Rectangle clip = new Rectangle(rect.width, rect.height);
        clip.setArcWidth(overlayCornerRadius * 2);
        clip.setArcHeight(overlayCornerRadius * 2);
        webView.setClip(clip);
    }

    /**
     * The borderless window a widget card or a content block is shown in, with its web view already
     * in a transparent scene. Call on the JavaFX application thread.
     * <p>
     * Transparent, not merely undecorated: both a widget and a content block draw their own rounded
     * card with its own background, so everything around that card has to show the application
     * through it. An undecorated stage with a default scene fill puts an opaque white block there
     * instead.
     * <p>
     * Who the window belongs to follows where it is being shown. Inside the application window it is
     * a child of that window: ordered with it, above it, and carried along when it moves, which is
     * what something laid out against that window should do. On the whole screen it belongs to
     * nobody - a card placed against the screen is a system wide message for as long as the process
     * runs, so it sits on the top layer and stays where it was put, instead of being dragged around
     * by an application window it was never laid out against.
     *
     * @param owner the application window to belong to, may be {@code null}
     * @param webView the view to show, which becomes the scene root
     * @param sceneWidth the scene's initial width
     * @param sceneHeight the scene's initial height
     * @return the window, not yet placed and not yet shown
     */
    static Stage newOverlayStage(Window owner, WebView webView, int sceneWidth, int sceneHeight) {
        Stage stage = new Stage(StageStyle.TRANSPARENT);
        stage.setResizable(false);
        if (isDisplayWithinApp() && owner != null && owner.isShowing()) {
            stage.initOwner(owner);
        } else {
            stage.setAlwaysOnTop(true);
        }
        Scene scene = new Scene(webView, sceneWidth, sceneHeight);
        scene.setFill(Color.TRANSPARENT);
        stage.setScene(scene);
        return stage;
    }

    /**
     * Applies a rectangle to the window <i>and</i> to the page inside it.
     * <p>
     * Moving the window is not enough: a web view reports its own 800x600 preferred size, so the
     * page's viewport stays whatever it was when the scene was built and the page never reflows or
     * sees a resize. Something laid out for one width then keeps that width in a window of another.
     * The view is resized outright rather than only asked to lay out, because a window that has not
     * run a layout pass yet - one that has never been shown - would otherwise leave the page
     * measuring the scene it started in, and any fit that follows would have nothing to work with.
     *
     * @param stage the window to move and size
     * @param webView the view inside it, {@code null} when the scene holds something else
     * @param rect where it belongs, screen absolute
     * @param surface the area it was placed on, for the rounded corners of a covering block
     */
    static void applyGeometry(Stage stage, WebView webView, ContentPlacement rect, WidgetSurface surface) {
        stage.setX(rect.x);
        stage.setY(rect.y);
        stage.setWidth(rect.width);
        stage.setHeight(rect.height);

        if (webView == null) {
            return;
        }
        webView.setPrefSize(rect.width, rect.height);
        webView.resize(rect.width, rect.height);
        webView.requestLayout();
        // Something covering the application window has to follow its rounded corners too.
        applyOverlayCorners(webView, rect, surface);
    }

    /**
     * The engine handed to a {@code target="_blank"} link, which sends it to the system browser
     * instead of rendering it in the card.
     *
     * @return a throwaway engine that only reports where it was asked to go
     */
    static WebEngine newExternalLinkEngine() {
        WebEngine popup = new WebEngine();
        popup.locationProperty().addListener((observable, oldUrl, newUrl) -> {
            if (newUrl != null && !newUrl.isEmpty()) {
                ExternalBrowser.open(newUrl);
            }
        });
        return popup;
    }

    private static Rectangle2D screenBoundsOf(Window owner) {
        try {
            if (isOnScreen(owner)) {
                // Match on the window's centre rather than its origin, so a window straddling two
                // monitors lands on the one it is mostly on.
                double centreX = owner.getX() + owner.getWidth() / 2;
                double centreY = owner.getY() + owner.getHeight() / 2;
                List<Screen> screens = Screen.getScreensForRectangle(centreX, centreY, 1, 1);
                if (!screens.isEmpty()) {
                    return screens.get(0).getVisualBounds();
                }
            }
            return Screen.getPrimary().getVisualBounds();
        } catch (Throwable t) {
            UiLog.w("[FxSurfaces] screenBoundsOf, could not measure the screen, [" + t + "]");
            return new Rectangle2D(0, 0, 0, 0);
        }
    }

    private static boolean isOnScreen(Window owner) {
        if (owner == null || !owner.isShowing()) {
            return false;
        }
        return !Double.isNaN(owner.getX()) && !Double.isNaN(owner.getY())
            && owner.getWidth() > 0 && owner.getHeight() > 0;
    }

    /**
     * The application window a display should follow when the integrator did not name one. Prefers
     * the focused window, then any showing one. Must be called on the JavaFX application thread.
     *
     * @return the window to follow, or {@code null} when the application has none on screen
     */
    static Window primaryApplicationWindow() {
        try {
            Window focused = null;
            Window showing = null;
            for (Window window : Window.getWindows()) {
                if (!window.isShowing()) {
                    continue;
                }
                if (showing == null) {
                    showing = window;
                }
                if (window.isFocused()) {
                    focused = window;
                }
            }
            return focused != null ? focused : showing;
        } catch (Throwable t) {
            UiLog.w("[FxSurfaces] primaryApplicationWindow, could not read the window list, [" + t + "]");
            return null;
        }
    }
}
