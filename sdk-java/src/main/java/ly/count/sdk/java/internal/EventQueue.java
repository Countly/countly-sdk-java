package ly.count.sdk.java.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nonnull;

public class EventQueue {

    static final String DELIMITER = ":::";
    Log L;
    List<EventImpl> eventQueueMemoryCache;

    protected final Object lockEQ = new Object();

    protected EventQueue() {
    }

    protected EventQueue(@Nonnull Log logger) {
        L = logger;
        eventQueueMemoryCache = new ArrayList<>();
    }

    /**
     * Returns the number of events currently stored in the queue.
     */
    protected int eqSize() {
        synchronized (lockEQ) {
            return eventQueueMemoryCache.size();
        }
    }

    protected List<EventImpl> getEQ() {
        synchronized (lockEQ) {
            List<EventImpl> copy = new ArrayList<>(eventQueueMemoryCache);
            Collections.copy(copy, eventQueueMemoryCache);
            return copy;
        }
    }

    void addEvent(@Nonnull final EventImpl event) {
        if (event == null) {
            L.w("[EventQueue] Event is null, skipping");
            return;
        }
        L.d("[EventQueue] Adding event: " + event.key);
        synchronized (lockEQ) {
            eventQueueMemoryCache.add(event);
            writeEventQueueToStorage();
        }
    }

    /**
     * set the new value in event data storage
     */
    void writeEventQueueToStorage() {
        if (eventQueueMemoryCache.isEmpty()) {
            L.d("[EventQueue] No events to write to disk");
            return;
        }

        final String eventQueue = joinEvents(eventQueueMemoryCache);

        L.d("[EventQueue] Setting event data: " + eventQueue);
        SDKCore.instance.sdkStorage.storeEventQueue(eventQueue);
    }

    /**
     * Restores events from disk
     */
    void restoreFromDisk() {
        synchronized (lockEQ) {
            L.d("[EventQueue] Restoring events from disk");
            eventQueueMemoryCache.clear();

            final String[] array = getEvents();
            for (String s : array) {

                final EventImpl event = EventImpl.fromJSON(s, (ev) -> {
                }, L);
                if (event != null) {
                    eventQueueMemoryCache.add(event);
                }
            }
            // order the events from least to most recent
            eventQueueMemoryCache.sort((e1, e2) -> (int) (e1.timestamp - e2.timestamp));
        }
    }

    @Nonnull String joinEvents(@Nonnull final Collection<EventImpl> collection) {
        final List<String> strings = new ArrayList<>();
        for (EventImpl e : collection) {
            strings.add(e.toJSON(L));
        }
        return Utils.join(strings, EventQueue.DELIMITER);
    }

    /**
     * Returns an unsorted array of the current stored event JSON strings.
     */
    private synchronized @Nonnull String[] getEvents() {
        L.d("[EventQueue] Getting events from disk");
        final String joinedEventsStr = SDKCore.instance.sdkStorage.readEventQueue();
        return joinedEventsStr.isEmpty() ? new String[0] : joinedEventsStr.split(DELIMITER);
    }

    public void clear() {
        SDKCore.instance.sdkStorage.storeEventQueue("");
        synchronized (lockEQ) {
            eventQueueMemoryCache.clear();
        }
    }
}
