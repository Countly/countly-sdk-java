package ly.count.sdk.java.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ModuleFeedbackTests {

    @After
    public void stop() {
        Countly.stop(true);
    }

    private void init(Config cc) {
        Countly.instance().init(cc);
    }

    /**
     * Parse feedback list response given null
     * "parseFeedbackList" function should return empty list
     * returned feedback widget list should be empty
     */
    @Test
    public void parseFeedbackList_null() throws JSONException {
        Config config = TestUtils.getBaseConfig();
        config.enableFeatures(Config.Feature.Feedback).setEventQueueSizeToSend(4);
        init(config);

        List<CountlyFeedbackWidget> result = ModuleFeedback.parseFeedbackList(null);
        Assert.assertNotNull(result);
        Assert.assertEquals(0, result.size());
    }

    /**
     * Parse feedback list successfully
     * "parseFeedbackList" function should return correct feedback widget list
     * returned feedback widget list should have same widgets as in the response
     */
    @Test
    public void parseFeedbackList() throws JSONException {
        Config config = TestUtils.getBaseConfig();
        config.enableFeatures(Config.Feature.Feedback).setEventQueueSizeToSend(4);
        init(config);

        String requestJson =
            "{\"result\":[{\"_id\":\"5f8c6f959627f99e8e7de746\",\"type\":\"survey\",\"exitPolicy\":\"onAbandon\",\"appearance\":{\"show\":\"uSubmit\",\"position\":\"bLeft\",\"color\":\"#2eb52b\"},\"name\":\"sdfsdfdsf\",\"tg\":[\"/\"]},{\"_id\":\"5f8c6fd81ac8659e8846acf4\",\"type\":\"nps\",\"name\":\"fdsfsd\",\"tg\":[\"a\",\"0\"]},{\"_id\":\"5f97284635935cc338e78200\",\"type\":\"nps\",\"name\":\"fsdfsdf\",\"tg\":[]},{\"_id\":\"614871419f030e44be07d82f\",\"type\":\"rating\",\"appearance\":{\"position\":\"mleft\",\"bg_color\":\"#fff\",\"text_color\":\"#ddd\",\"text\":\"Feedback\"},\"tg\":[\"\\/\"],\"name\":\"ratingName1\"}]}";

        JSONObject jObj = new JSONObject(requestJson);

        List<CountlyFeedbackWidget> ret = ModuleFeedback.parseFeedbackList(jObj);
        Assert.assertNotNull(ret);
        Assert.assertEquals(4, ret.size());

        ValidateReturnedFeedbackWidget(FeedbackWidgetType.survey, "sdfsdfdsf", "5f8c6f959627f99e8e7de746", new String[] { "/" }, ret.get(0));
        ValidateReturnedFeedbackWidget(FeedbackWidgetType.nps, "fdsfsd", "5f8c6fd81ac8659e8846acf4", new String[] { "a", "0" }, ret.get(1));
        ValidateReturnedFeedbackWidget(FeedbackWidgetType.nps, "fsdfsdf", "5f97284635935cc338e78200", new String[] {}, ret.get(2));
        ValidateReturnedFeedbackWidget(FeedbackWidgetType.rating, "ratingName1", "614871419f030e44be07d82f", new String[] { "/" }, ret.get(3));
    }

    /**
     * Parse feedback list successfully, remove garbage json
     * "parseFeedbackList" function should return correct json feedback widget list without garbage json
     * returned feedback widget list should not have garbage json
     */
    @Test
    public void parseFeedbackList_oneGoodWithGarbage() throws JSONException {
        Config config = TestUtils.getBaseConfig();
        config.enableFeatures(Config.Feature.Feedback).setEventQueueSizeToSend(4);
        init(config);

        String requestJson =
            "{\"result\":[{\"_id\":\"asd\",\"type\":\"qwe\",\"name\":\"zxc\",\"tg\":[]},{\"_id\":\"5f97284635935cc338e78200\",\"type\":\"nps\",\"name\":\"fsdfsdf\",\"tg\":[\"/\"]},{\"g4id\":\"asd1\",\"t4type\":\"432\",\"nagdfgme\":\"zxct\",\"tgm\":[\"/\"]}]}";

        JSONObject jObj = new JSONObject(requestJson);

        List<CountlyFeedbackWidget> ret = ModuleFeedback.parseFeedbackList(jObj);
        Assert.assertNotNull(ret);
        Assert.assertEquals(1, ret.size());
        ValidateReturnedFeedbackWidget(FeedbackWidgetType.nps, "fsdfsdf", "5f97284635935cc338e78200", new String[] { "/" }, ret.get(0));
    }

    /**
     * Parse feedback list successfully, remove faulty widgets
     * "parseFeedbackList" function should return correct feedback widget list without faulty widgets
     * returned feedback widget list should not have faulty widgets
     */
    @Test
    public void parseFeedbackList_faulty() throws JSONException {
        Config config = TestUtils.getBaseConfig();
        config.enableFeatures(Config.Feature.Feedback).setEventQueueSizeToSend(4);
        init(config);
        // 9 widgets (3 from each)
        // First variation => no 'tg' key
        // Second variation => no 'name' key
        // First variation => no '_id' key
        String requestJson =
            "{\"result\":["
                + "{\"_id\":\"survID1\",\"type\":\"survey\",\"exitPolicy\":\"onAbandon\",\"appearance\":{\"show\":\"uSubmit\",\"position\":\"bLeft\",\"color\":\"#2eb52b\"},\"name\":\"surv1\"},"
                + "{\"_id\":\"survID2\",\"type\":\"survey\",\"exitPolicy\":\"onAbandon\",\"appearance\":{\"show\":\"uSubmit\",\"position\":\"bLeft\",\"color\":\"#2eb52b\"},\"tg\":[\"/\"]},"
                + "{\"type\":\"survey\",\"exitPolicy\":\"onAbandon\",\"appearance\":{\"show\":\"uSubmit\",\"position\":\"bLeft\",\"color\":\"#2eb52b\"},\"name\":\"surv3\",\"tg\":[\"/\"]},"
                + "{\"_id\":\"npsID1\",\"type\":\"nps\",\"name\":\"nps1\"},"
                + "{\"_id\":\"npsID2\",\"type\":\"nps\",\"tg\":[]},"
                + "{\"type\":\"nps\",\"name\":\"nps3\",\"tg\":[]},"
                + "{\"_id\":\"ratingID1\",\"type\":\"rating\",\"appearance\":{\"position\":\"mleft\",\"bg_color\":\"#fff\",\"text_color\":\"#ddd\",\"text\":\"Feedback\"},\"name\":\"rating1\"},"
                + "{\"_id\":\"ratingID2\",\"type\":\"rating\",\"appearance\":{\"position\":\"mleft\",\"bg_color\":\"#fff\",\"text_color\":\"#ddd\",\"text\":\"Feedback\"},\"tg\":[\"\\/\"]},"
                + "{\"type\":\"rating\",\"appearance\":{\"position\":\"mleft\",\"bg_color\":\"#fff\",\"text_color\":\"#ddd\",\"text\":\"Feedback\"},\"tg\":[\"\\/\"],\"name\":\"rating3\"}"
                + "]}";

        JSONObject jObj = new JSONObject(requestJson);

        List<CountlyFeedbackWidget> ret = ModuleFeedback.parseFeedbackList(jObj);
        Assert.assertNotNull(ret);
        Assert.assertEquals(6, ret.size());

        ValidateReturnedFeedbackWidget(FeedbackWidgetType.survey, "surv1", "survID1", new String[] {}, ret.get(0));
        ValidateReturnedFeedbackWidget(FeedbackWidgetType.survey, "", "survID2", new String[] { "/" }, ret.get(1));
        ValidateReturnedFeedbackWidget(FeedbackWidgetType.nps, "nps1", "npsID1", new String[] {}, ret.get(2));
        ValidateReturnedFeedbackWidget(FeedbackWidgetType.nps, "", "npsID2", new String[] {}, ret.get(3));
        ValidateReturnedFeedbackWidget(FeedbackWidgetType.rating, "rating1", "ratingID1", new String[] {}, ret.get(4));
        ValidateReturnedFeedbackWidget(FeedbackWidgetType.rating, "", "ratingID2", new String[] { "/" }, ret.get(5));
    }

    /**
     * Getting feedback widget list successfully
     * "getAvailableFeedbackWidgets" function should return correct feedback widget list
     * returned feedback widget list should be equal to the expected feedback widget list
     */
    @Test
    public void getAvailableFeedbackWidgets() {
        Config config = TestUtils.getBaseConfig();
        config.enableFeatures(Config.Feature.Feedback).setEventQueueSizeToSend(4);
        init(config);

        List<CountlyFeedbackWidget> widgets = new ArrayList<>();
        widgets.add(createFeedbackWidget(FeedbackWidgetType.nps, "nps1", "npsID1", new String[] {}));
        widgets.add(createFeedbackWidget(FeedbackWidgetType.rating, "rating1", "ratingID1", new String[] {}));
        widgets.add(createFeedbackWidget(FeedbackWidgetType.survey, "surv1", "survID1", new String[] {}));

        JSONArray widgetsJson = new JSONArray();
        widgetsJson.put(createFeedbackWidgetJson(widgets.get(0)));
        widgetsJson.put(createFeedbackWidgetJson(widgets.get(1)));
        widgetsJson.put(createFeedbackWidgetJson(widgets.get(2)));
        JSONObject responseJson = new JSONObject();
        responseJson.put("result", widgetsJson);

        ImmediateRequestI requestMaker = (requestData, customEndpoint, cp, requestShouldBeDelayed, networkingIsEnabled, callback, log) -> {
            Assert.assertEquals("/o/sdk", customEndpoint);
            Map<String, String> params = TestUtils.parseQueryParams(requestData);
            Assert.assertEquals("feedback", params.get("method"));
            Assert.assertFalse(requestShouldBeDelayed);
            Assert.assertTrue(networkingIsEnabled);
            validateRequiredParams(params);
            callback.callback(responseJson);
        };
        SDKCore.instance.config.immediateRequestGenerator = () -> requestMaker;

        List<CountlyFeedbackWidget> widgetResponse = new ArrayList<>();
        Countly.instance().feedback().getAvailableFeedbackWidgets((response, error) -> {
            Assert.assertNull(error);
            Assert.assertEquals(3, response.size());
            widgetResponse.addAll(response);
        });

        widgetResponse.sort(Comparator.comparing(o -> o.widgetId));
        Assert.assertEquals(3, widgetResponse.size());
        Assert.assertEquals(widgetResponse, widgets);
    }

    /**
     * Getting feedback widget list with garbage json
     * "getAvailableFeedbackWidgets" function should return empty feedback widget list because json is garbage
     * returned feedback widget list should be empty
     */
    @Test
    public void getAvailableFeedbackWidgets_garbageJson() {
        Config config = TestUtils.getBaseConfig();
        config.enableFeatures(Config.Feature.Feedback).setEventQueueSizeToSend(4);
        init(config);

        JSONArray garbageArray = new JSONArray();
        garbageArray.put(createGarbageJson());
        garbageArray.put(createGarbageJson());
        JSONObject responseJson = new JSONObject();
        responseJson.put("result", garbageArray);

        ImmediateRequestI requestMaker = (requestData, customEndpoint, cp, requestShouldBeDelayed, networkingIsEnabled, callback, log) -> {
            callback.callback(responseJson);
        };
        SDKCore.instance.config.immediateRequestGenerator = () -> requestMaker;

        Countly.instance().feedback().getAvailableFeedbackWidgets((response, error) -> {
            Assert.assertNull(error);
            Assert.assertEquals(0, response.size());
        });
    }

    /**
     * Getting feedback widget list errored
     * "getAvailableFeedbackWidgets" function should return error message
     * returned feedback widget list should be empty and error message should not be empty
     */
    @Test
    public void getAvailableFeedbackWidgets_null() {
        Config config = TestUtils.getBaseConfig();
        config.enableFeatures(Config.Feature.Feedback).setEventQueueSizeToSend(4);
        init(config);

        ImmediateRequestI requestMaker = (requestData, customEndpoint, cp, requestShouldBeDelayed, networkingIsEnabled, callback, log) ->
            callback.callback(null);

        SDKCore.instance.config.immediateRequestGenerator = () -> requestMaker;

        Countly.instance().feedback().getAvailableFeedbackWidgets((response, error) -> {
            Assert.assertNotNull(error);
            Assert.assertNull(response);
            Assert.assertEquals("Not possible to retrieve widget list. Probably due to lack of connection to the server", error);
        });
    }

    private void validateRequiredParams(Map<String, String> params) {
        int hour = Integer.parseInt(params.get("hour"));
        int dow = Integer.parseInt(params.get("dow"));
        int tz = Integer.parseInt(params.get("tz"));

        Assert.assertEquals(SDKCore.instance.config.getSdkVersion(), params.get("sdk_version"));
        Assert.assertEquals(SDKCore.instance.config.getDeviceId().id, params.get("device_id"));
        Assert.assertEquals(SDKCore.instance.config.getSdkName(), params.get("sdk_name"));
        Assert.assertEquals(SDKCore.instance.config.getServerAppKey(), params.get("app_key"));
        Assert.assertTrue(Long.valueOf(params.get("timestamp")) > 0);
        Assert.assertTrue(hour > 0 && hour < 24);
        Assert.assertTrue(dow >= 0 && dow < 7);
        Assert.assertTrue(tz >= -720 && tz <= 840);
    }

    private CountlyFeedbackWidget createFeedbackWidget(FeedbackWidgetType type, String name, String id, String[] tags) {
        CountlyFeedbackWidget widget = new CountlyFeedbackWidget();
        widget.type = type;
        widget.name = name;
        widget.widgetId = id;
        widget.tags = tags;
        return widget;
    }

    private JSONObject createGarbageJson() {
        JSONObject garbageJson = new JSONObject();
        garbageJson.put("garbage", "garbage");
        garbageJson.put("_no_meaning", true);
        garbageJson.put("_no_tear", 123);

        return garbageJson;
    }

    private JSONObject createFeedbackWidgetJson(CountlyFeedbackWidget widget) throws JSONException {
        JSONObject widgetJson = new JSONObject();
        widgetJson.put("_id", widget.widgetId);
        widgetJson.put("type", widget.type.toString());
        widgetJson.put("name", widget.name);
        widgetJson.put("tg", widget.tags);
        return widgetJson;
    }

    private void ValidateReturnedFeedbackWidget(FeedbackWidgetType type, String wName, String wId, String[] wTags, CountlyFeedbackWidget fWidget) {
        Assert.assertEquals(type, fWidget.type);
        Assert.assertEquals(wName, fWidget.name);
        Assert.assertEquals(wId, fWidget.widgetId);
        Assert.assertArrayEquals(wTags, fWidget.tags);
    }
}
