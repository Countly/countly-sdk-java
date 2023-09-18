package ly.count.sdk.java.internal;

import java.util.Map;

public class ModuleEvents extends ModuleBase {

    @Override
    public void init(InternalConfig config, Log logger) {
        super.init(config, logger);
        internalConfig = config;
    }

    private void recordEventInternal(String key, int count, double sum, Map<String, Object> segmentation, double dur) {

    }

    private boolean startEventInternal(String key) {
        return false;
    }

    private boolean endEventInternal(String key) {
        return false;
    }

    private boolean cancelEventInternal(String key) {
        return false;
    }

    public class Events {

        /**
         * Record an event.
         *
         * @param key key for this event, cannot be null or empty
         * @param count how many of these events have occurred, default value is "1", must be greater than 0
         * @param sum set sum parameter of the event default value is "0"
         * @param dur set duration of event, default value is "0"
         * @param segmentation additional segmentation data that you want to set, leave null if you don't want to add anything
         */
        public void recordEvent(String key, int count, double sum, Map<String, Object> segmentation, double dur) {
            recordEventInternal(key, count, sum, segmentation, dur);
        }

        /**
         * Record an event with "duration" 0
         *
         * @param key key for this event, cannot be null or empty
         * @param count how many of these events have occurred, default value is "1", must be greater than 0
         * @param sum set sum parameter of the event default value is "0"
         * @param segmentation additional segmentation data that you want to set, leave null if you don't want to add anything
         */
        public void recordEvent(String key, int count, double sum, Map<String, Object> segmentation) {
            recordEvent(key, count, sum, segmentation, 0.0);
        }

        /**
         * Record an event with "segmentation","key" and "count" value only
         * "duration" is zero by default
         *
         * @param key key for this event, cannot be null or empty
         * @param count how many of these events have occurred, default value is "1", must be greater than 0
         * @param segmentation additional segmentation data that you want to set, leave null if you don't want to add anything
         */
        public void recordEvent(String key, int count, Map<String, Object> segmentation) {
            recordEvent(key, count, 0.0, segmentation);
        }

        /**
         * Record an event with "segmentation" and "key" value only
         * "sum" and "duration" is zero by default
         *
         * @param key key for this event, cannot be null or empty
         * @param segmentation additional segmentation data that you want to set, leave null if you don't want to add anything
         */
        public void recordEvent(String key, Map<String, Object> segmentation) {
            recordEvent(key, 1, 0.0, segmentation);
        }

        /**
         * Record an event with "key" only
         * "sum" and "duration" is zero by default
         * "count" is 1 by default
         *
         * @param key key for this event, cannot be null or empty
         */
        public void recordEvent(String key) {
            recordEvent(key, 1, 0, null);
        }

        /**
         * Record an event with "key" and "count" only
         * "sum" and "duration" is zero by default
         *
         * @param key key for this event, cannot be null or empty
         * @param count how many of these events have occurred, default value is "1", must be greater than 0
         */
        public void recordEvent(String key, int count) {
            recordEvent(key, count, 0, null);
        }

        /**
         * Record an event with "key", "sum" and "count" only
         * "duration" is zero by default
         *
         * @param key key for this event, cannot be null or empty
         * @param count how many of these events have occurred, default value is "1", must be greater than 0
         * @param sum set sum parameter of the event default value is "0"
         */
        public void recordEvent(String key, double sum, int count) {
            recordEvent(key, count, sum, null);
        }

        /**
         * Start timed event with a specified key
         *
         * @param key name of the custom event, required, must not be the empty string or null
         * @return true if no event with this key existed before and event is started, false otherwise
         */
        public boolean startEvent(final String key) {
            return startEventInternal(key);
        }

        /**
         * End timed event with a specified key
         *
         * @param key name of the custom event, required, must not be the empty string or null
         * @return true if event with this key has been previously started, false otherwise
         */
        public boolean endEvent(final String key) {
            return endEventInternal(key);
        }

        /**
         * Cancel timed event with a specified key
         *
         * @return true if event with this key has been previously started, false otherwise
         **/
        public boolean cancelEvent(final String key) {
            return cancelEventInternal(key);
        }
    }
}
