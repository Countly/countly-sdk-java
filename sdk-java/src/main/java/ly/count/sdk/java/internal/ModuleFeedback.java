package ly.count.sdk.java.internal;

import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.json.JSONObject;

public class ModuleFeedback extends ModuleBase {

    public enum FeedbackWidgetType {SURVEY, NPS, RATING}

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
    }

    private void reportFeedbackWidgetManuallyInternal(CountlyFeedbackWidget widgetInfo, JSONObject widgetData, Map<String, Object> widgetResult) {

    }

    private void getFeedbackWidgetDataInternal(CountlyFeedbackWidget widgetInfo, RetrieveFeedbackWidgetData callback) {
    }

    private void constructFeedbackWidgetUrlInternal(CountlyFeedbackWidget widgetInfo, FeedbackCallback devCallback) {
    }

    public class Feedback {

        /**
         * Get a list of available feedback widgets for this device ID
         *
         * @param callback
         */
        public void getAvailableFeedbackWidgets(@Nullable RetrieveFeedbackWidgets callback) {
            synchronized (internalConfig) {
                L.i("[Feedback] Trying to retrieve feedback widget list");

                getAvailableFeedbackWidgetsInternal(callback);
            }
        }

        /**
         * Construct a URL that can be used to present a feedback widget in a web viewer
         *
         * @param widgetInfo
         * @param devCallback
         */
        public void constructFeedbackWidgetUrl(@Nullable CountlyFeedbackWidget widgetInfo, @Nullable FeedbackCallback devCallback) {
            synchronized (internalConfig) {
                L.i("[Feedback] Trying to present feedback widget in an alert dialog");

                constructFeedbackWidgetUrlInternal(widgetInfo, devCallback);
            }
        }

        /**
         * Download data for a specific widget so that it can be displayed with a custom UI
         * When requesting this data, it will count as a shown widget (will increment that "shown" count in the dashboard)
         *
         * @param widgetInfo
         * @param callback
         */
        public void getFeedbackWidgetData(@Nullable CountlyFeedbackWidget widgetInfo, @Nullable RetrieveFeedbackWidgetData callback) {
            synchronized (internalConfig) {
                L.i("[Feedback] Trying to retrieve feedback widget data");

                getFeedbackWidgetDataInternal(widgetInfo, callback);
            }
        }

        /**
         * Manually report a feedback widget in case the client used a custom interface
         * In case widgetResult is passed as "null," it would be assumed that the widget was canceled
         *
         * @param widgetInfo
         * @param widgetData
         * @param widgetResult
         */
        public void reportFeedbackWidgetManually(@Nullable CountlyFeedbackWidget widgetInfo, @Nullable JSONObject widgetData, @Nullable Map<String, Object> widgetResult) {
            synchronized (internalConfig) {
                L.i("[Feedback] Trying to report feedback widget manually");

                reportFeedbackWidgetManuallyInternal(widgetInfo, widgetData, widgetResult);
            }
        }
    }
}
