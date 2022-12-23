package ly.count.sdk.java.internal;

import ly.count.sdk.java.Config;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import ly.count.sdk.java.Event;

/**
 * Event base class implementation
 */

class EventImpl implements Event, JSONable {
    private static final Log.Module L = Log.module("EventImpl");

    private final EventRecorder recorder;
    private String key;

    private Map<String, String> segmentation;

    private int count;
    private Double sum;
    private Double duration;

    private long timestamp;
    private int hour;
    private int dow;

    public interface EventRecorder {
        void recordEvent(Event event);
    }

    /**
     * True when some validation failed in any Event method. Results in event being discarded in
     * production mode and {@link IllegalStateException} in test mode thanks to {@link Log#wtf(String, Throwable)}.
     */
    private boolean invalid = false;

    EventImpl(EventRecorder recorder, String key) {
        if (recorder == null) {
            invalid = true;
            L.wtf("recorder cannot be null for an event");
        }
        if (key == null || "".equals(key)) {
            invalid = true;
            L.wtf("Event key cannot be null or empty");
        }
        this.recorder = recorder;
        this.key = key;
        this.count = 1;
        this.timestamp = DeviceCore.dev.uniqueTimestamp();
        this.hour = DeviceCore.dev.currentHour();
        this.dow = DeviceCore.dev.currentDayOfWeek();
    }

    private void trimEvent() {
        key = trimKey(key);
        segmentation = FixSegmentKeysAndValues(segmentation);
    }

    private String trimKey(String k) {
        Config config = SDKCore.instance.config();

        if (k.length() > config.getMaxKeyLength()) {
            L.w("[EventImpl] RecordEventInternal : Max allowed key length is " + config.getMaxKeyLength());
            k = k.substring(0, config.getMaxKeyLength());
        }

        return k;
    }

    private String trimValue(String fieldName, String v) {
        Config config = SDKCore.instance.config();
        if (v != null && v.length() > config.getMaxValueSize()) {
            L.w("[EventImpl] TrimValue : Max allowed '" + fieldName + "' length is " + config.getMaxValueSize() + ". " + v + " will be truncated.");
            v = v.substring(0, config.getMaxValueSize());
        }

        return v;
    }

    private Map<String, String> FixSegmentKeysAndValues(Map<String, String> segments) {
        if (segments == null || segments.size() == 0) {
            return segments;
        }

        Map<String, String> segmentation = new HashMap<>();

        for (Map.Entry<String, String> entry : segments.entrySet()) {
            String k = entry.getKey();
            String v = entry.getValue();
            if (k == null || k.length() == 0 || v == null) {
                continue;
            }

            k = trimKey(k);
            v = trimValue(k, v);

            segmentation.put(k, v);
        }
        return segmentation;
    }

    @Override
    public void record() {
        if (SDKCore.instance != null && SDKCore.instance.config.isBackendModeEnabled()) {
            L.w("record: Skipping event, backend mode is enabled!");
            return;
        }

        if (recorder != null && !invalid) {
            invalid = true;
            trimEvent();
            recorder.recordEvent(this);

            L.d("record: " + this.toString());
        }
    }

    @Override
    public void endAndRecord() {
        if (SDKCore.instance != null && SDKCore.instance.config.isBackendModeEnabled()) {
            L.w("endAndRecord: Skipping event, backend mode is enabled!");
            return;
        }

        setDuration((DeviceCore.dev.uniqueTimestamp() - timestamp) / 1000);
        record();
    }

    @Override
    public Event addSegment(String key, String value) {
        L.d("addSegment: key = " + key + " value = " + value);
        if (key == null || "".equals(key)) {
            invalid = true;
            L.wtf("Segmentation key " + key + " for event " + this.key + " is empty");
            return this;
        }

        if (value == null || "".equals(value)) {
            invalid = true;
            L.wtf("Segmentation value " + value + " (" + key + ") for event " + this.key + " is empty");
            return this;
        }

        if (segmentation == null) {
            segmentation = new HashMap<>();
        }

        segmentation.put(key, value);

        return this;
    }

    @Override
    public Event addSegments(String... segmentation) {
        L.d("addSegment: segmentation = " + segmentation);

        if (segmentation == null || segmentation.length == 0) {
            invalid = true;
            L.wtf("Segmentation varargs array is empty");
            return this;
        }

        if (segmentation.length % 2 != 0) {
            invalid = true;
            L.wtf("Segmentation varargs array length is not even");
            return this;
        }

        for (int i = 0; i < segmentation.length; i += 2) {
            addSegment(segmentation[i], segmentation[i + 1]);
        }
        return this;
    }

    @Override
    public Event setSegmentation(Map<String, String> segmentation) {
        L.d("setSegmentation: segmentation = " + segmentation);

        if (segmentation == null) {
            invalid = true;
            L.wtf("Segmentation map is null");
            return this;
        }

        this.segmentation = new HashMap<>();
        for (String k : segmentation.keySet()) {
            addSegment(k, segmentation.get(k));
        }

        return this;
    }

    @Override
    public Event setCount(int count) {
        L.d("setCount: count = " + count);
        if (count <= 0) {
            invalid = true;
            L.wtf("Event " + key + " count cannot be 0 or less");
            return this;
        }
        this.count = count;
        return this;
    }

    @Override
    public Event setSum(double sum) {
        L.d("setSum: sum = " + sum);
        if (Double.isInfinite(sum) || Double.isNaN(sum)) {
            invalid = true;
            L.wtf("NaN infinite value cannot be event '" + key + "' sum");
        } else {
            this.sum = sum;
        }
        return this;
    }

    @Override
    public Event setDuration(double duration) {
        L.d("setDuration: duration = " + duration);
        if (Double.isInfinite(duration) || Double.isNaN(duration) || duration < 0) {
            invalid = true;
            L.wtf("NaN, infinite or negative value cannot be event '" + key + "' duration");
        } else {
            this.duration = duration;
        }
        return this;
    }

    @Override
    public int hashCode() {
        return (key + timestamp).hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof EventImpl)) {
            return false;
        }
        EventImpl event = (EventImpl) obj;
        if (timestamp != event.timestamp) {
            return false;
        }
        if (key == null || event.key == null || !key.equals(event.key)) {
            return false;
        }
        if (count != event.count) {
            return false;
        }
        if ((sum != null && !sum.equals(event.sum) || (event.sum != null && !event.sum.equals(sum)))) {
            return false;
        }
        if ((duration != null && !duration.equals(event.duration) || (event.duration != null && !event.duration.equals(duration)))) {
            return false;
        }
        if ((segmentation != null && !segmentation.equals(event.segmentation) || (event.segmentation != null && !event.segmentation.equals(segmentation)))) {
            return false;
        }
        return true;
    }

    private static final String SEGMENTATION_KEY = "segmentation";
    private static final String KEY_KEY = "key";
    private static final String COUNT_KEY = "count";
    private static final String SUM_KEY = "sum";
    private static final String DUR_KEY = "dur";
    private static final String TIMESTAMP_KEY = "timestamp";
    private static final String DAY_OF_WEEK = "dow";
    private static final String HOUR = "hour";

    /**
     * Serialize to JSON format according to Countly server requirements
     *
     * @return JSON string
     */
    public String toJSON() {
        final JSONObject json = new JSONObject();

        try {
            json.put(KEY_KEY, key);
            json.put(COUNT_KEY, count);
            json.put(TIMESTAMP_KEY, timestamp);
            json.put(HOUR, hour);
            json.put(DAY_OF_WEEK, dow);

            if (segmentation != null) {
                json.put(SEGMENTATION_KEY, new JSONObject(segmentation));
            }

            if (sum != null) {
                json.put(SUM_KEY, sum);
            }

            if (duration != null) {
                json.put(DUR_KEY, duration);
            }
        } catch (JSONException e) {
            L.wtf("Cannot serialize event to JSON", e);
        }

        return json.toString();
    }

    /**
     * Deserialize from JSON format according to Countly server requirements
     *
     * @return JSON string
     */
    static EventImpl fromJSON(String jsonString, EventRecorder recorder) {
        try {
            JSONObject json = new JSONObject(jsonString);

            if (!json.has(KEY_KEY) || json.isNull(KEY_KEY)) {
                L.wtf("Bad JSON for deserialization of event: " + jsonString);
                return null;
            }
            EventImpl event = new EventImpl(recorder == null ? new EventRecorder() {
                @Override
                public void recordEvent(Event event) {
                    L.wtf("Shouldn't record serialized events");
                }
            } : recorder, json.getString(KEY_KEY));

            event.count = json.optInt(COUNT_KEY, 1);
            if (json.has(SUM_KEY) && !json.isNull(SUM_KEY)) {
                event.sum = json.optDouble(SUM_KEY, 0);
            }
            if (json.has(DUR_KEY) && !json.isNull(DUR_KEY)) {
                event.duration = json.optDouble(DUR_KEY, 0);
            }
            event.timestamp = json.optLong(TIMESTAMP_KEY);
            event.hour = json.optInt(HOUR);
            event.dow = json.optInt(DAY_OF_WEEK);

            if (!json.isNull(SEGMENTATION_KEY)) {
                final JSONObject segm = json.getJSONObject(SEGMENTATION_KEY);
                final HashMap<String, String> segmentation = new HashMap<String, String>(segm.length());
                final Iterator<String> nameItr = segm.keys();
                while (nameItr.hasNext()) {
                    final String key = nameItr.next();
                    if (!segm.isNull(key)) {
                        segmentation.put(key, segm.getString(key));
                    }
                }
                event.segmentation = segmentation;
            }

            return event;
        } catch (JSONException e) {
            L.wtf("Cannot deserialize event from JSON", e);
        }

        return null;
    }

    long getTimestamp() {
        return timestamp;
    }

    public String getKey() {
        return key;
    }

    public int getCount() {
        return count;
    }

    public Double getSum() {
        return sum;
    }

    public Double getDuration() {
        return duration;
    }

    public String getSegment(String key) {
        return segmentation.get(key);
    }

    public boolean isInvalid() {
        return invalid;
    }

    @Override
    public String toString() {
        return toJSON();
    }
}
