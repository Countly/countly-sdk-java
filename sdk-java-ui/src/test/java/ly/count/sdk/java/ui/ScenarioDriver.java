package ly.count.sdk.java.ui;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import ly.count.sdk.java.Config;
import org.junit.Assume;

/**
 * Drives the real widget and content UI against a real Countly server, the way the Android SDK's
 * {@code content_test_runner.py} drives its demo app.
 * <p>
 * The Android harness has to reach into the app from outside, through adb and accessibility nodes,
 * because the WebView belongs to another process. Here the page is in this process, so the same
 * scenarios are driven by scripting the DOM directly with the selectors that harness uses, and the
 * SDK's own log is captured in memory instead of being scraped out of logcat.
 * <p>
 * Off by default: it needs a desktop session and a reachable server. Enable with
 * {@code -Dcountly.ui.scenarios=true}, and point it somewhere else with
 * {@code -Dcountly.ui.scenarioServer} / {@code -Dcountly.ui.scenarioAppKey}.
 */
final class ScenarioDriver {

    static final String SERVER = System.getProperty("countly.ui.scenarioServer", "https://master.count.ly");
    static final String APP_KEY = System.getProperty("countly.ui.scenarioAppKey", "dte_mobile_v2");

    /**
     * Distinguishes one run's device IDs from the last one's.
     * <p>
     * A journey serves a device its content once: re-running with the same IDs gets nothing, which
     * looks exactly like a server with no campaigns on it. Every run gets fresh devices instead.
     */
    static final String RUN_ID = Long.toString(System.currentTimeMillis(), 36);

    /** Where the findings of a run are collected, one line per checked step. */
    private static final List<String> FINDINGS = Collections.synchronizedList(new ArrayList<>());

    private ScenarioDriver() {
    }

    static void assumeEnabled() {
        Assume.assumeTrue("skipped: needs a desktop session and a live server, enable with"
            + " -Dcountly.ui.scenarios=true", Boolean.getBoolean("countly.ui.scenarios"));
        FxTestToolkit.assumeToolkitAvailable();
        FxTestToolkit.start();
    }

    /**
     * @param deviceId the device ID to run as, which is how the server picks which fixtures to serve
     * @param log where to send the SDK's own log
     * @return a config against the live server
     */
    static Config liveConfig(String deviceId, LogBuffer log) {
        return liveConfig(deviceId, log, SERVER);
    }

    /**
     * @param deviceId the device ID to run as
     * @param log where to send the SDK's own log
     * @param serverUrl the server to talk to, which a stub driven scenario points at itself
     * @return a config against that server
     */
    static Config liveConfig(String deviceId, LogBuffer log, String serverUrl) {
        File storage = new File(System.getProperty("java.io.tmpdir"), "countly-scenarios");
        if (!storage.exists() && !storage.mkdirs()) {
            throw new IllegalStateException("could not create " + storage);
        }

        Config config = new Config(serverUrl, APP_KEY, storage)
            // Sessions included on purpose: a device that never began one has not entered any
            // journey, so the server has no content for it, and session().begin() is a no-op while
            // the feature is off. Leaving it out made every content variant look unconfigured.
            .enableFeatures(Config.Feature.Content, Config.Feature.Events, Config.Feature.Feedback,
                Config.Feature.Sessions)
            .setLoggingLevel(Config.LoggingLevel.DEBUG)
            .setLogListener(log)
            .setApplicationVersion("1.0.0")
            .setCustomDeviceId(deviceId);
        // A short zone timer, so a content fetch does not cost half a minute per scenario.
        config.content.setZoneTimerInterval(30);
        return config;
    }

    // ---------------------------------------------------------------- findings

    enum Verdict {
        PASS, FAIL, SKIP, WARN
    }

    static void record(String scenario, String step, Verdict verdict, String detail) {
        FINDINGS.add(verdict + "\t" + scenario + "\t" + step + "\t" + (detail == null ? "" : detail));
        System.out.println("[scenario] " + verdict + " | " + scenario + " | " + step
            + (detail == null || detail.isEmpty() ? "" : " | " + detail));
    }

    static void check(String scenario, String step, boolean ok, String detail) {
        record(scenario, step, ok ? Verdict.PASS : Verdict.FAIL, detail);
    }

    /**
     * Writes everything recorded so far as a markdown table, so a run leaves the same kind of
     * artifact the Android harness's {@code summary.md} does.
     */
    static void writeReport(String name) {
        StringBuilder out = new StringBuilder("# " + name + "\n\n");
        out.append("Server `").append(SERVER).append("`, app key `").append(APP_KEY).append("`\n\n");
        out.append("| Verdict | Scenario | Step | Detail |\n|---|---|---|---|\n");
        for (String finding : new ArrayList<>(FINDINGS)) {
            String[] parts = finding.split("\t", 4);
            out.append("| ").append(parts[0]).append(" | ").append(parts[1]).append(" | ")
                .append(parts[2]).append(" | ").append(parts[3].replace("|", "\\|")).append(" |\n");
        }

        try {
            File dir = new File(System.getProperty("countly.ui.scenarioOut",
                System.getProperty("java.io.tmpdir")));
            // A clean build has no such directory, and losing a five minute live run to that is a
            // waste of the run.
            if (!dir.exists() && !dir.mkdirs()) {
                System.out.println("[scenario] could not create " + dir + ", falling back to the temp directory");
                dir = new File(System.getProperty("java.io.tmpdir"));
            }
            Files.write(Paths.get(new File(dir, name + ".md").toURI()),
                out.toString().getBytes(StandardCharsets.UTF_8));
            System.out.println("[scenario] report written to " + new File(dir, name + ".md"));
        } catch (IOException t) {
            System.out.println("[scenario] could not write the report: " + t);
        }
    }

    // ------------------------------------------------------------------ pages

    /**
     * A page element, addressed by the same CSS selectors the Android harness uses. Every call hops
     * onto the JavaFX thread itself, so a scenario reads as a sequence of steps.
     */
    static boolean exists(WebEngine engine, String selector) {
        return count(engine, selector) > 0;
    }

    static int count(WebEngine engine, String selector) {
        Object result = script(engine, "(function(){try{return ''+document.querySelectorAll(\""
            + selector + "\").length;}catch(e){return '0';}})()");
        try {
            return Integer.parseInt(String.valueOf(result));
        } catch (NumberFormatException t) {
            return 0;
        }
    }

    /**
     * @return {@code true} when the element was there to be clicked
     */
    static boolean click(WebEngine engine, String selector) {
        Object result = script(engine, "(function(){try{"
            + "var e=document.querySelector(\"" + selector + "\");"
            + "if(!e){return 'no';}"
            // A real click, not just the handler: these widgets bind through jQuery delegation on
            // body, so dispatching on the element is what actually reaches them.
            + "e.scrollIntoView();e.click();return 'yes';}catch(err){return 'err '+err;}})()");
        return "yes".equals(String.valueOf(result));
    }

    /**
     * Types into a field the way a person would, then fires the events a framework listens for.
     */
    static boolean type(WebEngine engine, String selector, String text) {
        Object result = script(engine, "(function(){try{"
            + "var e=document.querySelector(\"" + selector + "\");if(!e){return 'no';}"
            + "e.focus();e.value=" + quote(text) + ";"
            + "e.dispatchEvent(new Event('input',{bubbles:true}));"
            + "e.dispatchEvent(new Event('change',{bubbles:true}));"
            + "e.dispatchEvent(new Event('keyup',{bubbles:true}));"
            + "return 'yes';}catch(err){return 'err '+err;}})()");
        return "yes".equals(String.valueOf(result));
    }

    /** Ticks a checkbox and tells whether it ended up ticked. */
    static boolean tick(WebEngine engine, String selector) {
        Object result = script(engine, "(function(){try{"
            + "var e=document.querySelector(\"" + selector + "\");if(!e){return 'no';}"
            + "if(e.tagName!=='INPUT'){e.click();var i=e.querySelector('input');"
            + "return i?(i.checked?'yes':'no'):'yes';}"
            + "if(!e.checked){e.click();}"
            + "return e.checked?'yes':'no';}catch(err){return 'err '+err;}})()");
        return "yes".equals(String.valueOf(result));
    }

    /**
     * Clicks the first element whose own text is one of the given hints, which is how the Android
     * harness finds a widget's Close or Go control ({@code WEBVIEW_HINTS}). A CSS selector cannot
     * express "the button that says Close", and picking the first button instead clicks whatever
     * happens to come first in the markup.
     *
     * @param engine the page to click in
     * @param hints the texts to look for, case insensitively
     * @return the text that was clicked, or {@code null} when none of them was there
     */
    static String clickByText(WebEngine engine, String... hints) {
        StringBuilder list = new StringBuilder();
        for (String hint : hints) {
            list.append(list.length() == 0 ? "" : ",").append(quote(hint));
        }

        Object result = script(engine, "(function(){try{"
            + "var hints=[" + list + "].map(function(h){return h.toLowerCase();});"
            + "var nodes=document.querySelectorAll('button,a,[role=button],[onclick],span,div');"
            + "for(var i=0;i<nodes.length;i++){"
            + "var n=nodes[i];"
            // Own text only: a wrapper contains every hint its children do.
            + "var t=(n.innerText||n.textContent||'').trim();"
            + "if(!t||t.length>24||n.children.length>0){continue;}"
            + "if(hints.indexOf(t.toLowerCase())>=0){n.scrollIntoView();n.click();return t;}}"
            + "return '';}catch(err){return 'err '+err;}})()");
        String clicked = String.valueOf(result);
        return clicked.isEmpty() || clicked.startsWith("err ") ? null : clicked;
    }

    static String visibleText(WebEngine engine) {
        Object result = script(engine, "(function(){try{return document.body?document.body.innerText:'';}"
            + "catch(e){return '';}})()");
        return String.valueOf(result);
    }

    static Object script(WebEngine engine, String js) {
        AtomicReference<Object> out = new AtomicReference<>();
        FxTestToolkit.onFx(() -> {
            try {
                out.set(engine.executeScript(js));
            } catch (Throwable t) {
                out.set("threw " + t);
            }
        });
        return out.get();
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ------------------------------------------------------------------ hosts

    /**
     * A widget card built the way {@link CountlyWebView#presentFeedbackWidget} builds one, kept open
     * so a scenario can script the page inside it.
     */
    static final class Card {

        final Stage stage;
        final WebView webView;
        final JavaFxWidgetHost host;
        FeedbackWidgetPresenter presenter;

        Card(Stage stage, WebView webView, JavaFxWidgetHost host) {
            this.stage = stage;
            this.webView = webView;
            this.host = host;
        }

        WebEngine engine() {
            return webView.getEngine();
        }
    }

    static Card newCard(WidgetSurface surface, WidgetWebHost.Listener listener) {
        AtomicReference<Card> out = new AtomicReference<>();
        FxTestToolkit.onFx(() -> {
            WebView webView = new WebView();
            Stage stage = new Stage(StageStyle.UNDECORATED);
            stage.setAlwaysOnTop(true);
            Scene scene = new Scene(webView, 1, 1);
            stage.setScene(scene);

            JavaFxWidgetHost host = new JavaFxWidgetHost(stage, webView, surface);
            host.initialize();
            if (listener != null) {
                host.setListener(listener);
            }
            out.set(new Card(stage, webView, host));
        });
        return out.get();
    }

    /**
     * @return an application window to anchor cards and content to, as a real integration would
     */
    static Stage newApplicationWindow(double x, double y, double width, double height) {
        AtomicReference<Stage> out = new AtomicReference<>();
        FxTestToolkit.onFx(() -> {
            Stage owner = new Stage(StageStyle.DECORATED);
            owner.setTitle("scenario host");
            owner.setScene(new Scene(new Pane(), width, height));
            owner.setX(x);
            owner.setY(y);
            owner.show();
            out.set(owner);
        });
        return out.get();
    }

    // -------------------------------------------------------------------- log

    /**
     * The SDK's own log, in memory. Stands in for the {@code LOG_PATTERNS} logcat matching the
     * Android harness does.
     */
    static final class LogBuffer implements ly.count.sdk.java.internal.LogCallback {

        private final List<String> lines = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void LogHappened(String logMessage, Config.LoggingLevel logLevel) {
            lines.add(logLevel + " " + logMessage);
        }

        boolean has(String regex) {
            return find(regex) != null;
        }

        /**
         * @param regex the pattern to look for
         * @return the first matching line, or {@code null}
         */
        String find(String regex) {
            Pattern pattern = Pattern.compile(regex);
            for (String line : new ArrayList<>(lines)) {
                if (pattern.matcher(line).find()) {
                    return line;
                }
            }
            return null;
        }

        int countOf(String regex) {
            Pattern pattern = Pattern.compile(regex);
            int found = 0;
            for (String line : new ArrayList<>(lines)) {
                if (pattern.matcher(line).find()) {
                    found++;
                }
            }
            return found;
        }

        boolean waitFor(String regex, long timeoutMs) {
            long until = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < until) {
                if (has(regex)) {
                    return true;
                }
                pause(100);
            }
            return false;
        }

        void clear() {
            lines.clear();
        }

        List<String> snapshot() {
            return new ArrayList<>(lines);
        }
    }

    static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
