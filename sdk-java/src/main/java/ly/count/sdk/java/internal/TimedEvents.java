package ly.count.sdk.java.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import ly.count.sdk.java.Countly;
import ly.count.sdk.java.Event;

class TimedEvents implements EventImpl.EventRecorder {

    private final Map<String, EventImpl> events;

    protected TimedEvents() {
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
