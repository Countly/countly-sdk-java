package ly.count.sdk.java.internal;

/**
 * Interface for {@link Module} calls uncoupling from Android SDK.
 * Contract:
 * <ul>
 *     <li>Ctx cannot be saved in a module, it expires as soon as method returns</li>
 * </ul>
 */

public interface CtxCore {
    Object getContext();
    InternalConfig getConfig();
    SDKInterface getSDK();
    boolean isExpired();

    Log getLogger();
}
