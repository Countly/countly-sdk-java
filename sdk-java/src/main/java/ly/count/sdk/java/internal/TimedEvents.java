package ly.count.sdk.java.internal;

import java.util.HashSet;
import java.util.Set;
import ly.count.sdk.java.Countly;
import ly.count.sdk.java.Event;

class TimedEvents implements EventImpl.EventRecorder {

    protected TimedEvents() {
    }

    /**
     * @return key set
     * @deprecated this will not function anymore
     */
    Set<String> keys() {
        return new HashSet<>();
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
        }

        return event;
    }

    /**
     * @param key key of event to check
     * @return true if event with given key is currently timed
     * @deprecated use {@link ModuleEvents.Events#startEvent(String)}} instead via <code>instance().events()</code> call
     * this will return false always, because timed events are not stored now
     */
    boolean has(String key) {
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
        Countly.instance().events().endEvent(eventImpl.key, eventImpl.segmentation, eventImpl.count, eventImpl.sum);
    }

    /**
     * @return this will return 0 always, because timed events are not stored now
     */
    public int size() {
        return 0;
    }
}
