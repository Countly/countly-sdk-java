package ly.count.sdk.java.internal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import ly.count.sdk.java.Countly;
import ly.count.sdk.java.Event;

class TimedEvents implements Storable, EventImpl.EventRecorder {

    private final Log L;

    private final Map<String, EventImpl> events;

    protected TimedEvents(Log logger) {
        L = logger;
        events = new HashMap<>();
    }

    /**
     * @return key set
     * @deprecated this will not function anymore
     */
    Set<String> keys() {
        return events.keySet();
    }

    /**
     * @param key key of event to get
     * @param config config to use
     * @return event with given key
     * @deprecated use {@link ModuleEvents.Events#startEvent(String)}} instead via <code>instance().events()</code> call
     */
    EventImpl event(InternalConfig config, String key) {
        EventImpl event = null;
        if (Countly.instance().events().startEvent(key)) {
            event = config.sdk.module(ModuleEvents.class).timedEvents.get(key);
        } else {
            if (events.containsKey(key)) {
                event = events.remove(key);
                SDKCore.instance.module(ModuleEvents.class).timedEvents.put(key, event);
            }
        }

        return event;
    }

    /**
     * @param key key of event to check
     * @return true if event with given key is currently timed
     * @deprecated use {@link ModuleEvents.Events#startEvent(String)}} instead via <code>instance().events()</code> call
     */
    boolean has(String key) {
        return events.containsKey(key);
    }

    @Override
    public Long storageId() {
        return 0L;
    }

    @Override
    public String storagePrefix() {
        return "timedEvent";
    }

    @Override
    public void setId(Long id) {
        //do nothing
    }

    @Override
    public byte[] store(Log L) {
        ByteArrayOutputStream bytes = null;
        ObjectOutputStream stream = null;
        try {
            bytes = new ByteArrayOutputStream();
            stream = new ObjectOutputStream(bytes);
            stream.writeInt(events.size());
            for (String key : events.keySet()) {
                stream.writeUTF(key);
                stream.writeUTF(events.get(key).toJSON(L));
            }
            stream.close();
            return bytes.toByteArray();
        } catch (IOException e) {
            if (L != null) {
                L.e("[TimedEvents] Cannot serialize timed events" + e);
            }
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException e) {
                    if (L != null) {
                        L.e("[TimedEvents] Cannot happen" + e);
                    }
                }
            }
            if (bytes != null) {
                try {
                    bytes.close();
                } catch (IOException e) {
                    if (L != null) {
                        L.e("[TimedEvents] Cannot happen" + e);
                    }
                }
            }
        }
        return null;
    }

    @Override
    public boolean restore(byte[] data, Log L) {
        ByteArrayInputStream bytes = null;
        ObjectInputStream stream = null;

        try {
            bytes = new ByteArrayInputStream(data);
            stream = new ObjectInputStream(bytes);

            int l = stream.readInt();
            while (l-- > 0) {
                String key = stream.readUTF();
                EventImpl event = EventImpl.fromJSON(stream.readUTF(), this, L);
                ModuleEvents eventsModule = SDKCore.instance.module(ModuleEvents.class);
                if (eventsModule != null) {
                    eventsModule.timedEvents.put(key, event);
                } else {
                    events.put(key, event);
                }
            }

            return true;
        } catch (IOException e) {
            if (L != null) {
                L.e("[TimedEvents] Cannot deserialize config" + e);
            }
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException e) {
                    if (L != null) {
                        L.e("[TimedEvents] Cannot happen" + e);
                    }
                }
            }
            if (bytes != null) {
                try {
                    bytes.close();
                } catch (IOException e) {
                    if (L != null) {
                        L.e("[TimedEvents] Cannot happen" + e);
                    }
                }
            }
        }

        return false;
    }

    /**
     * Record event
     *
     * @param event event to record
     * @deprecated use {@link ModuleEvents.Events#endEvent(String) instead via <code>instance().events()</code> call
     */
    @Override
    public void recordEvent(Event event) {
        EventImpl eventImpl = (EventImpl) event;
        if (events.containsKey(eventImpl.key)) {
            SDKCore.instance.module(ModuleEvents.class).timedEvents.put(eventImpl.key, eventImpl);
            events.remove(eventImpl.key);
        }
        Countly.instance().events().endEvent(eventImpl.key, eventImpl.segmentation, eventImpl.count, eventImpl.sum);
    }

    public int size() {
        return events.size();
    }
}
