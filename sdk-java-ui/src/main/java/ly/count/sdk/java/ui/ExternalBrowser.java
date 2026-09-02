package ly.count.sdk.java.ui;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
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
        int colon = url.trim().indexOf(':');
        if (colon <= 0) {
            return null;
        }
        return url.trim().substring(0, colon).toLowerCase(Locale.ROOT);
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
}
