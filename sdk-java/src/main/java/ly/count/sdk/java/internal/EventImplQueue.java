package ly.count.sdk.java.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class EventImplQueue {

    static final String DELIMITER = ":::";

    private final Log L;

    protected int size = 0;
    private final InternalConfig config;

    protected EventImplQueue(Log logger, InternalConfig config) {
        L = logger;
        this.config = config;
    }

    void addEvent(final EventImpl event) {
        L.d("[EventImplQueue] Adding event: " + event.key);
        final List<EventImpl> events = getEventList();
        if (events.size() < config.getEventsBufferSize()) {
            events.add(event);
            size = events.size();
            setEventData(joinEvents(events));
        }
    }

    String joinEvents(final Collection<EventImpl> collection) {
        final List<String> strings = new ArrayList<>();
        for (EventImpl e : collection) {
            strings.add(e.toJSON(L));
        }
        return Utils.join(strings, EventImplQueue.DELIMITER);
    }

    /**
     * set the new value in event data storage
     *
     * @param eventData
     */
    void setEventData(String eventData) {
        L.d("[EventImplQueue] Setting event data: " + eventData);
        SDKCore.instance.sdkStorage.storeEventQueue(eventData);
    }

    /**
     * Returns a list of the current stored events, sorted by timestamp from oldest to newest.
     */
    public synchronized List<EventImpl> getEventList() {
        L.d("[EventImplQueue] Getting event list");
        final String[] array = getEvents();
        final List<EventImpl> events = new ArrayList<>(array.length);
        for (String s : array) {

            final EventImpl event = EventImpl.fromJSON(s, (ev) -> {
            }, L);
            if (event != null) {
                events.add(event);
            }
        }
        // order the events from least to most recent
        events.sort((e1, e2) -> (int) (e1.timestamp - e2.timestamp));
        size = events.size();
        return events;
    }

    public void clear() {
        size = 0;
        SDKCore.instance.sdkStorage.storeEventQueue("");
    }

    /**
     * Returns an unsorted array of the current stored event JSON strings.
     */
    private synchronized String[] getEvents() {
        L.d("[EventImplQueue] Getting events from disk");
        final String joinedEventsStr = SDKCore.instance.sdkStorage.readEventQueue();
        return joinedEventsStr.isEmpty() ? new String[0] : joinedEventsStr.split(DELIMITER);
    }

    /**
     * Restores events from disk
     */
    void restore(){
        L.d("[EventImplQueue] Restoring events from disk");
        getEventList();
    }
}
