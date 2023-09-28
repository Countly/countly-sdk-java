package ly.count.sdk.java;

import ly.count.sdk.java.internal.ModuleEvents;
import javax.annotation.Nonnull;
import java.util.Map;

/**
 * Event interface. By default event is created with count=1 and all other fields empty or 0.
 */

public interface Event {
    /**
     * Add event to the buffer, send it to the server in case number of events in the session
     * is equal or bigger than {@link Config#eventQueueThreshold} or wait until next {@link Session#update()}.
     *
     * @deprecated this function is deprecated, use {@link ModuleEvents.Events#recordEvent(String, int, double, Map, double)} instead
     */
    void record();

    /**
     * Set timed {@link Event} duration as difference between moment {@link Event} was created
     * and current time in seconds. Then add the event to its session (if they're enabled),
     * send it to the server in case number of events in the session is equal or bigger
     * than {@link Config#eventQueueThreshold} or wait until next {@link Session#update()}.
     *
     * @deprecated this function is deprecated, use {@link ModuleEvents.Events#endEvent(String, Map, int, double)} instead
     */
    void endAndRecord();

    /**
     * Add one segmentation entry to this event
     *
     * @param key key of segment, must not be null or empty
     * @param value value of segment, must not be null or empty
     * @return this instance for method chaining
     * @deprecated this function is deprecated, use {@link ModuleEvents.Events#recordEvent(String, int, double, Map, double)} instead
     */
    Event addSegment(@Nonnull String key, @Nonnull String value);

    /**
     * Set event segmentation from
     *
     * @param segmentation set of strings in form of (key1, value1, key2, value2, ...) to set
     * segmentation from; cannot contain nulls or empty strings; must have
     * even length
     * @return this instance for method chaining
     * @deprecated this function is deprecated, use {@link ModuleEvents.Events#recordEvent(String, int, double, Map, double)} instead
     */
    Event addSegments(@Nonnull String... segmentation);

    /**
     * Set event segmentation from a map
     *
     * @param segmentation map of segment pairs ({key1: value1, key2: value2}
     * @return this instance for method chaining
     * @deprecated this function is deprecated, use {@link ModuleEvents.Events#recordEvent(String, int, double, Map, double)} instead
     */
    Event setSegmentation(@Nonnull Map<String, String> segmentation);

    /**
     * Overwrite default count=1 for this event
     *
     * @param count event count, cannot be 0
     * @return this instance for method chaining
     * @deprecated this function is deprecated, use {@link ModuleEvents.Events#recordEvent(String, int, double, Map, double)} instead
     */
    Event setCount(int count);

    /**
     * Set event sum
     *
     * @param sum event sum
     * @return this instance for method chaining
     * @deprecated this function is deprecated, use {@link ModuleEvents.Events#recordEvent(String, int, double, Map, double)} instead
     */
    Event setSum(double sum);

    /**
     * Set event duration
     *
     * @param duration event duration
     * @return this instance for method chaining
     * @deprecated this function is deprecated, use {@link ModuleEvents.Events#recordEvent(String, int, double, Map, double)} instead
     */
    Event setDuration(double duration);

    /**
     * Whether event has been marked as invalid, meaning it won't be recorded
     *
     * @return {@code true} if event has been invalidated due to:
     * <ul>
     *     <li>Invalid data supplied (count < 0, NaN as sum, duration < 0, etc.) while in production mode</li>
     *     <li>Event has been already recorded in session, thus should be discarded</li>
     * </ul>
     * @deprecated this function is deprecated
     */
    boolean isInvalid();
}
