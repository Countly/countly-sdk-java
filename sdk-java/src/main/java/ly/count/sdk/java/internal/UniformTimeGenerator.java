package ly.count.sdk.java.internal;

/**
 * Always increasing timer.
 */
public class UniformTimeGenerator implements Device.TimeGenerator {
    private Long last;

    @Override
    public synchronized long timestamp() {
        long ms = System.currentTimeMillis();
        if (last == null) {
            last = ms;
        } else if (last >= ms) {
            last = last + 1;
            return last;
        } else {
            last = ms;
        }
        return ms;
    }
}