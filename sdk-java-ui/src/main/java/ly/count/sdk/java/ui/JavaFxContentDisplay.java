package ly.count.sdk.java.ui;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
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
        WidgetSurface current = surface;
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
        ContentPlacement placement = WidgetPlacement.resolve(content.placementFor(current.isLandscape()), current);
        if (placement == null) {
            return;
        }

        UiLog.d("[JavaFxContentDisplay] repositionActiveContent, following the window to " + placement);
        stage.setX(placement.x);
        stage.setY(placement.y);
        stage.setWidth(placement.width);
        stage.setHeight(placement.height);
    }

    private void show(ContentData content, AtomicBoolean closed, ContentCloseCallback onClosed) {
        try {
            surface = FxSurfaces.surfaceFor(owner);
            WidgetSurface currentSurface = surface;

            ContentPlacement placement = WidgetPlacement.resolve(content.placementFor(currentSurface.isLandscape()), currentSurface);
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

            // Transparent, not merely undecorated. Content is laid out by the server as a card
            // with its own background inside the rectangle it asked for, exactly as on the other
            // platforms, so anything the card does not paint has to show the application through it.
            // An undecorated stage with a default scene fill put an opaque white block there instead.
            Stage stage = new Stage(StageStyle.TRANSPARENT);
            stage.setAlwaysOnTop(true);
            stage.setResizable(false);
            Scene scene = new Scene(webView, placement.width, placement.height);
            scene.setFill(Color.TRANSPARENT);
            stage.setScene(scene);
            stage.setX(placement.x);
            stage.setY(placement.y);
            stage.setWidth(placement.width);
            stage.setHeight(placement.height);

            engine.locationProperty().addListener((observable, oldUrl, newUrl) ->
                onContentUrl(newUrl, engine, stage, currentSurface, contentInterface, closed, onClosed));
            engine.setCreatePopupHandler(features -> {
                WebEngine popup = new WebEngine();
                popup.locationProperty().addListener((observable, oldUrl, newUrl) -> ExternalBrowser.open(newUrl));
                return popup;
            });

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

            engine.getLoadWorker().stateProperty().addListener((observable, oldState, newState) -> {
                if (newState == Worker.State.SUCCEEDED) {
                    pageLoaded.set(true);
                    UiLog.i("[JavaFxContentDisplay] show, content painted at " + placement
                        + " after [" + (System.currentTimeMillis() - loadStarted) + "] ms");
                    if (!stage.isShowing()) {
                        stage.show();
                    }
                    FxSurfaces.logPageDiagnostics(engine, "JavaFxContentDisplay", () -> !closed.get());
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
            // visually and the content is never late to appear.
            stage.show();

            engine.load(content.url);
        } catch (Throwable t) {
            UiLog.e("[JavaFxContentDisplay] show, could not show the content, [" + t + "]");
            notifyClosed(closed, onClosed, Collections.emptyMap());
        }
    }

    private void onContentUrl(String url, WebEngine engine, Stage stage, WidgetSurface currentSurface,
        ModuleContent.Content contentInterface, AtomicBoolean closed, ContentCloseCallback onClosed) {

        WidgetAction action = WidgetActionParser.parse(url, SDKCore.logger());
        if (!action.isSdkSignal) {
            return;
        }

        engine.getLoadWorker().cancel();

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
            ContentPlacement resized = WidgetPlacement.resolve(action, surface);
            if (resized != null) {
                UiLog.d("[JavaFxContentDisplay] onContentUrl, resizing the content to " + resized);
                stage.setX(resized.x);
                stage.setY(resized.y);
                stage.setWidth(resized.width);
                stage.setHeight(resized.height);
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
        if (!closed.compareAndSet(false, true)) {
            return;
        }
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
