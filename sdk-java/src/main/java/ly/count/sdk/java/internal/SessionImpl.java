package ly.count.sdk.java.internal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import ly.count.sdk.java.Config;
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

public class SessionImpl implements Session, Storable, EventImpl.EventRecorder {

    protected Log L = null;
    /**
     * {@link System#nanoTime()} of time when {@link Session} object is created.
     */
    protected Long id;

    protected final CtxCore ctx;

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
     * Whether to push changes to storage on every change automatically (false only for testing)
     */
    private boolean pushOnChange = true;

    /**
     * Create session with current time as id.
     */
    protected SessionImpl(CtxCore ctx) {
        L = ctx.getLogger();
        this.id = TimeUtils.uniqueTimestampMs();
        this.ctx = ctx;
    }

    /**
     * Deserialization constructor (use existing id).
     */
    public SessionImpl(CtxCore ctx, Long id) {
        L = ctx.getLogger();
        this.ctx = ctx;
        this.id = id == null ? TimeUtils.uniqueTimestampMs() : id;
        if (SDKCore.instance != null) {
            this.consents = SDKCore.instance.consents;
        }
    }

    @Override
    public Session begin() {
        if (ctx.getConfig().isBackendModeEnabled()) {
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

        if (pushOnChange) {
            Storage.pushAsync(ctx, this);
        }

        Future<Boolean> ret = ModuleRequests.sessionBegin(ctx, this);

        SDKCore.instance.onSessionBegan(ctx, this);

        return ret;
    }

    @Override
    public Session update() {
        if (ctx.getConfig().isBackendModeEnabled()) {
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

        if (pushOnChange) {
            Storage.pushAsync(ctx, this);
        }

        return ModuleRequests.sessionUpdate(ctx, this, duration);
    }

    @Override
    public void end() {
        if (ctx.getConfig().isBackendModeEnabled()) {
            L.w("end: Skipping session end, backend mode is enabled!");
            return;
        }

        L.d("[SessionImpl] end");
        end(null, null, null);
    }

    Future<Boolean> end(Long now, final Tasks.Callback<Boolean> callback, String did) {
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

        //        // TODO: check if needed
        //        for (Event event: timedEvents.values()) {
        //            event.endAndRecord();
        //        }

        if (currentView != null) {
            currentView.stop(true);
        } else {
            Storage.pushAsync(ctx, this);
        }

        Long duration = updateDuration(now);

        Future<Boolean> ret = ModuleRequests.sessionEnd(ctx, this, duration, did, removed -> {
            if (!removed) {
                L.i("[SessionImpl] No data in session end request");
            }
            Storage.removeAsync(ctx, SessionImpl.this, callback);
        });

        SDKCore.instance.onSessionEnded(ctx, this);

        return ret;
    }

    Boolean recover(Config config, Log L) {
        if ((System.currentTimeMillis() - id) < 0) {
            return null;
        } else {
            Future<Boolean> future = null;
            if (began == null) {
                return Storage.remove(ctx, this);
            } else if (ended == null && updated == null) {
                future = end(began, null, null);
            } else if (ended == null) {
                future = end(updated, null, null);
            } else {
                // began != null && ended != null
                return Storage.remove(ctx, this);
            }

            if (future == null) {
                return null;
            }

            try {
                return future.get();
            } catch (InterruptedException | ExecutionException e) {
                if (L != null) {
                    L.e("[SessionImpl] Interrupted while resolving session recovery future" + e);
                }
                return false;
            }
        }
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

    public Event timedEvent(String key) {
        return timedEvents().event(ctx, key);
    }

    protected TimedEvents timedEvents() {
        return SDKCore.instance.timedEvents();
    }

    /**
     * Record event to session.
     *
     * @param event
     * @deprecated use {@link ModuleEvents.Events#recordEvent(String, int, Double, Map, Double)} instead
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
        eventsModule.recordEventInternal(eventImpl.key, eventImpl.count, eventImpl.sum, eventImpl.segmentation, eventImpl.duration);
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
        if (ctx.getConfig().isBackendModeEnabled()) {
            L.w("[SessionImpl] addCrashReport: Skipping crash, backend mode is enabled!");
            return this;
        }

        if (!SDKCore.enabled(CoreFeature.CrashReporting)) {
            L.i("[SessionImpl] addCrashReport: Skipping event - feature is not enabled");
            return this;
        }

        SDKCore.instance.onCrash(ctx, t, fatal, name, segments, logs);
        return this;
    }

    @Override
    public Session addLocation(double latitude, double longitude) {

        if (ctx.getConfig().isBackendModeEnabled()) {
            L.w("[SessionImpl] addLocation: Skipping location, backend mode is enabled!");
            return this;
        }

        L.d("[SessionImpl] addLocation: latitude = " + latitude + " longitude = " + longitude);
        if (!SDKCore.enabled(CoreFeature.Location)) {
            L.i("[SessionImpl] addLocation: Skipping event - feature is not enabled");
            return this;
        }
        return (Session) addParam("location", latitude + "," + longitude);
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
        SDKCore.instance.login(ctx, id);
        return this;
    }

    @Override
    public Usage logout() {
        SDKCore.instance.logout(ctx);
        return this;
    }

    @Override
    public String getDeviceId() {
        return ctx.getConfig().getDeviceId().id;
    }

    @Override
    public Usage changeDeviceIdWithMerge(String id) {
        if (ctx.getConfig().isBackendModeEnabled()) {
            L.w("[SessionImpl] changeDeviceIdWithMerge: Skipping change device id with merge, backend mode is enabled!");
            return this;
        }

        L.d("[SessionImpl] changeDeviceIdWithoutMerge: id = " + id);
        SDKCore.instance.changeDeviceIdWithMerge(ctx, id);
        return this;
    }

    @Override
    public Usage changeDeviceIdWithoutMerge(String id) {
        if (ctx.getConfig().isBackendModeEnabled()) {
            L.w("[SessionImpl] changeDeviceIdWithoutMerge: Skipping change device id without merge, backend mode is enabled!");
            return this;
        }

        L.d("[SessionImpl] changeDeviceIdWithoutMerge: id = " + id);
        SDKCore.instance.changeDeviceIdWithoutMerge(ctx, id);
        return this;
    }

    public Session addParam(String key, Object value) {
        params.add(key, value);
        if (pushOnChange) {
            Storage.pushAsync(ctx, this);
        }
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

    public Long storageId() {
        return this.id;
    }

    public String storagePrefix() {
        return getStoragePrefix();
    }

    @Override
    public void setId(Long id) {
        this.id = id;
    }

    public static String getStoragePrefix() {
        return "session";
    }

    public byte[] store(Log L) {
        ByteArrayOutputStream bytes = null;
        ObjectOutputStream stream = null;
        try {
            bytes = new ByteArrayOutputStream();
            stream = new ObjectOutputStream(bytes);
            stream.writeLong(id);
            stream.writeLong(began == null ? 0 : began);
            stream.writeLong(updated == null ? 0 : updated);
            stream.writeLong(ended == null ? 0 : ended);
            stream.writeInt(events.size());
            for (Event event : events) {
                stream.writeUTF(event.toString());
            }
            stream.writeUTF(params.toString());
            stream.writeInt(consents);
            stream.close();
            return bytes.toByteArray();
        } catch (IOException e) {
            if (L != null) {
                L.e("[SessionImpl] Cannot serialize session" + e);
            }
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException e) {
                    if (L != null) {
                        L.e("[SessionImpl] Cannot happen" + e);
                    }
                }
            }
            if (bytes != null) {
                try {
                    bytes.close();
                } catch (IOException e) {
                    if (L != null) {
                        L.e("[SessionImpl] Cannot happen" + e);
                    }
                }
            }
        }
        return null;
    }

    public boolean restore(byte[] data, Log L) {
        ByteArrayInputStream bytes = null;
        ObjectInputStream stream = null;

        try {
            bytes = new ByteArrayInputStream(data);
            stream = new ObjectInputStream(bytes);
            if (id != stream.readLong()) {
                if (L != null) {
                    L.e("[SessionImpl] Wrong file for session deserialization");
                }
            }

            began = stream.readLong();
            began = began == 0 ? null : began;
            updated = stream.readLong();
            updated = updated == 0 ? null : updated;
            ended = stream.readLong();
            ended = ended == 0 ? null : ended;

            int count = stream.readInt();
            for (int i = 0; i < count; i++) {
                Event event = EventImpl.fromJSON(stream.readUTF(), null, L);
                if (event != null) {
                    events.add(event);
                }
            }

            params.add(stream.readUTF());
            consents = stream.readInt();

            return true;
        } catch (IOException e) {
            if (L != null) {
                L.e("[SessionImpl] Cannot deserialize session" + e);
            }
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException e) {
                    if (L != null) {
                        L.e("[SessionImpl] Cannot happen" + e);
                    }
                }
            }
            if (bytes != null) {
                try {
                    bytes.close();
                } catch (IOException e) {
                    if (L != null) {
                        L.e("[SessionImpl] Cannot happen" + e);
                    }
                }
            }
        }

        return false;
    }

    SessionImpl setPushOnChange(boolean pushOnChange) {
        this.pushOnChange = pushOnChange;
        return this;
    }

    boolean hasConsent(CoreFeature feature) {
        return hasConsent(feature.getIndex());
    }

    boolean hasConsent(int feature) {
        return (consents & feature) > 0;
    }

    void setConsents(CtxCore ctx, int features) {
        consents = features;
        Storage.pushAsync(ctx, this);
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
        if (!events.equals(session.events)) {
            return false;
        }
        return true;
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
