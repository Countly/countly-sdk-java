package ly.count.sdk.java.internal;

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
}
