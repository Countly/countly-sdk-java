package ly.count.sdk.java.ui;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;
import org.junit.Assert;
import org.junit.Assume;

/**
 * Runs the JavaFX display classes headlessly.
 * <p>
 * The build points JavaFX at Monocle's headless platform, so a stage can be shown and a web view can
 * load a page with no display attached. That is what makes {@link CountlyWebView} and the two
 * display classes testable at all.
 * <p>
 * The toolkit starts once per JVM and is never shut down, because JavaFX cannot be restarted in the
 * same process.
 */
final class FxTestToolkit {

    private static final long TIMEOUT_SECONDS = 30;

    private static boolean started = false;

    private FxTestToolkit() {
    }

    /**
     * Skips a test class when this machine cannot start a JavaFX toolkit at all.
     * <p>
     * Most of the toolkit dependent classes are fine: with one JVM per class (see the build file)
     * they load real pages and pass. Only {@code JavaFxContentDisplayTests} cannot run, and it has
     * its own stricter gate.
     */
    static void assumeToolkitAvailable() {
        try {
            start();
        } catch (Throwable t) {
            Assume.assumeNoException("skipped: no JavaFX toolkit on this machine", t);
        }
    }

    /**
     * Skips a class that shows a TRANSPARENT stage with a web view in it.
     * <p>
     * Narrowed down by elimination: with one JVM per test class, every other toolkit dependent class
     * loads real pages and passes. This one segfaults {@code libjfxwebkit} even run entirely alone,
     * and it is the only class that combines {@code StageStyle.TRANSPARENT} with
     * {@code WebView.setPageFill(TRANSPARENT)} — the transparency the content overlay needs. The
     * same code is stable in a real application; it dies in a forked test JVM that creates and tears
     * down transparent stages back to back.
     * <p>
     * Ruled out along the way: the jacoco agent (crashes identically without it), the URL scheme
     * (file: and http: both fine elsewhere), headless Monocle, and xvfb on Linux.
     * <p>
     * Opt in with {@code -Dcountly.ui.pageLoadTests=true} if a future JavaFX fixes it.
     */
    static void assumeTransparentStagesAreSafe() {
        Assume.assumeTrue(
            "skipped: needs a live JavaFX toolkit. CI runs these on Linux under xvfb; enable"
                + " locally with -Dcountly.ui.pageLoadTests=true and a desktop session.",
            Boolean.getBoolean("countly.ui.pageLoadTests"));
        try {
            start();
        } catch (Throwable t) {
            Assume.assumeNoException("skipped: the JavaFX toolkit would not start", t);
        }
    }

    static synchronized void start() {
        if (started) {
            return;
        }

        CountDownLatch ready = new CountDownLatch(1);
        try {
            Platform.startup(ready::countDown);
            await(ready, "the JavaFX toolkit to start");
        } catch (IllegalStateException alreadyRunning) {
            // Another test class in this JVM got there first.
        }

        // Closing the last stage would otherwise shut the toolkit down for every later test.
        Platform.setImplicitExit(false);
        started = true;
    }

    /**
     * Runs the block on the JavaFX thread and waits for it, so a test reads top to bottom.
     *
     * @param work what to run
     */
    static void onFx(Runnable work) {
        start();

        if (Platform.isFxApplicationThread()) {
            work.run();
            return;
        }

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                work.run();
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                done.countDown();
            }
        });

        await(done, "the JavaFX thread to run the block");
        if (thrown.get() != null) {
            throw new AssertionError("the block threw on the JavaFX thread", thrown.get());
        }
    }

    /**
     * Waits for something the JavaFX thread will eventually do.
     *
     * @param what described in the failure message
     * @param condition polled until true
     */
    static void waitUntil(String what, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(25);
        }
        Assert.fail("timed out waiting for " + what);
    }

    static void await(CountDownLatch latch, String what) {
        try {
            Assert.assertTrue("timed out waiting for " + what, latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for " + what, e);
        }
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * A page served from disk. The engine's own {@code load} path is used, rather than
     * {@code loadContent}, so the tests exercise the same code a real widget URL goes through, and
     * a "data:" URL is not an option: the engine never finishes loading one.
     *
     * @param html the page body
     * @return a "file:" URL for it
     */
    static String pageUrl(String html) {
        try {
            File file = File.createTempFile("cly-ui-test", ".html");
            file.deleteOnExit();
            Files.write(file.toPath(), html.getBytes(StandardCharsets.UTF_8));
            return file.toURI().toString();
        } catch (IOException e) {
            throw new AssertionError("could not write the test page", e);
        }
    }

    /**
     * @param signalUrl where the page should navigate once it has loaded
     * @return a page that sends the SDK one signal
     */
    static String pageThatSignals(String signalUrl) {
        return "<html><head><title>test</title></head><body><p id='widget-body'>content</p>"
            + "<script>setTimeout(function(){window.location='" + signalUrl + "';},50);</script>"
            + "</body></html>";
    }
}
