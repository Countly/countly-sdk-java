package ly.count.sdk.java.internal;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import ly.count.sdk.java.View;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

@RunWith(JUnit4.class)
public class ViewImplTests {

    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
    }

    @After
    public void afterTest() {
        Countly.instance().halt();
    }

    /**
     * "constructor" with null session
     * Validating default values
     * Values should be set to default
     */
    @Test
    public void constructor_defaults() {
        ViewImpl view = new ViewImpl(null, TestUtils.keysValues[0], Mockito.mock(Log.class));
        Assert.assertNull(view.session);
        Assert.assertEquals(TestUtils.keysValues[0], view.name);
        Assert.assertEquals("start", ModuleViews.KEY_START);
        Assert.assertEquals("1", ModuleViews.KEY_START_VALUE);
        Assert.assertEquals("visit", ModuleViews.KEY_VISIT);
        Assert.assertEquals("1", ModuleViews.KEY_VISIT_VALUE);
        Assert.assertEquals("segment", ModuleViews.KEY_SEGMENT);
        Assert.assertEquals("[CLY]_view", ModuleViews.KEY_VIEW_EVENT);
        Assert.assertEquals("name", ModuleViews.KEY_NAME);
        Assert.assertFalse(view.toString().isEmpty());
    }

    /**
     * "start" with defaults
     * Validating default values and that view is recorded
     * Values should be set to default and view should be recorded
     */
    @Test
    public void start() {
        InternalConfig config = new InternalConfig(TestUtils.getBaseConfig().setFeatures(Config.Feature.Views, Config.Feature.Events));
        config.viewIdGenerator = TestUtils.idGenerator();
        Countly.instance().init(config);

        TestUtils.validateEQSize(0);
        Countly.instance().view(TestUtils.keysValues[0]); // this calls start automatically
        TestUtils.validateEQSize(1);
        Map<String, Object> segmentations = new ConcurrentHashMap<>();
        segmentations.put("name", TestUtils.keysValues[0]);
        segmentations.put("visit", "1");
        segmentations.put("segment", TestUtils.getOS());
        segmentations.put("start", "1");
        TestUtils.validateEventInEQ("[CLY]_view", segmentations, 1, 0.0, null, 0, 1, TestUtils.keysValues[0], "", null, null);
    }

    /**
     * "start" with backendModeEnabled
     * Validating that view is not recorded
     * A view should not be recorded
     */
    @Test
    public void start_backendModeEnabled() {
        Countly.instance().init(TestUtils.getBaseConfig().setFeatures(Config.Feature.Views, Config.Feature.Events).enableBackendMode());
        TestUtils.validateEQSize(0);
        Countly.instance().view(TestUtils.keysValues[0]); // this calls start automatically
        TestUtils.validateEQSize(0);
    }

    /**
     * "start" with null and empty name
     * Validating that views are not recorded
     * Views should not be recorded
     */
    @Test
    public void start_nullAndEmptyName() {
        Countly.instance().init(TestUtils.getBaseConfig().setFeatures(Config.Feature.Views, Config.Feature.Events).enableBackendMode());
        TestUtils.validateEQSize(0);
        Countly.instance().view(null); // this calls start automatically
        TestUtils.validateEQSize(0);
        Countly.instance().view(""); // this calls start automatically
        TestUtils.validateEQSize(0);
    }

    /**
     * "start" with no consent to Events
     * Validating that view is not recorded
     * A view should not be recorded
     */
    @Test
    public void start_noEventsConsent() {
        Countly.instance().init(TestUtils.getBaseConfig().setFeatures(Config.Feature.Views));
        TestUtils.validateEQSize(0);
        Countly.instance().view(TestUtils.keysValues[0]); // this calls start automatically
        TestUtils.validateEQSize(0);
    }

    /**
     * "stop" with defaults
     * Validating that start view and stop view are recorded
     * Start and stop views should be recorded
     *
     * @throws InterruptedException for the duration of the view
     */
    @Test
    public void stop() throws InterruptedException {
        InternalConfig config = new InternalConfig(TestUtils.getBaseConfig().setFeatures(Config.Feature.Views, Config.Feature.Events));
        config.viewIdGenerator = TestUtils.idGenerator();
        Countly.instance().init(config);

        TestUtils.validateEQSize(0);
        View view = Countly.instance().view(TestUtils.keysValues[0]); // this calls start automatically
        TestUtils.validateEQSize(1);
        Map<String, Object> segmentations = new ConcurrentHashMap<>();
        segmentations.put("name", TestUtils.keysValues[0]);
        segmentations.put("visit", "1");
        segmentations.put("segment", TestUtils.getOS());
        segmentations.put("start", "1");
        TestUtils.validateEventInEQ("[CLY]_view", segmentations, 1, 0.0, null, 0, 1, TestUtils.keysValues[0], "", null, null);

        segmentations.remove("start");
        segmentations.remove("visit");
        Thread.sleep(1000);
        view.stop(false);
        TestUtils.validateEventInEQ("[CLY]_view", segmentations, 1, 0.0, 1.0, 1, 2, TestUtils.keysValues[0], "", null, null);
    }

    /**
     * "stop" with defaults via calling stop on Countly
     * Validating that start view and stop view are recorded but there should be 4 events recorded
     * Start and stop views should be recorded and expected numbers of events should be in the queue
     *
     * @throws InterruptedException for the duration of the view
     */
    @Test
    public void stop_sdkCall() throws InterruptedException {
        InternalConfig config = new InternalConfig(TestUtils.getBaseConfig().setFeatures(Config.Feature.Views, Config.Feature.Events));
        config.viewIdGenerator = TestUtils.idGenerator();
        Countly.instance().init(config);

        TestUtils.validateEQSize(0);
        Countly.instance().view(TestUtils.keysValues[0]); // this calls start automatically
        TestUtils.validateEQSize(1);
        Map<String, Object> segmentations = new ConcurrentHashMap<>();
        segmentations.put("name", TestUtils.keysValues[0]);
        segmentations.put("visit", "1");
        segmentations.put("segment", TestUtils.getOS());
        segmentations.put("start", "1");
        TestUtils.validateEventInEQ("[CLY]_view", segmentations, 1, 0.0, null, 0, 1, TestUtils.keysValues[0], "", null, null);

        segmentations.remove("start");
        segmentations.remove("visit");
        Thread.sleep(1000);
        Countly.instance().view(TestUtils.keysValues[0]).stop(false); // this call stop previous view and creates new one and stops it
        TestUtils.validateEventInEQ("[CLY]_view", segmentations, 1, 0.0, null, 3, 4, TestUtils.keysValues[1], TestUtils.keysValues[0], null, null);
    }

    /**
     * "stop" with backend mode enabled
     * Validating that nothing should be recorded
     * Event queue should be empty
     */
    @Test
    public void stop_backendModeEnabled() {
        Countly.instance().init(TestUtils.getBaseConfig().setFeatures(Config.Feature.Views, Config.Feature.Events).enableBackendMode());
        TestUtils.validateEQSize(0);
        Countly.instance().view(TestUtils.keysValues[0]); // this calls start automatically
        TestUtils.validateEQSize(0);
        Countly.instance().view(TestUtils.keysValues[0]).stop(false); // this call stop previous view and creates new one and stops it
        TestUtils.validateEQSize(0);
    }

    /**
     * "stop" with no consent to events
     * Validating that nothing should be recorded
     * Event queue should be empty
     */
    @Test
    public void stop_noConsentForEvents() {
        Countly.instance().init(TestUtils.getBaseConfig().setFeatures(Config.Feature.Views));
        TestUtils.validateEQSize(0);
        Countly.instance().view(TestUtils.keysValues[0]); // this calls start automatically
        TestUtils.validateEQSize(0);
        Countly.instance().view(TestUtils.keysValues[0]).stop(false); // this call stop previous view and creates new one and stops it
        TestUtils.validateEQSize(0);
    }

    /**
     * "stop" a not started view
     * Validating that stop call does not generate any events
     * Event queue should be empty
     */
    @Test
    public void stop_notStartedView() {
        Countly.instance().init(TestUtils.getBaseConfig().setFeatures(Config.Feature.Views, Config.Feature.Events));
        ViewImpl view = new ViewImpl(Countly.session(), TestUtils.keysValues[0], Mockito.mock(Log.class));
        TestUtils.validateEQSize(0);
        view.stop(false);
        TestUtils.validateEQSize(0);
    }
}
