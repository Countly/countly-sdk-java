package ly.count.sdk.java.ui;

import javafx.application.Platform;

/**
 * One presentation at a time, across feedback widgets and content blocks alike.
 * <p>
 * Every other SDK enforces this: Android's {@code showContentOverlay} refuses when
 * {@code ContentOverlayView.isOtherOverlayPresented} says a content or feedback overlay is already
 * up, and skips the content. Two cards on screen at once would fight for the same edge of the
 * window, and a survey opening over a content block would leave the block's close event unrecorded
 * when the survey took the click meant for it.
 * <p>
 * The refused caller is told straight away, through the same callback a dismissal uses, so nothing
 * waits on a card that will never show. For content that means the zone carries on and fetches
 * again after its interval, exactly as Android's {@code shouldFetchContents = true} does.
 * <p>
 * Only ever touched on the JavaFX application thread, so a plain field is enough.
 */
final class PresentationLock {

    private static String showing;

    private PresentationLock() {
    }

    /**
     * @param what a description of what wants to show, for the log
     * @return {@code true} when it may show; {@code false} when something else is already on screen
     */
    static boolean tryAcquire(String what) {
        if (showing != null) {
            UiLog.w("[PresentationLock] " + what + " cannot be shown: " + showing
                + " is already being shown, skipping");
            return false;
        }
        showing = what;
        return true;
    }

    /**
     * Frees the screen for the next presentation. Safe to call more than once, and from any thread:
     * a close can arrive from the page, the window manager or the SDK, and each path releases.
     */
    static void release(String what) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> release(what));
            return;
        }
        if (what != null && what.equals(showing)) {
            showing = null;
        }
    }

    /** @return what is on screen, or {@code null} when nothing is */
    static String showing() {
        return showing;
    }

    /** Test seam. */
    static void resetForTests() {
        showing = null;
    }
}
