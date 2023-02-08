package ly.count.sdk.java.internal;

/**
 * Serialization interface.
 */

public interface Byteable {
    byte[] store();

    boolean restore(byte[] data);
}
