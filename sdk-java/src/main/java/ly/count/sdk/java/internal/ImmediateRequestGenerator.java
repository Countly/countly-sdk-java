package ly.count.sdk.java.internal;

/**
 * Interface for creating ImmediateRequestMaker
 * a basic factory pattern implementation
 *
 * @see ImmediateRequestI
 */
public interface ImmediateRequestGenerator {
    /**
     * Create a new instance of ImmediateRequestI
     * and override when needed
     *
     * @return new instance of ImmediateRequestI
     */
    ImmediateRequestI createImmediateRequestMaker();
}
