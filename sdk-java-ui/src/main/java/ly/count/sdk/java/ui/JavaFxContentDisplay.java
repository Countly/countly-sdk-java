package ly.count.sdk.java.ui;

import java.net.URI;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import ly.count.sdk.java.Countly;
import ly.count.sdk.java.internal.ContentCloseCallback;
import ly.count.sdk.java.internal.ContentData;
import ly.count.sdk.java.internal.ContentDisplay;
import ly.count.sdk.java.internal.ContentPlacement;
import ly.count.sdk.java.internal.ContentScreen;
import ly.count.sdk.java.internal.ModuleContent;
import ly.count.sdk.java.internal.SDKCore;
import ly.count.sdk.java.internal.WidgetAction;
import ly.count.sdk.java.internal.WidgetActionParser;
import netscape.javascript.JSObject;

/**
 * Shows Countly content in a borderless, always on top JavaFX window placed where the server asked,
 * leaving the rest of the application usable.
 * <p>
 * The placement follows the screen the application window is on, and keeps following it when the
 * application is dragged to another monitor, so content never opens on a monitor the user is not
 * looking at.
 * <p>
 * JavaFX lays a web page out in logical pixels, which is also the unit the server's coordinates come
 * back in, so the reported surface and the applied rectangles need no density conversion.
 */
public class JavaFxContentDisplay implements ContentDisplay {

    /**
     * How long a content page gets to load before the attempt is abandoned. Without this a page that
     * never finishes would leave the content zone believing something is on screen, and no further
     * content would ever be fetched.
     * <p>
     * Shortened by tests, the same way {@link ly.count.sdk.java.internal.CountlyTimer} exposes its
     * own delay, so the give-up path can be exercised without a twenty second test.
     */
    static Duration loadTimeout = Duration.seconds(20); // shortened for testing purposes

    private final Window owner;
    private volatile WidgetSurface surface;

    /** The block on screen right now, so it can follow the window it was placed against. */
    private Stage activeStage;
    private ContentData activeContent;

    /** The surface last reported to the server, which its coordinates are relative to. */
    private volatile WidgetSurface reportedSurface;

    /** The surface the block on screen was last placed against, so an unchanged one costs nothing. */
    private volatile WidgetSurface appliedSurface;

    /** Where the block was last put, so a window manager moving it can be undone. */
    private volatile ContentPlacement appliedPlacement;

    /** What this display holds the screen for, released when the block closes. */
    private volatile String presentationClaim;

    /**
     * The rectangle the page asked for through {@code resize_me}, and the surface it asked against.
     * Once the page has spoken it outranks the server's rectangle: it is the only party that knows
     * how tall its own content turned out.
     */
    private volatile ContentPlacement pageRequested;
    private volatile WidgetSurface pageRequestedFor;

    /**
     * The object the page posts to. A field, because the engine holds only a weak reference to it
     * and a bridge nobody else references is collected after its first message.
     */
    private ContentJsBridge contentBridge;

    /**
     * Follows the primary application window. Construct on the JavaFX application thread.
     */
    public JavaFxContentDisplay() {
        this(FxSurfaces.primaryApplicationWindow());
    }

    /**
     * Follows the given window. Construct on the JavaFX application thread.
     *
     * @param owner the application window whose screen content is placed on, {@code null} to use the
     *     primary screen
     */
    public JavaFxContentDisplay(Window owner) {
        this.owner = owner;
        this.surface = FxSurfaces.surfaceFor(owner);
        watchOwner();
        // Pay for starting WebKit now, while entering the zone, rather than when a content block
        // finally arrives and the user is waiting to see it.
        FxSurfaces.prewarm();
    }

    /**
     * The fetch runs off the JavaFX thread and has to know the surface, so the cached value is kept
     * current by listening to the window instead of measuring on demand.
     */
    private void watchOwner() {
        if (owner == null) {
            return;
        }
        Runnable refresh = () -> {
            surface = FxSurfaces.surfaceFor(owner);
            repositionActiveContent();
        };
        owner.xProperty().addListener((observable, old, current) -> refresh.run());
        owner.yProperty().addListener((observable, old, current) -> refresh.run());
        owner.widthProperty().addListener((observable, old, current) -> refresh.run());
        owner.heightProperty().addListener((observable, old, current) -> refresh.run());
    }

    @Override
    public ContentScreen getScreen() {
        // Re-resolved, not the surface captured when this display was built. The server lays a block
        // out inside the size reported here, and show() places it inside the surface as it is then:
        // if those two disagree the coordinates are computed for one area and clamped into another,
        // which pins the card to a corner. They disagree easily - the application window may not
        // have been on screen when this display was constructed, in which case the surface was the
        // screen, and it is the window by the time a block arrives.
        surface = FxSurfaces.surfaceFor(owner);
        WidgetSurface current = surface;
        // Kept, because the server's coordinates are a proportion of this: following a resize means
        // keeping those proportions, which needs the area they were computed against.
        reportedSurface = current;
        UiLog.d("[JavaFxContentDisplay] getScreen, reporting " + current
            + " withinApp=" + FxSurfaces.isDisplayWithinApp());
        return new ContentScreen(current.width, current.height);
    }

    @Override
    public void present(ContentData content, ContentCloseCallback onClosed) {
        // The guard lives out here, not inside the JavaFX call, so the SDK is told the content is
        // gone even when the toolkit never runs our block. Without that the content zone would wait
        // forever for a close that cannot come.
        AtomicBoolean closed = new AtomicBoolean(false);

        try {
            Platform.runLater(() -> show(content, closed, onClosed));
        } catch (Throwable t) {
            UiLog.e("[JavaFxContentDisplay] present, the JavaFX toolkit is not running, [" + t + "]");
            notifyClosed(closed, onClosed, Collections.emptyMap());
        }
    }

    /**
     * @return the window the block on screen is in, or {@code null} when nothing is showing. For the
     *     scenario driver, which scripts the page inside it.
     */
    Stage activeStage() {
        return activeStage;
    }

    /**
     * Moves the block on screen to where the window it belongs to has gone. A content block is laid
     * out by the server against a specific area, so a window that is moved, resized or dragged to
     * another monitor has to take its content with it instead of leaving it behind.
     */
    private void repositionActiveContent() {
        Stage stage = activeStage;
        ContentData content = activeContent;
        if (stage == null || content == null || !stage.isShowing()) {
            return;
        }

        WidgetSurface current = surface;
        // Dragging a window fires a stream of move events, and on the whole screen - the default -
        // none of them change the area a block was placed against. Doing the work anyway re-placed
        // the card and told the page its size on every frame of the drag, and the page answered with
        // a rectangle of its own, so a block visibly resized while the window was merely moved.
        WidgetSurface applied = appliedSurface;
        if (current.sameAs(applied)) {
            // The area is unchanged, so the block belongs exactly where it was put. It can still
            // have been moved without asking: an owned stage is a child window, and a child window
            // is dragged along with its parent, so a block placed against the whole screen follows
            // the application around unless it is put back. Nothing is recomputed and the page is
            // not told anything - this only undoes the drag.
            ContentPlacement keep = appliedPlacement;
            if (keep != null && ((int) stage.getX() != keep.x || (int) stage.getY() != keep.y)) {
                UiLog.d("[JavaFxContentDisplay] repositionActiveContent, the window took the block"
                    + " with it, putting it back at " + keep);
                stage.setX(keep.x);
                stage.setY(keep.y);
            }
            return;
        }
        appliedSurface = current;
        boolean resized = !current.sameSizeAs(applied);

        ContentPlacement fromPage = pageRequested;
        // The page's own rectangle when it has asked for one, the server's otherwise, either way in
        // this surface's proportions. An immediate answer: when the page reacts to the message below
        // with a resize_me of its own, that replaces this.
        ContentPlacement placement = WidgetPlacement.following(fromPage, pageRequestedFor,
            content.placementFor(current.isLandscape()), reportedSurface, current);
        if (placement == null) {
            return;
        }

        UiLog.d("[JavaFxContentDisplay] repositionActiveContent, following the window to " + placement
            + " (" + (fromPage != null ? "page" : "server") + " asked for it, "
            + (resized ? "the area changed size" : "the area only moved") + ")");
        applyGeometry(stage, placement, current);

        // Only a change of size is worth telling the page about: it lays itself out against the
        // width and height, and a block that merely moved with its window has nothing to recompute.
        WebView webView = resized ? webViewOf(stage) : null;
        if (webView != null) {
            FxSurfaces.notifyPageOfSurface(webView.getEngine(), current);
        }
    }

    /**
     * Applies a rectangle to the window and the page inside it, remembering it so a window manager
     * that moves the block afterwards can be undone.
     *
     * @param stage the window showing the block
     * @param rect where the block belongs
     * @param onSurface the area it is being placed on
     */
    private void applyGeometry(Stage stage, ContentPlacement rect, WidgetSurface onSurface) {
        appliedPlacement = rect;
        FxSurfaces.applyGeometry(stage, webViewOf(stage), rect, onSurface);
    }

    /**
     * @param stage the window to look inside
     * @return the view showing the block, or {@code null} when the scene holds something else
     */
    private static WebView webViewOf(Stage stage) {
        if (stage.getScene() == null || !(stage.getScene().getRoot() instanceof WebView)) {
            return null;
        }
        return (WebView) stage.getScene().getRoot();
    }

    private void show(ContentData content, AtomicBoolean closed, ContentCloseCallback onClosed) {
        // One presentation at a time, shared with feedback widgets: see PresentationLock. Refused
        // content is reported closed at once, so the zone carries on and fetches again later rather
        // than waiting on a block that never appeared.
        String claim = "content " + (content == null ? "?" : content.url);
        if (!PresentationLock.tryAcquire(claim)) {
            // Told at once, without touching the claim: this block never held the screen, and the
            // one that does must keep its claim, or the next request would open on top of it.
            notifyClosed(closed, onClosed, Collections.emptyMap(), null);
            return;
        }
        presentationClaim = claim;

        try {
            surface = FxSurfaces.surfaceFor(owner);
            WidgetSurface currentSurface = surface;
            // Belongs to the block that asked for it, not to this one.
            pageRequested = null;
            pageRequestedFor = null;
            appliedSurface = currentSurface;

            ContentPlacement placement = WidgetPlacement.resolve(content.placementFor(currentSurface.isLandscape()), currentSurface);
            UiLog.d("[JavaFxContentDisplay] show, placing into " + currentSurface
                + " server asked for " + content.placementFor(currentSurface.isLandscape()));
            if (placement == null) {
                UiLog.w("[JavaFxContentDisplay] show, the content has no usable placement, dropping it");
                notifyClosed(closed, onClosed, Collections.emptyMap());
                return;
            }

            // Captured now, while consent is known to be good. Re-resolving it per navigation could
            // hand back null after a consent change and silently drop the content's own events.
            ModuleContent.Content contentInterface = Countly.instance().content();

            WebView webView = new WebView();
            WebEngine engine = webView.getEngine();
            FxSurfaces.configure(engine);

            // Public JavaFX API since version 20. Everything the page does not paint shows the
            // application through it, which is how content behaves on the other platforms, and it
            // means showing the window before the page has painted costs nothing visually.
            FxSurfaces.makePageBackgroundTransparent(webView);

            // Transparent and owned or always on top, as FxSurfaces explains: content is laid out by
            // the server as a card with its own background inside the rectangle it asked for,
            // exactly as on the other platforms, so anything the card does not paint has to show
            // the application through it.
            Stage stage = FxSurfaces.newOverlayStage(owner, webView, placement.width, placement.height);
            applyGeometry(stage, placement, currentSurface);

            engine.locationProperty().addListener((observable, oldUrl, newUrl) ->
                onContentUrl(newUrl, engine, stage, currentSurface, contentInterface, closed, onClosed));
            engine.setCreatePopupHandler(features -> FxSurfaces.newExternalLinkEngine());

            activeStage = stage;
            activeContent = content;

            // A window the user closed by other means must still release the content zone.
            stage.setOnHidden(event -> {
                if (activeStage == stage) {
                    activeStage = null;
                    activeContent = null;
                }
                notifyClosed(closed, onClosed, Collections.emptyMap());
            });

            // The window is shown only once the page has painted. Showing it before the load, as an
            // empty white rectangle that fills in a moment later, is what makes content look like it
            // arrives late.
            // Two separate facts, which must not share a flag: whether the page finished loading,
            // and whether the window is on screen. Showing a transparent window early is an
            // optimisation; giving up still has to depend only on the page never arriving.
            AtomicBoolean pageLoaded = new AtomicBoolean(false);
            long loadStarted = System.currentTimeMillis();

            // The page's postMessage channel, for a survey the content queue hosts. Installed as
            // soon as there is a document, so the page's first message is not lost, and again on
            // load in case the document was replaced; the script guards against running twice.
            contentBridge = new ContentJsBridge(action ->
                handleContentAction(action, engine, stage, contentInterface, closed, onClosed));
            engine.documentProperty().addListener((observable, oldDocument, document) -> {
                if (document != null) {
                    installContentBridge(engine);
                }
            });

            engine.getLoadWorker().stateProperty().addListener((observable, oldState, newState) -> {
                if (newState == Worker.State.SUCCEEDED) {
                    installContentBridge(engine);
                    // The blank page a dismissed block is navigated away to loads successfully too;
                    // treating that as the content arriving logged a second paint and re-revealed a
                    // window that was on its way out.
                    if (pageLoaded.get() || closed.get()) {
                        return;
                    }
                    pageLoaded.set(true);
                    if (!stage.isShowing()) {
                        stage.show();
                    }
                    // The picture and the fonts are asked for before the reveal, so the card is
                    // seen once, complete, rather than appearing and then rearranging itself.
                    FxSurfaces.repaintBackgroundImagesWhenTheyArrive(engine);
                    WebFontPrefetch.remember(engine);

                    // Shown as soon as the page is loaded, like the other platforms' SDKs do.
                    // Holding the card back until the page's fonts settled was measured on the live
                    // server and never paid off: document.fonts.ready did not resolve on any of the
                    // nine content variants, so every card paid the full deadline and still showed
                    // with fonts in flight. What removes the swap is having the faces in the cache
                    // before the page asks, which is WebFontPrefetch's job.
                    stage.setOpacity(1);
                    UiLog.i("[JavaFxContentDisplay] show, content painted at " + placement
                        + " after [" + (System.currentTimeMillis() - loadStarted) + "] ms");
                    FxSurfaces.logPageDiagnostics(engine, "JavaFxContentDisplay", () -> !closed.get());
                    // Straight away, not only on a later resize: the page's viewport is the card, so
                    // this is the first and only chance it gets to learn the real surface and ask for
                    // the rectangle it actually wants.
                    FxSurfaces.notifyPageOfSurface(engine, surface);
                } else if (newState == Worker.State.FAILED && !pageLoaded.get()) {
                    UiLog.w("[JavaFxContentDisplay] show, the content page could not be loaded");
                    notifyClosed(closed, onClosed, Collections.emptyMap());
                    stage.close();
                }
            });

            PauseTransition loadDeadline = new PauseTransition(loadTimeout);
            loadDeadline.setOnFinished(event -> {
                if (!pageLoaded.get()) {
                    UiLog.w("[JavaFxContentDisplay] show, the content page did not load in time, giving up");
                    notifyClosed(closed, onClosed, Collections.emptyMap());
                    stage.close();
                }
            });
            loadDeadline.play();

            // Transparent all the way down, so showing before the page has painted costs nothing
            // visually and the content is never late to appear. Fully invisible until the page and
            // its fonts are both ready, so it is never seen half drawn.
            stage.setOpacity(0);
            stage.show();

            engine.load(withOrigin(content.url));
        } catch (Throwable t) {
            UiLog.e("[JavaFxContentDisplay] show, could not show the content, [" + t + "]");
            notifyClosed(closed, onClosed, Collections.emptyMap());
        }
    }

    /**
     * The content URL with an {@code origin} parameter added, so a survey the content queue hosts
     * accepts this display's resize message.
     * <p>
     * The widget templates only act on the host's {@code {type:'resize'}} message when
     * {@code event.origin} equals the URL's {@code origin} parameter, which the server's content URL
     * does not carry. For a page posting to its own window that origin is the page's own, so it is
     * taken from the URL itself. A content block ignores the parameter.
     *
     * @param url the URL the server handed out
     * @return the same URL, carrying {@code origin} when it did not already
     */
    static String withOrigin(String url) {
        if (url == null || url.contains("origin=")) {
            return url;
        }
        try {
            URI uri = new URI(url);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return url;
            }
            String origin = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
            return url + (url.contains("?") ? "&" : "?") + "origin=" + origin;
        } catch (Throwable t) {
            // An unparseable URL is the server's problem to report, not this parameter's.
            return url;
        }
    }

    private void installContentBridge(WebEngine engine) {
        if (contentBridge == null) {
            return;
        }
        try {
            JSObject window = (JSObject) engine.executeScript("window");
            window.setMember(ContentJsBridge.MEMBER_NAME, contentBridge);
            Object result = engine.executeScript(ContentJsBridge.INSTALL_SCRIPT);
            UiLog.d("[JavaFxContentDisplay] installContentBridge, " + result);
        } catch (Throwable t) {
            // A block that only navigates to signal still works; only a hosted survey loses its
            // resize and close.
            UiLog.w("[JavaFxContentDisplay] installContentBridge, could not install, [" + t + "]");
        }
    }

    private void onContentUrl(String url, WebEngine engine, Stage stage, WidgetSurface currentSurface,
        ModuleContent.Content contentInterface, AtomicBoolean closed, ContentCloseCallback onClosed) {

        WidgetAction action = WidgetActionParser.parse(url, SDKCore.logger());
        if (!action.isSdkSignal) {
            return;
        }

        // A navigation, not a destination: stop it before it goes anywhere.
        engine.getLoadWorker().cancel();
        handleContentAction(action, engine, stage, contentInterface, closed, onClosed);
    }

    /**
     * Acts on a signal from the page, whichever way it arrived: as a navigation to the action URL,
     * which content blocks use, or as a posted message, which a survey served through the content
     * queue uses. The two carry the same commands.
     */
    private void handleContentAction(WidgetAction action, WebEngine engine, Stage stage,
        ModuleContent.Content contentInterface, AtomicBoolean closed, ContentCloseCallback onClosed) {

        if (action.isExternalLink) {
            UiLog.d("[JavaFxContentDisplay] onContentUrl, opening an external link");
            ExternalBrowser.open(action.link);

            // A "cly_x_int=1" link that also carries "close=1" closes the content, the same way an
            // action event does. Returning here unconditionally, as this used to, meant a link that
            // asked to be opened AND dismissed only got opened.
            if (action.close) {
                notifyClosed(closed, onClosed, action.queryParams);
                engine.load("about:blank");
                stage.close();
            }
            return;
        }

        // Events and links are processed first, the close comes last, as the content protocol asks.
        if (action.eventPayload != null && contentInterface != null) {
            try {
                contentInterface.recordContentEvents(action.eventPayload);
            } catch (Throwable t) {
                UiLog.e("[JavaFxContentDisplay] onContentUrl, could not record the content events, [" + t + "]");
            }
        }

        if (action.link != null) {
            ExternalBrowser.open(action.link);
        }

        if (action.hasResize) {
            // Against the area the window is on now, not the one it was shown on: a content block
            // that resizes itself after the application window moved would otherwise jump back to
            // where that window used to be.
            WidgetSurface current = surface;
            ContentPlacement asked = action.resizeFor(current.isLandscape());
            ContentPlacement resized = WidgetPlacement.resolve(asked, current);
            if (resized != null) {
                // The page's own rectangle replaces the server's as the source of truth, the way
                // Android replaces its stored config in resizeMeAction. Otherwise the next window
                // move recomputes from the server's numbers and undoes what the page asked for.
                pageRequested = asked;
                pageRequestedFor = current;
                UiLog.d("[JavaFxContentDisplay] onContentUrl, resizing the content to " + resized
                    + " as the page asked");
                applyGeometry(stage, resized, current);
            }
        }

        if (action.close) {
            UiLog.i("[JavaFxContentDisplay] onContentUrl, the content asked to be closed");
            notifyClosed(closed, onClosed, action.queryParams);
            engine.load("about:blank");
            stage.close();
        }
    }

    private void notifyClosed(AtomicBoolean closed, ContentCloseCallback onClosed, Map<String, Object> contentData) {
        notifyClosed(closed, onClosed, contentData, presentationClaim);
    }

    /**
     * @param claim the screen claim this close frees, {@code null} when the block never held one
     */
    private void notifyClosed(AtomicBoolean closed, ContentCloseCallback onClosed, Map<String, Object> contentData,
        String claim) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // Freed before the SDK hears about the close, so the next thing it wants to show is not
        // refused by the block that just went away.
        if (claim != null && claim.equals(presentationClaim)) {
            presentationClaim = null;
        }
        PresentationLock.release(claim);
        if (onClosed == null) {
            return;
        }
        try {
            onClosed.onClosed(contentData);
        } catch (Throwable t) {
            UiLog.e("[JavaFxContentDisplay] notifyClosed, the close callback threw, [" + t + "]");
        }
    }
}
