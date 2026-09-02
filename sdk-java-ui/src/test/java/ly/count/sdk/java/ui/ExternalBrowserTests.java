package ly.count.sdk.java.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import ly.count.sdk.java.Countly;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Handing a link to the system browser. The {@code :sdk-java-ui} test task runs with
 * {@code java.awt.headless=true} (see build.gradle) specifically so {@link java.awt.Desktop} is
 * never supported here, meaning {@link ExternalBrowser#open} always falls into its "browsing not
 * supported" branch and no test in this class can ever actually launch a browser.
 */
@RunWith(JUnit4.class)
public class ExternalBrowserTests {

    @After
    public void stopSdk() {
        Countly.instance().halt();
    }


    /**
     * Which desktop action each scheme needs. A "mailto:" link opened through the browse action does
     * not reliably reach a mail client, and that failure is silent, so the routing is asserted.
     */
    @Test
    public void handlerFor_routesMailLinksToTheMailClient() {
        Assert.assertEquals(ExternalBrowser.Handler.MAIL, ExternalBrowser.handlerFor("mailto:someone@count.ly"));
        Assert.assertEquals(ExternalBrowser.Handler.MAIL, ExternalBrowser.handlerFor("MAILTO:someone@count.ly?subject=Hi"));
        Assert.assertEquals(ExternalBrowser.Handler.MAIL, ExternalBrowser.handlerFor("  mailto:someone@count.ly  "));

        for (String browsed : new String[] { "https://count.ly", "http://count.ly", "tel:+15551234",
            "sms:+15551234", "maps://?q=here", "myapp://deep/link", null }) {
            Assert.assertEquals("should browse: " + browsed,
                ExternalBrowser.Handler.BROWSE, ExternalBrowser.handlerFor(browsed));
        }
    }

    /**
     * System schemes and an application's own custom scheme are allowed; anything that could make
     * the desktop read a local file, evaluate a script or unpack an archive is not. The blocked list
     * is kept identical to the Countly Android SDK's.
     */
    @Test
    public void isAllowed_permitsSystemAndCustomSchemesAndBlocksDangerousOnes() {
        for (String allowed : new String[] { "https://count.ly", "http://count.ly",
            "mailto:support@count.ly", "tel:+15551234", "sms:+15551234", "maps://?q=here",
            "geo:0,0", "myapp://open/thing", "MYAPP://OPEN" }) {
            Assert.assertTrue("should be allowed: " + allowed, ExternalBrowser.isAllowed(allowed));
        }

        for (String blocked : new String[] { "file:///etc/passwd", "content://media/x",
            "javascript:alert(1)", "jar:file:///x!/y", "zip:/x", "intent://scan", "data:text/html,x",
            "FILE:///etc/passwd", "JavaScript:alert(1)" }) {
            Assert.assertFalse("should be blocked: " + blocked, ExternalBrowser.isAllowed(blocked));
        }

        // A web view's own placeholder locations name nothing to open. macOS answers a handover of
        // one with a "can't be opened: -50" dialog, which the user sees.
        for (String placeholder : new String[] { "about:blank", "ABOUT:BLANK", "about:", "blob:https://count.ly/x" }) {
            Assert.assertFalse("should be blocked: " + placeholder, ExternalBrowser.isAllowed(placeholder));
        }

        // No scheme is not openable, and guessing one is how a relative path becomes a file read.
        for (String noScheme : new String[] { null, "", "   ", "count.ly", "/etc/passwd", ":nohost" }) {
            Assert.assertFalse("should be blocked: " + noScheme, ExternalBrowser.isAllowed(noScheme));
        }
    }

    /**
     * The per-platform launcher used when {@link java.awt.Desktop} cannot open a link, which is the
     * usual case for a custom scheme. The URL has to stay one argument: run through a shell it would
     * be parsed, and it comes from server-issued content.
     */
    @Test
    public void launcherCommandFor_handsTheUrlToTheOsAsOneArgument() {
        String url = "myapp://open/thing?a=1&b=2";

        Assert.assertArrayEquals(new String[] { "open", url },
            ExternalBrowser.launcherCommandFor("Mac OS X", url));
        Assert.assertArrayEquals(new String[] { "rundll32", "url.dll,FileProtocolHandler", url },
            ExternalBrowser.launcherCommandFor("Windows 11", url));
        Assert.assertArrayEquals(new String[] { "xdg-open", url },
            ExternalBrowser.launcherCommandFor("Linux", url));

        Assert.assertNull(ExternalBrowser.launcherCommandFor("Plan 9", url));
        Assert.assertNull(ExternalBrowser.launcherCommandFor(null, url));
    }

    /**
     * An application that registers a URL handler owns its links: it is offered every link first,
     * gets the scheme the content actually sent, and a handler that throws must not stop the SDK
     * from carrying on.
     */
    @Test
    public void applicationUrlHandler_isOfferedEveryLinkFirst() {
        List<String> seen = new ArrayList<>();
        Countly.instance().init(UiTestConfigs.refusedServer()
            .content.setContentUrlHandler(url -> {
                seen.add(url);
                return url.startsWith("myapp://");
            }));

        // Claimed by the application: reported as opened without the SDK touching the desktop.
        Assert.assertTrue(ExternalBrowser.open("myapp://open/thing"));

        // Declined: falls through to the SDK, which is headless here so it cannot open it.
        Assert.assertFalse(ExternalBrowser.open("https://count.ly"));

        // Offered even for a scheme the SDK would refuse itself: the application decides.
        Assert.assertFalse(ExternalBrowser.open("file:///etc/passwd"));

        Assert.assertEquals(Arrays.asList("myapp://open/thing", "https://count.ly", "file:///etc/passwd"), seen);
    }

    /**
     * A handler that throws is contained: the SDK logs it and opens the link as if no handler was
     * registered.
     */
    @Test
    public void applicationUrlHandler_thatThrows_isContained() {
        Countly.instance().init(UiTestConfigs.refusedServer()
            .content.setContentUrlHandler(url -> {
                throw new IllegalStateException("this handler is broken");
            }));

        Assert.assertFalse(ExternalBrowser.open("https://count.ly"));
    }

    /**
     * A null or blank URL is rejected before anything else is attempted, and a well-formed URL runs
     * into the headless "no desktop support" branch, so both paths report "not opened" without ever
     * touching a real browser.
     */
    @Test
    public void open_guardsBlankUrlsAndReportsUnsupportedWhenHeadless() {
        for (String blank : new String[] { null, "", "   " }) {
            Assert.assertFalse("blank URL [" + blank + "] must be rejected", ExternalBrowser.open(blank));
        }

        Assert.assertFalse("a headless JVM never supports Desktop.browse",
            ExternalBrowser.open("https://count.ly"));
    }
}
