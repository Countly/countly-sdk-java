package ly.count.sdk.java.ui;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * The second run: a list left behind by a previous process has to be found at initialization, which
 * is the whole point of keeping it on disk.
 */
@RunWith(JUnit4.class)
public class PrefetchAcrossRunsTests {

    private static final String LIST =
        "https://master.count.ly/content/dist/assets/inter-v7-vietnamese_latin-ext_latin-regular.26.01.5.woff\n"
            + "https://master.count.ly/content/dist/assets/Inter-SemiBold.26.01.5.woff";

    @After
    public void stopSdk() {
        Countly.instance().halt();
    }

    @Test
    public void aListLeftByThePreviousRunIsFound() throws Exception {
        File storage = new File(System.getProperty("java.io.tmpdir"), "countly-across-runs");
        if (!storage.exists()) {
            Assert.assertTrue(storage.mkdirs());
        }
        File list = new File(storage, "countly_display_fonts.txt");
        Files.write(list.toPath(), LIST.getBytes(StandardCharsets.UTF_8));

        Countly.instance().init(new Config("http://localhost:1", "UI_TEST_APP_KEY", storage)
            .enableFeatures(Config.Feature.Content)
            .setLoggingLevel(Config.LoggingLevel.DEBUG)
            .setCustomDeviceId("across-runs"));

        Assert.assertEquals("the previous run's list must be readable at startup",
            2, WebFontPrefetch.rememberedCount());
        Assert.assertNotNull(WebFontPrefetch.warmupPage());
        Assert.assertTrue(list.delete());
    }
}
