package ly.count.sdk.java.ui;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
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
     */
    private static final Duration LOAD_TIMEOUT = Duration.seconds(20);

    private final Window owner;
    private volatile WidgetSurface surface;

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
        this.surface = FxSurfaces.screenOf(owner);
        watchOwner();
    }

    /**
     * The fetch runs off the JavaFX thread and has to know the surface, so the cached value is kept
     * current by listening to the window instead of measuring on demand.
     */
    private void watchOwner() {
        if (owner == null) {
            return;
        }
        Runnable refresh = () -> surface = FxSurfaces.screenOf(owner);
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

    private void show(ContentData content, AtomicBoolean closed, ContentCloseCallback onClosed) {
        try {
            surface = FxSurfaces.screenOf(owner);
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

            Stage stage = new Stage(StageStyle.UNDECORATED);
            stage.setAlwaysOnTop(true);
            stage.setResizable(false);
            stage.setScene(new Scene(webView, placement.width, placement.height));
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

            // A window the user closed by other means must still release the content zone.
            stage.setOnHidden(event -> notifyClosed(closed, onClosed, Collections.emptyMap()));

            // The window is shown only once the page has painted. Showing it before the load, as an
            // empty white rectangle that fills in a moment later, is what makes content look like it
            // arrives late.
            AtomicBoolean shown = new AtomicBoolean(false);
            engine.getLoadWorker().stateProperty().addListener((observable, oldState, newState) -> {
                if (newState == Worker.State.SUCCEEDED && shown.compareAndSet(false, true)) {
                    UiLog.i("[JavaFxContentDisplay] show, showing content at " + placement);
                    stage.show();
                } else if (newState == Worker.State.FAILED && !shown.get()) {
                    UiLog.w("[JavaFxContentDisplay] show, the content page could not be loaded");
                    notifyClosed(closed, onClosed, Collections.emptyMap());
                    stage.close();
                }
            });

            PauseTransition loadDeadline = new PauseTransition(LOAD_TIMEOUT);
            loadDeadline.setOnFinished(event -> {
                if (!shown.get()) {
                    UiLog.w("[JavaFxContentDisplay] show, the content page did not load in time, giving up");
                    notifyClosed(closed, onClosed, Collections.emptyMap());
                    stage.close();
                }
            });
            loadDeadline.play();

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
            ContentPlacement resized = WidgetPlacement.resolve(action, currentSurface);
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
