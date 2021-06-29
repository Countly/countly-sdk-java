package ly.count.sdk.java.internal;

/**
 * Interface for transforming date in {@link Storage}
 */

public interface Transformer {
    /**
     * Transform {@code data} and return new {@code byte[]} if necessary. In case no transformation
     * needed, return {@code null}.
     *
     * @param data {@code byte[]} to transform
     * @return new {@code byte[]} if transform succeeded, {@code null} if no transformation needed
     */
    byte[] doTheJob(Long id, byte[] data);
}
