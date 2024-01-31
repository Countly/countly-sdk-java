package ly.count.sdk.java.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import ly.count.sdk.java.Countly;

public class ModuleViews extends ModuleBase implements ViewIdProvider {
    String currentViewID = null;
    String previousViewID = null;
    private boolean firstView = true;
    static final String KEY_VIEW_EVENT = "[CLY]_view";
    static final String KEY_NAME = "name";
    static final String KEY_VISIT = "visit";
    static final String KEY_VISIT_VALUE = "1";
    static final String KEY_SEGMENT = "segment";
    static final String KEY_START = "start";
    static final String KEY_START_VALUE = "1";
    Map<String, ViewData> viewDataMap = new LinkedHashMap<>(); // map viewIDs to its viewData
    String[] reservedSegmentationKeysViews = new String[] { KEY_NAME, KEY_VISIT, KEY_START, KEY_SEGMENT };
    //interface for SDK users
    Views viewsInterface;
    IdGenerator idGenerator;
    Map<String, Object> globalViewSegmentation = new ConcurrentHashMap<>();

    static class ViewData {
        String viewID;
        long viewStartTimeSeconds; // if this is 0 then the view is not started yet or was paused
        String viewName;
        boolean isAutoStoppedView = false;//views started with "startAutoStoppedView" would have this as "true". If set to "true" views should be automatically closed when another one is started.
        Map<String, Object> viewSegmentation = new ConcurrentHashMap<>();
    }

    ModuleViews() {
    }

    @Override
    public void init(InternalConfig config) {
        super.init(config);
        L.v("[ModuleViews] Initializing");
        viewsInterface = new Views();

        setGlobalViewSegmentationInternal(config.views.globalViewSegmentation);

        idGenerator = config.viewIdGenerator;
        config.viewIdProvider = this;
    }

    @Override
    public void deviceIdChanged(String oldDeviceId, boolean withMerge) {
        super.deviceIdChanged(oldDeviceId, withMerge);
        L.d("[ModuleViews] deviceIdChanged: oldDeviceId = " + oldDeviceId + ", withMerge = " + withMerge);
        if (!withMerge) {
            stopAllViewsInternal(null);
        }
    }

    @Override
    public void stop(InternalConfig config, boolean clear) {
        viewsInterface = null;
        viewDataMap.clear();
        if (globalViewSegmentation != null) {
            globalViewSegmentation.clear();
            globalViewSegmentation = null;
        }
    }

    private void removeReservedKeysFromViewSegmentation(Map<String, Object> segmentation) {
        if (segmentation == null) {
            return;
        }

        for (String key : reservedSegmentationKeysViews) {
            if (segmentation.containsKey(key)) {
                segmentation.remove(key);
                L.w("[ModuleViews] removeReservedKeysAndUnsupportedTypesFromViewSegmentation, You cannot use the key:[" + key + "] in your segmentation since it's reserved by the SDK");
            }
        }
    }

    /**
     * Checks the provided Segmentation by the user. Sanitizes it
     * and transfers the data into an internal Segmentation Object.
     */
    void setGlobalViewSegmentationInternal(@Nullable Map<String, Object> segmentation) {
        L.d("[ModuleViews] setGlobalViewSegmentationInternal, with[" + (segmentation == null ? "null" : segmentation.size()) + "] entries");

        globalViewSegmentation.clear();

        if (segmentation != null && !segmentation.isEmpty()) {
            removeReservedKeysFromViewSegmentation(segmentation);
            Utils.removeInvalidDataFromSegments(segmentation, L);
            globalViewSegmentation.putAll(segmentation);
        }
    }

    public void updateGlobalViewSegmentationInternal(@Nonnull Map<String, Object> segmentation) {
        removeReservedKeysFromViewSegmentation(segmentation);
        Utils.removeInvalidDataFromSegments(segmentation, L);

        globalViewSegmentation.putAll(segmentation);
    }

    private Map<String, Object> createViewEventSegmentation(@Nonnull ViewData vd, boolean firstView, boolean visit, Map<String, Object> customViewSegmentation) {
        Map<String, Object> viewSegmentation = new ConcurrentHashMap<>();

        viewSegmentation.put(KEY_NAME, vd.viewName);
        if (visit) {
            viewSegmentation.put(KEY_VISIT, KEY_VISIT_VALUE);
        }
        if (firstView) {
            viewSegmentation.put(KEY_START, KEY_START_VALUE);
        }
        viewSegmentation.put(KEY_SEGMENT, internalConfig.getSdkPlatform());
        if (customViewSegmentation != null) {
            viewSegmentation.putAll(customViewSegmentation);
        }
        viewSegmentation.putAll(vd.viewSegmentation);
        viewSegmentation.putAll(globalViewSegmentation);

        return viewSegmentation;
    }

    private void autoCloseRequiredViews(boolean closeAllViews, Map<String, Object> customViewSegmentation) {
        L.d("[ModuleViews] autoCloseRequiredViews");
        List<String> viewsToRemove = new ArrayList<>();

        for (Map.Entry<String, ViewData> entry : viewDataMap.entrySet()) {
            ViewData vd = entry.getValue();
            if (closeAllViews || vd.isAutoStoppedView) {
                viewsToRemove.add(vd.viewID);
            }
        }

        if (!viewsToRemove.isEmpty()) {
            L.d("[ModuleViews] autoCloseRequiredViews, about to close [" + viewsToRemove.size() + "] views");
        }

        removeReservedKeysFromViewSegmentation(customViewSegmentation);
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

        removeReservedKeysFromViewSegmentation(customViewSegmentation);

        int segmCount = 0;
        if (customViewSegmentation != null) {
            segmCount = customViewSegmentation.size();
        }
        L.d("[ModuleViews] Recording view with name: [" + viewName + "], previous view ID:[" + currentViewID + "] custom view segment count:[" + segmCount + "], first:[" + firstView + "], autoStop:[" + viewShouldBeAutomaticallyStopped + "]");

        //stop views that should be automatically stopped
        //no segmentation should be used in this case
        autoCloseRequiredViews(false, null);

        ViewData currentViewData = new ViewData();
        currentViewData.viewID = idGenerator.generateId();
        currentViewData.viewName = viewName;
        currentViewData.viewStartTimeSeconds = TimeUtils.uniqueTimestampS();
        currentViewData.isAutoStoppedView = viewShouldBeAutomaticallyStopped;

        viewDataMap.put(currentViewData.viewID, currentViewData);
        previousViewID = currentViewID;
        currentViewID = currentViewData.viewID;

        Map<String, Object> viewSegmentation = createViewEventSegmentation(currentViewData, firstView, true, customViewSegmentation);

        if (firstView) {
            L.d("[ModuleViews] Recording view as the first one in the session. [" + viewName + "]");
            firstView = false;
        }

        recordView(currentViewID, 0.0, viewSegmentation);
        return currentViewData.viewID;
    }

    void stopViewWithNameInternal(@Nullable String viewName, @Nullable Map<String, Object> customViewSegmentation) {
        String viewID = validateViewWithName(viewName, "stopViewWithNameInternal");
        if (viewID == null) {
            return;
        }

        stopViewWithIDInternal(viewID, customViewSegmentation);
    }

    void stopViewWithIDInternal(@Nullable String viewID, @Nullable Map<String, Object> customViewSegmentation) {
        ViewData vd = validateViewID(viewID, "stopViewWithIDInternal");
        if (vd == null) {
            return;
        }
        removeReservedKeysFromViewSegmentation(customViewSegmentation);

        L.d("[ModuleViews] View [" + vd.viewName + "], id:[" + vd.viewID + "] is getting closed, reporting duration: [" + (TimeUtils.uniqueTimestampS() - vd.viewStartTimeSeconds) + "] s, current timestamp: [" + TimeUtils.uniqueTimestampMs() + "]");
        recordViewEndEvent(vd, customViewSegmentation, "stopViewWithIDInternal");

        viewDataMap.remove(vd.viewID);
    }

    private void recordView(String id, Double duration, Map<String, Object> segmentation) {
        ModuleEvents events = internalConfig.sdk.module(ModuleEvents.class);
        if (events == null) {
            L.e("[ModuleViews] recordView, events module is not initialized");
            return;
        }

        events.recordEventInternal(KEY_VIEW_EVENT, 1, 0.0, duration, segmentation, id);
    }

    private void recordViewEndEvent(ViewData vd, @Nullable Map<String, Object> filteredCustomViewSegmentation, String viewRecordingSource) {
        double lastElapsedDurationSeconds = 0.0;
        //we do sanity check the time component and print error in case of problem
        if (vd.viewStartTimeSeconds < 0) {
            L.e("[ModuleViews] " + viewRecordingSource + ", view start time value is not normal: [" + vd.viewStartTimeSeconds + "], ignoring that duration");
        } else if (vd.viewStartTimeSeconds == 0) {
            L.i("[ModuleViews] " + viewRecordingSource + ", view is either paused or didn't run, ignoring start timestamp");
        } else {
            lastElapsedDurationSeconds = (double) (TimeUtils.uniqueTimestampS() - vd.viewStartTimeSeconds);
        }

        //only record view if the view name is not null
        if (vd.viewName == null) {
            L.e("[ModuleViews] " + viewRecordingSource + " , view has no internal name, ignoring it");
            return;
        }

        Map<String, Object> segments = createViewEventSegmentation(vd, false, false, filteredCustomViewSegmentation);
        recordView(vd.viewID, lastElapsedDurationSeconds, segments);
    }

    void pauseViewWithIDInternal(String viewID) {
        ViewData vd = validateViewID(viewID, "pauseViewWithIDInternal");
        if (vd == null) {
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
        ViewData vd = validateViewID(viewID, "resumeViewWithIDInternal");
        if (vd == null) {
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

    private ViewData validateViewID(String viewID, String function) {
        if (viewID == null || viewID.isEmpty()) {
            L.e("[ModuleViews] validateViewID, " + function + ", Trying to process view with null or empty view ID, ignoring request");
            return null;
        }

        if (!viewDataMap.containsKey(viewID)) {
            L.w("[ModuleViews] validateViewID, " + function + ",  there is no view with the provided view id");
            return null;
        }

        ViewData vd = viewDataMap.get(viewID);
        if (vd == null) {
            L.e("[ModuleViews] validateViewID, " + function + ",  view id:[" + viewID + "] has a 'null' value. This should not be happening");
        }

        return vd;
    }

    private String validateViewWithName(String viewName, String function) {
        if (viewName == null || viewName.isEmpty()) {
            L.e("[ModuleViews] " + function + ", Trying to process the view with null or empty view name, ignoring request");
            return null;
        }

        String viewID = null;

        for (Map.Entry<String, ViewData> entry : viewDataMap.entrySet()) {
            ViewData vd = entry.getValue();
            if (vd != null && viewName.equals(vd.viewName)) {
                viewID = entry.getKey();
            }
        }

        if (viewID == null) {
            L.e("[ModuleViews] " + function + ", No view entry found with the provided name :[" + viewName + "]");
        }

        return viewID;
    }

    void addSegmentationToViewWithNameInternal(@Nullable String viewName, @Nullable Map<String, Object> viewSegmentation) {
        String viewID = validateViewWithName(viewName, "addSegmentationToViewWithNameInternal");
        if (viewID == null) {
            return;
        }
        addSegmentationToViewWithIDInternal(viewID, viewSegmentation);
    }

    private void addSegmentationToViewWithIDInternal(String viewID, Map<String, Object> viewSegmentation) {
        ViewData vd = validateViewID(viewID, "addSegmentationToViewWithIdInternal");
        if (vd == null) {
            return;
        }

        if (viewSegmentation == null || viewSegmentation.isEmpty()) {
            L.e("[ModuleViews] addSegmentationToViewWithIdInternal, Trying to add segmentation with null or empty view segmentation, ignoring request");
            return;
        }
        removeReservedKeysFromViewSegmentation(viewSegmentation);
        vd.viewSegmentation.putAll(viewSegmentation);
    }

    public @Nonnull String getCurrentViewId() {
        return currentViewID == null ? "" : currentViewID;
    }

    public @Nonnull String getPreviousViewId() {
        return previousViewID == null ? "" : previousViewID;
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
        public String startAutoStoppedView(@Nonnull String viewName) {
            synchronized (Countly.instance()) {
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
        public String startAutoStoppedView(@Nonnull String viewName, @Nullable Map<String, Object> viewSegmentation) {
            synchronized (Countly.instance()) {
                L.i("[Views] Calling startAutoStoppedView [" + viewName + "] sg[" + viewSegmentation + "]");
                return startViewInternal(viewName, viewSegmentation, true);
            }
        }

        /**
         * Starts a view which would not close automatically (For multi view tracking)
         *
         * @param viewName - String
         * @return String - View ID
         */
        public @Nullable String startView(@Nonnull String viewName) {
            synchronized (Countly.instance()) {
                L.i("[Views] Calling startView vn[" + viewName + "]");
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
        public @Nullable String startView(@Nonnull String viewName, @Nullable Map<String, Object> viewSegmentation) {
            synchronized (Countly.instance()) {
                L.i("[Views] Calling startView vn[" + viewName + "] sg[" + viewSegmentation + "]");
                return startViewInternal(viewName, viewSegmentation, false);
            }
        }

        /**
         * Stops a view with the given name if it was open
         *
         * @param viewName String - view name
         */
        public void stopViewWithName(@Nonnull String viewName) {
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
        public void stopViewWithName(@Nonnull String viewName, @Nullable Map<String, Object> viewSegmentation) {
            synchronized (Countly.instance()) {
                L.i("[Views] Calling stopViewWithName vn[" + viewName + "] sg[" + viewSegmentation + "]");
                stopViewWithNameInternal(viewName, viewSegmentation);
            }
        }

        /**
         * Stops a view with the given ID if it was open
         *
         * @param viewID String - view ID
         */
        public void stopViewWithID(@Nonnull String viewID) {
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
        public void stopViewWithID(@Nonnull String viewID, @Nullable Map<String, Object> viewSegmentation) {
            synchronized (Countly.instance()) {
                L.i("[Views] Calling stopViewWithID vi[" + viewID + "] sg[" + viewSegmentation + "]");
                stopViewWithIDInternal(viewID, viewSegmentation);
            }
        }

        /**
         * Pauses a view with the given ID
         *
         * @param viewID String - view ID
         */
        public void pauseViewWithID(@Nonnull String viewID) {
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
        public void resumeViewWithID(@Nonnull String viewID) {
            synchronized (Countly.instance()) {
                L.i("[Views] Calling resumeViewWithID vi[" + viewID + "]");
                resumeViewWithIDInternal(viewID);
            }
        }

        /**
         * Stops all views and records a segmentation if set
         *
         * @param viewSegmentation Map<String, Object> - view segmentation
         */
        public void stopAllViews(@Nullable Map<String, Object> viewSegmentation) {
            synchronized (Countly.instance()) {
                L.i("[Views] Calling stopAllViews sg[" + viewSegmentation + "]");
                stopAllViewsInternal(viewSegmentation);
            }
        }

        /**
         * Adds segmentation to a view with the given name
         *
         * @param viewName String
         * @param viewSegmentation Map<String, Object>
         */
        public void addSegmentationToViewWithName(@Nonnull String viewName, @Nullable Map<String, Object> viewSegmentation) {
            synchronized (Countly.instance()) {
                L.i("[Views] Calling addSegmentationToViewWithName vn[" + viewName + "] sg[" + viewSegmentation + "]");
                addSegmentationToViewWithNameInternal(viewName, viewSegmentation);
            }
        }

        /**
         * Adds segmentation to a view with the given ID
         *
         * @param viewId String
         * @param viewSegmentation Map<String, Object>
         */
        public void addSegmentationToViewWithID(@Nonnull String viewId, @Nullable Map<String, Object> viewSegmentation) {
            synchronized (Countly.instance()) {
                L.i("[Views] Calling addSegmentationToViewWithID vi[" + viewId + "] sg[" + viewSegmentation + "]");
                addSegmentationToViewWithIDInternal(viewId, viewSegmentation);
            }
        }

        /**
         * Set a segmentation to be recorded with all views
         *
         * @param segmentation Map<String, Object> - global view segmentation
         */
        public void setGlobalViewSegmentation(@Nullable Map<String, Object> segmentation) {
            synchronized (Countly.instance()) {
                L.i("[Views] Calling setGlobalViewSegmentation sg[" + (segmentation == null ? null : segmentation.size()) + "]");

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
                L.i("[Views] Calling updateGlobalViewSegmentation sg[" + (segmentation == null ? null : segmentation.size()) + "]");

                if (segmentation == null) {
                    L.w("[View] When updating segmentation values, they can't be 'null'.");
                    return;
                }

                updateGlobalViewSegmentationInternal(segmentation);
            }
        }
    }
}
