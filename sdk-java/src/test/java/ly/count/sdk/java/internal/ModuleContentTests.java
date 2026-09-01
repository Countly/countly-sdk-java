package ly.count.sdk.java.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Content zone behaviour, driven through the public {@code Countly.instance().content()} interface.
 * <p>
 * The zone timer is driven by hand ({@link ModuleContent#onZoneTimerTick()}) in every test but
 * {@link #zoneTimer_drivesFetchesOnItsOwn()}, so the assertions do not depend on wall clock timing.
 */
@RunWith(JUnit4.class)
public class ModuleContentTests {

    private static final String CONTENT_URL = "https://content.count.ly/block-1";
    private static final String CONTENT_RESPONSE =
        "{\"html\":\"" + CONTENT_URL + "\",\"geo\":{"
            + "\"p\":{\"x\":10,\"y\":20,\"w\":300,\"h\":400},"
            + "\"l\":{\"x\":30,\"y\":40,\"w\":500,\"h\":600}}}";
    private static final String NO_CONTENT_RESPONSE = "{\"jsonArray\":[{\"result\":\"No content block found!\"}]}";

    private final List<Map<String, String>> requests = new ArrayList<>();
    private final List<String> endpoints = new ArrayList<>();
    private JSONObject nextResponse = null;
    private CountDownLatch requestLatch = null;

    private FakeDisplay display;

    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
        requests.clear();
        endpoints.clear();
        nextResponse = null;
        requestLatch = null;
        display = new FakeDisplay();
    }

    @After
    public void stop() {
        CountlyTimer.TIMER_DELAY_MS = 0;
        Countly.instance().halt();
    }

    // region scenarios

    /**
     * Entering a content zone, then letting the zone poll once.
     * <p>
     * Verifies the wire shape of the fetch, that the parsed content reaches the display with the
     * placement matching the surface orientation, and that a second poll does not fetch again while
     * that content is still on screen.
     */
    @Test
    public void enterContentZone_fetchesOnceAndPresentsTheContent() throws JSONException {
        initWithContent(TestUtils.getConfigContent());
        nextResponse = new JSONObject(CONTENT_RESPONSE);

        Countly.instance().content().enterContentZone();
        // The first fetch waits for the zone's start delay, so nothing is on the wire yet.
        Assert.assertTrue(requests.isEmpty());

        tick();

        Assert.assertEquals(1, requests.size());
        Assert.assertEquals("/o/sdk/content?", endpoints.get(0));

        Map<String, String> params = requests.get(0);
        TestUtils.validateRequiredParams(params);
        Assert.assertEquals("queue", params.get("method"));
        Assert.assertEquals("desktop", params.get("dt"));
        // Content categories are not supported by the server, so nothing is sent for them.
        Assert.assertNull(params.get("category"));
        Assert.assertFalse(params.get("la").isEmpty());
        Assert.assertEquals("{\"l\":{\"w\":1600,\"h\":900},\"p\":{\"w\":1600,\"h\":900}}", Utils.urldecode(params.get("resolution")));
        Assert.assertNull(params.get("content_id"));
        Assert.assertNull(params.get("preview"));

        Assert.assertEquals(1, display.presented.size());
        ContentData shown = display.presented.get(0);
        Assert.assertEquals(CONTENT_URL, shown.url);
        // A 1600x900 surface is landscape, so the landscape rectangle wins.
        ContentPlacement placement = shown.placementFor(true);
        Assert.assertEquals(30, placement.x);
        Assert.assertEquals(40, placement.y);
        Assert.assertEquals(500, placement.width);
        Assert.assertEquals(600, placement.height);

        tick();
        Assert.assertEquals(1, requests.size());
        Assert.assertEquals(1, display.presented.size());
    }

    /**
     * A content zone cannot be entered before a display is registered, and entering again once one
     * is registered works.
     */
    @Test
    public void enterContentZone_withoutADisplay_isIgnored() throws JSONException {
        init(TestUtils.getConfigContent());
        nextResponse = new JSONObject(CONTENT_RESPONSE);

        Countly.instance().content().enterContentZone();
        tick();
        Assert.assertTrue(requests.isEmpty());
        Assert.assertTrue(display.presented.isEmpty());

        Countly.instance().content().setContentDisplay(display);
        Countly.instance().content().enterContentZone();
        tick();

        Assert.assertEquals(1, requests.size());
        Assert.assertEquals(1, display.presented.size());
    }

    /**
     * With consent required, the content interface is unreachable until content consent is given,
     * and a zone that is already running is torn down when that consent is taken away again.
     */
    @Test
    public void content_isGatedByConsent() throws JSONException {
        Config config = TestUtils.getConfigContent().setRequiresConsent(true);
        init(config);
        nextResponse = new JSONObject(CONTENT_RESPONSE);

        Assert.assertNull(Countly.instance().content());

        Countly.onConsent(Config.Feature.Content, Config.Feature.Events);
        installRequestMaker();

        ModuleContent.Content content = Countly.instance().content();
        Assert.assertNotNull(content);
        content.setContentDisplay(display);
        content.enterContentZone();
        tick();
        Assert.assertEquals(1, requests.size());

        Countly.onConsentRemoval(Config.Feature.Content);
        Assert.assertNull(Countly.instance().content());
        Assert.assertEquals(1, requests.size());
    }

    /**
     * A response the server sends when it has nothing to show, and a failed request, both leave the
     * zone polling instead of wedging it.
     */
    @Test
    public void noContentInResponse_keepsThePollingGoing() throws JSONException {
        initWithContent(TestUtils.getConfigContent());

        Countly.instance().content().enterContentZone();

        nextResponse = new JSONObject(NO_CONTENT_RESPONSE);
        tick();
        Assert.assertEquals(1, requests.size());
        Assert.assertTrue(display.presented.isEmpty());

        // A null response is what a failed request looks like to the module.
        nextResponse = null;
        tick();
        Assert.assertEquals(2, requests.size());
        Assert.assertTrue(display.presented.isEmpty());

        nextResponse = new JSONObject(CONTENT_RESPONSE);
        tick();
        Assert.assertEquals(3, requests.size());
        Assert.assertEquals(1, display.presented.size());
    }

    /**
     * Closing a content block reports it to the global content callback and holds the zone back for
     * a couple of polls, so the server can process whatever the content recorded.
     */
    @Test
    public void contentClose_reportsToTheCallbackAndPausesTheZone() throws JSONException {
        final List<ContentStatus> statuses = new ArrayList<>();
        final List<Map<String, Object>> payloads = new ArrayList<>();

        Config config = TestUtils.getConfigContent();
        config.content.setGlobalContentCallback((status, data) -> {
            statuses.add(status);
            payloads.add(data);
        });
        initWithContent(config);
        nextResponse = new JSONObject(CONTENT_RESPONSE);

        Countly.instance().content().enterContentZone();
        tick();
        Assert.assertEquals(1, display.presented.size());

        Map<String, Object> closeData = new HashMap<>();
        closeData.put("cly_x_action_event", "1");
        closeData.put("close", "1");
        display.lastCallback.onClosed(closeData);

        Assert.assertEquals(1, statuses.size());
        Assert.assertEquals(ContentStatus.CLOSED, statuses.get(0));
        Assert.assertEquals("1", payloads.get(0).get("close"));

        for (int i = 0; i < ModuleContent.POST_CLOSE_SKIPPED_TICKS; i++) {
            tick();
            Assert.assertEquals(1, requests.size());
        }

        tick();
        Assert.assertEquals(2, requests.size());
        Assert.assertEquals(2, display.presented.size());
    }

    /**
     * Leaving a content zone stops the polling but leaves a content block that is already on screen
     * alone, and entering again restarts the cycle.
     */
    @Test
    public void exitContentZone_stopsPollingWithoutClosingWhatIsOnScreen() throws JSONException {
        initWithContent(TestUtils.getConfigContent());
        nextResponse = new JSONObject(CONTENT_RESPONSE);

        Countly.instance().content().enterContentZone();
        tick();
        Assert.assertEquals(1, requests.size());
        Assert.assertEquals(1, display.presented.size());

        Countly.instance().content().exitContentZone();
        Assert.assertFalse(display.closed.get());

        tick();
        Assert.assertEquals(1, requests.size());

        Countly.instance().content().enterContentZone();
        tick();
        Assert.assertEquals(2, requests.size());
        Assert.assertEquals(2, display.presented.size());
    }

    /**
     * A display that throws while showing content must not leave the zone believing something is on
     * screen, which would block every later fetch.
     */
    @Test
    public void throwingDisplay_doesNotWedgeTheZone() throws JSONException {
        initWithContent(TestUtils.getConfigContent());
        display.throwOnPresent = true;
        nextResponse = new JSONObject(CONTENT_RESPONSE);

        Countly.instance().content().enterContentZone();
        tick();
        Assert.assertEquals(1, requests.size());
        Assert.assertEquals(1, display.presented.size());

        tick();
        Assert.assertEquals(2, requests.size());
        Assert.assertEquals(2, display.presented.size());
    }

    /**
     * Previewing one specific content block: the fetch carries the block's ID, a blank ID is
     * rejected, and a preview cannot stack on top of a content block that is already on screen.
     */
    @Test
    public void previewContent_fetchesTheGivenBlockAndGuardsAgainstStacking() throws JSONException {
        initWithContent(TestUtils.getConfigContent());
        nextResponse = new JSONObject(CONTENT_RESPONSE);

        Countly.instance().content().previewContent(null);
        Countly.instance().content().previewContent("");
        Assert.assertTrue(requests.isEmpty());

        Countly.instance().content().previewContent("block_42");
        Assert.assertEquals(1, requests.size());
        Assert.assertEquals("/o/sdk/content?", endpoints.get(0));
        Assert.assertEquals("block_42", requests.get(0).get("content_id"));
        Assert.assertEquals("true", requests.get(0).get("preview"));
        Assert.assertEquals(1, display.presented.size());

        Countly.instance().content().previewContent("block_43");
        Assert.assertEquals(1, requests.size());
    }

    /**
     * Refreshing a content zone flushes the event queue and re-enters, but is ignored while a
     * content block is on screen.
     */
    @Test
    public void refreshContentZone_flushesEventsAndReEnters() throws JSONException {
        initWithContent(TestUtils.getConfigContent());
        nextResponse = new JSONObject(CONTENT_RESPONSE);

        Countly.instance().content().enterContentZone();
        tick();
        Assert.assertEquals(1, display.presented.size());

        Countly.instance().content().refreshContentZone();
        tick();
        Assert.assertEquals(1, requests.size());

        display.lastCallback.onClosed(new HashMap<>());

        Countly.instance().events().recordEvent("trigger_event");
        Assert.assertEquals(1, eventQueueSize());

        Countly.instance().content().refreshContentZone();
        Assert.assertEquals(0, eventQueueSize());

        // Re-entering resets the post close wait, so the very next poll fetches.
        tick();
        Assert.assertEquals(2, requests.size());
        Assert.assertEquals(2, display.presented.size());
    }

    /**
     * Events a content block asks for are recorded with either segmentation key, entries without a
     * key are dropped, and the queue is pushed out so the server can act on them.
     */
    @Test
    public void recordContentEvents_recordsEveryUsableEntryAndFlushes() {
        initWithContent(TestUtils.getConfigContent());

        Countly.instance().content().recordContentEvents(
            "[{\"key\":\"[CLY]_content_shown\",\"sg\":{\"a\":\"1\"}},"
                + "{\"key\":\"with_segmentation\",\"segmentation\":{\"b\":2}},"
                + "{\"sg\":{\"c\":\"3\"}},"
                + "{\"key\":\"\"}]");

        Assert.assertEquals(0, eventQueueSize());
        List<EventImpl> events = TestUtils.readEventsFromRequest();
        Assert.assertEquals(2, events.size());
        Assert.assertEquals("[CLY]_content_shown", events.get(0).key);
        Assert.assertEquals("1", events.get(0).segmentation.get("a"));
        Assert.assertEquals("with_segmentation", events.get(1).key);
        Assert.assertEquals(2, events.get(1).segmentation.get("b"));

        // Nothing usable, nothing recorded, and no crash on malformed input.
        int requestCount = TestUtils.getCurrentRQ().length;
        Countly.instance().content().recordContentEvents("not json at all");
        Countly.instance().content().recordContentEvents("");
        Countly.instance().content().recordContentEvents(null);
        Assert.assertEquals(requestCount, TestUtils.getCurrentRQ().length);
    }

    /**
     * Switching users. A device ID change with merge is the same user, so the zone keeps polling. A
     * change without merge is a different user, so the zone is torn down and only a deliberate
     * enter brings it back.
     */
    @Test
    public void deviceIdChange_withoutMergeLeavesTheContentZone() throws JSONException {
        initWithContent(TestUtils.getConfigContent());
        nextResponse = new JSONObject(NO_CONTENT_RESPONSE);

        Countly.instance().content().enterContentZone();
        tick();
        Assert.assertEquals(1, requests.size());

        // Same user: the zone survives and keeps fetching, now under the new ID.
        Countly.instance().deviceId().changeWithMerge("merged_user");
        tick();
        Assert.assertEquals(2, requests.size());
        Assert.assertEquals("merged_user", requests.get(1).get("device_id"));

        // Different user: the zone is gone, so polling stops.
        Countly.instance().deviceId().changeWithoutMerge("other_user");
        tick();
        Assert.assertEquals(2, requests.size());

        // Only a deliberate enter brings it back, and it fetches for the new user.
        Countly.instance().content().setContentDisplay(display);
        Countly.instance().content().enterContentZone();
        tick();
        Assert.assertEquals(3, requests.size());
        Assert.assertEquals("other_user", requests.get(2).get("device_id"));
    }

    /**
     * "setID" changes the device ID without merge once an ID was already developer supplied, so it
     * leaves the content zone. Granting the consents again and entering again is what an application
     * runs on a login, and it has to end with a working zone.
     */
    @Test
    public void setID_leavesTheZoneAndSurvivesAConsentRegrant() throws JSONException {
        Config config = TestUtils.getConfigContent().setRequiresConsent(true);
        init(config);
        Countly.onConsent(Config.Feature.values());
        installRequestMaker();

        Countly.instance().content().setContentDisplay(display);
        Countly.instance().content().enterContentZone();
        nextResponse = new JSONObject(NO_CONTENT_RESPONSE);
        tick();
        Assert.assertEquals(1, requests.size());

        // The test config supplies a custom device ID, so setID changes it without merge.
        Countly.instance().deviceId().setID("logged_in_user");
        tick();
        Assert.assertEquals(1, requests.size());

        Countly.onConsent(Config.Feature.values());

        ModuleContent.Content content = Countly.instance().content();
        Assert.assertNotNull(content);
        content.setContentDisplay(display);
        content.enterContentZone();
        tick();

        Assert.assertEquals(2, requests.size());
        Assert.assertEquals("logged_in_user", requests.get(1).get("device_id"));
    }

    /**
     * The zone fetch interval only accepts sane values, so a mistyped configuration cannot turn the
     * zone into a busy loop.
     */
    @Test
    public void zoneTimerInterval_rejectsValuesBelowTheMinimum() {
        Config config = TestUtils.getConfigContent();

        config.content.setZoneTimerInterval(1);
        Assert.assertEquals(ConfigContent.DEFAULT_ZONE_TIMER_INTERVAL, config.content.zoneTimerInterval);

        config.content.setZoneTimerInterval(ConfigContent.MIN_ZONE_TIMER_INTERVAL - 1);
        Assert.assertEquals(ConfigContent.DEFAULT_ZONE_TIMER_INTERVAL, config.content.zoneTimerInterval);

        config.content.setZoneTimerInterval(60);
        Assert.assertEquals(60, config.content.zoneTimerInterval);
    }

    /**
     * The zone really is driven by its own timer, not only by the hand driven ticks the other tests
     * use.
     */
    @Test
    public void zoneTimer_drivesFetchesOnItsOwn() throws JSONException, InterruptedException {
        CountlyTimer.TIMER_DELAY_MS = 50;
        initWithContent(TestUtils.getConfigContent());
        nextResponse = new JSONObject(CONTENT_RESPONSE);
        requestLatch = new CountDownLatch(1);

        Countly.instance().content().enterContentZone();

        Assert.assertTrue("the zone timer never fetched", requestLatch.await(5, TimeUnit.SECONDS));
        Countly.instance().content().exitContentZone();
    }

    // endregion
    // region helpers

    private void init(Config config) {
        Countly.instance().init(config);
        installRequestMaker();
    }

    private void initWithContent(Config config) {
        init(config);
        Countly.instance().content().setContentDisplay(display);
    }

    private void installRequestMaker() {
        ImmediateRequestI requestMaker = (requestData, customEndpoint, cp, requestShouldBeDelayed, networkingIsEnabled, callback, log) -> {
            synchronized (requests) {
                requests.add(TestUtils.parseQueryParams(requestData));
                endpoints.add(customEndpoint);
            }
            if (requestLatch != null) {
                requestLatch.countDown();
            }
            callback.callback(nextResponse);
        };
        SDKCore.instance.config.immediateRequestGenerator = () -> requestMaker;
    }

    private void tick() {
        SDKCore.instance.module(ModuleContent.class).onZoneTimerTick();
    }

    private int eventQueueSize() {
        return SDKCore.instance.module(ModuleEvents.class).eventQueue.eqSize();
    }

    private static class FakeDisplay implements ContentDisplay {

        final List<ContentData> presented = new ArrayList<>();
        final AtomicBoolean closed = new AtomicBoolean(false);
        ContentCloseCallback lastCallback;
        boolean throwOnPresent = false;

        @Override
        public ContentScreen getScreen() {
            return new ContentScreen(1600, 900);
        }

        @Override
        public void present(ContentData content, ContentCloseCallback onClosed) {
            presented.add(content);
            lastCallback = data -> {
                closed.set(true);
                onClosed.onClosed(data);
            };
            if (throwOnPresent) {
                throw new IllegalStateException("this display cannot show anything");
            }
        }
    }

    // endregion
}
