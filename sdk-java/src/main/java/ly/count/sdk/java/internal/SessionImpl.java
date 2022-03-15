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
 *
 * Any data sent to Countly server is processed in a context of Session.
 * Only one session can send requests at a time, so even if you create 2 parallel sessions,
 * they will be made consequent automatically at the time of Countly SDK choice with no
 * correctness guarantees, so please avoid having parallel sessions.
 *
 */

public class SessionImpl implements Session, Storable, EventImpl.EventRecorder {
    private static final Log.Module L = Log.module("SessionImpl");

    /**
     * {@link System#nanoTime()} of time when {@link Session} object is created.
     */
    protected final Long id;

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
        this.id = DeviceCore.dev.uniformTimestamp();
        this.ctx = ctx;
    }

    /**
     * Deserialization constructor (use existing id).
     */
    public SessionImpl(CtxCore ctx, Long id) {
        this.ctx = ctx;
        this.id = id == null ? DeviceCore.dev.uniformTimestamp() : id;
        if (SDKCore.instance != null) {
            this.consents = SDKCore.instance.consents;
        }
    }

    @Override
    public Session begin() {
        if(ctx.getConfig().isBackendModeEnable()) {
            L.w("Skipping session begin, backend mode is enabled!");
            return this;
        }

        L.d("begin");
        begin(null);
        return this;
    }

    Future<Boolean> begin(Long now) {
        if (SDKCore.instance == null) {
            L.wtf("Countly is not initialized");
            return null;
        } else if (began != null) {
            L.wtf("Session already began");
            return null;
        } else if (ended != null) {
            L.wtf("Session already ended");
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
        if(ctx.getConfig().isBackendModeEnable()) {
            L.w("Skipping session update, backend mode is enabled!");
            return this;
        }

        L.d("update");
        update(null);
        return this;
    }

    Future<Boolean> update(Long now) {
        if (SDKCore.instance == null) {
            L.wtf("Countly is not initialized");
            return null;
        } else if (began == null) {
            L.wtf("Session is not began to update it");
            return null;
        } else if (ended != null) {
            L.wtf("Session is already ended to update it");
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
        if(ctx.getConfig().isBackendModeEnable()) {
            L.w("Skipping session end, backend mode is enabled!");
            return;
        }

        L.d("end");
        end(null, null, null);
    }

    Future<Boolean> end(Long now, final Tasks.Callback<Boolean> callback, String did) {
        if (SDKCore.instance == null) {
            L.wtf("Countly is not initialized");
            return null;
        } else if (began == null) {
            L.wtf("Session is not began to end it");
            return null;
        } else if (ended != null) {
            L.wtf("Session already ended");
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

        Future<Boolean> ret = ModuleRequests.sessionEnd(ctx, this, duration, did, new Tasks.Callback<Boolean>() {
            @Override
            public void call(Boolean removed) throws Exception {
                if (!removed) {
                    L.i("No data in session end request");
                }
                Storage.removeAsync(ctx, SessionImpl.this, callback);
            }
        });

        SDKCore.instance.onSessionEnded(ctx, this);

        return ret;
    }

    Boolean recover(Config config) {
        if ((System.currentTimeMillis() - id) < DeviceCore.dev.secToMs(config.getSessionCooldownPeriod() * 2)) {
            return null;
        } else {
            Future<Boolean> future = null;
            if (began == null) {
                return Storage.remove(ctx, this);
            } else if (ended == null && updated == null) {
                future = end(began + DeviceCore.dev.secToNs(config.getSessionCooldownPeriod()), null, null);
            } else if (ended == null) {
                future = end(updated + DeviceCore.dev.secToNs(config.getSessionCooldownPeriod()), null, null);
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
                L.wtf("Interrupted while resolving session recovery future", e);
                return false;
            }
        }
    }

    @Override
    public boolean isActive() {
        L.d("isActive");
        return began != null && ended == null;
    }

    /**
     * Calculate time since last {@link #update()} or since {@link #begin()} if no {@link #update()} yet made,
     * set {@link #updated} to now.
     *
     * @return calculated session duration to send in seconds
     */
    private Long updateDuration(Long now){
        now = now == null ? System.nanoTime() : now;
        Long duration;

        if (updated == null) {
            duration = now - began;
        } else {
            duration = now - updated;
        }
        updated = now;
        return DeviceCore.dev.nsToSec(duration);
    }

    public Event event(String key) {
        return new EventImpl(this, key);
    }

    public Event timedEvent(String key) {
        return timedEvents().event(ctx, key);
    }

    protected TimedEvents timedEvents () { return SDKCore.instance.timedEvents(); }

    @Override
    public void recordEvent(Event event) {
        L.d("recordEvent: " + event.toString());
        if (!SDKCore.enabled(CoreFeature.Events)) {
            L.i("Skipping event - feature is not enabled");
            return;
        }
        if (began == null) {
            begin();
        }

        synchronized (storageId()) {
            events.add(event);
            if (pushOnChange) {
                Storage.pushAsync(ctx, this);
            }

            Config config = SDKCore.instance.config();
            if (config != null && config.getEventsBufferSize() <= events.size()) {
                update();
            }
        }
    }

    @Override
    public User user() {
        if (SDKCore.instance == null) {
            L.wtf("Countly is not initialized");
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
        if(ctx.getConfig().isBackendModeEnable()) {
            L.w("Skipping crash, backend mode is enabled!");
            return this;
        }

        if (!SDKCore.enabled(CoreFeature.CrashReporting)) {
            L.i("Skipping event - feature is not enabled");
            return this;
        }
        SDKCore.instance.onCrash(ctx, t, fatal, name, segments, logs);
        return this;
    }

    @Override
    public Session addLocation(double latitude, double longitude) {
        if(ctx.getConfig().isBackendModeEnable()) {
            L.w("Skipping location, backend mode is enabled!");
            return this;
        }

        L.d("addLocation: latitude = " + latitude + " longitude = " + longitude);
        if (!SDKCore.enabled(CoreFeature.Location)) {
            L.i("Skipping event - feature is not enabled");
            return this;
        }
        return addParam("location", latitude + "," + longitude);
    }

    public View view(String name, boolean start) {
        L.d("view: name = " + name + " start = " + start);
        if (!SDKCore.enabled(CoreFeature.Views)) {
            L.i("Skipping view - feature is not enabled");
            return null;
        }
        if (currentView != null) {
            currentView.stop(false);
        }
        currentView = new ViewImpl(this, name);
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
    public Usage resetDeviceId(String id) {
        L.d("resetDeviceId: id = " + id);
        SDKCore.instance.changeDeviceIdWithoutMerge(ctx, id);
        return this;
    }

    @Override
    public Usage changeDeviceIdWithMerge(String id) {
        if(ctx.getConfig().isBackendModeEnable()) {
            L.w("Skipping change device id with merge, backend mode is enabled!");
            return this;
        }

        L.d("changeDeviceIdWithoutMerge: id = " + id);
        SDKCore.instance.changeDeviceIdWithMerge(ctx, id);
        return this;
    }

    @Override
    public Usage changeDeviceIdWithoutMerge(String id) {
        if(ctx.getConfig().isBackendModeEnable()) {
            L.w("Skipping change device id without merge, backend mode is enabled!");
            return this;
        }

        L.d("changeDeviceIdWithoutMerge: id = " + id);
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

    public static String getStoragePrefix() {
        return "session";
    }

    public byte[] store() {
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
            L.wtf("Cannot serialize session", e);
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException e) {
                    L.wtf("Cannot happen", e);
                }
            }
            if (bytes != null) {
                try {
                    bytes.close();
                } catch (IOException e) {
                    L.wtf("Cannot happen", e);
                }
            }
        }
        return null;
    }

    public boolean restore(byte[] data) {
        ByteArrayInputStream bytes = null;
        ObjectInputStream stream = null;

        try {
            bytes = new ByteArrayInputStream(data);
            stream = new ObjectInputStream(bytes);
            if (id != stream.readLong()) {
                L.wtf("Wrong file for session deserialization");
            }

            began = stream.readLong();
            began = began == 0 ? null : began;
            updated = stream.readLong();
            updated = updated == 0 ? null : updated;
            ended = stream.readLong();
            ended = ended == 0 ? null : ended;

            int count = stream.readInt();
            for (int i = 0; i < count; i++) {
                Event event = EventImpl.fromJSON(stream.readUTF(), null);
                if (event != null) {
                    events.add(event);
                }
            }

            params.add(stream.readUTF());
            consents = stream.readInt();

            return true;
        } catch (IOException e) {
            L.wtf("Cannot deserialize session", e);
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException e) {
                    L.wtf("Cannot happen", e);
                }
            }
            if (bytes != null) {
                try {
                    bytes.close();
                } catch (IOException e) {
                    L.wtf("Cannot happen", e);
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

        SessionImpl session = (SessionImpl)obj;
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
