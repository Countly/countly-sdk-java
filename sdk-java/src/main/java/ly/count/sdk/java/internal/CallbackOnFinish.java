package ly.count.sdk.java.internal;

public interface CallbackOnFinish<T> {
    void onFinished(T result, String error);
}