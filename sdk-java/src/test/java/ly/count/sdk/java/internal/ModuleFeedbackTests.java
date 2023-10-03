package ly.count.sdk.java.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import ly.count.sdk.java.Config;
import ly.count.sdk.java.Countly;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static ly.count.sdk.java.internal.TestUtils.getOs;
import static ly.count.sdk.java.internal.TestUtils.validateEvent;
import static ly.count.sdk.java.internal.TestUtils.validateEventQueueSize;
import static org.mockito.Mockito.mock;

@RunWith(JUnit4.class)
public class ModuleFeedbackTests {

    Log L = mock(Log.class);

    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
    }

    @After
    public void stop() {
        Countly.instance().halt();
    }

    private void init(Config cc) {
        Countly.instance().init(cc);
    }

    /**
     * "parseFeedbackList"
     * receives a "null" response to parse
     * returned feedback widget list should be empty
     */
    @Test
    public void parseFeedbackList_null() throws JSONException {
        init(TestUtils.getConfigFeedback());

        List<CountlyFeedbackWidget> result = ModuleFeedback.parseFeedbackList(null);
        Assert.assertNotNull(result);
        Assert.assertEquals(0, result.size());
    }

    /**
     * "parseFeedbackList"
     * Correct response is given with all widget types
     * returned feedback widget list should have same widgets as in the response
     */
    @Test
    public void parseFeedbackList() throws JSONException {
        init(TestUtils.getConfigFeedback());

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
     * "parseFeedbackList"
     * Response with a correct entry and bad entries is given
     * The correct entry should be correctly parsed and the bad ones should be ignored
     */
    //todo: can this test be combined with the next one?
    @Test
    public void parseFeedbackList_oneGoodWithGarbage() throws JSONException {
        init(TestUtils.getConfigFeedback());

        String requestJson =
            "{\"result\":[{\"_id\":\"asd\",\"type\":\"qwe\",\"name\":\"zxc\",\"tg\":[]},{\"_id\":\"5f97284635935cc338e78200\",\"type\":\"nps\",\"name\":\"fsdfsdf\",\"tg\":[\"/\"]},{\"g4id\":\"asd1\",\"t4type\":\"432\",\"nagdfgme\":\"zxct\",\"tgm\":[\"/\"]}]}";

        JSONObject jObj = new JSONObject(requestJson);

        List<CountlyFeedbackWidget> ret = ModuleFeedback.parseFeedbackList(jObj);
        Assert.assertNotNull(ret);
        Assert.assertEquals(1, ret.size());
        ValidateReturnedFeedbackWidget(FeedbackWidgetType.nps, "fsdfsdf", "5f97284635935cc338e78200", new String[] { "/" }, ret.get(0));
    }

    /**
     * "parseFeedbackList"
     * Response with partial entries given
     * Only the entries with all important fields given should be returned
     */
    @Test
    public void parseFeedbackList_faulty() throws JSONException {
        init(TestUtils.getConfigFeedback());
        // 9 widgets (3 from each)
        // First variation => no 'tg' key
        // Second variation => no 'name' key
        // First variation => no '_id' key
        String requestJson =
            "{\"result\":["
                + "{\"_id\":\"survID1\",\"type\":\"survey\",\"exitPolicy\":\"onAbandon\",\"appearance\":{\"show\":\"uSubmit\",\"position\":\"bLeft\",\"color\":\"#2eb52b\"},\"name\":\"surv1\",\"tg\":[\"aaa\"]},"
                + "{\"_id\":\"survID2\",\"type\":\"survey\",\"exitPolicy\":\"onAbandon\",\"appearance\":{\"show\":\"uSubmit\",\"position\":\"bLeft\",\"color\":\"#2eb52b\"},\"tg\":[\"/\"]},"
                + "{\"type\":\"survey\",\"exitPolicy\":\"onAbandon\",\"appearance\":{\"show\":\"uSubmit\",\"position\":\"bLeft\",\"color\":\"#2eb52b\"},\"name\":\"surv3\",\"tg\":[\"/\"]},"
                + "{\"_id\":\"npsID1\",\"type\":\"nps\",\"name\":\"nps1\",\"tg\":[\"bbb\", \"123\"]},"
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

        ValidateReturnedFeedbackWidget(FeedbackWidgetType.survey, "surv1", "survID1", new String[] { "aaa" }, ret.get(0));
        ValidateReturnedFeedbackWidget(FeedbackWidgetType.survey, "", "survID2", new String[] { "/" }, ret.get(1));
        ValidateReturnedFeedbackWidget(FeedbackWidgetType.nps, "nps1", "npsID1", new String[] { "bbb", "123" }, ret.get(2));
        ValidateReturnedFeedbackWidget(FeedbackWidgetType.nps, "", "npsID2", new String[] {}, ret.get(3));
        ValidateReturnedFeedbackWidget(FeedbackWidgetType.rating, "rating1", "ratingID1", new String[] {}, ret.get(4));
        ValidateReturnedFeedbackWidget(FeedbackWidgetType.rating, "", "ratingID2", new String[] { "/" }, ret.get(5));
    }

    /**
     * "getAvailableFeedbackWidgets" with mocked server response
     * server responds with a correct response with all widget type
     * All returned widgets should match the received ones
     */
    @Test
    public void getAvailableFeedbackWidgets_properResponse() {
        List<CountlyFeedbackWidget> widgets = new ArrayList<>();
        widgets.add(createFeedbackWidget(FeedbackWidgetType.nps, "nps1", "npsID1", new String[] { "123", "89" }));
        widgets.add(createFeedbackWidget(FeedbackWidgetType.rating, "rating1", "ratingID1", new String[] { "vbn" }));
        widgets.add(createFeedbackWidget(FeedbackWidgetType.survey, "surv1", "survID1", new String[] {}));

        JSONArray widgetsJson = new JSONArray();
        widgetsJson.put(createFeedbackWidgetJson(widgets.get(0)));
        widgetsJson.put(createFeedbackWidgetJson(widgets.get(1)));
        widgetsJson.put(createFeedbackWidgetJson(widgets.get(2)));
        JSONObject responseJson = new JSONObject();
        responseJson.put("result", widgetsJson);

        getAvailableFeedbackWidgets_base(widgets, responseJson);
    }

    /**
     * "getAvailableFeedbackWidgets" with mocked server response
     * server responds with a response that on the surface looks as expected (JSON with "result") but inside it has garbage JSON
     * returned feedback widget list should be empty
     */
    @Test
    public void getAvailableFeedbackWidgets_garbageJsonInCorrectStructure() {
        JSONArray garbageArray = new JSONArray();
        garbageArray.put(createGarbageJson());
        garbageArray.put(createGarbageJson());
        JSONObject responseJson = new JSONObject();
        responseJson.put("result", garbageArray);

        getAvailableFeedbackWidgets_base(new ArrayList<>(), responseJson);
    }

    /**
     * "getAvailableFeedbackWidgets" with mocked server response
     * server responds with a response that is a JSON but with the wrong root field, and inside it has garbage JSON
     * returned feedback widget list should be empty
     */
    @Test
    public void getAvailableFeedbackWidgets_garbageJsonInWrongStructure() {
        JSONArray garbageArray = new JSONArray();
        garbageArray.put(createGarbageJson());
        garbageArray.put(createGarbageJson());
        JSONObject responseJson = new JSONObject();
        responseJson.put("xxxx", garbageArray);

        getAvailableFeedbackWidgets_base(new ArrayList<>(), responseJson);
    }

    /**
     * "getAvailableFeedbackWidgets" with mocked server response
     * IRM failed to parse response and therefore returns "null"
     * returned feedback widget list should be empty and error message should contain the expected text
     */
    @Test
    public void getAvailableFeedbackWidgets_null() {
        getAvailableFeedbackWidgets_base(null, null);
    }

    public void getAvailableFeedbackWidgets_base(List<CountlyFeedbackWidget> expectedWidgets, JSONObject returnedResponse) {
        init(TestUtils.getConfigFeedback());

        ImmediateRequestI requestMaker = (requestData, customEndpoint, cp, requestShouldBeDelayed, networkingIsEnabled, callback, log) -> {
            Map<String, String> params = TestUtils.parseQueryParams(requestData);
            Assert.assertEquals("feedback", params.get("method"));
            validateWidgetRequiredParams("/o/sdk", customEndpoint, requestShouldBeDelayed, networkingIsEnabled);
            TestUtils.validateRequiredParams(params);
            callback.callback(returnedResponse);
        };
        SDKCore.instance.config.immediateRequestGenerator = () -> requestMaker;

        Countly.instance().feedback().getAvailableFeedbackWidgets((response, error) -> {
            if (expectedWidgets != null) {
                Assert.assertNull(error);
                Assert.assertEquals(expectedWidgets.size(), response.size());

                List<CountlyFeedbackWidget> widgetResponse = new ArrayList<>(response);

                widgetResponse.sort(Comparator.comparing(o -> o.widgetId));
                Assert.assertEquals(widgetResponse, expectedWidgets);
            } else {
                Assert.assertNull(response);
                Assert.assertEquals("Not possible to retrieve widget list. Probably due to lack of connection to the server", error);
            }
        });
    }

    /**
     * "constructFeedbackWidgetUrl"
     * We are passing a valid "CountlyFeedbackWidget" structure
     * A correct URL should be produced
     */
    @Test
    public void constructFeedbackWidgetUrl() {
        init(TestUtils.getConfigFeedback());
        CountlyFeedbackWidget widgetInfo = createFeedbackWidget(FeedbackWidgetType.nps, "nps1", "npsID1", new String[] {});

        StringBuilder widgetListUrl = new StringBuilder();
        widgetListUrl.append(TestUtils.SERVER_URL);
        widgetListUrl.append("/feedback/");
        widgetListUrl.append(widgetInfo.type.name());
        widgetListUrl.append("?widget_id=");
        widgetListUrl.append(Utils.urlencode(widgetInfo.widgetId, L));
        widgetListUrl.append("&device_id=");
        widgetListUrl.append(Utils.urlencode(TestUtils.DEVICE_ID, L));
        widgetListUrl.append("&app_key=");
        widgetListUrl.append(Utils.urlencode(TestUtils.SERVER_APP_KEY, L));
        widgetListUrl.append("&sdk_version=");
        widgetListUrl.append(TestUtils.SDK_VERSION);
        widgetListUrl.append("&sdk_name=");
        widgetListUrl.append(TestUtils.SDK_NAME);
        widgetListUrl.append("&platform=");
        widgetListUrl.append(Utils.urlencode(getOs(), L));

        Assert.assertEquals(widgetListUrl.toString(), Countly.instance().feedback().constructFeedbackWidgetUrl(widgetInfo));
    }

    /**
     * "constructFeedbackWidgetUrl"
     * We pass a "null" widgetInfo
     * Response should be "null" as that is bad input
     */
    @Test
    public void constructFeedbackWidgetUrl_nullWidgetInfo() {
        init(TestUtils.getConfigFeedback());
        Assert.assertNull(Countly.instance().feedback().constructFeedbackWidgetUrl(null));
    }

    /**
     * "constructFeedbackWidgetUrl"
     * We pass a default widgetInfo, all fields would be "null"
     * Response should be "null" as that is bad input
     */
    @Test
    public void constructFeedbackWidgetUrl_defaultWidgetInfo() {
        init(TestUtils.getConfigFeedback());
        CountlyFeedbackWidget widgetInfo = new CountlyFeedbackWidget();
        Assert.assertNull(Countly.instance().feedback().constructFeedbackWidgetUrl(widgetInfo));
    }

    /**
     * "constructFeedbackWidgetUrl"
     * We pass a default widgetInfo, all fields would be "null"
     * Response should be "null" as that is bad input
     */
    @Test
    public void constructFeedbackWidgetUrl_emptyWidgetInfo() {
        init(TestUtils.getConfigFeedback());
        CountlyFeedbackWidget widgetInfo = new CountlyFeedbackWidget();
        widgetInfo.widgetId = "";
        widgetInfo.name = "";
        widgetInfo.type = FeedbackWidgetType.nps;
        widgetInfo.tags = new String[] {};
        Assert.assertNull(Countly.instance().feedback().constructFeedbackWidgetUrl(widgetInfo));
    }

    /**
     * Get feedback widget data with null widget info
     * "getFeedbackWidgetData" function should not return widget data and return error message
     * data should be null and error message should same as expected
     */
    @Test
    public void getFeedbackWidgetData_nullWidgetInfo() {
        init(TestUtils.getConfigFeedback());

        Countly.instance().feedback().getFeedbackWidgetData(null, (response, error) -> {
            Assert.assertNull(response);
            Assert.assertEquals("[ModuleFeedback] getFeedbackWidgetDataInternal, Can't continue operation with null widget", error);
        });
    }

    /**
     * Get feedback widget data
     * "getFeedbackWidgetData" function should return widget data related to it
     * data should fill with correct data
     */
    @Test
    public void getFeedbackWidgetData() {
        init(TestUtils.getConfigFeedback());

        JSONObject result = new JSONObject();
        result.put("result", TestUtils.feedbackWidgetData);

        CountlyFeedbackWidget widgetInfo = createFeedbackWidget(FeedbackWidgetType.nps, "nps1", "npsID1", new String[] { "hgj" });

        ImmediateRequestI requestMaker = (requestData, customEndpoint, cp, requestShouldBeDelayed, networkingIsEnabled, callback, log) -> {
            validateWidgetRequiredParams("/o/surveys/" + widgetInfo.type.name() + "/widget", customEndpoint, requestShouldBeDelayed, networkingIsEnabled);
            validateWidgetDataParams(TestUtils.parseQueryParams(requestData), widgetInfo);
            callback.callback(result);
        };
        SDKCore.instance.config.immediateRequestGenerator = () -> requestMaker;

        Countly.instance().feedback().getFeedbackWidgetData(widgetInfo, (response, error) -> {
            Assert.assertNull(error);
            Assert.assertEquals(TestUtils.feedbackWidgetData, response.get("result"));
        });
    }

    /**
     * Get feedback widget data network error
     * "getFeedbackWidgetData" function should return null widget data and error message
     * data should be null and error message should be same as expected
     */
    @Test
    public void getFeedbackWidgetData_nullResponse() {
        init(TestUtils.getConfigFeedback());

        CountlyFeedbackWidget widgetInfo = createFeedbackWidget(FeedbackWidgetType.nps, "nps1", "npsID1", new String[] { "jyg", "jhg" });

        ImmediateRequestI requestMaker = (requestData, customEndpoint, cp, requestShouldBeDelayed, networkingIsEnabled, callback, log) -> {
            validateWidgetRequiredParams("/o/surveys/" + widgetInfo.type.name() + "/widget", customEndpoint, requestShouldBeDelayed, networkingIsEnabled);
            validateWidgetDataParams(TestUtils.parseQueryParams(requestData), widgetInfo);
            callback.callback(null);
        };
        SDKCore.instance.config.immediateRequestGenerator = () -> requestMaker;

        Countly.instance().feedback().getFeedbackWidgetData(widgetInfo, (response, error) -> {
            Assert.assertEquals("Not possible to retrieve widget data. Probably due to lack of connection to the server", error);
            Assert.assertNull(response);
        });
    }

    /**
     * Get feedback widget data successfully
     * "getFeedbackWidgetData" function should return widget data and error message should be null
     * data should be same as expected and error message should null
     */
    @Test
    public void getFeedbackWidgetData_garbageResult() {
        init(TestUtils.getConfigFeedback());

        JSONObject responseJson = new JSONObject();
        responseJson.put("result", "Success");

        CountlyFeedbackWidget widgetInfo = createFeedbackWidget(FeedbackWidgetType.nps, "nps1", "npsID1", new String[] { "yh" });

        ImmediateRequestI requestMaker = (requestData, customEndpoint, cp, requestShouldBeDelayed, networkingIsEnabled, callback, log) -> {
            validateWidgetRequiredParams("/o/surveys/" + widgetInfo.type.name() + "/widget", customEndpoint, requestShouldBeDelayed, networkingIsEnabled);
            validateWidgetDataParams(TestUtils.parseQueryParams(requestData), widgetInfo);
            callback.callback(responseJson);
        };
        SDKCore.instance.config.immediateRequestGenerator = () -> requestMaker;

        Countly.instance().feedback().getFeedbackWidgetData(widgetInfo, (response, error) -> {
            Assert.assertNull(error);
            Assert.assertEquals(responseJson, response);
        });
    }

    /**
     * Report feedback widget manually with null widget info
     * "reportFeedbackWidgetManually" function should not record widget as an event,
     * event queue should be empty
     */
    @Test
    public void reportFeedbackWidgetManually_nullWidgetInfo() {
        init(TestUtils.getConfigFeedback());

        Countly.instance().feedback().reportFeedbackWidgetManually(null, null, null);
        List<EventImpl> events = TestUtils.getCurrentEventQueue();
        Assert.assertEquals(0, events.size());
    }

    /**
     * Report feedback widget manually with null widgetData and widgetResult
     * "reportFeedbackWidgetManually" function should record widget as an event,
     * event queue should contain it and it should have correct segmentation
     */
    @Test
    public void reportFeedbackWidgetManually() {
        init(TestUtils.getConfigFeedback(Config.Feature.Events));

        CountlyFeedbackWidget widgetInfo = createFeedbackWidget(FeedbackWidgetType.nps, "nps1", "npsID1", new String[] { "kjh" });
        validateEventQueueSize(0, moduleEvents().eventQueue);
        Countly.instance().feedback().reportFeedbackWidgetManually(widgetInfo, null, null);
        validateEventQueueSize(1, moduleEvents().eventQueue);

        feedbackValidateManualResultEQ(FeedbackWidgetType.nps.eventKey, widgetInfo.widgetId, null, 0);
    }

    /**
     * Report feedback widget manually with null widgetData and null key-value widget results
     * "reportFeedbackWidgetManually" function should record widget as an event,
     * event queue should contain it and it should have correct segmentation
     */
    @Test
    public void reportFeedbackWidgetManually_nullWidgetResultValueKeys() {
        init(TestUtils.getConfigFeedback(Config.Feature.Events));

        CountlyFeedbackWidget widgetInfo = createFeedbackWidget(FeedbackWidgetType.survey, "nps1", "npsID1", new String[] { "fg", "lh" });
        validateEventQueueSize(0, moduleEvents().eventQueue);

        Map<String, Object> widgetResult = new HashMap<>();
        widgetResult.put("key1", null);
        widgetResult.put(null, null);
        widgetResult.put("", 6);
        widgetResult.put("accepted", true);

        Map<String, Object> expectedWidgetResult = new HashMap<>();
        expectedWidgetResult.put("accepted", true);

        Countly.instance().feedback().reportFeedbackWidgetManually(widgetInfo, null, widgetResult);
        validateEventQueueSize(1, moduleEvents().eventQueue);

        feedbackValidateManualResultEQ(FeedbackWidgetType.survey.eventKey, widgetInfo.widgetId, widgetResult, 0);
    }

    /**
     * Report a nps and a rating feedback widget manually with null widgetData and not existing rating
     * "reportFeedbackWidgetManually" function should not record,
     * event queue should be empty
     */
    @Test
    public void reportFeedbackWidgetManually_nonExistingRating() {
        init(TestUtils.getConfigFeedback(Config.Feature.Events));

        CountlyFeedbackWidget widgetInfo = createFeedbackWidget(FeedbackWidgetType.nps, "nps1", "npsID1", new String[] { "ou" });
        validateEventQueueSize(0, moduleEvents().eventQueue);

        Map<String, Object> widgetResult = new HashMap<>();
        widgetResult.put("accepted", true);

        Countly.instance().feedback().reportFeedbackWidgetManually(widgetInfo, null, widgetResult);
        validateEventQueueSize(0, moduleEvents().eventQueue);

        widgetInfo = createFeedbackWidget(FeedbackWidgetType.rating, "rating1", "ratingID1", new String[] {});
        Countly.instance().feedback().reportFeedbackWidgetManually(widgetInfo, null, widgetResult);
        validateEventQueueSize(0, moduleEvents().eventQueue);
    }

    /**
     * Report a nps and a rating feedback widget manually with null widgetData and invalid rating result
     * "reportFeedbackWidgetManually" function should not record,
     * event queue should be empty
     */
    @Test
    public void reportFeedbackWidgetManually_invalidRating() {
        init(TestUtils.getConfigFeedback(Config.Feature.Events));

        CountlyFeedbackWidget widgetInfo = createFeedbackWidget(FeedbackWidgetType.nps, "nps1", "npsID1", new String[] { "as" });
        validateEventQueueSize(0, moduleEvents().eventQueue);

        Map<String, Object> widgetResult = new HashMap<>();
        widgetResult.put("rating", true);

        Countly.instance().feedback().reportFeedbackWidgetManually(widgetInfo, null, widgetResult);
        validateEventQueueSize(0, moduleEvents().eventQueue);

        widgetInfo = createFeedbackWidget(FeedbackWidgetType.rating, "rating1", "ratingID1", new String[] {});
        Countly.instance().feedback().reportFeedbackWidgetManually(widgetInfo, null, widgetResult);
        validateEventQueueSize(0, moduleEvents().eventQueue);
    }

    /**
     * Report a nps and a rating feedback widget manually with null widgetData and valid rating result
     * "reportFeedbackWidgetManually" function should record,
     * event queue should contain widget event and it should have correct segmentation
     */
    @Test
    public void reportFeedbackWidgetManually_validRating() {
        init(TestUtils.getConfigFeedback(Config.Feature.Events));

        CountlyFeedbackWidget widgetInfo = createFeedbackWidget(FeedbackWidgetType.nps, "nps1", "npsID1", new String[] { "sa" });
        validateEventQueueSize(0, moduleEvents().eventQueue);

        Map<String, Object> widgetResult = new HashMap<>();
        widgetResult.put("rating", 11);

        Countly.instance().feedback().reportFeedbackWidgetManually(widgetInfo, null, widgetResult);
        validateEventQueueSize(1, moduleEvents().eventQueue);

        feedbackValidateManualResultEQ(FeedbackWidgetType.nps.eventKey, widgetInfo.widgetId, widgetResult, 0);

        widgetInfo = createFeedbackWidget(FeedbackWidgetType.rating, "rating1", "ratingID1", new String[] {});
        Countly.instance().feedback().reportFeedbackWidgetManually(widgetInfo, null, widgetResult);
        validateEventQueueSize(2, moduleEvents().eventQueue);
        feedbackValidateManualResultEQ(FeedbackWidgetType.rating.eventKey, widgetInfo.widgetId, widgetResult, 1);
    }

    /**
     * Report feedback widget manually with null widgetData and null widget result
     * "reportFeedbackWidgetManually" function should record, and widgets should be written to request queue
     * event queue should contain widget event, and it should have correct segmentation, also request queue should contain
     */
    @Test
    public void reportFeedbackWidgetManually_rq() {
        init(TestUtils.getConfigFeedback(Config.Feature.Events).setEventQueueSizeToSend(2));

        CountlyFeedbackWidget widgetInfoNps = createFeedbackWidget(FeedbackWidgetType.nps, "nps1", "npsID1", new String[] { "rt" });
        validateEventQueueSize(0, moduleEvents().eventQueue);
        Assert.assertEquals(0, TestUtils.getCurrentRequestQueue().length);

        Countly.instance().feedback().reportFeedbackWidgetManually(widgetInfoNps, null, null);
        validateEventQueueSize(1, moduleEvents().eventQueue);
        Assert.assertEquals(0, TestUtils.getCurrentRequestQueue().length);

        feedbackValidateManualResultEQ(FeedbackWidgetType.nps.eventKey, widgetInfoNps.widgetId, null, 0);

        CountlyFeedbackWidget widgetInfoRating = createFeedbackWidget(FeedbackWidgetType.rating, "rating1", "ratingID1", new String[] {});
        Countly.instance().feedback().reportFeedbackWidgetManually(widgetInfoRating, null, null);
        validateEventQueueSize(0, moduleEvents().eventQueue);

        Assert.assertEquals(1, TestUtils.getCurrentRequestQueue().length);

        feedbackValidateManualResultRQ(FeedbackWidgetType.nps.eventKey, widgetInfoNps.widgetId, null, 0);
        feedbackValidateManualResultRQ(FeedbackWidgetType.rating.eventKey, widgetInfoRating.widgetId, null, 1);
    }

    /**
     * Report feedback widget manually with invalid widgetData and null widgetResult
     * "reportFeedbackWidgetManually" function should record widget as an event,
     * event queue should contain it and it should have correct segmentation
     */
    @Test
    public void reportFeedbackWidgetManually_invalidWidgetData() {
        init(TestUtils.getConfigFeedback(Config.Feature.Events));

        JSONObject widgetData = new JSONObject();
        widgetData.put("_id", "diff");
        widgetData.put("type", "rating");

        CountlyFeedbackWidget widgetInfo = createFeedbackWidget(FeedbackWidgetType.nps, "nps1", "npsID1", new String[] { "ty" });
        validateEventQueueSize(0, moduleEvents().eventQueue);
        Countly.instance().feedback().reportFeedbackWidgetManually(widgetInfo, widgetData, null);
        validateEventQueueSize(1, moduleEvents().eventQueue);

        feedbackValidateManualResultEQ(FeedbackWidgetType.nps.eventKey, widgetInfo.widgetId, null, 0);
    }

    private void validateWidgetDataParams(Map<String, String> params, CountlyFeedbackWidget widgetInfo) {
        Assert.assertEquals(widgetInfo.widgetId, params.get("widget_id"));
        Assert.assertEquals(Utils.urlencode(getOs(), L), params.get("platform"));
        Assert.assertEquals("1", params.get("shown"));
        Assert.assertEquals(String.valueOf(SDKCore.instance.config.getApplicationVersion()), params.get("app_version"));
        TestUtils.validateSdkIdentityParams(params);
    }

    private void validateWidgetRequiredParams(String expectedEndpoint, String customEndpoint, Boolean requestShouldBeDelayed, Boolean networkingIsEnabled) {
        Assert.assertEquals(expectedEndpoint, customEndpoint);
        Assert.assertFalse(requestShouldBeDelayed);
        Assert.assertTrue(networkingIsEnabled);
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
        widgetJson.put("tg", new JSONArray(widget.tags));
        return widgetJson;
    }

    private void ValidateReturnedFeedbackWidget(FeedbackWidgetType type, String wName, String wId, String[] wTags, CountlyFeedbackWidget fWidget) {
        Assert.assertEquals(type, fWidget.type);
        Assert.assertEquals(wName, fWidget.name);
        Assert.assertEquals(wId, fWidget.widgetId);
        Assert.assertArrayEquals(wTags, fWidget.tags);
    }

    private ModuleEvents moduleEvents() {
        return SDKCore.instance.module(ModuleEvents.class);
    }

    private Map<String, Object> requiredWidgetSegmentation(String widgetId, Map<String, Object> widgetResult) {
        Map<String, Object> segm = new HashMap<>();
        segm.put("platform", getOs());
        segm.put("app_version", SDKCore.instance.config.getApplicationVersion());
        segm.put("widget_id", widgetId);
        if (widgetResult != null) {
            segm.putAll(widgetResult);
        } else {
            segm.put("closed", "1");
        }
        return segm;
    }

    void feedbackValidateManualResultEQ(String eventKey, String widgetID, Map<String, Object> feedbacklResult, int eqIndex) {
        validateEvent(TestUtils.getCurrentEventQueue().get(eqIndex), eventKey, requiredWidgetSegmentation(widgetID, feedbacklResult), 1, null, null);
    }

    void feedbackValidateManualResultRQ(String eventKey, String widgetID, Map<String, Object> feedbacklResult, int eqIndex) {
        validateEvent(TestUtils.readEventsFromRequest().get(eqIndex), eventKey, requiredWidgetSegmentation(widgetID, feedbacklResult), 1, null, null);
    }
}
