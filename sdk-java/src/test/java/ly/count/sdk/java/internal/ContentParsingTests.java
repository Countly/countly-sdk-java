package ly.count.sdk.java.internal;

import java.util.Map;
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

import static org.mockito.Mockito.mock;

/**
 * The pure parsing and URL building around the content and feedback widget web views.
 */
@RunWith(JUnit4.class)
public class ContentParsingTests {

    private final Log L = mock(Log.class);

    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
    }

    @After
    public void stop() {
        Countly.instance().halt();
    }

    /**
     * Every shape of a {@code /o/sdk/content} response: a usable block, a block with only one
     * orientation, and the several ways a response can carry nothing to show.
     */
    @Test
    public void contentParser_acceptsUsableBlocksAndRejectsTheRest() throws JSONException {
        ContentData both = ContentParser.parse(new JSONObject(
            "{\"html\":\"https://a.b/c\",\"geo\":{\"p\":{\"x\":1,\"y\":2,\"w\":3,\"h\":4},\"l\":{\"x\":5,\"y\":6,\"w\":7,\"h\":8}}}"), L);
        Assert.assertNotNull(both);
        Assert.assertEquals("https://a.b/c", both.url);
        Assert.assertEquals(1, both.portrait.x);
        Assert.assertEquals(8, both.landscape.height);
        Assert.assertEquals(both.landscape, both.placementFor(true));
        Assert.assertEquals(both.portrait, both.placementFor(false));

        // Only one orientation: both shapes have to fall back to it.
        ContentData portraitOnly = ContentParser.parse(new JSONObject(
            "{\"html\":\"https://a.b/c\",\"geo\":{\"p\":{\"x\":1,\"y\":2,\"w\":3,\"h\":4}}}"), L);
        Assert.assertNotNull(portraitOnly);
        Assert.assertEquals(portraitOnly.portrait, portraitOnly.placementFor(true));

        // Missing coordinates default to zero rather than failing the whole block.
        ContentData partial = ContentParser.parse(new JSONObject("{\"html\":\"https://a.b/c\",\"geo\":{\"p\":{\"w\":3}}}"), L);
        Assert.assertNotNull(partial);
        Assert.assertEquals(0, partial.portrait.x);
        Assert.assertEquals(3, partial.portrait.width);

        Assert.assertNull(ContentParser.parse(null, L));
        Assert.assertNull(ContentParser.parse(new JSONObject("{\"jsonArray\":[{\"result\":\"No content block found!\"}]}"), L));
        Assert.assertNull(ContentParser.parse(new JSONObject("{\"html\":\"https://a.b/c\"}"), L));
        Assert.assertNull(ContentParser.parse(new JSONObject("{\"geo\":{\"p\":{\"x\":1,\"y\":2,\"w\":3,\"h\":4}}}"), L));
        Assert.assertNull(ContentParser.parse(new JSONObject("{\"html\":\"\",\"geo\":{\"p\":{\"x\":1,\"y\":2,\"w\":3,\"h\":4}}}"), L));
        Assert.assertNull(ContentParser.parse(new JSONObject("{\"html\":\"https://a.b/c\",\"geo\":{}}"), L));
    }

    /**
     * The signalling URLs a content block navigates to: an event payload, a resize request, a link
     * with the close flag hidden in its own query, and an external link.
     */
    @Test
    public void widgetActionParser_readsEveryContentSignal() {
        WidgetAction event = WidgetActionParser.parse(
            "https://countly_action_event/?cly_x_action_event=1&action=event"
                + "&event=%5B%7B%22key%22%3A%22ev1%22%7D%5D&close=0", L);
        Assert.assertTrue(event.isSdkSignal);
        Assert.assertTrue(event.isActionEvent);
        Assert.assertFalse(event.close);
        Assert.assertEquals("[{\"key\":\"ev1\"}]", event.eventPayload);
        Assert.assertEquals("1", event.queryParams.get("cly_x_action_event"));

        WidgetAction resize = WidgetActionParser.parse(
            "https://countly_action_event/?cly_x_action_event=1&action=resize_me"
                + "&resize_me=%7B%22p%22%3A%7B%22x%22%3A1%2C%22y%22%3A2%2C%22w%22%3A3%2C%22h%22%3A4%7D%2C"
                + "%22l%22%3A%7B%22x%22%3A5%2C%22y%22%3A6%2C%22w%22%3A7%2C%22h%22%3A8%7D%7D&close=1", L);
        Assert.assertTrue(resize.hasResize);
        Assert.assertTrue(resize.close);
        Assert.assertEquals(3, resize.resizeFor(false).width);
        Assert.assertEquals(7, resize.resizeFor(true).width);

        // A rectangle without a positive size is not usable.
        WidgetAction emptyResize = WidgetActionParser.parse(
            "https://countly_action_event/?cly_x_action_event=1&resize_me=%7B%22p%22%3A%7B%22w%22%3A0%2C%22h%22%3A0%7D%7D", L);
        Assert.assertFalse(emptyResize.hasResize);
        Assert.assertNull(emptyResize.resizeFor(false));

        WidgetAction malformedResize = WidgetActionParser.parse(
            "https://countly_action_event/?cly_x_action_event=1&resize_me=not-json", L);
        Assert.assertTrue(malformedResize.isSdkSignal);
        Assert.assertFalse(malformedResize.hasResize);

        // The close flag inside the destination's own query belongs to us, not to the destination.
        WidgetAction link = WidgetActionParser.parse(
            "https://countly_action_event/?cly_x_action_event=1&action=link"
                + "&link=https%3A%2F%2Fcount.ly%3Fa%3D1%26close%3D1", L);
        Assert.assertTrue(link.close);
        Assert.assertEquals("https://count.ly?a=1", link.link);

        WidgetAction external = WidgetActionParser.parse("https://count.ly/pricing?cly_x_int=1", L);
        Assert.assertTrue(external.isSdkSignal);
        Assert.assertTrue(external.isExternalLink);
        Assert.assertEquals("https://count.ly/pricing?cly_x_int=1", external.link);

        WidgetAction widgetClose = WidgetActionParser.parse("https://countly_action_event/?cly_widget_command=1&close=1", L);
        Assert.assertTrue(widgetClose.isWidgetCommand);
        Assert.assertTrue(widgetClose.close);

        // A plain page navigation is not a signal and must be left alone.
        WidgetAction plain = WidgetActionParser.parse("https://test.server.com/feedback/nps?widget_id=1", L);
        Assert.assertFalse(plain.isSdkSignal);
        Assert.assertFalse(plain.close);

        Assert.assertFalse(WidgetActionParser.parse(null, L).isSdkSignal);
        Assert.assertFalse(WidgetActionParser.parse("", L).isSdkSignal);
    }

    /**
     * Dropping a query parameter has to leave a valid URL behind, whatever position it was in.
     */
    @Test
    public void widgetActionParser_stripsOneParameterAtATime() {
        Assert.assertEquals("https://a.b/c", WidgetActionParser.stripParam("https://a.b/c?close=1", "close"));
        Assert.assertEquals("https://a.b/c?x=1", WidgetActionParser.stripParam("https://a.b/c?close=1&x=1", "close"));
        Assert.assertEquals("https://a.b/c?x=1", WidgetActionParser.stripParam("https://a.b/c?x=1&close=1", "close"));
        Assert.assertEquals("https://a.b/c?x=1&y=2", WidgetActionParser.stripParam("https://a.b/c?x=1&close=1&y=2", "close"));
        Assert.assertEquals("https://a.b/c", WidgetActionParser.stripParam("https://a.b/c", "close"));

        Map<String, Object> query = WidgetActionParser.parseQuery("https://a.b/c?x=1&broken&y=%7B%22a%22%3A1%7D");
        Assert.assertEquals(2, query.size());
        Assert.assertEquals("1", query.get("x"));
        Assert.assertEquals("{\"a\":1}", query.get("y"));
    }

    /**
     * The feedback widget display URL carries everything a desktop web view needs: the widget
     * identity, the SDK identity, the card rendering flags and the page origin.
     */
    @Test
    public void widgetUrlBuilder_buildsADesktopReadyUrl() {
        Countly.instance().init(TestUtils.getConfigFeedback());

        CountlyFeedbackWidget widget = new CountlyFeedbackWidget();
        widget.widgetId = "widget_1";
        widget.type = FeedbackWidgetType.nps;

        String url = Countly.instance().feedback().constructFeedbackWidgetUrl(widget);
        Assert.assertTrue(url.startsWith(TestUtils.SERVER_URL + "/feedback/nps?"));

        Map<String, String> params = TestUtils.parseQueryParams(url.substring(url.indexOf('?') + 1));
        Assert.assertEquals("widget_1", params.get("widget_id"));
        Assert.assertEquals(TestUtils.DEVICE_ID, params.get("device_id"));
        Assert.assertEquals(TestUtils.SERVER_APP_KEY, params.get("app_key"));
        Assert.assertEquals(WidgetUrlBuilder.CUSTOM_PARAMS, Utils.urldecode(params.get("custom")));
        Assert.assertEquals(TestUtils.SERVER_URL, params.get("origin"));
        Assert.assertFalse(params.get("sdk_name").isEmpty());
        Assert.assertFalse(params.get("sdk_version").isEmpty());
    }

    /**
     * The origin of a server URL, with and without an explicit port.
     */
    @Test
    public void widgetUrlBuilder_readsTheOrigin() throws Exception {
        Assert.assertEquals("https://try.count.ly", WidgetUrlBuilder.originOf(new java.net.URL("https://try.count.ly")));
        Assert.assertEquals("http://localhost:3001", WidgetUrlBuilder.originOf(new java.net.URL("http://localhost:3001/path")));
        Assert.assertNull(WidgetUrlBuilder.originOf(null));
    }

    /**
     * The content fetch parameters, including the category filter and the preview flags.
     */
    @Test
    public void contentRequestBuilder_buildsTheFetchParameters() {
        Params plain = ContentRequestBuilder.build(new ContentScreen(800, 600), null, null, L);
        Map<String, String> params = TestUtils.parseQueryParams(plain.toString());
        Assert.assertEquals("queue", params.get("method"));
        Assert.assertEquals(ContentRequestBuilder.DEVICE_TYPE, params.get("dt"));
        Assert.assertEquals("[]", Utils.urldecode(params.get("category")));
        Assert.assertEquals("{\"l\":{\"w\":800,\"h\":600},\"p\":{\"w\":800,\"h\":600}}", Utils.urldecode(params.get("resolution")));

        Params filtered = ContentRequestBuilder.build(new ContentScreen(800, 600), new String[] { "promo", "news" }, "block_1", L);
        Map<String, String> filteredParams = TestUtils.parseQueryParams(filtered.toString());
        Assert.assertEquals("[promo, news]", Utils.urldecode(filteredParams.get("category")));
        Assert.assertEquals("block_1", filteredParams.get("content_id"));
        Assert.assertEquals("true", filteredParams.get("preview"));

        // A missing screen must not throw; it simply reports nothing to fit into.
        Params noScreen = ContentRequestBuilder.build(null, null, null, L);
        Assert.assertEquals("{\"l\":{\"w\":0,\"h\":0},\"p\":{\"w\":0,\"h\":0}}",
            Utils.urldecode(TestUtils.parseQueryParams(noScreen.toString()).get("resolution")));
    }

    /**
     * The content feature has its own consent bit, and it has to survive the round trip through
     * {@link Config.Feature#byIndex(int)} the consent plumbing relies on.
     */
    @Test
    public void contentFeature_isWiredIntoTheFeatureBitmask() {
        Assert.assertEquals(CoreFeature.Content.getIndex(), Config.Feature.Content.getIndex());
        Assert.assertEquals(Config.Feature.Content, Config.Feature.byIndex(CoreFeature.Content.getIndex()));
        Assert.assertNotNull(CoreFeature.Content.getCreator());
    }
}
