package ly.count.sdk.java.ui;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import ly.count.sdk.java.internal.ContentUrlHandler;
import ly.count.sdk.java.internal.SDKCore;

/**
 * Opens a link the content or a widget asked for, outside the SDK's own web views.
 * <p>
 * Supports the system schemes a content block realistically uses ({@code https}, {@code mailto},
 * {@code tel}, {@code sms}, {@code maps}) as well as an application's own custom scheme
 * ({@code myapp://...}), because a content block's whole purpose can be to deep link back into the
 * host application.
 */
class ExternalBrowser {

    /**
     * Schemes that are never opened, matching the Countly Android SDK's list exactly. A link comes
     * from server-issued content, and content should not be able to make the desktop read a local
     * file, evaluate a script, or unpack an archive. Kept identical across SDKs on purpose: a
     * scheme that is unsafe on one platform should not be quietly allowed on another.
     */
    private static final Set<String> DANGEROUS_SCHEMES = new HashSet<>(
        Arrays.asList("file", "content", "javascript", "jar", "zip", "intent", "data"));

    /**
     * Schemes that name no destination outside the web view, so there is nothing to hand over.
     * <p>
     * A web view reports {@code about:blank} as the location of a popup it is still setting up, and
     * as the page a dismissed card is navigated away to. Handing that to the desktop is not a no-op:
     * macOS puts up "The application can't be opened: -50" in the user's face.
     */
    private static final Set<String> NOT_A_DESTINATION = new HashSet<>(Arrays.asList("about", "blob"));

    /**
     * Which desktop action a link needs. A {@code mailto:} link opened through the browse action
     * does not reliably reach a mail client: the mail handler is a separate action, and browsing a
     * mailto URI is not specified to work at all.
     */
    enum Handler {
        MAIL, BROWSE
    }

    private ExternalBrowser() {
    }

    /**
     * @param url the link to inspect
     * @return its lower-cased scheme, or {@code null} when it has none
     */
    static String schemeOf(String url) {
        if (url == null) {
            return null;
        }
        String trimmed = url.trim();
        int colon = trimmed.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        return trimmed.substring(0, colon).toLowerCase(Locale.ROOT);
    }

    /**
     * @param url the link to check
     * @return {@code true} when the SDK is willing to hand this link to the desktop
     */
    static boolean isAllowed(String url) {
        String scheme = schemeOf(url);
        // A link with no scheme is not something the desktop can open, and guessing one for it
        // would be how a relative path becomes a local file read.
        return scheme != null && !DANGEROUS_SCHEMES.contains(scheme) && !NOT_A_DESTINATION.contains(scheme);
    }

    /**
     * @param url the link to open
     * @return the desktop action that can open it
     */
    static Handler handlerFor(String url) {
        return "mailto".equals(schemeOf(url)) ? Handler.MAIL : Handler.BROWSE;
    }

    /**
     * The platform command that opens a URL when {@link Desktop} cannot.
     * <p>
     * {@code Desktop} is not guaranteed for anything but {@code http(s)}, and on Linux it is often
     * reported unsupported entirely, so a custom scheme deep link would silently do nothing. These
     * launchers hand the URL to the OS handler, which is what registered the scheme in the first
     * place. Returned as an argument array and run without a shell, so nothing in the URL can be
     * interpreted as a command.
     *
     * @param osName value of the {@code os.name} property
     * @param url the link to open
     * @return the command, or {@code null} for an unrecognised platform
     */
    static String[] launcherCommandFor(String osName, String url) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return new String[] { "open", url };
        }
        if (os.contains("win")) {
            // Not "cmd /c start": that goes through the shell, which parses the URL.
            return new String[] { "rundll32", "url.dll,FileProtocolHandler", url };
        }
        if (os.contains("nix") || os.contains("nux") || os.contains("aix")) {
            return new String[] { "xdg-open", url };
        }
        return null;
    }

    /**
     * Best effort: a headless JVM has no desktop to open anything on, and a link that cannot be
     * opened must never take the host application down.
     *
     * @param url the link to open
     * @return {@code true} when the link was handed to the desktop
     */
    static boolean open(String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }

        String trimmed = url.trim();

        // The application gets first refusal, before any of the SDK's own policy. An app that
        // registered a handler owns its deep links entirely, including deciding what is safe to act
        // on; the scheme rules below govern only what the SDK itself is willing to open.
        ContentUrlHandler handler = SDKCore.contentUrlHandler();
        if (handler != null) {
            try {
                if (handler.onContentUrl(trimmed)) {
                    UiLog.d("[ExternalBrowser] open, the application handled " + trimmed);
                    return true;
                }
            } catch (Throwable t) {
                UiLog.e("[ExternalBrowser] open, the application's URL handler threw, opening it"
                    + " normally instead, [" + t + "]");
            }
        }

        if (!isAllowed(trimmed)) {
            UiLog.w("[ExternalBrowser] open, refusing to open a link with scheme ["
                + schemeOf(trimmed) + "]: " + trimmed);
            return false;
        }

        // Checked before anything else: in a headless JVM the launcher fallback below would still
        // spawn a process, which is not something a headless application asked for.
        if (GraphicsEnvironment.isHeadless()) {
            UiLog.d("[ExternalBrowser] open, headless, cannot open " + trimmed);
            return false;
        }

        // Linux never goes through java.awt.Desktop. Where it is supported at all, its browse is
        // gnome_url_show underneath, which does not return when called on the JavaFX Application
        // Thread (the GTK main loop) and holds the AWT lock while it waits: the click froze the
        // application, no browser appeared and nothing was logged. The launchers below return in
        // milliseconds from that same thread, so they are the only path there.
        if (isLinux(System.getProperty("os.name"))) {
            return openOnLinux(trimmed);
        }

        if (openWithDesktop(trimmed)) {
            return true;
        }
        return openWithPlatformLauncher(trimmed);
    }

    private static boolean openWithDesktop(String url) {
        try {
            if (!Desktop.isDesktopSupported()) {
                return false;
            }
            Desktop desktop = Desktop.getDesktop();
            URI uri = new URI(url);

            if (handlerFor(url) == Handler.MAIL && desktop.isSupported(Desktop.Action.MAIL)) {
                desktop.mail(uri);
                return true;
            }
            if (desktop.isSupported(Desktop.Action.BROWSE)) {
                desktop.browse(uri);
                return true;
            }
            return false;
        } catch (Throwable t) {
            // A custom scheme is the common case here: Desktop refuses it, the launcher takes it.
            UiLog.d("[ExternalBrowser] openWithDesktop, could not open [" + url + "], [" + t + "]");
            return false;
        }
    }

    private static boolean openWithPlatformLauncher(String url) {
        String[] command = launcherCommandFor(System.getProperty("os.name"), url);
        if (command == null) {
            UiLog.w("[ExternalBrowser] open, no way to open links on this platform: " + url);
            return false;
        }

        try {
            new ProcessBuilder(command).start();
            return true;
        } catch (Throwable t) {
            UiLog.w("[ExternalBrowser] open, the platform launcher could not open [" + url + "], [" + t + "]");
            return false;
        }
    }

    /**
     * How long a launcher gets to fail before it is taken to have succeeded. {@code xdg-open} and
     * its relatives exit non-zero within a few tens of milliseconds when they have nothing to hand
     * the URL to; a launcher that is still running after this has handed it over.
     */
    private static final long LAUNCHER_VERDICT_MS = 400;

    /**
     * Linux has no single way to open a URL. {@code java.awt.Desktop} is out (see {@link #open}: it
     * hangs the JavaFX thread where it is supported, and is missing where it is not), and
     * {@code xdg-open} is missing on minimal installs, has no default browser on a fresh desktop,
     * and cannot reach a browser installed as a snap from some sandboxes. Starting it and calling
     * that success, as this used to, logged an open that never happened. So the candidates are
     * tried in turn, each given a moment to fail, and every failure is logged so the log says why a
     * link did not open rather than that it did. {@code xdg-open} and {@code gio} take
     * {@code mailto:} links as well, so mail needs no separate path here.
     *
     * @param url the link
     * @return whether some launcher took it
     */
    private static boolean openOnLinux(String url) {
        for (String[] command : linuxLaunchers(url, System.getenv("BROWSER"), defaultBrowserDesktopId())) {
            try {
                Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
                if (!process.waitFor(LAUNCHER_VERDICT_MS, TimeUnit.MILLISECONDS)) {
                    UiLog.d("[ExternalBrowser] openOnLinux, [" + command[0] + "] took the link");
                    return true;
                }
                if (process.exitValue() == 0) {
                    UiLog.d("[ExternalBrowser] openOnLinux, [" + command[0] + "] opened the link");
                    return true;
                }
                UiLog.w("[ExternalBrowser] openOnLinux, [" + command[0] + "] exited with ["
                    + process.exitValue() + "], trying the next launcher");
            } catch (Throwable t) {
                // Typically "No such file or directory": this launcher is not installed.
                UiLog.w("[ExternalBrowser] openOnLinux, [" + command[0] + "] is not available, [" + t + "]");
            }
        }
        UiLog.w("[ExternalBrowser] openOnLinux, no launcher could open [" + url + "]. Set the BROWSER"
            + " environment variable, or give the SDK a ContentUrlHandler to open links itself.");
        return false;
    }

    static boolean isLinux(String osName) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        return os.contains("nix") || os.contains("nux") || os.contains("aix");
    }

    /**
     * What a desktop file id looks like, as {@code xdg-settings} prints it: {@code firefox.desktop},
     * {@code org.mozilla.firefox.desktop}. Anything else it prints is an error message, not an id.
     */
    private static final Pattern DESKTOP_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]*\\.desktop");

    /**
     * The desktop file id of the default browser, asked of {@code xdg-settings}.
     *
     * @return the id, or {@code null} when there is no {@code xdg-settings}, no default, or the
     *     answer took too long
     */
    private static String defaultBrowserDesktopId() {
        try {
            Process process = new ProcessBuilder("xdg-settings", "get", "default-web-browser").start();
            if (!process.waitFor(LAUNCHER_VERDICT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            return desktopIdOf(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } catch (Throwable t) {
            // No xdg-settings here; the chain has plenty left.
            return null;
        }
    }

    /**
     * @param output what {@code xdg-settings get default-web-browser} printed
     * @return the desktop file id in it, or {@code null} when it printed something else
     */
    static String desktopIdOf(String output) {
        if (output == null) {
            return null;
        }
        String firstLine = output.trim().split("\\R", 2)[0].trim();
        return DESKTOP_ID.matcher(firstLine).matches() ? firstLine : null;
    }

    /**
     * The launchers to try on Linux, most specific first: the user's own {@code BROWSER}, then the
     * desktop's openers, then the browsers themselves.
     * <p>
     * The default browser is launched through {@code gtk-launch} before {@code xdg-open} gets a turn.
     * Both open the link, but on Wayland a browser that is already running only comes to the front
     * for a launcher that hands it an activation token, which {@code gtk-launch} does and
     * {@code xdg-open} does not: through {@code xdg-open} the page opened in a background tab and,
     * from inside the application, nothing visibly happened.
     *
     * @param url the link
     * @param browserEnv the {@code BROWSER} environment variable, may be {@code null}; a {@code %s}
     *     in it is replaced by the URL, as the convention has it
     * @param defaultBrowserDesktopId the default browser's desktop file id, may be {@code null}
     * @return commands, each ready for a ProcessBuilder
     */
    static List<String[]> linuxLaunchers(String url, String browserEnv, String defaultBrowserDesktopId) {
        List<String[]> launchers = new ArrayList<>();
        if (browserEnv != null && !browserEnv.trim().isEmpty()) {
            // BROWSER may hold several, colon separated, each possibly with a %s placeholder.
            for (String candidate : browserEnv.split(":")) {
                String trimmed = candidate.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                launchers.add(trimmed.contains("%s")
                    ? new String[] { "/bin/sh", "-c", trimmed.replace("%s", "\"$0\""), url }
                    : new String[] { trimmed, url });
            }
        }
        if (defaultBrowserDesktopId != null) {
            launchers.add(new String[] { "gtk-launch", defaultBrowserDesktopId, url });
        }
        launchers.add(new String[] { "xdg-open", url });
        launchers.add(new String[] { "gio", "open", url });
        launchers.add(new String[] { "sensible-browser", url });
        launchers.add(new String[] { "x-www-browser", url });
        launchers.add(new String[] { "firefox", url });
        launchers.add(new String[] { "google-chrome", url });
        launchers.add(new String[] { "chromium", url });
        launchers.add(new String[] { "chromium-browser", url });
        return launchers;
    }
}
