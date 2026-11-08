package ly.count.sdk.java.internal;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The counters of the SDK health check: warnings and errors logged, the last failed request,
 * backoffs, method usage ("fu") and log codes ("lc"), kept since the last health check the server
 * accepted. Logs at debug level only, as every warning and error the SDK logs is counted here.
 */
public class HealthCheckCounter {
    static final int COUNT_CAP = 65535;
    static final int MAX_ERROR_MESSAGE_LENGTH = 1000;

    static final String KEY_LOG_ERROR = "LErr";
    static final String KEY_LOG_WARNING = "LWar";
    static final String KEY_STATUS_CODE = "RStatC";
    static final String KEY_ERROR_MESSAGE = "REMsg";
    static final String KEY_BACKOFF_REQUEST = "BReq";
    static final String KEY_CONSECUTIVE_BACKOFF_REQUEST = "CBReq";
    static final String KEY_METHOD_USAGE = "MU";
    static final String KEY_LOG_CODES = "LC";

    static final String REQUEST_KEY_ERROR_COUNT = "el";
    static final String REQUEST_KEY_WARNING_COUNT = "wl";
    static final String REQUEST_KEY_STATUS_CODE = "sc";
    static final String REQUEST_KEY_ERROR_MESSAGE = "em";
    static final String REQUEST_KEY_BACKOFF_REQUEST = "bom";
    static final String REQUEST_KEY_CONSECUTIVE_BACKOFF_REQUEST = "cbom";
    static final String REQUEST_KEY_METHOD_USAGE = "fu";
    static final String REQUEST_KEY_LOG_CODES = "lc";

    static final String AREA_SESSIONS = "sessions";
    static final String AREA_EVENTS = "events";
    static final String AREA_VIEWS = "views";
    static final String AREA_CRASHES = "crashes";

    long countLogWarning = 0;
    long countLogError = 0;
    int statusCode = -1;
    String errorMessage = "";
    long countBackoffRequest = 0;
    int consecutiveBackoffRequest = 0;
    private int consecutiveBackoffRequestCounter = 0;
    final Map<String, Map<String, Integer>> methodUsage = new HashMap<>();
    final Map<String, Integer> logCodes = new HashMap<>();

    private volatile boolean recordingStoppedForCrash = false;
    private final StorageProvider storageProvider;
    private final Log L;
    private final Predicate<CoreFeature> consentGiven;

    /**
     * Creates the counters from the state a previous run stored, and adds the automatically
     * detected crashes that run counted to "crashes" / "record:a".
     *
     * @param storageProvider the storage the state is kept in
     * @param logger the SDK logger, used at debug level only
     * @param consentGiven whether a feature has consent now, {@code true} for every feature while no consent is required
     */
    public HealthCheckCounter(@Nonnull StorageProvider storageProvider, @Nonnull Log logger, @Nonnull Predicate<CoreFeature> consentGiven) {
        this.storageProvider = storageProvider;
        this.L = logger;
        this.consentGiven = consentGiven;

        setupInitialCounters(storageProvider.getHealthCheckState());

        int automaticCrashes = storageProvider.takeAutomaticCrashCount();
        if (automaticCrashes > 0) {
            synchronized (this) {
                addUsage(AREA_CRASHES, "record:a", automaticCrashes);
            }
            saveState();
        }
    }

    /**
     * Adds the counters a previous run stored to the current ones. A stored state that is not valid
     * JSON is reset and reported as log code e503. An entry of the wrong type is skipped.
     *
     * @param initialState the stored state, {@code null} or empty when nothing was stored
     */
    void setupInitialCounters(@Nullable String initialState) {
        if (initialState == null || initialState.isEmpty()) {
            return;
        }

        JSONObject stored;
        try {
            stored = new JSONObject(initialState);
        } catch (JSONException e) {
            clearAndSave();
            synchronized (this) {
                addLogCode("e503", 1);
            }
            L.d("[HealthCheckCounter] setupInitialCounters, the stored health check state could not be read and is reset, [" + e + "]");
            return;
        }

        synchronized (this) {
            countLogWarning += optLong(stored, KEY_LOG_WARNING);
            countLogError += optLong(stored, KEY_LOG_ERROR);
            if (statusCode == -1 && stored.opt(KEY_STATUS_CODE) instanceof Number) {
                statusCode = ((Number) stored.opt(KEY_STATUS_CODE)).intValue();
            }
            if (errorMessage.isEmpty() && stored.opt(KEY_ERROR_MESSAGE) instanceof String) {
                errorMessage = (String) stored.opt(KEY_ERROR_MESSAGE);
            }
            countBackoffRequest += optLong(stored, KEY_BACKOFF_REQUEST);
            consecutiveBackoffRequest = (int) Math.max(consecutiveBackoffRequest, optLong(stored, KEY_CONSECUTIVE_BACKOFF_REQUEST));

            JSONObject storedUsage = stored.optJSONObject(KEY_METHOD_USAGE);
            if (storedUsage != null) {
                Iterator<String> areas = storedUsage.keys();
                while (areas.hasNext()) {
                    String area = areas.next();
                    JSONObject methods = storedUsage.optJSONObject(area);
                    if (methods == null) {
                        continue;
                    }
                    Iterator<String> methodNames = methods.keys();
                    while (methodNames.hasNext()) {
                        String method = methodNames.next();
                        if (methods.opt(method) instanceof Number) {
                            addUsage(area, method, ((Number) methods.opt(method)).intValue());
                        }
                    }
                }
            }

            JSONObject storedCodes = stored.optJSONObject(KEY_LOG_CODES);
            if (storedCodes != null) {
                Iterator<String> codes = storedCodes.keys();
                while (codes.hasNext()) {
                    String code = codes.next();
                    if (storedCodes.opt(code) instanceof Number) {
                        addLogCode(code, ((Number) storedCodes.opt(code)).intValue());
                    }
                }
            }
        }

        L.d("[HealthCheckCounter] setupInitialCounters, loaded the stored health check state [" + stored + "]");
    }

    private static long optLong(@Nonnull JSONObject json, @Nonnull String key) {
        Object value = json.opt(key);
        return value instanceof Number ? Math.max(0, ((Number) value).longValue()) : 0;
    }

    /**
     * Counts one warning the SDK logged.
     */
    void logWarning() {
        synchronized (this) {
            countLogWarning++;
        }
    }

    /**
     * Counts one error the SDK logged.
     */
    void logError() {
        synchronized (this) {
            countLogError++;
        }
    }

    /**
     * Keeps the answer of the server to a queued request it did not accept. Only the most recent
     * one is kept.
     *
     * @param statusCode the HTTP status code, ignored unless above 0 and below 1000
     * @param errorResponse the body of the answer, cut to 1000 characters
     */
    void logFailedNetworkRequest(int statusCode, @Nullable String errorResponse) {
        if (statusCode <= 0 || statusCode >= 1000) {
            return;
        }

        String response = errorResponse == null ? "" : errorResponse;
        synchronized (this) {
            this.statusCode = statusCode;
            this.errorMessage = response.length() > MAX_ERROR_MESSAGE_LENGTH ? response.substring(0, MAX_ERROR_MESSAGE_LENGTH) : response;
        }
    }

    /**
     * Counts a backoff of the request queue and extends the current run of backoffs in a row. The
     * longest run ("cbom") is raised right away, so no save has to end the run to keep it. Records
     * log code w320, and w321 when the backoff continues a run. The run is kept in memory only, so
     * a restart ends it.
     */
    void logBackoffRequest() {
        synchronized (this) {
            boolean continuesRun = consecutiveBackoffRequestCounter > 0;
            countBackoffRequest++;
            consecutiveBackoffRequestCounter++;
            consecutiveBackoffRequest = Math.max(consecutiveBackoffRequest, consecutiveBackoffRequestCounter);

            if (!recordingStoppedForCrash) {
                addLogCode("w320", 1);
                if (continuesRun) {
                    addLogCode("w321", 1);
                }
            }
        }
    }

    /**
     * Ends the current run of backoffs in a row, when a queued request was answered in time. The
     * longest run is already kept by {@link #logBackoffRequest()}.
     */
    void logConsecutiveBackoffRequest() {
        synchronized (this) {
            consecutiveBackoffRequestCounter = 0;
        }
    }

    /**
     * Counts one call of a method for the "fu" map, under its feature area and method name. A call
     * the SDK made by itself carries ":a", a deprecated method ":d". Nothing is counted while consent
     * is required and the feature of the area has no consent, or while an automatic crash is reported.
     *
     * @param area the feature area: "sessions", "events", "views" or "crashes"
     * @param method the method name with its modifiers
     */
    void recordUsage(@Nullable String area, @Nullable String method) {
        if (area == null || method == null || area.isEmpty() || method.isEmpty() || recordingStoppedForCrash) {
            return;
        }

        if (!hasConsentForUsageArea(area)) {
            return;
        }

        synchronized (this) {
            addUsage(area, method, 1);
        }
    }

    /**
     * Counts occurrences of a log code for the "lc" map, a level letter and three digits such as "w740".
     *
     * @param code the log code
     * @param count how many times the condition occurred
     */
    void recordLogCode(@Nullable String code, int count) {
        if (code == null || code.isEmpty() || count <= 0 || recordingStoppedForCrash) {
            return;
        }

        synchronized (this) {
            addLogCode(code, count);
        }
    }

    /**
     * Counts one occurrence of a log code that a public method of a feature area records. Like
     * {@link #recordUsage(String, String)}, nothing is counted while consent is required and the
     * feature of the area has no consent.
     *
     * @param code the log code
     * @param area the feature area of the method that recorded it
     */
    void recordLogCode(@Nullable String code, @Nullable String area) {
        if (!hasConsentForUsageArea(area)) {
            return;
        }

        recordLogCode(code, 1);
    }

    /**
     * Makes method usage and log codes ignore their calls while an automatically detected crash is
     * reported, so the report never counts into these maps.
     */
    void stopRecordingForCrash() {
        recordingStoppedForCrash = true;
    }

    /**
     * Lets method usage and log codes count again after an automatically detected crash was
     * reported. A Java process outlives a crashed thread, so counting goes on once the report is stored.
     */
    void resumeRecordingAfterCrash() {
        recordingStoppedForCrash = false;
    }

    /**
     * Whether calls of a method usage area are counted: the feature behind it has consent, or no
     * consent is required. An unknown area is never counted.
     *
     * @param area the method usage area
     * @return {@code true} when calls of the area are counted
     */
    boolean hasConsentForUsageArea(@Nullable String area) {
        if (area == null) {
            return false;
        }

        switch (area) {
            case AREA_SESSIONS:
                return consentGiven.test(CoreFeature.Sessions);
            case AREA_EVENTS:
                return consentGiven.test(CoreFeature.Events);
            case AREA_VIEWS:
                return consentGiven.test(CoreFeature.Views);
            case AREA_CRASHES:
                return consentGiven.test(CoreFeature.CrashReporting);
            default:
                return false;
        }
    }

    /**
     * Adds to the count of a method, up to the cap a health check reports. Call while holding this
     * object's lock.
     */
    private void addUsage(@Nonnull String area, @Nonnull String method, int count) {
        if (count <= 0) {
            return;
        }

        Map<String, Integer> methods = methodUsage.computeIfAbsent(area, key -> new HashMap<>());
        Integer current = methods.get(method);
        methods.put(method, (int) Math.min((long) (current == null ? 0 : current) + count, COUNT_CAP));
    }

    /**
     * Adds to the count of a log code, up to the cap a health check reports. Call while holding this
     * object's lock.
     */
    private void addLogCode(@Nonnull String code, int count) {
        if (count <= 0) {
            return;
        }

        Integer current = logCodes.get(code);
        logCodes.put(code, (int) Math.min((long) (current == null ? 0 : current) + count, COUNT_CAP));
    }

    /**
     * Resets every counter to its default and clears the stored state.
     */
    void clearAndSave() {
        synchronized (this) {
            countLogWarning = 0;
            countLogError = 0;
            statusCode = -1;
            errorMessage = "";
            countBackoffRequest = 0;
            consecutiveBackoffRequest = 0;
            consecutiveBackoffRequestCounter = 0;
            methodUsage.clear();
            logCodes.clear();
        }
        storageProvider.setHealthCheckState("");
    }

    /**
     * Stores the counters. The current run of backoffs in a row goes on, as "cbom" already holds
     * the longest run. The storage is written outside this object's lock, since writing can log.
     */
    void saveState() {
        String state;
        synchronized (this) {
            JSONObject json = new JSONObject();
            json.put(KEY_LOG_WARNING, countLogWarning);
            json.put(KEY_LOG_ERROR, countLogError);
            json.put(KEY_STATUS_CODE, statusCode);
            json.put(KEY_ERROR_MESSAGE, errorMessage);
            json.put(KEY_BACKOFF_REQUEST, countBackoffRequest);
            json.put(KEY_CONSECUTIVE_BACKOFF_REQUEST, consecutiveBackoffRequest);
            json.put(KEY_METHOD_USAGE, usageToJson());
            json.put(KEY_LOG_CODES, new JSONObject(logCodes));
            state = json.toString();
        }

        storageProvider.setHealthCheckState(state);
    }

    /**
     * The method usage counts as a JSON object. Call while holding this object's lock.
     */
    @Nonnull
    private JSONObject usageToJson() {
        JSONObject usage = new JSONObject();
        for (Map.Entry<String, Map<String, Integer>> area : methodUsage.entrySet()) {
            usage.put(area.getKey(), new JSONObject(area.getValue()));
        }
        return usage;
    }

    /**
     * The health check parameter as it is sent, taken from the current counters. "fu" and "lc" are
     * always present, empty when nothing was counted, so the server can tell an SDK that reports
     * them from one that does not.
     *
     * @return a copy of the counters that later counting does not change
     */
    @Nonnull
    JSONObject snapshot() {
        JSONObject json = new JSONObject();
        synchronized (this) {
            json.put(REQUEST_KEY_ERROR_COUNT, countLogError);
            json.put(REQUEST_KEY_WARNING_COUNT, countLogWarning);
            json.put(REQUEST_KEY_STATUS_CODE, statusCode);
            json.put(REQUEST_KEY_ERROR_MESSAGE, errorMessage);
            json.put(REQUEST_KEY_BACKOFF_REQUEST, countBackoffRequest);
            json.put(REQUEST_KEY_CONSECUTIVE_BACKOFF_REQUEST, consecutiveBackoffRequest);
            json.put(REQUEST_KEY_METHOD_USAGE, usageToJson());
            json.put(REQUEST_KEY_LOG_CODES, new JSONObject(logCodes));
        }
        return json;
    }

    /**
     * Takes away what a health check the server accepted reported, so what was counted while it was
     * on its way is reported next time. The last failed request and the longest backoff run are
     * cleared only when they did not change since. Stores what is left.
     *
     * @param sent the snapshot the delivered health check was built from
     */
    void clearSentValues(@Nonnull JSONObject sent) {
        synchronized (this) {
            countLogError = Math.max(0, countLogError - sent.optLong(REQUEST_KEY_ERROR_COUNT, 0));
            countLogWarning = Math.max(0, countLogWarning - sent.optLong(REQUEST_KEY_WARNING_COUNT, 0));
            countBackoffRequest = Math.max(0, countBackoffRequest - sent.optLong(REQUEST_KEY_BACKOFF_REQUEST, 0));

            if (consecutiveBackoffRequest == sent.optInt(REQUEST_KEY_CONSECUTIVE_BACKOFF_REQUEST, -1)) {
                consecutiveBackoffRequest = 0;
            }

            if (statusCode == sent.optInt(REQUEST_KEY_STATUS_CODE, -2) && errorMessage.equals(sent.optString(REQUEST_KEY_ERROR_MESSAGE, ""))) {
                statusCode = -1;
                errorMessage = "";
            }

            JSONObject sentUsage = sent.optJSONObject(REQUEST_KEY_METHOD_USAGE);
            if (sentUsage != null) {
                Iterator<String> areas = sentUsage.keys();
                while (areas.hasNext()) {
                    String area = areas.next();
                    JSONObject sentMethods = sentUsage.optJSONObject(area);
                    Map<String, Integer> counted = methodUsage.get(area);
                    if (sentMethods == null || counted == null) {
                        continue;
                    }
                    Iterator<String> methods = sentMethods.keys();
                    while (methods.hasNext()) {
                        String method = methods.next();
                        subtract(counted, method, sentMethods.optInt(method, 0));
                    }
                    if (counted.isEmpty()) {
                        methodUsage.remove(area);
                    }
                }
            }

            JSONObject sentCodes = sent.optJSONObject(REQUEST_KEY_LOG_CODES);
            if (sentCodes != null) {
                Iterator<String> codes = sentCodes.keys();
                while (codes.hasNext()) {
                    String code = codes.next();
                    subtract(logCodes, code, sentCodes.optInt(code, 0));
                }
            }
        }

        saveState();
    }

    /**
     * Takes a reported amount away from a count, removing the count when nothing is left.
     */
    private static void subtract(@Nonnull Map<String, Integer> counts, @Nonnull String key, int reported) {
        Integer current = counts.get(key);
        int left = (current == null ? 0 : current) - reported;
        if (left > 0) {
            counts.put(key, left);
        } else {
            counts.remove(key);
        }
    }
}
