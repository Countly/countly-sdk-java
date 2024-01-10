package ly.count.sdk.java.internal;

import ly.count.sdk.java.Countly;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class sc_MV_ManuelViewTests {
    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
    }

    @After
    public void afterTest() {
        Countly.instance().halt();
    }

    //(1XX) Value sanitation, wrong usage, simple tests

    /**
     * recordView(x2), startAutoStoppedView(x2), startView(x2), pauseViewWithID, resumeViewWithID, stopViewWithName(x2),
     * stopViewWithID(x2), addSegmentationToViewWithID, addSegmentationToViewWithName
     * <p>
     * called with "null" values. versions with and without segmentation. nothing should crash, no events should be recorded
     */
    @Test
    public void MV_100_badValues_null() {
        Countly.instance().init(TestUtils.getConfigViews());
        TestUtils.validateEQSize(0);
        Assert.assertEquals(0, TestUtils.getCurrentRQ().length);

        Countly.instance().view(null);
        Countly.instance().view(null, false);
        Countly.instance().views().startAutoStoppedView(null);
        Countly.instance().views().startAutoStoppedView(null, TestUtils.map());
        Countly.instance().views().startView(null);
        Countly.instance().views().startView(null, TestUtils.map());
        Countly.instance().views().pauseViewWithID(null);
        Countly.instance().views().resumeViewWithID(null);
        Countly.instance().views().stopViewWithName(null);
        Countly.instance().views().stopViewWithName(null, TestUtils.map());
        Countly.instance().views().stopViewWithID(null);
        Countly.instance().views().stopViewWithID(null, TestUtils.map());
        Countly.instance().views().addSegmentationToViewWithID(null, TestUtils.map());

        Countly.instance().views().addSegmentationToViewWithName(null, TestUtils.map());
        TestUtils.validateEQSize(0);
        Assert.assertEquals(0, TestUtils.getCurrentRQ().length);
    }

    /**
     * recordView(x2), startAutoStoppedView(x2), startView(x2), pauseViewWithID, resumeViewWithID, stopViewWithName(x2),
     * stopViewWithID(x2), addSegmentationToViewWithID, addSegmentationToViewWithName
     * <p>
     * called with empty string values
     * <p>
     * versions with and without segmentation
     * <p>
     * nothing should crash, no events should be recorded
     */
    @Test
    public void MV_101_badValues_emptyString() {
        Countly.instance().init(TestUtils.getConfigViews());
        TestUtils.validateEQSize(0);
        Assert.assertEquals(0, TestUtils.getCurrentRQ().length);

        Countly.instance().view("");
        Countly.instance().view("", false);
        Countly.instance().views().startAutoStoppedView("");
        Countly.instance().views().startAutoStoppedView("", TestUtils.map());
        Countly.instance().views().startView("");
        Countly.instance().views().startView("", TestUtils.map());
        Countly.instance().views().pauseViewWithID("");
        Countly.instance().views().resumeViewWithID("");
        Countly.instance().views().stopViewWithName("");
        Countly.instance().views().stopViewWithName("", TestUtils.map());
        Countly.instance().views().stopViewWithID("");
        Countly.instance().views().stopViewWithID("", TestUtils.map());
        Countly.instance().views().addSegmentationToViewWithID("", TestUtils.map());
        Countly.instance().views().addSegmentationToViewWithName("", TestUtils.map());

        TestUtils.validateEQSize(0);
        Assert.assertEquals(0, TestUtils.getCurrentRQ().length);
    }

    /**
     * pauseViewWithID, resumeViewWithID, stopViewWithName(x2),
     * stopViewWithID(x2), addSegmentationToViewWithID, addSegmentationToViewWithName
     * <p>
     * called with empty string values
     * <p>
     * versions with and without segmentation
     * <p>
     * nothing should crash, no events should be recorded
     */
    @Test
    public void MV_102_badValues_nonExistingViews() {
        Countly.instance().init(TestUtils.getConfigViews());
        TestUtils.validateEQSize(0);
        Assert.assertEquals(0, TestUtils.getCurrentRQ().length);

        Countly.instance().views().pauseViewWithID("idv1");
        Countly.instance().views().resumeViewWithID(TestUtils.keysValues[1]);
        Countly.instance().views().stopViewWithName(TestUtils.keysValues[2]);
        Countly.instance().views().stopViewWithName(TestUtils.keysValues[3], TestUtils.map());
        Countly.instance().views().stopViewWithID(TestUtils.keysValues[4]);
        Countly.instance().views().stopViewWithID(TestUtils.keysValues[5], TestUtils.map());
        Countly.instance().views().addSegmentationToViewWithID("idv1", TestUtils.map());
        Countly.instance().views().addSegmentationToViewWithName(TestUtils.keysValues[1], TestUtils.map());

        TestUtils.validateEQSize(0);
        Assert.assertEquals(0, TestUtils.getCurrentRQ().length);
    }

    //(2XX) Usage flows

    /**
     * <pre>
     * Make sure auto closing views behave correctly
     *
     * - recordView view A (sE_A id=idv1 pvid="" segm={visit="1" start="1"})
     *   wait 1 sec
     * - recordView view B (eE_A d=1 id=idv1 pvid="", segm={}) (sE_B id=idv2 pvid=idv1 segm={visit="1"})
     *   wait 1 sec
     * - start view C (eE_B d=1 id=idv2 pvid=idv1, segm={}) (sE_C id=idv3 pvid=idv2 segm={visit="1"})
     *   wait 1 sec
     * - startAutoStoppedView D (sE_D id=idv4 pvid=idv3 segm={visit="1"})
     *   wait 1 sec
     * - startAutoStoppedView E (eE_D d=1 id=idv4 pvid=idv3, segm={}) (sE_E id=idv5 pvid=idv4 segm={visit="1"})
     *   wait 1 sec
     * - start view F (eE_E d=1 id=idv5 pvid=idv4, segm={}) (sE_F id=idv6 pvid=idv5 segm={visit="1"})
     *   wait 1 sec
     * - recordView view G (sE_G id=idv7 pvid=idv6 segm={visit="1"})
     *   wait 1 sec
     * - startAutoStoppedView H (sE_H id=idv8 pvid=idv7 segm={visit="1"})
     *   wait 1 sec
     * - recordView view I (eE_H d=1 id=idv8 pvid=idv7, segm={}) (sE_I id=idv8 pvid=idv8 segm={visit="1"})
     * </pre>
     */
    @Test
    public void MV_200A_autostartView_autoClose() throws InterruptedException {
        Countly.instance().init(TestUtils.getConfigViews().setEventQueueSizeToSend(20));
        TestUtils.validateEQSize(0);

        Countly.instance().view("A");
        Thread.sleep(1000);
        ModuleViewsTests.validateView("A", 0.0, 0, 1, true, true, null, "idv1", "");

        Countly.instance().view("B");
        Thread.sleep(1000);
        ModuleViewsTests.validateView("A", 1.0, 1, 3, false, false, null, "idv1", "");
        ModuleViewsTests.validateView("B", 0.0, 2, 3, false, true, null, "idv2", "idv1");

        Countly.instance().views().startView("C", TestUtils.map("a", 1));
        Thread.sleep(1000);
        ModuleViewsTests.validateView("B", 1.0, 3, 5, false, false, null, "idv2", "idv1");
        ModuleViewsTests.validateView("C", 0.0, 4, 5, false, true, TestUtils.map("a", 1), "idv3", "idv2");

        Countly.instance().views().startAutoStoppedView("D");
        Thread.sleep(1000);
        ModuleViewsTests.validateView("D", 0.0, 5, 6, false, true, null, "idv4", "idv3");

        Countly.instance().views().startAutoStoppedView("E");
        Thread.sleep(1000);
        ModuleViewsTests.validateView("D", 1.0, 6, 8, false, false, null, "idv4", "idv3");
        ModuleViewsTests.validateView("E", 0.0, 7, 8, false, true, null, "idv5", "idv4");

        Countly.instance().views().startView("F");
        Thread.sleep(1000);
        ModuleViewsTests.validateView("E", 1.0, 8, 10, false, false, null, "idv5", "idv4");
        ModuleViewsTests.validateView("F", 0.0, 9, 10, false, true, null, "idv6", "idv5");

        Countly.instance().view("G");
        Thread.sleep(1000);
        ModuleViewsTests.validateView("G", 0.0, 10, 11, false, true, null, "idv7", "idv6");

        Countly.instance().views().startAutoStoppedView("H");
        Thread.sleep(1000);
        ModuleViewsTests.validateView("G", 1.0, 11, 13, false, false, null, "idv7", "idv6");
        ModuleViewsTests.validateView("H", 0.0, 12, 13, false, true, null, "idv8", "idv7");

        Countly.instance().view("I");
        ModuleViewsTests.validateView("H", 1.0, 13, 15, false, false, null, "idv8", "idv7");
        ModuleViewsTests.validateView("I", 0.0, 14, 15, false, true, null, "idv9", "idv8");

        Countly.instance().views().stopAllViews(null);
        ModuleViewsTests.validateView("C", 6.0, 15, 18, false, false, null, "idv3", "idv8");
        ModuleViewsTests.validateView("F", 3.0, 16, 18, false, false, null, "idv6", "idv8");
        ModuleViewsTests.validateView("I", 0.0, 17, 18, false, false, null, "idv9", "idv8");
    }

    /**
     * Make sure all the basic functions are working correctly and we are keeping time correctly
     * <p>
     * start view A (sE_A)
     * start view B (sE_B)
     * wait 1 sec
     * pause view A (eE_A_1)
     * wait 1 sec
     * resume view A
     * stop view with stopViewWithName/stopViewWithID/stopAllViews (eE_1, eE_2)
     * Stop view B if needed
     */
    @Test
    public void MV_201_simpleFlowMultipleViews() throws InterruptedException {
        Countly.instance().init(TestUtils.getConfigViews());
        TestUtils.validateEQSize(0);

        Countly.instance().views().startView("A", TestUtils.map("a", 1));
        TestUtils.validateEQSize(1);

        Countly.instance().views().startView("B", TestUtils.map("b", 2));
        TestUtils.validateEQSize(2);

        Thread.sleep(1000);

        Countly.instance().views().pauseViewWithID("idv1");

        Thread.sleep(1000);

        Countly.instance().views().resumeViewWithID("idv1");
        TestUtils.validateEQSize(3);
        Countly.instance().views().stopAllViews(null);
        TestUtils.validateEQSize(5);

        ModuleViewsTests.validateView("A", 0.0, 0, 5, true, true, TestUtils.map("a", 1), "idv1", "");
        ModuleViewsTests.validateView("B", 0.0, 1, 5, false, true, TestUtils.map("b", 2), "idv2", "idv1");
        ModuleViewsTests.validateView("A", 1.0, 2, 5, false, false, null, "idv1", "idv1");
        ModuleViewsTests.validateView("A", 0.0, 3, 5, false, false, null, "idv1", "idv1");
        ModuleViewsTests.validateView("B", 2.0, 4, 5, false, false, null, "idv2", "idv1");
    }
}
