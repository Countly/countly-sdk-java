package ly.count.sdk.java.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Unique timer, keeps last 10 returned values in memory.
 */
class UniqueTimeGenerator {
    final List<Long> lastTsMs = new ArrayList<>(10);
    long addition = 0;
    long lastId = 0;

    long currentTimeMillis() {
        return System.currentTimeMillis() + addition;
    }

    public synchronized long timestamp() {
        long ms = currentTimeMillis();

        // change time back case
        if (lastTsMs.size() > 2) {
            long min = Collections.min(lastTsMs);
            if (ms < min) {
                lastTsMs.clear();
                lastTsMs.add(ms);
                return ms;
            }
        }
        // usual case
        while (lastTsMs.contains(ms)) {
            ms += 1;
        }
        while (lastTsMs.size() >= 10) {
            lastTsMs.remove(0);
        }
        lastTsMs.add(ms);
        return ms;
    }

    /**
     * Returns the current time in milliseconds, or one more than the previous id while the clock
     * has not moved past it. Unlike {@link #timestamp()}, which only remembers its last 10 values,
     * an id never repeats or goes back within a process, not in a burst and not after the clock is
     * set back.
     *
     * @return an id greater than every id returned before
     */
    synchronized long id() {
        lastId = Math.max(currentTimeMillis(), lastId + 1);
        return lastId;
    }
}
