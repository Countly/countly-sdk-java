package ly.count.sdk.java.internal;

public interface DeviceIdGenerator {
    boolean isAvailable();

    String generate(CtxCore context);
}
