package ly.count.sdk.java.internal;

import ly.count.sdk.java.Session;

/**
 * Created by artem on 05/01/2017.
 * Contract:
 * <ul>
 *     <li>ModuleBase instances must provide empty constructor with no parameters.</li>
 *     <li>ModuleBase instance can be accessed only through this interface (static methods allowed for encapsulation).</li>
 *     <li>ModuleBase instance encapsulates all module-specific logic inside.</li>
 *     <li>ModuleBase cannot acquire instance or call another ModuleBase.</li>
 * </ul>
 */
public abstract class ModuleBase {
    private boolean active = false;

    protected Log L = null;
    InternalConfig internalConfig = null;

    /**
     * All initialization must be done in this method, not the constructor.
     * This method is guaranteed to be run right after the constructor with no module-related actions in between.
     *
     * @param config Countly configuration object: can be stored locally if needed.
     * @throws IllegalArgumentException in case supplied {@link InternalConfig} is not consistent.
     * @throws IllegalStateException if some required for this module platform feature is not available on this platform.
     */
    public void init(InternalConfig config) {
        L = config.getLogger();
        internalConfig = config;
    }

    /**
     * App users opted out of analytics, or the developer changed essential preferences.
     * Clear all module-related data, close any resources, and prepare to start from a clean sheet.
     * This method is guaranteed to be the latest call to this module instance.
     * <ul>
     *     <li>Stop all tasks, clear all context references.</li>
     *     <li>Not a single function call can be fired from this object after the method returns.</li>
     *     <li>Remove all module-related {@link Storable} files if {@code clear} is {@code true}</li>
     * </ul>
     *
     * @param config {@link InternalConfig} to run in
     * @param clear {@code true} if module must clear its data files, {@code false} otherwise
     */
    public void stop(InternalConfig config, boolean clear) {
    }

    /**
     * A method to be used by the module itself to determine if {@link #init(InternalConfig)} initialized it
     * and haven't been stopped yet by {@link #stop(InternalConfig, boolean)}.
     *
     * @return {@code true} if module is allowed to continue to run, {@code false} otherwise
     */
    public boolean isActive() {
        return active;
    }

    /**
     * Method to activate and deactivate module itself
     *
     * @param active a boolean parameter to either be true or false
     */
    public void setActive(boolean active) {
        this.active = active;
    }

    /**
     * Session is started.
     *
     * @param session session which began
     */
    public void onSessionBegan(Session session, InternalConfig config) {
    }

    /**
     * Session is ended.
     *
     * @param session session which ended
     */
    public void onSessionEnded(Session session, InternalConfig config) {
    }

    /**
     * This method is called only on owning module only if module marks request as owned ({@link Request#own(Class)}.
     * Gives a module another chance to modify request before sending. Being run in {@code Countly}.
     *
     * @param request request to check
     * @return {@code true} if ok to send now, {@code false} if not ok to (remove request
     * from queue), {@code null} if cannot decide yet
     */
    public Boolean onRequest(Request request) {
        return false;
    }

    /**
     * Called when the request is executed.
     * Gives the module the ability to respond to the response received
     * Request identification should be done through the Request ID
     */
    public void onRequestCompleted(Request request, String response, int responseCode) {

    }

    /**
     * Called when the sdk timer ticks.
     */
    protected void onTimer() {
    }

    /**
     * Called when the initialization is finished.
     */
    protected void initFinished(InternalConfig config) {
    }

    /**
     * Called when the device id is changed.
     *
     * @param oldDeviceId old device id
     * @param withMerge if the device id change is with merge or not
     */
    protected void deviceIdChanged(String oldDeviceId, boolean withMerge) {

    }
}
