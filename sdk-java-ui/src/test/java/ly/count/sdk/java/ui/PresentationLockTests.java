package ly.count.sdk.java.ui;

import org.junit.After;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * One presentation at a time: the second claimant is refused while the first holds the screen, and
 * only the holder's release frees it.
 */
@RunWith(JUnit4.class)
public class PresentationLockTests {

    @BeforeClass
    public static void toolkit() {
        FxTestToolkit.assumeToolkitAvailable();
        FxTestToolkit.start();
    }

    @After
    public void reset() {
        FxTestToolkit.onFx(PresentationLock::resetForTests);
    }

    @Test
    public void theSecondClaimantIsRefusedUntilTheFirstReleases() {
        FxTestToolkit.onFx(() -> {
            Assert.assertNull(PresentationLock.showing());
            Assert.assertTrue(PresentationLock.tryAcquire("widget nps_1"));
            Assert.assertEquals("widget nps_1", PresentationLock.showing());

            Assert.assertFalse("content must wait for the widget", PresentationLock.tryAcquire("content block_2"));
            Assert.assertFalse("and so must another widget", PresentationLock.tryAcquire("widget survey_1"));
            Assert.assertEquals("a refusal changes nothing", "widget nps_1", PresentationLock.showing());

            // A release by something that does not hold the screen is ignored.
            PresentationLock.release("content block_2");
            Assert.assertEquals("widget nps_1", PresentationLock.showing());

            PresentationLock.release("widget nps_1");
            Assert.assertNull(PresentationLock.showing());
            Assert.assertTrue("free again", PresentationLock.tryAcquire("content block_2"));
        });
    }

    @Test
    public void releaseIsSafeFromAnyThreadAndMoreThanOnce() {
        FxTestToolkit.onFx(() -> Assert.assertTrue(PresentationLock.tryAcquire("widget nps_1")));

        // Off the application thread: a close reported by the SDK's own worker, for instance.
        PresentationLock.release("widget nps_1");
        PresentationLock.release("widget nps_1");
        PresentationLock.release(null);

        FxTestToolkit.waitUntil("the release to land on the application thread",
            () -> PresentationLock.showing() == null);
    }
}
