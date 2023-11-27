package ly.count.sdk.java.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import ly.count.sdk.java.Countly;
import ly.count.sdk.java.Event;
import ly.count.sdk.java.Session;
import ly.count.sdk.java.Usage;
import ly.count.sdk.java.User;
import ly.count.sdk.java.View;

/**
 * This class represents session concept, that is one indivisible usage occasion of your application.
 * Any data sent to Countly server is processed in a context of Session.
 * Only one session can send requests at a time, so even if you create 2 parallel sessions,
 * they will be made consequent automatically at the time of Countly SDK choice with no
 * correctness guarantees, so please avoid having parallel sessions.
 */

public class SessionImpl implements Session, EventImpl.EventRecorder {

    protected Log L;
    /**
     * {@link System#nanoTime()} of time when {@link Session} object is created.
     */
    protected Long id;
    InternalConfig config;
    /**
     * {@link System#nanoTime()} of {@link #begin()}, {@link #update()} and {@link #end()} calls respectively.
     */
    protected Long began, updated, ended;

    /**
     * List of events still not added to request
     */
    protected final List<Event> events = new ArrayList<>();

    /**
     * Additional parameters to send with next request
     */
    protected final Params params = new Params();

    /**
     * Current view event, that is started, but not ended yet
     */
    protected View currentView = null;

    /**
     * Whether {@link #currentView} will be start view.
     */
    protected boolean startView = true;

    /**
     * Latest known consents for this {@link Session} instance.
     * Affects how it's handled during recovery.
     */
    private int consents;

    /**
     * Deserialization constructor (use existing id).
     */
    public SessionImpl(final InternalConfig config, Long id) {
        L = config.getLogger();
        this.config = config;
        this.id = id == null ? TimeUtils.uniqueTimestampMs() : id;
        if (SDKCore.instance != null) {
            this.consents = SDKCore.instance.consents;
        }
    }

    @Override
    public Session begin() {
        if (config.isBackendModeEnabled()) {
            L.w("[SessionImpl] begin: Skipping session begin, backend mode is enabled!");
            return this;
        }

        L.d("[SessionImpl] begin");
        begin(null);
        return this;
    }

    Future<Boolean> begin(Long now) {
        if (SDKCore.instance == null) {
            L.e("[SessionImpl] Countly is not initialized");
            return null;
        } else if (began != null) {
            L.e("[SessionImpl] Session already began");
            return null;
        } else if (ended != null) {
            L.e("[SessionImpl] Session already ended");
            return null;
        } else {
            began = now == null ? System.nanoTime() : now;
        }

        this.consents = SDKCore.instance.consents;

        Future<Boolean> ret = ModuleRequests.sessionBegin(config, this);
        SDKCore.instance.onSessionBegan(config, this);
        return ret;
    }

    @Override
    public Session update() {
        if (config.isBackendModeEnabled()) {
            L.w("[SessionImpl] update: Skipping session update, backend mode is enabled!");
            return this;
        }

        L.d("[SessionImpl] update");
        update(null);
        return this;
    }

    Future<Boolean> update(Long now) {
        if (SDKCore.instance == null) {
            L.e("[SessionImpl] Countly is not initialized");
            return null;
        } else if (began == null) {
            L.e("[SessionImpl] Session is not began to update it");
            return null;
        } else if (ended != null) {
            L.e("[SessionImpl] Session is already ended to update it");
            return null;
        }

        this.consents = SDKCore.instance.consents;

        Long duration = updateDuration(now);
        return ModuleRequests.sessionUpdate(config, this, duration);
    }

    @Override
    public void end() {
        if (config.isBackendModeEnabled()) {
            L.w("end: Skipping session end, backend mode is enabled!");
            return;
        }

        L.d("[SessionImpl] end");
        end(null, null);
    }

    Future<Boolean> end(Long now, String did) {
        if (SDKCore.instance == null) {
            L.e("[SessionImpl] Countly is not initialized");
            return null;
        } else if (began == null) {
            L.e("[SessionImpl] Session is not began to end it");
            return null;
        } else if (ended != null) {
            L.e("[SessionImpl] Session already ended");
            return null;
        }
        ended = now == null ? System.nanoTime() : now;

        this.consents = SDKCore.instance.consents;

        if (currentView != null) {
            currentView.stop(true);
        }

        Long duration = updateDuration(now);

        Future<Boolean> ret = ModuleRequests.sessionEnd(config, this, duration, did, removed -> {
            if (!removed) {
                L.i("[SessionImpl] No data in session end request");
            }
        });

        SDKCore.instance.onSessionEnded(config, this);

        return ret;
    }

    @Override
    public boolean isActive() {
        L.d("[SessionImpl] isActive");
        return began != null && ended == null;
    }

    /**
     * Calculate time since last {@link #update()} or since {@link #begin()} if no {@link #update()} yet made,
     * set {@link #updated} to now.
     *
     * @return calculated session duration to send in seconds
     */
    private Long updateDuration(Long now) {
        now = now == null ? System.nanoTime() : now;
        long duration;

        if (updated == null) {
            duration = now - began;
        } else {
            duration = now - updated;
        }
        updated = now;
        return TimeUtils.nsToSec(duration);
    }

    public Event event(String key) {
        return new EventImpl(this, key, L);
    }

    /**
     * @param key key for this event, cannot be null or empty
     * @return timed Event instance.
     * @deprecated use {@link ModuleEvents.Events#startEvent(String)}} instead via <code>instance().events()</code> call
     */
    public Event timedEvent(String key) {
        return timedEvents().event(config, key);
    }

    /**
     * @return {@link TimedEvents} instance
     * @deprecated use {@link ModuleEvents.Events} instead via <code>instance().events()</code> call
     */
    protected TimedEvents timedEvents() {
        return SDKCore.instance.timedEvents();
    }

    /**
     * Record event to session.
     *
     * @param event to record
     * @deprecated use {@link ModuleEvents.Events#recordEvent(String, Map, int, Double, Double)} instead
     */
    @Override
    public void recordEvent(Event event) {
        L.d("[SessionImpl] recordEvent: " + event.toString());
        if (!SDKCore.enabled(CoreFeature.Events)) {
            L.i("[SessionImpl] recordEvent: Skipping event - feature is not enabled");
            return;
        }

        if (began == null) {
            begin();
        }

        ModuleEvents eventsModule = (ModuleEvents) SDKCore.instance.module(CoreFeature.Events.getIndex());
        EventImpl eventImpl = (EventImpl) event;
        eventsModule.recordEventInternal(eventImpl.key, eventImpl.count, eventImpl.sum, eventImpl.duration, eventImpl.segmentation);
    }

    @Override
    public User user() {
        if (SDKCore.instance == null) {
            L.e("[SessionImpl] Countly is not initialized");
            return null;
        } else {
            return SDKCore.instance.user();
        }
    }

    @Override
    public Session addCrashReport(Throwable t, boolean fatal) {
        return addCrashReport(t, fatal, null, null);
    }

    @Override
    public Session addCrashReport(Throwable t, boolean fatal, String name, Map<String, String> segments, String... logs) {
        if (!SDKCore.enabled(CoreFeature.CrashReporting)) {
            L.i("[SessionImpl] addCrashReport: Skipping event - feature is not enabled");
            return this;
        }

        if (fatal) {
            Countly.instance().crashes().recordUnhandledException(t, new HashMap<>(segments));
        } else {
            Countly.instance().crashes().recordHandledException(t, new HashMap<>(segments));
        }
        return this;
    }

    @Override
    public Session addLocation(double latitude, double longitude) {

        if (config.isBackendModeEnabled()) {
            L.w("[SessionImpl] addLocation: Skipping location, backend mode is enabled!");
            return this;
        }

        L.d("[SessionImpl] addLocation: latitude = " + latitude + " longitude = " + longitude);
        if (!SDKCore.enabled(CoreFeature.Location)) {
            L.i("[SessionImpl] addLocation: Skipping event - feature is not enabled");
            return this;
        }
        return addParam("location", latitude + "," + longitude);
    }

    public View view(String name, boolean start) {
        L.d("[SessionImpl] view: name = " + name + " start = " + start);
        if (!SDKCore.enabled(CoreFeature.Views)) {
            L.i("[SessionImpl] view: Skipping view - feature is not enabled");
            return null;
        }
        if (currentView != null) {
            currentView.stop(false);
        }

        currentView = new ViewImpl(this, name, L);

        currentView.start(start);
        startView = false;
        return currentView;
    }

    public View view(String name) {
        return view(name, startView);
    }

    @Override
    public Usage login(String id) {
        SDKCore.instance.login(id);
        return this;
    }

    @Override
    public Usage logout() {
        SDKCore.instance.logout();
        return this;
    }

    /**
     * @deprecated use {@link ModuleDeviceIdCore.DeviceId#getID()} instead
     */
    @Override
    public String getDeviceId() {
        return config.getDeviceId().id;
    }

    /**
     * @deprecated use {@link ModuleDeviceIdCore.DeviceId#changeWithMerge(String)} instead
     */
    @Override
    public Usage changeDeviceIdWithMerge(String id) {
        if (config.isBackendModeEnabled()) {
            L.w("[SessionImpl] changeDeviceIdWithMerge: Skipping change device id with merge, backend mode is enabled!");
            return this;
        }

        L.d("[SessionImpl] changeDeviceIdWithoutMerge: id = " + id);
        SDKCore.instance.changeDeviceIdWithMerge(config, id);
        return this;
    }

    /**
     * @deprecated use {@link ModuleDeviceIdCore.DeviceId#changeWithoutMerge(String)} instead
     */
    @Override
    public Usage changeDeviceIdWithoutMerge(String id) {
        if (config.isBackendModeEnabled()) {
            L.w("[SessionImpl] changeDeviceIdWithoutMerge: Skipping change device id without merge, backend mode is enabled!");
            return this;
        }

        L.d("[SessionImpl] changeDeviceIdWithoutMerge: id = " + id);
        SDKCore.instance.changeDeviceIdWithoutMerge(config, id);
        return this;
    }

    public Session addParam(String key, Object value) {
        params.add(key, value);
        return this;
    }

    @Override
    public Long getId() {
        return id;
    }

    @Override
    public Long getBegan() {
        return began;
    }

    @Override
    public Long getEnded() {
        return ended;
    }

    boolean hasConsent(CoreFeature feature) {
        return hasConsent(feature.getIndex());
    }

    boolean hasConsent(int feature) {
        return (consents & feature) > 0;
    }

    void setConsents(final InternalConfig config, int features) {
        consents = features;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof SessionImpl)) {
            return false;
        }

        SessionImpl session = (SessionImpl) obj;
        if (!id.equals(session.id)) {
            return false;
        }
        if ((began != null && !began.equals(session.began) || (session.began != null && !session.began.equals(began)))) {
            return false;
        }
        if ((updated != null && !updated.equals(session.updated) || (session.updated != null && !session.updated.equals(updated)))) {
            return false;
        }
        if ((ended != null && !ended.equals(session.ended) || (session.ended != null && !session.ended.equals(ended)))) {
            return false;
        }
        if (!params.equals(session.params)) {
            return false;
        }
        return events.equals(session.events);
    }

    @Override
    public String toString() {
        return params.toString();
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
