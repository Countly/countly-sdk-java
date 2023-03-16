package ly.count.sdk.java.internal;

/**
 * Serialization interface.
 */

public interface Byteable {
    byte[] store(Log L);

    boolean restore(byte[] data, Log L);
}
