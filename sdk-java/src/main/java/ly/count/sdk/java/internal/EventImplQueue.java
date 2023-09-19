package ly.count.sdk.java.internal;

import ly.count.sdk.java.Event;
import org.json.JSONArray;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.ArrayDeque;
import java.util.Queue;

public class EventImplQueue implements Storable {

    private final Log L;

    private Long id = System.currentTimeMillis();

    final Queue<EventImpl> events = new ArrayDeque<>();

    protected EventImplQueue(Log logger) {
        L = logger;
    }

    @Override
    public Long storageId() {
        return id;
    }

    @Override
    public String storagePrefix() {
        return "events";
    }

    @Override
    public void setId(Long id) {
        //do nothing
    }

    @Override
    public byte[] store(Log L) {
        JSONArray events = new JSONArray();
        for (EventImpl event : this.events) {
            events.put(event.toJSON(L));
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(events);
            oos.close();
        } catch (IOException e) {
            L.e("[EventImplQueue] Failed to serialize timed events " + e.getMessage());
        }

        events.clear();
        id = System.currentTimeMillis();
        return baos.toByteArray();
    }

    @Override
    public boolean restore(byte[] data, Log L) {
        return false;
    }
}
