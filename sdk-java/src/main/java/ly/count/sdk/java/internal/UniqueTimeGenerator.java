package ly.count.sdk.java.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Unique timer, keeps last 10 returned values in memory.
 */
class UniqueTimeGenerator implements Device.TimeGenerator {
    final List<Long> lastTsMs = new ArrayList<>(10);
    long addition = 0;

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
}
