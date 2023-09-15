package ly.count.sdk.java.internal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import ly.count.sdk.java.Event;
import ly.count.sdk.java.Session;

class TimedEvents implements Storable, EventImpl.EventRecorder {

    private final Log L;

    private final Map<String, EventImpl> events;

    protected TimedEvents(Log logger) {
        L = logger;
        events = new HashMap<>();
    }

    Set<String> keys() {
        return events.keySet();
    }

    EventImpl event(CtxCore ctx, String key) {
        EventImpl event = events.get(key);
        if (event == null) {
            event = new EventImpl(this, key, L);
            events.put(key, event);
            Storage.pushAsync(ctx, this);
        }
        return event;
    }

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
                events.put(key, event);
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

    @Override
    public void recordEvent(Event event) {
        if (events.containsKey(((EventImpl) event).getKey())) {
            event.endAndRecord();
            events.remove(((EventImpl) event).getKey());
            Session session = SDKCore.instance.getSession();
            if (session != null) {
                ((SessionImpl) session).recordEvent(event);
            }
        }
    }

    public int size() {
        return events.size();
    }
}
