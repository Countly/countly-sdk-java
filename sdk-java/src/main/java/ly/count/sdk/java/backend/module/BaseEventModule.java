package ly.count.sdk.java.backend.module;

import ly.count.sdk.java.backend.controller.RequestController;
import ly.count.sdk.java.backend.helper.ClyLogger;
import ly.count.sdk.java.backend.helper.TimeHelper;
import org.json.JSONObject;

import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;

public abstract class BaseEventModule extends BaseModule {

    private final TimeHelper timeHelper = new TimeHelper();
    private final Queue<JSONObject> eventQ = new LinkedList<>();

    protected final String NPSEvent = "[CLY]_nps";
    protected final String ViewEvent = "[CLY]_view";
    protected final String SurveyEvent = "[CLY]_survey";
    protected final String ViewActionEvent = "[CLY]_action";
    protected final String StarRatingEvent = "[CLY]_star_rating";
    protected final String PushActionEvent = "[CLY]_push_action";
    protected final String OrientationEvent = "[CLY]_orientation";

    protected BaseEventModule(RequestController requestController, ClyLogger logger) {
        super(requestController, logger);
    }

    protected void recordEventInternal(String deviceID, String key, int count, double sum, double dur, Map<String, String> segmentation, long timestamp) {
        TimeHelper.TimeInstant timeInstant = timestamp < 1 ? timeHelper.getUniqueInstant() : TimeHelper.TimeInstant.get(timestamp);
        JSONObject jsonObject = buildJSONObject(deviceID, key, count, sum, dur, segmentation, timeInstant);
        eventQ.add(jsonObject);
    }

    private JSONObject buildJSONObject(String deviceID, String key, int count, double sum, double dur, Map<String, String> segmentation, TimeHelper.TimeInstant timeInstant) {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("key", key);
        jsonObject.put("sum", sum);
        jsonObject.put("dur", dur);
        jsonObject.put("count", count);

        jsonObject.put("segmentation", segmentation);

        jsonObject.put("dow", timeInstant.dow);
        jsonObject.put("hour", timeInstant.hour);
        jsonObject.put("timestamp", timeInstant.timestamp);

        return jsonObject;
    }

    private void addEventsToRequestQueue() {

    }
}
