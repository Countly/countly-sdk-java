package ly.count.sdk.java.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import ly.count.sdk.java.Countly;
import org.json.JSONArray;
import org.json.JSONObject;

public class ModuleFeedback extends ModuleBase {

    String cachedAppVersion;
    Feedback feedbackInterface = null;

    ModuleFeedback() {
    }

    @Override
    public void init(InternalConfig config, Log logger) {
        super.init(config, logger);
        L.v("[ModuleFeedback] Initializing");

        cachedAppVersion = config.getApplicationVersion();
        feedbackInterface = new Feedback();
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

    private void getAvailableFeedbackWidgetsInternal(CallbackOnFinish<List<CountlyFeedbackWidget>> callback) {
        L.d("[ModuleFeedback] getAvailableFeedbackWidgetsInternal, callback set:[" + (callback != null) + "]");

        if (callback == null) {
            L.e("[ModuleFeedback] getAvailableFeedbackWidgetsInternal, available feedback widget list can't be retrieved without a callback");
            return;
        }

        // If someday we decide to support temporary device ID mode, this check will be needed
        if (internalConfig.isTemporaryIdEnabled()) {
            L.e("[ModuleFeedback] available feedback widget list can't be retrieved when in temporary device ID mode");
            callback.onFinished(null, "[ModuleFeedback] available feedback widget list can't be retrieved when in temporary device ID mode");
            return;
        }

        Transport transport = SDKCore.instance.networking.getTransport();
        final boolean networkingIsEnabled = internalConfig.getNetworkingEnabled();

        Request request = new Request();
        ModuleRequests.addRequiredTimeParams(request);
        ModuleRequests.addRequired(internalConfig, request);
        request.params.add("method", "feedback");
        String requestData = "?" + request.params.toString();

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
        if (widgetInfo == null) {
            L.e("[ModuleFeedback] Can't report feedback widget data manually with 'null' widget info");
            return;
        }

        L.d("[ModuleFeedback] reportFeedbackWidgetManuallyInternal, widgetData set:[" + (widgetData != null) + ", widget id:[" + widgetInfo.widgetId + "], widget type:[" + widgetInfo.type + "], widget result set:[" + (widgetResult != null) + "]");

        if (internalConfig.isTemporaryIdEnabled()) {
            L.e("[ModuleFeedback] feedback widget result can't be reported when in temporary device ID mode");
            return;
        }

        if (widgetResult != null) {
            //removing broken values first
            Iterator<Map.Entry<String, Object>> iter = widgetResult.entrySet().iterator();
            while (iter.hasNext()) {
                Map.Entry<String, Object> entry = iter.next();
                if (entry.getKey() == null) {
                    L.w("[ModuleFeedback] provided feedback widget result contains a 'null' key, it will be removed, value[" + entry.getValue() + "]");
                    iter.remove();
                } else if (entry.getKey().isEmpty()) {
                    L.w("[ModuleFeedback] provided feedback widget result contains an empty string key, it will be removed, value[" + entry.getValue() + "]");
                    iter.remove();
                } else if (entry.getValue() == null) {
                    L.w("[ModuleFeedback] provided feedback widget result contains a 'null' value, it will be removed, key[" + entry.getKey() + "]");
                    iter.remove();
                }
            }

            if (widgetInfo.type == FeedbackWidgetType.nps) {
                //in case a nps widget was completed
                if (!widgetResult.containsKey("rating")) {
                    L.e("Provided NPS widget result does not have a 'rating' field, result can't be reported");
                    return;
                }

                //check rating data type
                Object ratingValue = widgetResult.get("rating");
                if (!(ratingValue instanceof Integer)) {
                    L.e("Provided NPS widget 'rating' field is not an integer, result can't be reported");
                    return;
                }

                //check rating value range
                int ratingValI = (int) ratingValue;
                if (ratingValI < 0 || ratingValI > 10) {
                    L.e("Provided NPS widget 'rating' value is out of bounds of the required value '[0;10]', it is probably an error");
                }

                if (!widgetResult.containsKey("comment")) {
                    L.w("Provided NPS widget result does not have a 'comment' field");
                }
            } else if (widgetInfo.type == FeedbackWidgetType.survey) {
                //in case a survey widget was completed
            } else if (widgetInfo.type == FeedbackWidgetType.rating) {
                //in case a rating widget was completed
                if (!widgetResult.containsKey("rating")) {
                    L.e("Provided Rating widget result does not have a 'rating' field, result can't be reported");
                    return;
                }

                //check rating data type
                Object ratingValue = widgetResult.get("rating");
                if (!(ratingValue instanceof Integer)) {
                    L.e("Provided Rating widget 'rating' field is not an integer, result can't be reported");
                    return;
                }

                //check rating value range
                int ratingValI = (int) ratingValue;
                if (ratingValI < 1 || ratingValI > 5) {
                    L.e("Provided Rating widget 'rating' value is out of bounds of the required value '[1;5]', it is probably an error");
                }
            }
        }

        if (widgetData == null) {
            L.d("[ModuleFeedback] reportFeedbackWidgetManuallyInternal, widgetInfo is 'null', no validation will be done");
        } else {
            //perform data validation

            String idInData = widgetData.optString("_id");

            if (!widgetInfo.widgetId.equals(idInData)) {
                L.w("[ModuleFeedback] id in widget info does not match the id in widget data");
            }

            String typeInData = widgetData.optString("type");

            if (!widgetInfo.type.name().equals(typeInData)) {
                L.w("[ModuleFeedback] type in widget info [" + typeInData + "] does not match the type in widget data [" + widgetInfo.type.name() + "]");
            }
        }

        Map<String, Object> segm = new HashMap<>();
        segm.put("platform", internalConfig.getSdkPlatform());
        segm.put("app_version", cachedAppVersion);
        segm.put("widget_id", widgetInfo.widgetId);

        if (widgetResult == null) {
            //mark as closed
            segm.put("closed", "1");
        } else {
            //widget was filled out
            //merge given segmentation
            segm.putAll(widgetResult);
        }

        Countly.instance().events().recordEvent(widgetInfo.type.eventKey, segm);
    }

    private <T> void callCallback(String errorLog, CallbackOnFinish<T> callback) {
        L.e(errorLog);
        if (callback != null) {
            callback.onFinished(null, errorLog);
        }
    }

    private void getFeedbackWidgetDataInternal(CountlyFeedbackWidget widgetInfo, CallbackOnFinish<JSONObject> callback) {
        L.d("[ModuleFeedback] calling 'getFeedbackWidgetDataInternal', callback set:[" + (callback != null) + "]");

        String error = validateFields(callback, widgetInfo);

        if (error != null) {
            callCallback("[ModuleFeedback] getFeedbackWidgetDataInternal, " + error, callback);
            return;
        }

        StringBuilder requestData = new StringBuilder();
        String widgetDataEndpoint = "/o/surveys/" + widgetInfo.type.name() + "/widget";

        requestData.append("?widget_id=");
        requestData.append(Utils.urlencode(widgetInfo.widgetId, L));
        requestData.append("&shown=1");
        requestData.append("&sdk_version=");
        requestData.append(Utils.urlencode(internalConfig.getSdkVersion(), L));
        requestData.append("&sdk_name=");
        requestData.append(Utils.urlencode(internalConfig.getSdkName(), L));
        requestData.append("&platform=");
        requestData.append(Utils.urlencode(internalConfig.getSdkPlatform(), L));
        requestData.append("&app_version=");
        requestData.append(cachedAppVersion);

        Transport cp = SDKCore.instance.networking.getTransport();
        final boolean networkingIsEnabled = internalConfig.getNetworkingEnabled();
        String requestDataStr = requestData.toString();

        L.d("[ModuleFeedback] getFeedbackWidgetDataInternal, Using following request params for retrieving widget data:[" + requestDataStr + "]");

        ImmediateRequestGenerator iRGenerator = internalConfig.immediateRequestGenerator;

        iRGenerator.createImmediateRequestMaker().doWork(requestDataStr, widgetDataEndpoint, cp, false, networkingIsEnabled, checkResponse -> {
            if (checkResponse == null) {
                L.d("[ModuleFeedback] getFeedbackWidgetDataInternal, Not possible to retrieve widget data. Probably due to lack of connection to the server");
                callback.onFinished(null, "Not possible to retrieve widget data. Probably due to lack of connection to the server");
                return;
            }

            L.d("[ModuleFeedback] getFeedbackWidgetDataInternal, Retrieved widget data request: [" + checkResponse + "]");

            //TODO: in the future add some validation for some common widget data fields
            callback.onFinished(checkResponse, null);
        }, L);
    }

    private String validateFields(Object callback, CountlyFeedbackWidget widget) {
        if (callback == null) {
            return "Can't continue operation with null callback";
        }

        if (widget == null) {
            return "Can't continue operation with null widget";
        }

        if (internalConfig.isTemporaryIdEnabled()) {
            return "Can't continue operation when in temporary device ID mode";
        }

        return null;
    }

    private String constructFeedbackWidgetUrlInternal(CountlyFeedbackWidget widgetInfo) {
        L.d("[ModuleFeedback] constructFeedbackWidgetUrlInternal, widgetInfo :[" + widgetInfo + "]");

        if (widgetInfo == null) {
            L.e("[ModuleFeedback] constructFeedbackWidgetUrlInternal, can't continue operation with null widget");
            return null;
        }

        if (internalConfig.isTemporaryIdEnabled()) {
            L.e("[ModuleFeedback] constructFeedbackWidgetUrlInternal, can't continue operation when in temporary device ID mode");
            return null;
        }

        if (widgetInfo.type == null || widgetInfo.widgetId == null || widgetInfo.widgetId.isEmpty()) {
            L.e("[ModuleFeedback] constructFeedbackWidgetUrlInternal, can't continue operation, provided widget type or ID is 'null' or the provided ID is empty string");
            return null;
        }

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
        widgetListUrl.append("&platform=");
        widgetListUrl.append(Utils.urlencode(internalConfig.getSdkPlatform(), L));

        final String preparedWidgetUrl = widgetListUrl.toString();

        L.d("[ModuleFeedback] constructFeedbackWidgetUrlInternal, Using following url for widget:[" + widgetListUrl + "]");
        return preparedWidgetUrl;
    }

    public class Feedback {

        /**
         * Get a list of available feedback widgets for this device ID
         *
         * @param callback retrieve widget list callback
         */
        public void getAvailableFeedbackWidgets(@Nullable CallbackOnFinish<List<CountlyFeedbackWidget>> callback) {
            synchronized (Countly.instance()) {
                L.i("[Feedback] getAvailableFeedbackWidgets, Trying to retrieve feedback widget list");

                getAvailableFeedbackWidgetsInternal(callback);
            }
        }

        /**
         * Construct a URL that can be used to present a feedback widget in a web viewer
         *
         * @param widgetInfo widget info
         */
        public String constructFeedbackWidgetUrl(@Nullable CountlyFeedbackWidget widgetInfo) {
            synchronized (Countly.instance()) {
                L.i("[Feedback] constructFeedbackWidgetUrl, Trying to present feedback widget in an alert dialog");

                return constructFeedbackWidgetUrlInternal(widgetInfo);
            }
        }

        /**
         * Download data for a specific widget so that it can be displayed with a custom UI
         * When requesting this data, it will count as a shown widget (will increment that "shown" count in the dashboard)
         *
         * @param widgetInfo widget info
         * @param callback retrieve widget data callback
         */
        public void getFeedbackWidgetData(@Nullable CountlyFeedbackWidget widgetInfo, @Nullable CallbackOnFinish<JSONObject> callback) {
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
         * @param widgetData widget data to validate
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
