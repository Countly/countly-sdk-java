package ly.count.sdk.java.internal;

import java.math.BigDecimal;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ModuleViewsTests {

    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
    }

    @After
    public void afterTest() {
        Countly.instance().halt();
    }

    // Non-existing views

    /**
     * "stopViewWithName" with non-existing view
     * Validating that "stopViewWithName" with non-existing view doesn't generate any event
     * No event should be generated
     */
    @Test
    public void stopViewWithName() {
        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.Views, Config.Feature.Events));
        TestUtils.validateEQSize(0);
        Countly.instance().views().stopViewWithName(TestUtils.keysValues[0]);
        TestUtils.validateEQSize(0);
    }

    /**
     * "stopViewWithID" with non-existing view
     * Validating that "stopViewWithID" with non-existing view doesn't generate any event
     * No event should be generated
     */
    @Test
    public void stopViewWithID() {
        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.Views, Config.Feature.Events));
        TestUtils.validateEQSize(0);
        Countly.instance().views().stopViewWithID(TestUtils.keysValues[0]);
        TestUtils.validateEQSize(0);
    }

    /**
     * "pauseViewWithID" with non-existing view
     * Validating that "pauseViewWithID" with non-existing view doesn't generate any event
     * No event should be generated
     */
    @Test
    public void pauseViewWithID() {
        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.Views, Config.Feature.Events));
        TestUtils.validateEQSize(0);
        Countly.instance().views().pauseViewWithID(TestUtils.keysValues[0]);
        TestUtils.validateEQSize(0);
    }

    /**
     * "resumeViewWithID" with non-existing view
     * Validating that "resumeViewWithID" with non-existing view doesn't generate any event
     * No event should be generated
     */
    @Test
    public void resumeViewWithID() {
        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.Views, Config.Feature.Events));
        TestUtils.validateEQSize(0);
        Countly.instance().views().resumeViewWithID(TestUtils.keysValues[0]);
        TestUtils.validateEQSize(0);
    }

    // Providing bad values

    /**
     * "startView" with null and empty names
     * Both "startView" calls should be ignored and no event should be generated
     * No event should be existed in the EQ
     */
    @Test
    public void startView_nullAndEmpty() {
        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.Views, Config.Feature.Events));
        badValueTrier(Countly.instance().views()::startView, Countly.instance().views()::startView);
    }

    /**
     * "startAutoStoppedView" with null and empty names
     * Both "startAutoStoppedView" calls should be ignored and no event should be generated
     * No event should be existed in the EQ
     */
    @Test
    public void startAutoStoppedView_nullAndEmpty() {
        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.Views, Config.Feature.Events));
        badValueTrier(Countly.instance().views()::startAutoStoppedView, Countly.instance().views()::startAutoStoppedView);
    }

    /**
     * "stopViewWithName" with null and empty names
     * Both "stopViewWithName" calls should be ignored and no event should be generated
     * No event should be existed in the EQ
     */
    @Test
    public void stopViewWithName_nullAndEmpty() {
        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.Views, Config.Feature.Events));
        badValueTrier(Countly.instance().views()::stopViewWithName, Countly.instance().views()::stopViewWithName);
    }

    /**
     * "stopViewWithID" with null and empty names
     * Both "stopViewWithName" calls should be ignored and no event should be generated
     * No event should be existed in the EQ
     */
    @Test
    public void stopViewWithID_nullAndEmpty() {
        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.Views, Config.Feature.Events));
        badValueTrier(Countly.instance().views()::stopViewWithID, Countly.instance().views()::stopViewWithID);
    }

    /**
     * "pauseViewWithID" with null and empty names
     * Both "pauseViewWithID" calls should be ignored and no event should be generated
     * No event should be existed in the EQ
     */
    @Test
    public void pauseViewWithID_nullAndEmpty() {
        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.Views, Config.Feature.Events));
        badValueTrier(Countly.instance().views()::pauseViewWithID, null);
    }

    /**
     * "resumeViewWithID" with null and empty names
     * Both "resumeViewWithID" calls should be ignored and no event should be generated
     * No event should be existed in the EQ
     */
    @Test
    public void resumeViewWithID_nullAndEmpty() {
        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.Views, Config.Feature.Events));
        badValueTrier(Countly.instance().views()::resumeViewWithID, null);
    }

    /**
     * "addSegmentationToViewWithID" with null and empty names
     * Both "addSegmentationToViewWithID" calls should be ignored and no event should be generated
     * No event should be existed in the EQ
     */
    @Test
    public void addSegmentationToViewWithID_nullAndEmpty() {
        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.Views, Config.Feature.Events));
        badValueTrier(null, Countly.instance().views()::addSegmentationToViewWithID);
    }

    /**
     * "addSegmentationToViewWithName" with null and empty names
     * Both "addSegmentationToViewWithName" calls should be ignored and no event should be generated
     * No event should be existed in the EQ
     */
    @Test
    public void addSegmentationToViewWithName_nullAndEmpty() {
        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.Views, Config.Feature.Events));
        badValueTrier(null, Countly.instance().views()::addSegmentationToViewWithName);
    }

    private void badValueTrier(Consumer<String> idNameViewFunction, BiConsumer<String, Map<String, Object>> idNameSegmentViewFunction) {
        TestUtils.validateEQSize(0);
        if (idNameViewFunction != null) {
            idNameViewFunction.accept(null);
            TestUtils.validateEQSize(0);
            idNameViewFunction.accept("");
            TestUtils.validateEQSize(0);
        }
        if (idNameSegmentViewFunction != null) {
            idNameSegmentViewFunction.accept(null, null);
            TestUtils.validateEQSize(0);
            idNameSegmentViewFunction.accept("", null);
            TestUtils.validateEQSize(0);
        }
    }

    // A simple flow

    /**
     * Make sure all the basic functions are working correctly and we are keeping time correctly
     * 1- start view A
     * 2- start view B
     * 3- wait a moment
     * 4- pause view A
     * 5- wait a moment
     * 6- resume view A
     * 7- stop view with stopViewWithName/stopViewWithID/stopAllViews
     * 8- Stop view B if needed
     * 9- make sure the summary time is correct and two events are recorded
     */
    @Test
    public void simpleFlow() throws InterruptedException {
        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.Views, Config.Feature.Events));
        TestUtils.validateEQSize(0);

        Map<String, Object> customSegmentationA = TestUtils.map("count", 56, "start", "1", "visit", "1", "name", TestUtils.keysValues[0], "segment", TestUtils.keysValues[1]);
        String viewA = Countly.instance().views().startView("A", customSegmentationA);
        TestUtils.validateEQSize(1);
        Map<String, Object> customSegmentationB = TestUtils.map("gone", true, "lev", 78.91, "map", TestUtils.map("a", 1, "b", 2));
        Countly.instance().views().startView("B", customSegmentationB);
        Thread.sleep(1000);
        Countly.instance().views().pauseViewWithID(viewA);
        Thread.sleep(1000);
        Countly.instance().views().resumeViewWithID(viewA);
        Countly.instance().views().stopAllViews(null);
        TestUtils.validateEQSize(5);

        validateView("A", 0.0, 0, 5, true, true, TestUtils.map("count", 56));
        validateView("B", 0.0, 1, 5, false, true, TestUtils.map("gone", true, "lev", BigDecimal.valueOf(78.91)));
        validateView("A", 1.0, 2, 5, false, false, null);
        validateView("A", 0.0, 3, 5, false, false, null);
        validateView("B", 2.0, 4, 5, false, false, null);
    }

    private void validateView(String viewName, Double viewDuration, int idx, int size, boolean start, boolean visit, Map<String, Object> customSegmentation) {
        Map<String, Object> viewSegmentation = TestUtils.map("name", viewName, "segment", TestUtils.getOS());
        if (customSegmentation != null) {
            viewSegmentation.putAll(customSegmentation);
        }

        if (start) {
            viewSegmentation.put("start", "1");
        }
        if (visit) {
            viewSegmentation.put("visit", "1");
        }

        TestUtils.validateEventInEQ(ModuleViews.VIEW_EVENT_KEY, viewSegmentation, 1, 0.0, viewDuration, idx, size);
    }
}
