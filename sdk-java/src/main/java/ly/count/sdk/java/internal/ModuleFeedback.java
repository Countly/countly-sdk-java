package ly.count.sdk.java.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import ly.count.sdk.java.Countly;
import org.json.JSONArray;
import org.json.JSONObject;

public class ModuleFeedback extends ModuleBase {

    public enum FeedbackWidgetType {survey, nps, rating}

    public static class CountlyFeedbackWidget {
        public String widgetId;
        public FeedbackWidgetType type;
        public String name;
        public String[] tags;
    }

    final static String NPS_EVENT_KEY = "[CLY]_nps";
    final static String SURVEY_EVENT_KEY = "[CLY]_survey";
    final static String RATING_EVENT_KEY = "[CLY]_star_rating";

    String cachedAppVersion;
    Feedback feedbackInterface = null;
    private CtxCore ctx;

    public interface RetrieveFeedbackWidgets {
        void onFinished(List<CountlyFeedbackWidget> retrievedWidgets, String error);
    }

    public interface RetrieveFeedbackWidgetData {
        void onFinished(JSONObject retrievedWidgetData, String error);
    }

    public interface FeedbackCallback {
        void onFinished(String url, String error);
    }

    ModuleFeedback() {
    }

    @Override
    public void init(InternalConfig config, Log logger) {
        L.v("[ModuleFeedback] Initializing");
        super.init(config, logger);

        cachedAppVersion = Device.dev.getAppVersion();
        feedbackInterface = new Feedback();
    }

    @Override
    public void onContextAcquired(@Nonnull CtxCore ctx) {
        this.ctx = ctx;
        L.d("[ModuleFeedback] onContextAcquired: " + ctx);
    }

    @Override
    public Boolean onRequest(Request request) {
        return true;
    }

    @Override
    public void stop(CtxCore ctx, boolean clear) {
        super.stop(ctx, clear);
        feedbackInterface = null;
    }

    private void getAvailableFeedbackWidgetsInternal(RetrieveFeedbackWidgets callback) {
        L.d("[ModuleFeedback] getAvailableFeedbackWidgetsInternal, callback set:[" + (callback != null) + "]");

        if (callback == null) {
            L.e("[ModuleFeedback] getAvailableFeedbackWidgetsInternal, available feedback widget list can't be retrieved without a callback");
            return;
        }

        // If someday we decide to support temporary device ID mode, this check will be needed
        //if (internalConfig.isTemporaryIdEnabled()) {
        //    L.e("[ModuleFeedback] available feedback widget list can't be retrieved when in temporary device ID mode");
        //    callback.onFinished(null, "[ModuleFeedback] available feedback widget list can't be retrieved when in temporary device ID mode");
        //    return;
        //}

        Transport transport = ctx.getSDK().networking.getTransport();
        final boolean networkingIsEnabled = true; // this feature is not yet implemented

        Request request = new Request();
        ModuleRequests.addRequiredTimeParams(request);
        ModuleRequests.addRequired(internalConfig, request);
        request.params.add("method", "feedback");
        String requestData = request.params.toString();

        ImmediateRequestGenerator iRGenerator = internalConfig.immediateRequestGenerator;

        iRGenerator.createImmediateRequestMaker().doWork(requestData, "/o/sdk", transport, false, networkingIsEnabled, checkResponse -> {
            if (checkResponse == null) {
                L.d("[ModuleFeedback] getAvailableFeedbackWidgetsInternal, Not possible to retrieve widget list. Probably due to lack of connection to the server");
                callback.onFinished(null, "Not possible to retrieve widget list. Probably due to lack of connection to the server");
                return;
            }

            L.d("[ModuleFeedback] Retrieved request: [" + checkResponse + "]");

            List<CountlyFeedbackWidget> feedbackEntries = parseFeedbackList(checkResponse);

            callback.onFinished(feedbackEntries, null);
        }, L);
    }

    static List<CountlyFeedbackWidget> parseFeedbackList(JSONObject requestResponse) {
        Log L = SDKCore.instance.L;
        L.d("[ModuleFeedback] parseFeedbackList, calling");

        List<CountlyFeedbackWidget> parsedRes = new ArrayList<>();
        try {
            if (requestResponse != null) {
                JSONArray jArray = requestResponse.optJSONArray("result");

                if (jArray == null) {
                    L.w("[ModuleFeedback] parseFeedbackList, response does not have a valid 'result' entry. No widgets retrieved.");
                    return parsedRes;
                }

                for (int a = 0; a < jArray.length(); a++) {
                    try {
                        JSONObject jObj = jArray.getJSONObject(a);

                        String valId = jObj.optString("_id", "");
                        String valType = jObj.optString("type", "");
                        String valName = jObj.optString("name", "");
                        List<String> valTagsArr = new ArrayList<String>();

                        JSONArray jTagArr = jObj.optJSONArray("tg");
                        if (jTagArr == null) {
                            L.w("[ModuleFeedback] parseFeedbackList, no tags received");
                        } else {
                            for (int in = 0; in < jTagArr.length(); in++) {
                                valTagsArr.add(jTagArr.getString(in));
                            }
                        }

                        if (valId.isEmpty()) {
                            L.e("[ModuleFeedback] parseFeedbackList, retrieved invalid entry with null or empty widget id, dropping");
                            continue;
                        }

                        if (valType.isEmpty()) {
                            L.e("[ModuleFeedback] parseFeedbackList, retrieved invalid entry with null or empty widget type, dropping");
                            continue;
                        }

                        FeedbackWidgetType plannedType;

                        try {
                            plannedType = FeedbackWidgetType.valueOf(valType);
                        } catch (Exception ex) {
                            L.e("[ModuleFeedback] parseFeedbackList, retrieved unknown widget type, dropping");
                            continue;
                        }

                        CountlyFeedbackWidget se = new CountlyFeedbackWidget();
                        se.type = plannedType;
                        se.widgetId = valId;
                        se.name = valName;
                        se.tags = valTagsArr.toArray(new String[0]);

                        parsedRes.add(se);
                    } catch (Exception ex) {
                        L.e("[ModuleFeedback] parseFeedbackList, failed to parse json, [" + ex.toString() + "]");
                    }
                }
            }
        } catch (Exception ex) {
            L.e("[ModuleFeedback] parseFeedbackList, Encountered exception while parsing feedback list, [" + ex.toString() + "]");
        }

        return parsedRes;
    }

    private void reportFeedbackWidgetManuallyInternal(CountlyFeedbackWidget widgetInfo, JSONObject widgetData, Map<String, Object> widgetResult) {

    }

    private void getFeedbackWidgetDataInternal(CountlyFeedbackWidget widgetInfo, RetrieveFeedbackWidgetData callback) {
    }

    private void constructFeedbackWidgetUrlInternal(CountlyFeedbackWidget widgetInfo, FeedbackCallback callback) {
        if (widgetInfo == null) {
            L.e("[ModuleFeedback] constructFeedbackWidgetUrlInternal, Can't present widget with null widget info");
            if (callback != null) {
                callback.onFinished(null, "Can't present widget with null widget info");
                return;
            }
        }

        L.d("[ModuleFeedback] constructFeedbackWidgetUrlInternal, callback set:[" + (callback != null) + ", widget id:[" + widgetInfo.widgetId + "], widget type:[" + widgetInfo.type + "]");

        //if (internalConfig.isTemporaryIdEnabled()) {
        //    L.e("[ModuleFeedback] available feedback widget list can't be retrieved when in temporary device ID mode");
        //    if (callback != null) {
        //        callback.onFinished(null,"[ModuleFeedback] available feedback widget list can't be retrieved when in temporary device ID mode");
        //    }
        //    return;
        //}

        StringBuilder widgetListUrl = new StringBuilder();
        widgetListUrl.append(internalConfig.getServerURL());
        widgetListUrl.append("/feedback/");
        widgetListUrl.append(widgetInfo.type.name());
        widgetListUrl.append("?widget_id=");
        widgetListUrl.append(Utils.urlencode(widgetInfo.widgetId, L));
        widgetListUrl.append("&device_id=");
        widgetListUrl.append(Utils.urlencode(internalConfig.getDeviceId().id, L));
        widgetListUrl.append("&app_key=");
        widgetListUrl.append(Utils.urlencode(internalConfig.getServerAppKey(), L));
        widgetListUrl.append("&sdk_version=");
        widgetListUrl.append(internalConfig.getSdkVersion());
        widgetListUrl.append("&sdk_name=");
        widgetListUrl.append(internalConfig.getSdkName());
        widgetListUrl.append("&platform=desktop");

        final String preparedWidgetUrl = widgetListUrl.toString();

        L.d("[ModuleFeedback] constructFeedbackWidgetUrlInternal, Using following url for widget:[" + widgetListUrl + "]");

        if (callback != null) {
            callback.onFinished(preparedWidgetUrl, null);
        }
    }

    public class Feedback {

        /**
         * Get a list of available feedback widgets for this device ID
         *
         * @param callback callback
         */
        public void getAvailableFeedbackWidgets(@Nullable RetrieveFeedbackWidgets callback) {
            synchronized (Countly.instance()) {
                L.i("[Feedback] getAvailableFeedbackWidgets, Trying to retrieve feedback widget list");

                getAvailableFeedbackWidgetsInternal(callback);
            }
        }

        /**
         * Construct a URL that can be used to present a feedback widget in a web viewer
         *
         * @param widgetInfo widget info
         * @param callback callback
         */
        public void constructFeedbackWidgetUrl(@Nullable CountlyFeedbackWidget widgetInfo, @Nullable FeedbackCallback callback) {
            synchronized (Countly.instance()) {
                L.i("[Feedback] constructFeedbackWidgetUrl, Trying to present feedback widget in an alert dialog");

                constructFeedbackWidgetUrlInternal(widgetInfo, callback);
            }
        }

        /**
         * Download data for a specific widget so that it can be displayed with a custom UI
         * When requesting this data, it will count as a shown widget (will increment that "shown" count in the dashboard)
         *
         * @param widgetInfo widget info
         * @param callback callback
         */
        public void getFeedbackWidgetData(@Nullable CountlyFeedbackWidget widgetInfo, @Nullable RetrieveFeedbackWidgetData callback) {
            synchronized (Countly.instance()) {
                L.i("[Feedback] getFeedbackWidgetData, Trying to retrieve feedback widget data");

                getFeedbackWidgetDataInternal(widgetInfo, callback);
            }
        }

        /**
         * Manually report a feedback widget in case the client used a custom interface
         * In case widgetResult is passed as "null," it would be assumed that the widget was canceled
         *
         * @param widgetInfo widget info
         * @param widgetData widget data
         * @param widgetResult widget result
         */
        public void reportFeedbackWidgetManually(@Nullable CountlyFeedbackWidget widgetInfo, @Nullable JSONObject widgetData, @Nullable Map<String, Object> widgetResult) {
            synchronized (Countly.instance()) {
                L.i("[Feedback] reportFeedbackWidgetManually, Trying to report feedback widget manually");

                reportFeedbackWidgetManuallyInternal(widgetInfo, widgetData, widgetResult);
            }
        }
    }
}
