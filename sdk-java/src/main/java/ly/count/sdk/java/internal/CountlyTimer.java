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
        L.i("[CountlyTimer] stopTimer, Stopping global timer");
        if (timerService != null) {
            try {
                timerService.shutdown();
                if (!timerService.awaitTermination(1, TimeUnit.SECONDS)) {
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
        L.i("[CountlyTimer] startTimer, Starting global timer timerDelay: [" + timerDelay + "]");
        timerDelay = timerDelay * 1000;

        if (timerDelay < 1000) {
            timerDelay = 1000;
        }

        long startTime = timerDelay;

        if (TIMER_DELAY_MS > 0) {
            timerDelay = TIMER_DELAY_MS;
            startTime = 0;
        }

        timerService.scheduleWithFixedDelay(runnable, startTime, timerDelay, TimeUnit.MILLISECONDS);
    }
}
