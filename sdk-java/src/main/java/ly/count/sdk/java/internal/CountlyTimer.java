package ly.count.sdk.java.internal;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class CountlyTimer {

    private final Log L;
    private ScheduledExecutorService timerService;
    protected static int TIMER_DELAY_MS = 0; // for testing purposes

    protected CountlyTimer(Log logger) {
        L = logger;
        timerService = Executors.newSingleThreadScheduledExecutor();
    }

    protected void stopTimer() {
        stopTimer(true);
    }

    /**
     * @param awaitTermination whether to wait for a running task to finish. Must be {@code false}
     *     when called from the timer's own task, because a task cannot wait for itself.
     */
    protected void stopTimer(boolean awaitTermination) {
        L.i("[CountlyTimer] stopTimer, Stopping global timer");
        if (timerService != null) {
            try {
                timerService.shutdown();
                if (awaitTermination && !timerService.awaitTermination(1, TimeUnit.SECONDS)) {
                    timerService.shutdownNow();
                    if (!timerService.awaitTermination(1, TimeUnit.SECONDS)) {
                        L.e("[SDKCore] Global timer must be locked");
                    }
                }
            } catch (Throwable t) {
                L.e("[SDKCore] Error while stopping global timer " + t);
            }
            timerService = null;
        }
    }

    protected void startTimer(long timerDelay, Runnable runnable) {
        startTimer(timerDelay, -1, runnable);
    }

    /**
     * @param timerDelay interval between two runs, in seconds
     * @param initialDelayMs how long to wait before the first run, in milliseconds. A negative
     *     value uses the interval itself as the initial delay.
     * @param runnable what to run on every tick
     */
    protected void startTimer(long timerDelay, long initialDelayMs, Runnable runnable) {
        L.i("[CountlyTimer] startTimer, Starting global timer timerDelay: [" + timerDelay + "] initialDelayMs: [" + initialDelayMs + "]");
        long delay = timerDelay * 1000;

        if (delay < 1000) {
            delay = 1000;
        }

        long startTime = initialDelayMs < 0 ? delay : initialDelayMs;

        if (TIMER_DELAY_MS > 0) {
            delay = TIMER_DELAY_MS;
            startTime = 0;
        }

        timerService.scheduleWithFixedDelay(runnable, startTime, delay, TimeUnit.MILLISECONDS);
    }
}
