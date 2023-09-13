package ly.count.sdk.java.internal;

/**
 * Serialization interface.
 */

public interface Storable extends Byteable {
    Long storageId();

    String storagePrefix();

    void setId(Long id);
}
