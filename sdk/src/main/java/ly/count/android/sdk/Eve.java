package ly.count.android.sdk;

import java.util.Map;

/**
 * Event interface. By default event is created with count=1 and all other fields empty.
 */

// TODO: rename
public interface Eve {
    /**
     * Add event to the session, send it to the server in case number of events in the session
     * is equal or bigger than {@link Config#sendUpdateEachEvents}
     *
     * @return {@link Session} instance event is put into for method chaining
     */
    Session record();

    /**
     * Add one segmentation entry to this event
     *
     * @param key key of segment, must not be null or empty
     * @param value value of segment, must not be null or empty
     * @return this instance for method chaining
     */
    Eve addSegment(String key, String value);

    /**
     * Set event segmentation from
     *
     * @param segmentation set of strings in form of (key1, value1, key2, value2, ...) to set
     *                     segmentation from; cannot contain nulls or empty strings; must have
     *                     even length
     * @return this instance for method chaining
     */
    Eve addSegments(String ...segmentation);

    /**
     * Set event segmentation from a map
     *
     * @param segmentation map of segment pairs ({key1: value1, key2: value2}
     * @return this instance for method chaining
     */
    Eve setSegmentation(Map<String, String> segmentation);

    /**
     * Overwrite default count=1 for this event
     *
     * @param count event count, cannot be 0
     * @return this instance for method chaining
     */
    Eve setCount(int count);

    /**
     * Set event sum
     *
     * @param sum event sum
     * @return this instance for method chaining
     */
    Eve setSum(double sum);

    /**
     * Set event duration
     *
     * @param duration event duration
     * @return this instance for method chaining
     */
    Eve setDuration(double duration);
}
