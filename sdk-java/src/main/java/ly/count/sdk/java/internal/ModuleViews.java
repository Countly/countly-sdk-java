package ly.count.sdk.java.internal;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import ly.count.sdk.java.Countly;

public class ModuleViews extends ModuleBase {
    private String currentViewID = null;
    private String previousViewID = null;
    private boolean firstView = true;
    boolean autoViewTracker = false;
    boolean automaticTrackingShouldUseShortName = false;
    final static String VIEW_EVENT_KEY = "[CLY]_view";
    Map<String, Object> automaticViewSegmentation = new HashMap<>();//automatic view segmentation
    Map<String, ViewData> viewDataMap = new HashMap<>(); // map viewIDs to its viewData
    String[] reservedSegmentationKeysViews = new String[] { "name", "visit", "start", "segment" };

    public @Nonnull String getCurrentViewId() {
        return currentViewID == null ? "" : currentViewID;
    }

    public @Nonnull String getPreviousViewId() {
        return previousViewID == null ? "" : previousViewID;
    }

    static class ViewData {
        String viewID;
        long viewStartTimeSeconds; // if this is 0 then the view is not started yet or was paused
        String viewName;
        boolean isAutoStoppedView = false;//views started with "startAutoStoppedView" would have this as "true". If set to "true" views should be automatically closed when another one is started.
    }

    //interface for SDK users
    Views viewsInterface;

    ModuleViews() {
    }

    @Override
    public void init(InternalConfig config) {
        super.init(config);
        L.v("[ModuleViews] Initializing");

        if (config.isAutomaticViewTrackingEnabled()) {
            L.d("[ModuleViews] Enabling automatic view tracking");
            autoViewTracker = config.isAutomaticViewTrackingEnabled();
        }

        if (config.isAutoTrackingUseShortNameEnabled()) {
            L.d("[ModuleViews] Enabling automatic view tracking short names");
            automaticTrackingShouldUseShortName = config.isAutoTrackingUseShortNameEnabled();
        }

        setGlobalViewSegmentationInternal(config.getGlobalViewSegmentation());
        viewsInterface = new Views();
    }

    private void removeReservedKeysAndUnsupportedTypesFromViewSegmentation(Map<String, Object> segmentation) {
        if (segmentation == null) {
            return;
        }

        List<String> gonnaDeleteKeys = new ArrayList<>();

        for (String key : reservedSegmentationKeysViews) {
            if (segmentation.containsKey(key)) {
                segmentation.remove(key);
                L.w("[ModuleViews] removeReservedKeysAndUnsupportedTypesFromViewSegmentation, You cannot use the key:[" + key + "] in your segmentation since it's reserved by the SDK");
            }
        }

        for (String key : segmentation.keySet()) {
            if (!Utils.isValidDataType(segmentation.get(key))) {
                gonnaDeleteKeys.add(key);
                L.w("[ModuleViews] removeReservedKeysAndUnsupportedTypesFromViewSegmentation, You have provided an unsupported data type in your View Segmentation. Removing the unsupported values.");
            }
        }

        gonnaDeleteKeys.forEach(segmentation::remove);
    }

    /**
     * Checks the provided Segmentation by the user. Sanitizes it
     * and transfers the data into an internal Segmentation Object.
     */
    void setGlobalViewSegmentationInternal(@Nullable Map<String, Object> segmentation) {
        L.d("[ModuleViews] Calling setGlobalViewSegmentationInternal with[" + (segmentation == null ? "null" : segmentation.size()) + "] entries");

        automaticViewSegmentation.clear();

        if (segmentation != null) {
            removeReservedKeysAndUnsupportedTypesFromViewSegmentation(segmentation);
            automaticViewSegmentation.putAll(segmentation);
        }
    }

    public void updateGlobalViewSegmentationInternal(@Nonnull Map<String, Object> segmentation) {
        removeReservedKeysAndUnsupportedTypesFromViewSegmentation(segmentation);
        automaticViewSegmentation.putAll(segmentation);
    }

    /**
     * This should be called in case a new session starts so that we could identify the new "first view"
     */
    public void resetFirstView() {
        firstView = true;
    }

    Map<String, Object> createViewEventSegmentation(@Nonnull ViewData vd, boolean firstView, boolean visit, Map<String, Object> customViewSegmentation) {
        Map<String, Object> viewSegmentation = new HashMap<>();
        if (customViewSegmentation != null) {
            viewSegmentation.putAll(customViewSegmentation);
        }

        viewSegmentation.put("name", vd.viewName);
        if (visit) {
            viewSegmentation.put("visit", "1");
        }
        if (firstView) {
            viewSegmentation.put("start", "1");
        }
        viewSegmentation.put("segment", "Android");

        return viewSegmentation;
    }

    void autoCloseRequiredViews(boolean closeAllViews, Map<String, Object> customViewSegmentation) {
        L.d("[ModuleViews] autoCloseRequiredViews");
        List<String> viewsToRemove = new ArrayList<>(1);

        for (Map.Entry<String, ViewData> entry : viewDataMap.entrySet()) {
            ViewData vd = entry.getValue();
            if (closeAllViews || vd.isAutoStoppedView) {
                viewsToRemove.add(vd.viewID);
            }
        }

        if (!viewsToRemove.isEmpty()) {
            L.d("[ModuleViews] autoCloseRequiredViews, about to close [" + viewsToRemove.size() + "] views");
        }

        removeReservedKeysAndUnsupportedTypesFromViewSegmentation(customViewSegmentation);
        viewsToRemove.forEach(s -> stopViewWithIDInternal(s, customViewSegmentation));
    }

    /**
     * Record a view manually, without automatic tracking
     * or tracks a view that is not automatically tracked
     * like a fragment, Message box or a transparent Activity
     * with segmentation if provided. (This is the internal function)
     *
     * @param viewName String - name of the view
     * @param customViewSegmentation Map<String, Object> - segmentation that will be added to the view, set 'null' if none should be added
     * @return Returns link to Countly for call chaining
     */
    @Nullable String startViewInternal(@Nullable String viewName, @Nullable Map<String, Object> customViewSegmentation, boolean viewShouldBeAutomaticallyStopped) {

        if (viewName == null || viewName.isEmpty()) {
            L.e("[ModuleViews] startViewInternal, Trying to record view with null or empty view name, ignoring request");
            return null;
        }

        removeReservedKeysAndUnsupportedTypesFromViewSegmentation(customViewSegmentation);

        int segmCount = 0;
        if (customViewSegmentation != null) {
            segmCount = customViewSegmentation.size();
        }
        L.d("[ModuleViews] Recording view with name: [" + viewName + "], previous view ID:[" + currentViewID + "] custom view segment count:[" + segmCount + "], first:[" + firstView + "], autoStop:[" + viewShouldBeAutomaticallyStopped + "]");

        //stop views that should be automatically stopped
        //no segmentation should be used in this case
        autoCloseRequiredViews(false, null);

        ViewData currentViewData = new ViewData();
        currentViewData.viewID = safeRandomVal();
        currentViewData.viewName = viewName;
        currentViewData.viewStartTimeSeconds = TimeUtils.uniqueTimestampS();
        currentViewData.isAutoStoppedView = viewShouldBeAutomaticallyStopped;

        viewDataMap.put(currentViewData.viewID, currentViewData);
        previousViewID = currentViewID;
        currentViewID = currentViewData.viewID;

        Map<String, Object> accumulatedEventSegm = new HashMap<String, Object>(automaticViewSegmentation);
        if (customViewSegmentation != null) {
            accumulatedEventSegm.putAll(customViewSegmentation);
        }

        Map<String, Object> viewSegmentation = createViewEventSegmentation(currentViewData, firstView, true, accumulatedEventSegm);

        if (firstView) {
            L.d("[ModuleViews] Recording view as the first one in the session. [" + viewName + "]");
            firstView = false;
        }

        viewSegmentation.put("_idv", currentViewData.viewID);
        Countly.instance().events().recordEvent(VIEW_EVENT_KEY, viewSegmentation, 1, 0.0, 0.0);

        return currentViewData.viewID;
    }

    void stopViewWithNameInternal(@Nullable String viewName, @Nullable Map<String, Object> customViewSegmentation) {
        if (viewName == null || viewName.isEmpty()) {
            L.e("[ModuleViews] stopViewWithNameInternal, Trying to record view with null or empty view name, ignoring request");
            return;
        }

        String viewID = null;

        for (Map.Entry<String, ViewData> entry : viewDataMap.entrySet()) {
            ViewData vd = entry.getValue();
            if (vd != null && viewName.equals(vd.viewName)) {
                viewID = entry.getKey();
            }
        }

        if (viewID == null) {
            L.e("[ModuleViews] stopViewWithNameInternal, No view entry found with the provided name :[" + viewName + "]");
            return;
        }

        stopViewWithIDInternal(viewID, customViewSegmentation);
    }

    void stopViewWithIDInternal(@Nullable String viewID, @Nullable Map<String, Object> customViewSegmentation) {
        if (viewID == null || viewID.isEmpty()) {
            L.e("[ModuleViews] stopViewWithNameInternal, Trying to record view with null or empty view ID, ignoring request");
            return;
        }
        //todo extract common checks
        if (!viewDataMap.containsKey(viewID)) {
            L.w("[ModuleViews] stopViewWithIDInternal, there is no view with the provided view id to close");
            return;
        }

        ViewData vd = viewDataMap.get(viewID);
        if (vd == null) {
            L.e("[ModuleViews] stopViewWithIDInternal, view id:[" + viewID + "] has a 'null' value. This should not be happening");
            return;
        }

        L.d("[ModuleViews] View [" + vd.viewName + "], id:[" + vd.viewID + "] is getting closed, reporting duration: [" + (TimeUtils.uniqueTimestampS() - vd.viewStartTimeSeconds) + "] s, current timestamp: [" + TimeUtils.uniqueTimestampMs() + "]");
        recordViewEndEvent(vd, customViewSegmentation, "stopViewWithIDInternal");

        viewDataMap.remove(vd.viewID);
    }

    void recordViewEndEvent(ViewData vd, @Nullable Map<String, Object> filteredCustomViewSegmentation, String viewRecordingSource) {
        long lastElapsedDurationSeconds = 0;
        //we sanity check the time component and print error in case of problem
        if (vd.viewStartTimeSeconds < 0) {
            L.e("[ModuleViews] " + viewRecordingSource + ", view start time value is not normal: [" + vd.viewStartTimeSeconds + "], ignoring that duration");
        } else if (vd.viewStartTimeSeconds == 0) {
            L.i("[ModuleViews] " + viewRecordingSource + ", view is either paused or didn't run, ignoring start timestamp");
        } else {
            lastElapsedDurationSeconds = TimeUtils.uniqueTimestampS() - vd.viewStartTimeSeconds;
        }

        //only record view if the view name is not null
        if (vd.viewName == null) {
            L.e("[ModuleViews] stopViewWithIDInternal, view has no internal name, ignoring it");
            return;
        }

        Map<String, Object> accumulatedEventSegm = new HashMap<String, Object>(automaticViewSegmentation);
        if (filteredCustomViewSegmentation != null) {
            accumulatedEventSegm.putAll(filteredCustomViewSegmentation);
        }

        long viewDurationSeconds = lastElapsedDurationSeconds;
        Map<String, Object> segments = createViewEventSegmentation(vd, false, false, accumulatedEventSegm);
        segments.put("_idv", vd.viewID);
        Countly.instance().events().recordEvent(VIEW_EVENT_KEY, segments, 1, 0.0, new Long(viewDurationSeconds).doubleValue());
    }

    void pauseViewWithIDInternal(String viewID) {
        if (viewID == null || viewID.isEmpty()) {
            L.e("[ModuleViews] pauseViewWithIDInternal, Trying to record view with null or empty view ID, ignoring request");
            return;
        }

        if (!viewDataMap.containsKey(viewID)) {
            L.w("[ModuleViews] pauseViewWithIDInternal, there is no view with the provided view id to close");
            return;
        }

        ViewData vd = viewDataMap.get(viewID);
        if (vd == null) {
            L.e("[ModuleViews] pauseViewWithIDInternal, view id:[" + viewID + "] has a 'null' value. This should not be happening");
            return;
        }

        L.d("[ModuleViews] pauseViewWithIDInternal, pausing view for ID:[" + viewID + "], name:[" + vd.viewName + "]");

        if (vd.viewStartTimeSeconds == 0) {
            L.w("[ModuleViews] pauseViewWithIDInternal, pausing a view that is already paused. ID:[" + viewID + "], name:[" + vd.viewName + "]");
            return;
        }

        recordViewEndEvent(vd, null, "pauseViewWithIDInternal");

        vd.viewStartTimeSeconds = 0;
    }

    void resumeViewWithIDInternal(String viewID) {
        if (viewID == null || viewID.isEmpty()) {
            L.e("[ModuleViews] resumeViewWithIDInternal, Trying to record view with null or empty view ID, ignoring request");
            return;
        }

        if (!viewDataMap.containsKey(viewID)) {
            L.w("[ModuleViews] resumeViewWithIDInternal, there is no view with the provided view id to close");
            return;
        }

        ViewData vd = viewDataMap.get(viewID);
        if (vd == null) {
            L.e("[ModuleViews] resumeViewWithIDInternal, view id:[" + viewID + "] has a 'null' value. This should not be happening");
            return;
        }

        L.d("[ModuleViews] resumeViewWithIDInternal, resuming view for ID:[" + viewID + "], name:[" + vd.viewName + "]");

        if (vd.viewStartTimeSeconds > 0) {
            L.w("[ModuleViews] resumeViewWithIDInternal, resuming a view that is already running. ID:[" + viewID + "], name:[" + vd.viewName + "]");
            return;
        }

        vd.viewStartTimeSeconds = TimeUtils.uniqueTimestampS();
    }

    void stopAllViewsInternal(Map<String, Object> viewSegmentation) {
        L.d("[ModuleViews] stopAllViewsInternal");

        autoCloseRequiredViews(true, viewSegmentation);
    }

    @Override
    public void stop(InternalConfig config, boolean clear) {
        if (automaticViewSegmentation != null) {
            automaticViewSegmentation.clear();
            automaticViewSegmentation = null;
        }
        viewsInterface = null;
    }

    /**
     * Creates a crypto-safe SHA-256 hashed random value
     *
     * @return returns a random string value
     */
    public static String safeRandomVal() {
        long timestamp = System.currentTimeMillis();
        SecureRandom random = new SecureRandom();
        byte[] value = new byte[6];
        random.nextBytes(value);
        String b64Value = Utils.Base64.encode(value);
        return b64Value + timestamp;
    }

    public class Views {

        /**
         * Record a view manually, without automatic tracking
         * or tracks a view that is not automatically tracked
         * like a fragment, Message box or a transparent Activity
         *
         * @param viewName String - name of the view
         * @return Returns View ID
         */
        public String startAutoStoppedView(@Nullable String viewName) {
            synchronized (Countly.instance()) {
                // call the general function that has two parameters
                return startAutoStoppedView(viewName, null);
            }
        }

        /**
         * Record a view manually, without automatic tracking
         * or tracks a view that is not automatically tracked
         * like a fragment, Message box or a transparent Activity
         * with segmentation. (This is the main function that is used)
         *
         * @param viewName String - name of the view
         * @param viewSegmentation Map<String, Object> - segmentation that will be added to the view, set 'null' if none should be added
         * @return String - view ID
         */
        public String startAutoStoppedView(@Nullable String viewName, @Nullable Map<String, Object> viewSegmentation) {
            synchronized (Countly.instance()) {
                L.i("[Views] Calling startAutoStoppedView [" + viewName + "]");

                if (autoViewTracker) {
                    L.e("[Views] startAutoStoppedView, manual view call will be ignored since automatic tracking is enabled.");
                    return null;
                }

                return startViewInternal(viewName, viewSegmentation, true);
            }
        }

        /**
         * Starts a view which would not close automatically (For multi view tracking)
         *
         * @param viewName - String
         * @return String - View ID
         */
        public @Nullable String startView(@Nullable String viewName) {
            synchronized (Countly.instance()) {
                L.i("[Views] Calling startView vn[" + viewName + "]");

                if (autoViewTracker) {
                    L.e("[Views] startView, manual view call will be ignored since automatic tracking is enabled.");
                    return null;
                }

                return startViewInternal(viewName, null, false);
            }
        }

        /**
         * Starts a view which would not close automatically (For multi view tracking)
         *
         * @param viewName String - name of the view
         * @param viewSegmentation Map<String, Object> - segmentation that will be added to the view, set 'null' if none should be added
         * @return String - View ID
         */
        public @Nullable String startView(@Nullable String viewName, @Nullable Map<String, Object> viewSegmentation) {
            synchronized (Countly.instance()) {
                L.i("[Views] Calling startView vn[" + viewName + "] sg[" + (viewSegmentation == null ? viewSegmentation : viewSegmentation.size()) + "]");

                if (autoViewTracker) {
                    L.e("[Views] startView, manual view call will be ignored since automatic tracking is enabled.");
                    return null;
                }

                return startViewInternal(viewName, viewSegmentation, false);
            }
        }

        /**
         * Stops a view with the given name if it was open
         *
         * @param viewName String - view name
         */
        public void stopViewWithName(@Nullable String viewName) {
            synchronized (Countly.instance()) {
                L.i("[Views] Calling stopViewWithName vn[" + viewName + "]");

                stopViewWithNameInternal(viewName, null);
            }
        }

        /**
         * Stops a view with the given name if it was open
         *
         * @param viewName String - view name
         * @param viewSegmentation Map<String, Object> - view segmentation
         */
        public void stopViewWithName(@Nullable String viewName, @Nullable Map<String, Object> viewSegmentation) {
            synchronized (Countly.instance()) {
                L.i("[Views] Calling stopViewWithName vn[" + viewName + "] sg[" + (viewSegmentation == null ? viewSegmentation : viewSegmentation.size()) + "]");

                stopViewWithNameInternal(viewName, viewSegmentation);
            }
        }

        /**
         * Stops a view with the given ID if it was open
         *
         * @param viewID String - view ID
         */
        public void stopViewWithID(@Nullable String viewID) {
            synchronized (Countly.instance()) {
                L.i("[Views] Calling stopViewWithID vi[" + viewID + "]");

                stopViewWithIDInternal(viewID, null);
            }
        }

        /**
         * Stops a view with the given ID if it was open
         *
         * @param viewID String - view ID
         * @param viewSegmentation Map<String, Object> - view segmentation
         */
        public void stopViewWithID(@Nullable String viewID, @Nullable Map<String, Object> viewSegmentation) {
            synchronized (Countly.instance()) {
                L.i("[Views] Calling stopViewWithName vi[" + viewID + "] sg[" + (viewSegmentation == null ? viewSegmentation : viewSegmentation.size()) + "]");

                stopViewWithIDInternal(viewID, viewSegmentation);
            }
        }

        /**
         * Pauses a view with the given ID
         *
         * @param viewID String - view ID
         */
        public void pauseViewWithID(@Nullable String viewID) {
            synchronized (Countly.instance()) {
                L.i("[Views] Calling pauseViewWithID vi[" + viewID + "]");

                pauseViewWithIDInternal(viewID);
            }
        }

        /**
         * Resumes a view with the given ID
         *
         * @param viewID String - view ID
         */
        public void resumeViewWithID(@Nullable String viewID) {
            synchronized (Countly.instance()) {
                L.i("[Views] Calling resumeViewWithID vi[" + viewID + "]");

                resumeViewWithIDInternal(viewID);
            }
        }

        /**
         * Set a segmentation to be recorded with all views
         *
         * @param segmentation Map<String, Object> - global view segmentation
         */
        public void setGlobalViewSegmentation(@Nullable Map<String, Object> segmentation) {
            synchronized (Countly.instance()) {
                L.i("[Views] Calling setGlobalViewSegmentation sg[" + (segmentation == null ? segmentation : segmentation.size()) + "]");

                setGlobalViewSegmentationInternal(segmentation);
            }
        }

        /**
         * Updates the global segmentation for views
         *
         * @param segmentation Map<String, Object> - global view segmentation
         */
        public void updateGlobalViewSegmentation(@Nullable Map<String, Object> segmentation) {
            synchronized (Countly.instance()) {
                L.i("[Views] Calling updateGlobalViewSegmentation sg[" + (segmentation == null ? segmentation : segmentation.size()) + "]");

                if (segmentation == null) {
                    L.w("[View] When updating segmentation values, they can't be 'null'.");
                    return;
                }

                updateGlobalViewSegmentationInternal(segmentation);
            }
        }

        /**
         * Stops all views and records a segmentation if set
         *
         * @param viewSegmentation Map<String, Object> - view segmentation
         */
        public void stopAllViews(@Nullable Map<String, Object> viewSegmentation) {
            synchronized (Countly.instance()) {
                L.i("[Views] Calling stopAllViews sg[" + (viewSegmentation == null ? viewSegmentation : viewSegmentation.size()) + "]");

                stopAllViewsInternal(viewSegmentation);
            }
        }
    }
}
