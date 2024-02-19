package ly.count.sdk.java.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
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
    public void stopViewWithName_nonExisting() {
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
    public void stopViewWithID_nonExisting() {
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
    public void pauseViewWithID_nonExisting() {
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
    public void resumeViewWithID_nonExisting() {
        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.Views, Config.Feature.Events));
        TestUtils.validateEQSize(0);
        Countly.instance().views().resumeViewWithID(TestUtils.keysValues[0]);
        TestUtils.validateEQSize(0);
    }

    /**
     * "addSegmentationToView" with non-existing view
     * Validating that "addSegmentationToView" with non-existing view doesn't generate any event
     * No event should be generated
     */
    @Test
    public void addSegmentationToView_nonExisting() {
        Countly.instance().init(TestUtils.getBaseConfig().enableFeatures(Config.Feature.Views, Config.Feature.Events));
        TestUtils.validateEQSize(0);
        Countly.instance().views().addSegmentationToViewWithName(TestUtils.keysValues[0], TestUtils.map("a", 1, "b", 2));
        TestUtils.validateEQSize(0);
        Countly.instance().views().addSegmentationToViewWithID(TestUtils.keysValues[0], TestUtils.map("a", 1, "b", 2));
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
     * <pre>
     * Make sure all the basic functions are working correctly and we are keeping time correctly
     *
     * 1- start view A
     * 2- start view B
     * 3- wait a moment
     * 4- pause view A
     * 5- wait a moment
     * 6- resume view A
     * 7- stop view with stopViewWithName/stopViewWithID/stopAllViews
     * 8- Stop view B if needed
     * 9- make sure the summary time is correct and two events are recorded
     * </pre>
     */
    @Test
    public void simpleFlow() throws InterruptedException {
        Countly.instance().init(TestUtils.getConfigViews());
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

        validateView("A", 0.0, 0, 5, true, true, TestUtils.map("count", 56), "idv1", "");
        validateView("B", 0.0, 1, 5, false, true, TestUtils.map("gone", true, "lev", BigDecimal.valueOf(78.91)), "idv2", "idv1");
        validateView("A", 1.0, 2, 5, false, false, null, "idv1", "idv1");
        validateView("A", 0.0, 3, 5, false, false, null, "idv1", "idv1");
        validateView("B", 2.0, 4, 5, false, false, null, "idv2", "idv1");
    }

    /**
     * <pre>
     * Validate the interaction of "startView" and "startAutoStoppedView". "startAutoStoppedView" should be automatically stopped when calling "startView", but not the other way around
     *
     * startView A
     * startAutoStoppedView
     * startView B
     * make sure that at this point there are 4 events, 3 starting and 1 closing.
     *
     * stopViewWithName A
     * stopViewWithID B
     * </pre>
     */
    @Test
    public void mixedTestFlow1() throws InterruptedException {
        Countly.instance().init(TestUtils.getConfigViews());
        TestUtils.validateEQSize(0);

        Map<String, Object> customSegmentationA = TestUtils.map("money", 238_746_798_234_739L, "start", "1", "visit", "1", "name", TestUtils.keysValues[0], "segment", TestUtils.keysValues[1]);
        Map<String, Object> customSegmentationB = TestUtils.map("gone_to", "Wall Sina", "map", TestUtils.map("titan", true, "level", 65));

        Countly.instance().views().startView("A", customSegmentationA);
        Countly.instance().views().startAutoStoppedView("AutoStopped", customSegmentationB);
        Thread.sleep(1000);
        String viewB = Countly.instance().views().startView("B");

        TestUtils.validateEQSize(4);

        validateView("A", 0.0, 0, 4, true, true, TestUtils.map("money", 238_746_798_234_739L), "idv1", ""); // starting
        validateView("AutoStopped", 0.0, 1, 4, false, true, TestUtils.map("gone_to", "Wall Sina"), "idv2", "idv1"); // starting
        validateView("AutoStopped", 1.0, 2, 4, false, false, null, "idv2", "idv1"); // closing
        validateView("B", 0.0, 3, 4, false, true, null, "idv3", "idv2"); // starting

        Countly.instance().views().stopViewWithName("A");
        Countly.instance().views().stopViewWithID(viewB);
        TestUtils.validateEQSize(6);

        validateView("A", 1.0, 4, 6, false, false, null, "idv1", "idv2"); // closing
        validateView("B", 0.0, 5, 6, false, false, null, "idv3", "idv2"); // closing
    }

    /**
     * <pre>
     * Make sure we can use view actions with the auto stopped ones
     *
     * startAutoStoppedView A
     * wait a moment
     * pause view
     * wait a moment
     * resume view
     * stop view with stopViewWithName/stopViewWithID
     * </pre>
     */
    @Test
    public void useWithAutoStoppedOnes() throws InterruptedException {
        Countly.instance().init(TestUtils.getConfigViews());
        TestUtils.validateEQSize(0);

        Map<String, Object> customSegmentationA = TestUtils.map("power_percent", 56.7f, "start", "1", "visit", "1", "name", TestUtils.keysValues[0], "segment", TestUtils.keysValues[1]);

        String viewA = Countly.instance().views().startAutoStoppedView("A", customSegmentationA);
        Thread.sleep(1000);
        Countly.instance().views().pauseViewWithID(viewA);
        Thread.sleep(1000);
        Countly.instance().views().resumeViewWithID(viewA);
        Countly.instance().views().stopViewWithName("A", null);

        TestUtils.validateEQSize(3);

        validateView("A", 0.0, 0, 3, true, true, TestUtils.map("power_percent", BigDecimal.valueOf(56.7)), "idv1", ""); // starting
        validateView("A", 1.0, 1, 3, false, false, null, "idv1", ""); // starting
        validateView("A", 0.0, 2, 3, false, false, null, "idv1", ""); // closing
    }

    /**
     * <pre>
     * Validate segmentation
     * Just make sure the values are used
     *
     * startView A with segmentation
     * make sure the correct things are added
     *
     * startView B with segmentation
     * make sure the correct things are added
     *
     * Stop A with no segmentation
     *
     * Stop B with segmentation
     *
     * again make sure that segmentation is correctly applied
     * </pre>
     */
    @Test
    public void validateSegmentation1() {
        Countly.instance().init(TestUtils.getConfigViews());
        TestUtils.validateEQSize(0);

        Map<String, Object> customSegmentationA = TestUtils.map("FigmaId", "YXNkOThhZnM=", "start", "1", "visit", "1", "name", TestUtils.keysValues[0], "segment", TestUtils.keysValues[1]);
        Map<String, Object> customSegmentationB = TestUtils.map("FigmaId", "OWE4cZdkOWFz", "start", "1", "end", "1", "name", TestUtils.keysValues[2], "segment", TestUtils.keysValues[3]);

        String viewA = Countly.instance().views().startView("A", customSegmentationA);
        validateView("A", 0.0, 0, 1, true, true, TestUtils.map("FigmaId", "YXNkOThhZnM="), "idv1", ""); // starting

        Countly.instance().views().startView("B", customSegmentationB);
        validateView("B", 0.0, 1, 2, false, true, TestUtils.map("FigmaId", "OWE4cZdkOWFz", "end", "1"), "idv2", "idv1"); // starting

        Countly.instance().views().stopViewWithID(viewA, null);
        validateView("A", 0.0, 2, 3, false, false, null, "idv1", "idv1"); // closing

        Countly.instance().views().stopViewWithName("B", TestUtils.map("ClickCount", 45));
        validateView("B", 0.0, 3, 4, false, false, TestUtils.map("ClickCount", 45), "idv2", "idv1"); // closing
    }

    /**
     * <pre>
     * Validate segmentation 2
     *
     * - startView A
     * - startView B
     * - stopAllViews with segmentation
     *
     * make sure that the stop segmentation was added to all views
     * </pre>
     */
    @Test
    public void validateSegmentation2() {
        Countly.instance().init(TestUtils.getConfigViews());
        TestUtils.validateEQSize(0);

        Countly.instance().views().startView("A");
        Countly.instance().views().startView("B");
        validateView("A", 0.0, 0, 2, true, true, null, "idv1", "");
        validateView("B", 0.0, 1, 2, false, true, null, "idv2", "idv1");

        Map<String, Object> allSegmentation = TestUtils.map("Copyright", "Countly", "AppExit", true, "DestroyToken", false, "ExitedAt", 1_702_975_890_000L);
        Countly.instance().views().stopAllViews(allSegmentation);

        validateView("A", 0.0, 2, 4, false, false, allSegmentation, "idv1", "idv1");
        validateView("B", 0.0, 3, 4, false, false, allSegmentation, "idv2", "idv1");
    }

    /**
     * <h3> Validate segmentation does not override internal keys </h2>
     * <pre>
     * Internal keys: "name", "start", "visit", "segment"
     *
     * - Start view and provide segmentation with internal keys
     * - Stop view and provide segmentation with internal keys
     * make sure that internal keys are not overridden at any point
     * </pre>
     */
    @Test
    public void validateSegmentation_internalKeys() {
        Countly.instance().init(TestUtils.getConfigViews());
        TestUtils.validateEQSize(0);

        Map<String, Object> internalKeysSegmentation = TestUtils.map("start", "YES", "name", TestUtils.keysValues[0], "visit", "YES", "segment", TestUtils.keysValues[1]);

        Countly.instance().views().startView("A", TestUtils.map(internalKeysSegmentation, "ultimate", "YES"));
        Countly.instance().views().stopViewWithName("A", TestUtils.map(internalKeysSegmentation, "end", "Unfortunately", "time", 1_234_567_890L));
        validateView("A", 0.0, 0, 2, true, true, TestUtils.map("ultimate", "YES"), "idv1", "");
        validateView("A", 0.0, 1, 2, false, false, TestUtils.map("end", "Unfortunately", "time", 1_234_567_890), "idv1", "");
    }

    /**
     * <pre>
     * Try add segmentation to view functions with internal keys
     *
     * - start view A with segmentation - validate that event is created
     * - Add segmentation to view with name A - with internal keys + valid param
     * - pause view A - validate that segmentation is not empty and only valid segmentation is added and internal keys are not overridden
     * - Add segmentation to view A with ID - with internal keys + valid param
     * - stop view with ID A - validate that segmentation is not empty and only valid segmentation is added and internal keys are not overridden
     *
     * </pre>
     */
    @Test
    public void addSegmentationToView_internalKeys() {
        Countly.instance().init(TestUtils.getConfigViews());
        TestUtils.validateEQSize(0);

        Map<String, Object> internalKeysSegmentation = TestUtils.map("start", "YES", "name", TestUtils.keysValues[0], "visit", "YES", "segment", TestUtils.keysValues[1]);

        String viewIDA = Countly.instance().views().startView("A");
        validateView("A", 0.0, 0, 1, true, true, null, "idv1", "");
        Countly.instance().views().addSegmentationToViewWithName("A", TestUtils.map(internalKeysSegmentation, "aniki", "HAVE"));
        Countly.instance().views().pauseViewWithID(viewIDA);
        validateView("A", 0.0, 1, 2, false, false, TestUtils.map("aniki", "HAVE"), "idv1", "");

        Countly.instance().views().addSegmentationToViewWithID(viewIDA, TestUtils.map(internalKeysSegmentation, "oni-chan", "HAVE"));
        Countly.instance().views().stopViewWithID(viewIDA);
        validateView("A", 0.0, 2, 3, false, false, TestUtils.map("aniki", "HAVE", "oni-chan", "HAVE"), "idv1", "");
    }

    /**
     * <pre>
     * Try add segmentation to view functions with null and empty values
     *
     * - start view A with segmentation + some internal keys - validate event is created and only valid segmentation is added
     * - Add segmentation to view with name A - with a param
     * - Add segmentation to view with name A - null
     * - pause view A - validate that segmentation is not empty and first call added the segmentation
     * - Add segmentation to view with ID A - with a param
     * - Add segmentation to view with ID A - empty
     * - stop view A - validate that segmentation is not empty and added segmentations are exists and not overridden by null and empty values
     *
     * </pre>
     */
    @Test
    public void addSegmentationToView_nullEmpty() {
        Countly.instance().init(TestUtils.getConfigViews());
        TestUtils.validateEQSize(0);

        Map<String, Object> viewSegmentation = TestUtils.map("name", "A", "segment", TestUtils.getOS(), "arr", new ArrayList<>(), "done", true);

        String viewIDA = Countly.instance().views().startView("A", viewSegmentation);
        validateView("A", 0.0, 0, 1, true, true, TestUtils.map("done", true), "idv1", "");
        Countly.instance().views().addSegmentationToViewWithName("A", TestUtils.map("a", 1));
        Countly.instance().views().addSegmentationToViewWithName("A", null);
        Countly.instance().views().pauseViewWithID(viewIDA);
        validateView("A", 0.0, 1, 2, false, false, TestUtils.map("a", 1), "idv1", "");

        Countly.instance().views().addSegmentationToViewWithID(viewIDA, TestUtils.map("b", 2));
        Countly.instance().views().addSegmentationToViewWithID(viewIDA, TestUtils.map());
        Countly.instance().views().stopViewWithID(viewIDA);
        validateView("A", 0.0, 2, 3, false, false, TestUtils.map("a", 1, "b", 2), "idv1", "");
    }

    /**
     * <pre>
     * Add segmentation to view with init given global segmentation
     *
     * - start view A with none segmentation
     * - Add segmentation to view A
     * - pause view A - validate that segmentation is added
     * - stop view A - validate that segmentation is also added to stop view event
     *
     * </pre>
     */
    @Test
    public void addSegmentationToView() {
        Countly.instance().init(TestUtils.getConfigViews(TestUtils.map("glob", "al")));
        TestUtils.validateEQSize(0);
        String viewIDA = Countly.instance().views().startView("A");
        validateView("A", 0.0, 0, 1, true, true, TestUtils.map("glob", "al"), "idv1", "");
        Countly.instance().views().addSegmentationToViewWithName("A", TestUtils.map("a", 1, "b", 2));
        Countly.instance().views().pauseViewWithID(viewIDA);
        validateView("A", 0.0, 1, 2, false, false, TestUtils.map("a", 1, "b", 2, "glob", "al"), "idv1", "");
        Countly.instance().views().stopViewWithID(viewIDA);
        validateView("A", 0.0, 2, 3, false, false, TestUtils.map("a", 1, "b", 2, "glob", "al"), "idv1", "");
    }

    /**
     * <pre>
     * Resume already running view
     *
     * - start view A
     * - wait a moment
     * - pause view A
     * - wait a moment
     * - pause view A again
     * - wait a moment
     * - stop view A
     *
     * Total time should be 1 seconds because it was paused already
     *
     * </pre>
     *
     * @throws InterruptedException to wait
     */
    @Test
    public void pauseViewWithId_pausePaused() throws InterruptedException {
        Countly.instance().init(TestUtils.getConfigViews());
        TestUtils.validateEQSize(0);
        String viewIDA = Countly.instance().views().startView("A");

        Thread.sleep(1000);
        Countly.instance().views().pauseViewWithID(viewIDA);
        Thread.sleep(1000);
        Countly.instance().views().pauseViewWithID(viewIDA);
        Thread.sleep(1000);

        Countly.instance().views().stopViewWithID(viewIDA);
        validateView("A", 0.0, 0, 3, true, true, null, "idv1", "");
        validateView("A", 1.0, 1, 3, false, false, null, "idv1", "");
        validateView("A", 0.0, 2, 3, false, false, null, "idv1", "");
    }

    /**
     * <pre>
     * Resume already running view
     *
     * - start view A
     * - wait a moment
     * - resume view A
     * - wait a moment
     * - stop view A
     *
     * Total time should be 2 seconds because it was not paused
     *
     * </pre>
     *
     * @throws InterruptedException to wait
     */
    @Test
    public void resumeViewWithId_resumeRunning() throws InterruptedException {
        Countly.instance().init(TestUtils.getConfigViews());
        TestUtils.validateEQSize(0);
        String viewIDA = Countly.instance().views().startView("A");

        Thread.sleep(1000);
        Countly.instance().views().resumeViewWithID(viewIDA);
        Thread.sleep(1000);

        Countly.instance().views().stopViewWithID(viewIDA);
        validateView("A", 0.0, 0, 2, true, true, null, "idv1", "");
        validateView("A", 2.0, 1, 2, false, false, null, "idv1", "");
    }

    /**
     * <pre>
     * A mixed flow of sessions and views
     *
     * - start session
     * - start view A - firstView true- event is created
     * - wait a moment
     * - end session - this call ends existing views so it stops A
     * - start view B - firstView true - event is created
     * - start session
     * - start view C - firstView false - event is created
     *
     * There should be 5 events
     * </pre>
     *
     * @throws InterruptedException for wait
     */
    @Test
    public void mixedFlow_sessions() throws InterruptedException {
        Countly.instance().init(TestUtils.getConfigViews().enableFeatures(Config.Feature.Sessions));
        TestUtils.validateEQSize(0);
        Countly.session().begin();

        Countly.instance().view("A");

        Thread.sleep(1000);
        Countly.session().end(); // A will auto stop

        Countly.instance().view("B");
        Countly.session().begin();

        Countly.instance().views().startView("C");

        validateView("A", 0.0, 0, 5, true, true, null, "idv1", "");
        validateView("A", 1.0, 1, 5, false, false, null, "idv1", "");
        validateView("B", 0.0, 2, 5, true, true, null, "idv2", "idv1");
        validateView("B", 0.0, 3, 5, false, false, null, "idv2", "idv1");
        validateView("C", 0.0, 4, 5, false, true, null, "idv3", "idv2");
    }

    /**
     * "setGlobalSegmentation" flow
     * - set global segmentation to different one that has all accepted data types and an invalid data type in init
     * - start view A
     * - sleep for 1 sec
     * - pause view A
     * - start view B that has new segmentation
     * - sleep for 1 sec
     * - stop all views with segmentation
     * ------
     * Validate that all events are created and segmentation is correct, and init given segmentation should exist in all events
     */
    @Test
    public void setGlobalSegmentation_initGiven() throws InterruptedException {
        Countly.instance().init(TestUtils.getConfigViews(TestUtils.map("glob", "al", "int", Integer.MAX_VALUE, "float", BigDecimal.valueOf(Float.MAX_VALUE), "bool", true, "arr", new ArrayList<>(), "double", Double.MAX_VALUE, "long", Long.MAX_VALUE)));
        Map<String, Object> clearedSegmentation = TestUtils.map("glob", "al", "int", Integer.MAX_VALUE, "float", BigDecimal.valueOf(Float.MAX_VALUE), "bool", true, "double", BigDecimal.valueOf(Double.MAX_VALUE), "long", Long.MAX_VALUE);
        TestUtils.validateEQSize(0);
        Countly.instance().views().startView("A", TestUtils.map("a", 1, "b", 2));
        Thread.sleep(1000);
        Countly.instance().views().pauseViewWithID("idv1");
        Countly.instance().views().startView("B", TestUtils.map("c", 3, "d", 4));
        Thread.sleep(1000);
        Countly.instance().views().stopAllViews(TestUtils.map("e", 5, "f", 6));

        validateView("A", 0.0, 0, 5, true, true, TestUtils.map(clearedSegmentation, "a", 1, "b", 2), "idv1", "");
        validateView("A", 1.0, 1, 5, false, false, clearedSegmentation, "idv1", "");
        validateView("B", 0.0, 2, 5, false, true, TestUtils.map(clearedSegmentation, "c", 3, "d", 4), "idv2", "idv1");
        validateView("A", 0.0, 3, 5, false, false, TestUtils.map(clearedSegmentation, "e", 5, "f", 6), "idv1", "idv1");
        validateView("B", 1.0, 4, 5, false, false, TestUtils.map(clearedSegmentation, "e", 5, "f", 6), "idv2", "idv1");
    }

    /**
     * "setGlobalSegmentation" flow
     * - init countly with global segmentation
     * - start view A that overrides one of the global segmentation
     * - set global segmentation to different one that has all accepted data types and an invalid data type
     * - sleep for 1 sec
     * - pause view A
     * - start view B that has new segmentation
     * - sleep for 1 sec
     * - stop all views with segmentation
     * ------
     * Validate that all events are created and segmentation is correct, and things should be overridden correctly
     */
    @Test
    public void setGlobalSegmentation() throws InterruptedException {
        Countly.instance().init(TestUtils.getConfigViews(TestUtils.map("ab", 5, "a", 5)));
        Map<String, Object> clearedSegmentation = TestUtils.map("glob", "al", "int", Integer.MAX_VALUE, "float", BigDecimal.valueOf(Float.MAX_VALUE), "bool", true, "double", BigDecimal.valueOf(Double.MAX_VALUE), "long", Long.MAX_VALUE);
        TestUtils.validateEQSize(0);
        Countly.instance().views().startView("A", TestUtils.map("a", 1, "b", 2));
        Countly.instance().views().setGlobalViewSegmentation(TestUtils.map("glob", "al", "int", Integer.MAX_VALUE, "float", BigDecimal.valueOf(Float.MAX_VALUE), "bool", true, "arr", new ArrayList<>(), "double", Double.MAX_VALUE, "long", Long.MAX_VALUE));
        Thread.sleep(1000);
        Countly.instance().views().pauseViewWithID("idv1");
        Countly.instance().views().startView("B", TestUtils.map("c", 3, "d", 4));
        Thread.sleep(1000);
        Countly.instance().views().stopAllViews(TestUtils.map("e", 5, "f", 6));

        validateView("A", 0.0, 0, 5, true, true, TestUtils.map("ab", 5, "a", 1, "b", 2), "idv1", "");
        validateView("A", 1.0, 1, 5, false, false, clearedSegmentation, "idv1", "");
        validateView("B", 0.0, 2, 5, false, true, TestUtils.map(clearedSegmentation, "c", 3, "d", 4), "idv2", "idv1");
        validateView("A", 0.0, 3, 5, false, false, TestUtils.map(clearedSegmentation, "e", 5, "f", 6), "idv1", "idv1");
        validateView("B", 1.0, 4, 5, false, false, TestUtils.map(clearedSegmentation, "e", 5, "f", 6), "idv2", "idv1");
    }

    /**
     * "updateGlobalViewSegmentation" flow
     * - init countly with global segmentation
     * - start view A that overrides one of the global segmentation
     * - set global segmentation to different one that has all accepted data types and an invalid data type
     * - sleep for 1 sec
     * - pause view A
     * - update global segmentation with new values and override one of the old values
     * - start view B that has new segmentation
     * - sleep for 1 sec
     * - stop all views with segmentation
     * ------
     * Validate that all events are created and segmentation is correct, and things should be overridden correctly
     */
    @Test
    public void updateGlobalSegmentation() throws InterruptedException {
        Countly.instance().init(TestUtils.getConfigViews(TestUtils.map("ab", 5, "a", 5)));
        Map<String, Object> clearedSegmentation = TestUtils.map("glob", "al", "int", Integer.MAX_VALUE, "float", BigDecimal.valueOf(Float.MAX_VALUE), "bool", true, "double", BigDecimal.valueOf(Double.MAX_VALUE), "long", Long.MAX_VALUE);
        TestUtils.validateEQSize(0);
        Countly.instance().views().startView("A", TestUtils.map("a", 1, "b", 2));
        Countly.instance().views().setGlobalViewSegmentation(TestUtils.map("glob", "al", "int", Integer.MAX_VALUE, "float", BigDecimal.valueOf(Float.MAX_VALUE), "bool", true, "arr", new ArrayList<>(), "double", Double.MAX_VALUE, "long", Long.MAX_VALUE));
        Thread.sleep(1000);
        Countly.instance().views().pauseViewWithID("idv1");
        Countly.instance().views().updateGlobalViewSegmentation(TestUtils.map("int", Integer.MIN_VALUE, "all", "glob"));
        Countly.instance().views().startView("B", TestUtils.map("c", 3, "d", 4));
        Thread.sleep(1000);
        Countly.instance().views().stopAllViews(TestUtils.map("e", 5, "f", 6));

        validateView("A", 0.0, 0, 5, true, true, TestUtils.map("ab", 5, "a", 1, "b", 2), "idv1", "");
        validateView("A", 1.0, 1, 5, false, false, clearedSegmentation, "idv1", "");
        clearedSegmentation.put("int", Integer.MIN_VALUE);
        clearedSegmentation.put("all", "glob");
        validateView("B", 0.0, 2, 5, false, true, TestUtils.map(clearedSegmentation, "c", 3, "d", 4), "idv2", "idv1");
        validateView("A", 0.0, 3, 5, false, false, TestUtils.map(clearedSegmentation, "e", 5, "f", 6), "idv1", "idv1");
        validateView("B", 1.0, 4, 5, false, false, TestUtils.map(clearedSegmentation, "e", 5, "f", 6), "idv2", "idv1");
    }

    /**
     * "setGlobalViewSegmentation" init empty null
     * should not override global segmentation with empty map given
     * Global segmentation should stay as it is
     */
    @Test
    public void setGlobalSegmentation_initGiven_empty() {
        Countly.instance().init(TestUtils.getConfigViews(TestUtils.map()));
        TestUtils.validateEQSize(0);
        Countly.instance().views().startView("A", TestUtils.map("a", 1, "b", 2));

        validateView("A", 0.0, 0, 1, true, true, TestUtils.map("a", 1, "b", 2), "idv1", "");
    }

    /**
     * "setGlobalViewSegmentation" init given null
     * should not override global segmentation with null map given
     * Global segmentation should stay as it is
     */
    @Test
    public void setGlobalSegmentation_initGiven_null() {
        Countly.instance().init(TestUtils.getConfigViews(null));
        TestUtils.validateEQSize(0);
        Countly.instance().views().startView("A", TestUtils.map("a", 1, "b", 2));

        validateView("A", 0.0, 0, 1, true, true, TestUtils.map("a", 1, "b", 2), "idv1", "");
    }

    /**
     * "setGlobalViewSegmentation"
     * should not override global segmentation with empty map given
     * Global segmentation should stay as it is
     */
    @Test
    public void setGlobalSegmentation_empty() {
        Countly.instance().init(TestUtils.getConfigViews());
        TestUtils.validateEQSize(0);

        Countly.instance().views().setGlobalViewSegmentation(TestUtils.map());
        Countly.instance().views().startView("A", TestUtils.map("a", 1, "b", 2));

        validateView("A", 0.0, 0, 1, true, true, TestUtils.map("a", 1, "b", 2), "idv1", "");
    }

    /**
     * "setGlobalViewSegmentation"
     * should not override global segmentation with null map given
     * Global segmentation should stay as it is
     */
    @Test
    public void setGlobalSegmentation_null() {
        Countly.instance().init(TestUtils.getConfigViews());
        TestUtils.validateEQSize(0);

        Countly.instance().views().setGlobalViewSegmentation(null);
        Countly.instance().views().startView("A", TestUtils.map("a", 1, "b", 2));

        validateView("A", 0.0, 0, 1, true, true, TestUtils.map("a", 1, "b", 2), "idv1", "");
    }

    /**
     * "updateGlobalSegmentation"
     * should not override global segmentation with empty map given
     * Global segmentation should stay as it is
     */
    @Test
    public void updateGlobalSegmentation_empty() {
        Countly.instance().init(TestUtils.getConfigViews(TestUtils.map("glob", "all")));
        TestUtils.validateEQSize(0);

        Countly.instance().views().updateGlobalViewSegmentation(TestUtils.map());
        Countly.instance().views().startView("A", TestUtils.map("a", 1, "b", 2));

        validateView("A", 0.0, 0, 1, true, true, TestUtils.map("glob", "all", "a", 1, "b", 2), "idv1", "");
    }

    /**
     * "updateGlobalSegmentation"
     * should not override global segmentation with null given
     * Global segmentation should stay as it is
     */
    @Test
    public void updateGlobalSegmentation_null() {
        Countly.instance().init(TestUtils.getConfigViews(TestUtils.map("glob", "all")));
        TestUtils.validateEQSize(0);

        Countly.instance().views().updateGlobalViewSegmentation(null);
        Countly.instance().views().startView("A", TestUtils.map("a", 1, "b", 2));

        validateView("A", 0.0, 0, 1, true, true, TestUtils.map("glob", "all", "a", 1, "b", 2), "idv1", "");
    }

    /**
     * We make sure that "setGlobalSegmentation" and "updateGlobalSegmentation" updates/sets global segmentation
     * ------
     * initialize countly with init given global segmentation that includes all accepted data types and couple of incorrect ones (like arr=list())
     * start view A with segmentation that overrides one of the global segmentations
     * validate start view event is recorded for A with correct segmentation
     * setGlobalViewSegmentation with all accepted data types and couple of not accepted data types
     * sleep for 1 second
     * pause view A
     * validate pause view event, and setGlobalViewSegmentation values are exists
     * call updateGlobalViewSegmentation that overrides some of the globalSegmentation and also add couple of incorrect data types
     * start view B with segment that overrides couple of not overridden global segmentation values
     * validate start view event for B is recorded and global segmentation values are overwridden and incorrect data types removed
     * sleep for 1 second
     * stopAllViews with segmentation that has incorrect data types, 1 global segm override and new segm values
     * validate 2 stop view event is recorded in order of A,B and correct segm values are existed
     * ------
     *
     * @throws InterruptedException for wait
     */
    @Test
    public void updateGlobalSegmentation_flow() throws InterruptedException {
        Countly.instance().init(TestUtils.getConfigViews(TestUtils.map("glob", "al", "int", Integer.MAX_VALUE, "float", BigDecimal.valueOf(Float.MAX_VALUE), "bool", true, "double", BigDecimal.valueOf(Double.MAX_VALUE), "long", Long.MAX_VALUE, "arr", new ArrayList<>(), "map", TestUtils.map())));

        Map<String, Object> clearedSegmentation = TestUtils.map("glob", "al", "int", Integer.MAX_VALUE, "float", BigDecimal.valueOf(Float.MAX_VALUE), "bool", true, "double", BigDecimal.valueOf(Double.MAX_VALUE), "long", Long.MAX_VALUE);
        TestUtils.validateEQSize(0);

        Countly.instance().views().startView("A", TestUtils.map("glob", "no"));
        validateView("A", 0.0, 0, 1, true, true, TestUtils.map(clearedSegmentation, "glob", "no"), "idv1", "");

        Countly.instance().views().setGlobalViewSegmentation(TestUtils.map("glob", "al", "int", Integer.MAX_VALUE, "float", BigDecimal.valueOf(Float.MAX_VALUE), "bool", true, "arr", new ArrayList<>(), "double", Double.MAX_VALUE, "long", Long.MAX_VALUE));
        Thread.sleep(1000);

        Countly.instance().views().pauseViewWithID("idv1");
        validateView("A", 1.0, 1, 2, false, false, clearedSegmentation, "idv1", "");

        Countly.instance().views().updateGlobalViewSegmentation(TestUtils.map("int", Integer.MIN_VALUE, "arr", new ArrayList<>(), "all", "glob"));

        Countly.instance().views().startView("B", TestUtils.map("float", BigDecimal.valueOf(Float.MIN_VALUE), "in", "case"));
        validateView("B", 0.0, 2, 3, false, true, TestUtils.map(clearedSegmentation, "all", "glob", "int", Integer.MIN_VALUE, "float", BigDecimal.valueOf(Float.MIN_VALUE), "in", "case"), "idv2", "idv1");

        Thread.sleep(1000);
        Countly.instance().views().stopAllViews(TestUtils.map("bool", false));
        validateView("A", 0.0, 3, 5, false, false, TestUtils.map(clearedSegmentation, "all", "glob", "int", Integer.MIN_VALUE, "bool", false), "idv1", "idv1");
        validateView("B", 1.0, 4, 5, false, false, TestUtils.map(clearedSegmentation, "all", "glob", "int", Integer.MIN_VALUE, "bool", false), "idv2", "idv1");
    }

    static void validateView(String viewName, Double viewDuration, int idx, int size, boolean start, boolean visit, Map<String, Object> customSegmentation, String id, String pvid) {
        Map<String, Object> viewSegmentation = TestUtils.map("name", viewName, "segment", TestUtils.getOS());
        if (start) {
            viewSegmentation.put("start", "1");
        }
        if (visit) {
            viewSegmentation.put("visit", "1");
        }
        if (customSegmentation != null) {
            viewSegmentation.putAll(customSegmentation);
        }

        TestUtils.validateEventInEQ(ModuleViews.KEY_VIEW_EVENT, viewSegmentation, 1, 0.0, viewDuration, idx, size, id, pvid, null, null);
    }
}
