package ly.count.sdk.java.internal;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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
    //what a call through an interface while its module does not run hands to its callback
    static final String NOT_RUNNING_ERROR = "The feature does not run, as its consent is not given or it is not enabled";

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
     * The instance of this module that the SDK runs, which every call through an interface of this
     * module goes to: this one, or the running one when the interface was handed out before that
     * started or kept from an instance that stopped. A call while none runs, as before init or while
     * the consent of the feature is not given, is ignored and logged.
     *
     * @param call the call, starting with the bracketed name of the interface, for the log line
     * @param <M> the module type
     * @return the running instance, {@code null} while none runs
     */
    @SuppressWarnings("unchecked")
    @Nullable
    <M extends ModuleBase> M runningInstance(@Nonnull String call) {
        SDKCore core = SDKCore.instance;
        ModuleBase running = core == null ? null : core.module(getClass());
        if (running == null && core != null && core.L != null) {
            core.L.w(call + ", the feature does not run, as its consent is not given or it is not enabled, ignoring the call");
        }
        return (M) running;
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
     * Called when the request is executed, only on the owning module ({@link Request#own(Class)}),
     * once for every attempt to send it, on the networking thread. A request dropped unsent for
     * being older than the request drop age gets a call too, with a {@code null} response and
     * {@link Transport#NO_RESPONSE_CODE}.
     * Gives the module the ability to respond to the response received
     * Request identification should be done through the Request ID
     *
     * @param request the request that was sent
     * @param response the response body, {@code null} when none could be read
     * @param responseCode the response code, {@link Transport#NO_RESPONSE_CODE} when no response arrived
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

    /**
     * Called when a server response changed the SDK behavior settings while the SDK runs, after
     * the SDK applied what it owns: the logger, the timer interval and the request queue. Read the
     * new values through {@link InternalConfig#getConfigurationProvider()}. Runs on the thread that
     * delivered the response, which is usually not the thread that initialized the SDK, and can run
     * after {@link #init(InternalConfig)} but before {@link #initFinished(InternalConfig)} when the
     * response of the fetch made at init arrives that early.
     *
     * @param config configuration of the running SDK
     */
    protected void onSdkConfigurationChanged(InternalConfig config) {
    }
}
