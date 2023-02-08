package ly.count.sdk.java;

/**
 * Contract interface for Views functionality
 */

public interface View {
    void start(boolean firstView);

    void stop(boolean lastView);
}
