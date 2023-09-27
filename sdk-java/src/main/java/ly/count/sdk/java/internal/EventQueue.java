package ly.count.sdk.java.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class EventQueue {

    static final String DELIMITER = ":::";
    private final Log L;
    private final InternalConfig config;
    final List<EventImpl> eventQueueMemoryCache;

    protected EventQueue(Log logger, InternalConfig config) {
        L = logger;
        this.config = config;
        eventQueueMemoryCache = new ArrayList<>(config.getEventsBufferSize());
    }

    /**
     * Returns the number of events currently stored in the queue.
     */
    protected int eqSize() {
        return eventQueueMemoryCache.size();
    }

    void addEvent(final EventImpl event) {
        L.d("[EventQueue] Adding event: " + event.key);
        if (eventQueueMemoryCache.size() < config.getEventsBufferSize()) {
            eventQueueMemoryCache.add(event);
            writeEventQueueToStorage();
        }
    }

    /**
     * set the new value in event data storage
     */
    void writeEventQueueToStorage() {
        final String eventQueue = joinEvents(eventQueueMemoryCache);

        L.d("[EventQueue] Setting event data: " + eventQueue);
        SDKCore.instance.sdkStorage.storeEventQueue(eventQueue);
    }

    /**
     * Restores events from disk
     */
    void restore() {
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

    private String joinEvents(final Collection<EventImpl> collection) {
        final List<String> strings = new ArrayList<>();
        for (EventImpl e : collection) {
            strings.add(e.toJSON(L));
        }
        return Utils.join(strings, EventQueue.DELIMITER);
    }

    /**
     * Returns an unsorted array of the current stored event JSON strings.
     */
    private synchronized String[] getEvents() {
        L.d("[EventQueue] Getting events from disk");
        final String joinedEventsStr = SDKCore.instance.sdkStorage.readEventQueue();
        return joinedEventsStr.isEmpty() ? new String[0] : joinedEventsStr.split(DELIMITER);
    }

    public void clear() {
        SDKCore.instance.sdkStorage.storeEventQueue("");
        eventQueueMemoryCache.clear();
    }
}
