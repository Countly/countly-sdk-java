package ly.count.sdk.java.ui;

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
