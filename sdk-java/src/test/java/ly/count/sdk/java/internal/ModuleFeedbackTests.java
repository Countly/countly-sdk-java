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

import static ly.count.sdk.java.internal.TestUtils.getOS;
import static ly.count.sdk.java.internal.TestUtils.validateEvent;
import static ly.count.sdk.java.internal.TestUtils.validateEQSize;
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

    public void constructFeedbackWidgetUrl_base(CountlyFeedbackWidget widgetInfo, boolean goodResult) {
        init(TestUtils.getConfigFeedback());

        if (!goodResult) {
            Assert.assertNull(Countly.instance().feedback().constructFeedbackWidgetUrl(widgetInfo));
            return;
        }

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
        widgetListUrl.append(Utils.urlencode(getOS(), L));

        Assert.assertEquals(widgetListUrl.toString(), Countly.instance().feedback().constructFeedbackWidgetUrl(widgetInfo));
    }

    /**
     * "constructFeedbackWidgetUrl"
     * We are passing a valid "CountlyFeedbackWidget" structure
     * A correct URL should be produced
     */
    @Test
    public void constructFeedbackWidgetUrl_propperWidgets() {
        constructFeedbackWidgetUrl_base(createFeedbackWidget(FeedbackWidgetType.nps, "nps1", "npsID1", new String[] { "fff" }), true);
        constructFeedbackWidgetUrl_base(createFeedbackWidget(FeedbackWidgetType.rating, "rating4", "1234", new String[] { "343" }), true);
        constructFeedbackWidgetUrl_base(createFeedbackWidget(FeedbackWidgetType.survey, "SurveyTrip", "rtyu", new String[] {}), true);
    }

    /**
     * "constructFeedbackWidgetUrl"
     * We pass a "null" widgetInfo
     * Response should be "null" as that is bad input
     */
    @Test
    public void constructFeedbackWidgetUrl_nullWidgetInfo() {
        constructFeedbackWidgetUrl_base(null, false);
    }

    /**
     * "constructFeedbackWidgetUrl"
     * We pass a default widgetInfo, all fields would be "null"
     * Response should be "null" as that is bad input
     */
    @Test
    public void constructFeedbackWidgetUrl_defaultWidgetInfo() {
        constructFeedbackWidgetUrl_base(new CountlyFeedbackWidget(), false);
    }

    /**
     * "constructFeedbackWidgetUrl"
     * We pass a default widgetInfo, all fields would be "null"
     * Response should be "null" as that is bad input
     */
    @Test
    public void constructFeedbackWidgetUrl_emptyWidgetInfo() {
        CountlyFeedbackWidget widgetInfo = createFeedbackWidget(FeedbackWidgetType.nps, "", "", new String[] {});
        constructFeedbackWidgetUrl_base(widgetInfo, false);
    }

    /**
     * "getFeedbackWidgetData"
     * pass "null" widget info
     * Callback response should be "null" due to faulty input. Error message should also be proper
     */
    @Test
    public void getFeedbackWidgetData_nullWidgetInfo() {
        getFeedbackWidgetData_base(null, null, "[ModuleFeedback] getFeedbackWidgetDataInternal, Can't continue operation with null widget");
    }

    /**
     * "getFeedbackWidgetData"
     * Passing correct "CountlyFeedbackWidget". Returning mocked correct server response. Validating created request params.
     * The returned response should match the initial input
     */
    @Test
    public void getFeedbackWidgetData() {
        JSONObject responseJson = new JSONObject();
        responseJson.put("result", TestUtils.feedbackWidgetData);

        CountlyFeedbackWidget widgetInfo = createFeedbackWidget(FeedbackWidgetType.nps, "nps1", "npsID1", new String[] { "hgj" });

        getFeedbackWidgetData_base(responseJson, widgetInfo, null);
    }

    /**
     * "getFeedbackWidgetData"
     * Passing correct "CountlyFeedbackWidget". Returning mocked "null" response. Validating created request params.
     * Returned response should be null and the correct error message returned
     */
    @Test
    public void getFeedbackWidgetData_nullResponse() {
        CountlyFeedbackWidget widgetInfo = createFeedbackWidget(FeedbackWidgetType.survey, "nps1", "npsID1", new String[] { "jyg", "jhg" });
        getFeedbackWidgetData_base(null, widgetInfo, "Not possible to retrieve widget data. Probably due to lack of connection to the server");
    }

    /**
     * "getFeedbackWidgetData"
     * Passing correct "CountlyFeedbackWidget". Returning mocked garbage response. Validating created request params.
     * SDK does not filter returned output and just passes it along.
     */
    @Test
    public void getFeedbackWidgetData_garbageResult() {
        JSONObject responseJson = new JSONObject();
        responseJson.put("xxx", "123");

        CountlyFeedbackWidget widgetInfo = createFeedbackWidget(FeedbackWidgetType.rating, "nps1", "npsID1", new String[] { "yh" });
        getFeedbackWidgetData_base(responseJson, widgetInfo, null);
    }

    public void getFeedbackWidgetData_base(JSONObject responseJson, CountlyFeedbackWidget widgetInfo, String errorMessage) {
        init(TestUtils.getConfigFeedback());

        if (widgetInfo != null) {
            ImmediateRequestI requestMaker = (requestData, customEndpoint, cp, requestShouldBeDelayed, networkingIsEnabled, callback, log) -> {
                validateWidgetRequiredParams("/o/surveys/" + widgetInfo.type.name() + "/widget", customEndpoint, requestShouldBeDelayed, networkingIsEnabled);
                validateWidgetDataParams(TestUtils.parseQueryParams(requestData), widgetInfo);
                callback.callback(responseJson);
            };
            SDKCore.instance.config.immediateRequestGenerator = () -> requestMaker;
        }

        Countly.instance().feedback().getFeedbackWidgetData(widgetInfo, (response, error) -> {
            if (errorMessage == null) {
                Assert.assertNull(error);
                Assert.assertEquals(responseJson, response);
            } else {
                Assert.assertEquals(errorMessage, error);
                Assert.assertNull(response);
            }
        });
    }

    /**
     * "reportFeedbackWidgetManually"
     * All passed fields are "null"
     * No event should be recorded due to this
     */
    @Test
    public void reportFeedbackWidgetManually_nullWidgetInfo() {
        init(TestUtils.getConfigFeedback(Config.Feature.Events));
        validateRecordingWidgetsManually(null, null, null, 0, false);
    }

    /**
     * "reportFeedbackWidgetManually"
     * Report widget with "null" widgetData and widgetResult
     * The closed event should be recorded properly in the EQ
     */
    @Test
    public void reportFeedbackWidgetManually_closeEventNPS() {
        init(TestUtils.getConfigFeedback(Config.Feature.Events));

        validateRecordingWidgetsManually(createFeedbackWidget(FeedbackWidgetType.nps, "nps1", "npsID1", new String[] { "sa" }), null, null, 0, true);
        validateRecordingWidgetsManually(createFeedbackWidget(FeedbackWidgetType.survey, "survey", "npsID112", new String[] {}), null, null, 1, true);
        validateRecordingWidgetsManually(createFeedbackWidget(FeedbackWidgetType.rating, "rating", "npsID133", new String[] { "hhhh" }), null, null, 2, true);
    }

    //we want to know that the closing event can be recorded for all. missing Survey, Rating
    //that all widget types can be recorded normally. missing survey

    /**
     * "reportFeedbackWidgetManually"
     * Record NPS. Pass "null", empty keys and "null" values.
     * Bad entries should be removed
     */
    @Test
    public void reportFeedbackWidgetManually_nullWidgetResultValueKeys() {
        init(TestUtils.getConfigFeedback(Config.Feature.Events));

        Map<String, Object> widgetResult = new HashMap<>();
        widgetResult.put("key1", null);
        widgetResult.put(null, null);
        widgetResult.put("", 6);
        widgetResult.put("accepted", true);
        widgetResult.put("rating", 6);

        Map<String, Object> expectedWidgetResult = new HashMap<>();
        expectedWidgetResult.put("accepted", true);
        expectedWidgetResult.put("rating", 6);

        validateRecordingWidgetsManually(createFeedbackWidget(FeedbackWidgetType.survey, "nps1", "npsID1", new String[] { "sa" }), null, widgetResult, 0, true, expectedWidgetResult);
        validateRecordingWidgetsManually(createFeedbackWidget(FeedbackWidgetType.rating, "survey", "npsID331", new String[] { "sa" }), null, widgetResult, 1, true, expectedWidgetResult);
        validateRecordingWidgetsManually(createFeedbackWidget(FeedbackWidgetType.nps, "rating", "npsI44D1", new String[] { "sa" }), null, widgetResult, 2, true, expectedWidgetResult);
    }

    /**
     * "reportFeedbackWidgetManually"
     * Report a nps and a rating widget with "null" widgetData and no "rating" entry
     * No events should be recorded since the "rating" field is mandatory
     */
    @Test
    public void reportFeedbackWidgetManually_nonExistingRatingField() {
        init(TestUtils.getConfigFeedback(Config.Feature.Events));

        Map<String, Object> widgetResult = new HashMap<>();
        widgetResult.put("accepted", true);

        validateRecordingWidgetsManually(createFeedbackWidget(FeedbackWidgetType.nps, "nps1", "npsID1", new String[] { "sa" }), null, widgetResult, 0, false);
        validateRecordingWidgetsManually(createFeedbackWidget(FeedbackWidgetType.rating, "rating1", "ratingID1", new String[] {}), null, widgetResult, 0, false);
    }

    /**
     * "reportFeedbackWidgetManually"
     * Report a nps and a rating widget with "null" widgetData and the rating field has the wrong data type
     * No events should be recorded since the "rating" field should be an integer
     */
    @Test
    public void reportFeedbackWidgetManually_invalidRatingField() {
        init(TestUtils.getConfigFeedback(Config.Feature.Events));

        Map<String, Object> widgetResult = new HashMap<>();
        widgetResult.put("rating", true);

        validateRecordingWidgetsManually(createFeedbackWidget(FeedbackWidgetType.nps, "nps1", "npsID1", new String[] { "sa" }), null, widgetResult, 0, false);
        validateRecordingWidgetsManually(createFeedbackWidget(FeedbackWidgetType.rating, "rating1", "ratingID1", new String[] {}), null, widgetResult, 0, false);
    }

    /**
     * "reportFeedbackWidgetManually"
     * Recording rating and nps widgets with correct data
     * EQ should contain their recorded events
     */
    @Test
    public void reportFeedbackWidgetManually_validRatingField() {
        init(TestUtils.getConfigFeedback(Config.Feature.Events));

        Map<String, Object> widgetResult = new HashMap<>();
        widgetResult.put("rating", 11);

        validateRecordingWidgetsManually(createFeedbackWidget(FeedbackWidgetType.nps, "nps1", "npsID1", new String[] { "sa" }), null, widgetResult, 0, true);
        validateRecordingWidgetsManually(createFeedbackWidget(FeedbackWidgetType.rating, "rating1", "ratingID1", new String[] {}), null, widgetResult, 1, true);
    }

    /**
     * "reportFeedbackWidgetManually"
     * Record a NPS closing event. Pass widget data structure
     * Everything proceeds normally
     */
    @Test
    public void reportFeedbackWidgetManually_invalidWidgetData() {
        init(TestUtils.getConfigFeedback(Config.Feature.Events));

        JSONObject widgetData = new JSONObject();
        widgetData.put("_id", "diff");
        widgetData.put("type", "rating");

        validateRecordingWidgetsManually(createFeedbackWidget(FeedbackWidgetType.nps, "nps1", "npsID1", new String[] { "sa" }), widgetData, null, 0, true);
    }

    void validateRecordingWidgetsManually(CountlyFeedbackWidget widgetInfo, JSONObject widgetData, Map<String, Object> widgetResult, int initialEQSize, boolean goodResult, Map<String, Object> expectedWidgetResult) {
        TestUtils.validateEQSize(initialEQSize, moduleEvents().eventQueue);

        Countly.instance().feedback().reportFeedbackWidgetManually(widgetInfo, widgetData, expectedWidgetResult == null ? widgetResult : expectedWidgetResult);

        if (goodResult) {
            TestUtils.validateEQSize(initialEQSize + 1, moduleEvents().eventQueue);
            feedbackValidateManualResultEQ(widgetInfo.type.eventKey, widgetInfo.widgetId, expectedWidgetResult == null ? widgetResult : expectedWidgetResult, initialEQSize);
        } else {
            TestUtils.validateEQSize(initialEQSize, moduleEvents().eventQueue);
        }
    }

    void validateRecordingWidgetsManually(CountlyFeedbackWidget widgetInfo, JSONObject widgetData, Map<String, Object> widgetResult, int initialEQSize, boolean goodResult) {
        validateRecordingWidgetsManually(widgetInfo, widgetData, widgetResult, initialEQSize, goodResult, null);
    }

    /**
     * "reportFeedbackWidgetManually"
     * Record an amount of widgtets that exceed the event threshold
     * After exceeding the threshold the events should be spotted in the RQ
     */
    @Test
    public void reportFeedbackWidgetManually_rq() {
        init(TestUtils.getConfigFeedback(Config.Feature.Events).setEventQueueSizeToSend(2));

        CountlyFeedbackWidget widgetInfoNps = createFeedbackWidget(FeedbackWidgetType.nps, "nps1", "npsID1", new String[] { "rt" });
        TestUtils.validateEQSize(0, moduleEvents().eventQueue);
        Assert.assertEquals(0, TestUtils.getCurrentRQ().length);

        Countly.instance().feedback().reportFeedbackWidgetManually(widgetInfoNps, null, null);
        TestUtils.validateEQSize(1, moduleEvents().eventQueue);
        Assert.assertEquals(0, TestUtils.getCurrentRQ().length);

        feedbackValidateManualResultEQ(FeedbackWidgetType.nps.eventKey, widgetInfoNps.widgetId, null, 0);

        CountlyFeedbackWidget widgetInfoRating = createFeedbackWidget(FeedbackWidgetType.rating, "rating1", "ratingID1", new String[] {});
        Countly.instance().feedback().reportFeedbackWidgetManually(widgetInfoRating, null, null);
        TestUtils.validateEQSize(0, moduleEvents().eventQueue);

        Assert.assertEquals(1, TestUtils.getCurrentRQ().length);

        feedbackValidateManualResultRQ(FeedbackWidgetType.nps.eventKey, widgetInfoNps.widgetId, null, 0);
        feedbackValidateManualResultRQ(FeedbackWidgetType.rating.eventKey, widgetInfoRating.widgetId, null, 1);
    }

    private void validateWidgetDataParams(Map<String, String> params, CountlyFeedbackWidget widgetInfo) {
        Assert.assertEquals(widgetInfo.widgetId, params.get("widget_id"));
        Assert.assertEquals(Utils.urlencode(getOS(), L), params.get("platform"));
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
        segm.put("platform", getOS());
        segm.put("app_version", SDKCore.instance.config.getApplicationVersion());
        segm.put("widget_id", widgetId);
        if (widgetResult != null) {
            segm.putAll(widgetResult);
        } else {
            segm.put("closed", "1");
        }
        return segm;
    }

    void feedbackValidateManualResultEQ(String eventKey, String widgetID, Map<String, Object> feedbackWidgetResult, int eqIndex) {
        validateEvent(TestUtils.getCurrentEQ().get(eqIndex), eventKey, requiredWidgetSegmentation(widgetID, feedbackWidgetResult), 1, null, null);
    }

    void feedbackValidateManualResultRQ(String eventKey, String widgetID, Map<String, Object> feedbackWidgetResult, int eqIndex) {
        validateEvent(TestUtils.readEventsFromRequest().get(eqIndex), eventKey, requiredWidgetSegmentation(widgetID, feedbackWidgetResult), 1, null, null);
    }
}
