package ly.count.sdk.java;

import java.util.Map;
import ly.count.sdk.java.internal.ModuleDeviceIdCore;
import ly.count.sdk.java.internal.ModuleEvents;
import ly.count.sdk.java.internal.ModuleViews;

/**
 * This interface represents session concept, that is one indivisible usage occasion of your application.
 *
 * Any data sent to Countly server is processed in a context of Session.
 * Only one session can send requests at a time, so even if you create 2 parallel sessions,
 * they will be made consequent automatically at the time of Countly SDK choice with no
 * correctness guarantees, so please avoid having parallel sessions.
 */

public interface Usage {

    /**
     * Create event object, don't record it yet. Creates begin request if this session
     * hasn't yet been begun.
     *
     * @param key key for this event, cannot be null or empty
     * @return Event instance.
     * @see Event#record()
     * @deprecated this function is deprecated, use {@link ModuleEvents.Events#recordEvent} instead
     */
    Event event(String key);

    /**
     * Get existing or create new timed event object, don't record it. Creates begin request if this session
     * hasn't yet been begun.
     *
     * @param key key for this event, cannot be null or empty
     * @return timed Event instance.
     * @see Event#endAndRecord() to end timed event
     * @deprecated use {@link ModuleEvents.Events#startEvent(String)}} instead via <code>instance().events()</code> call
     */
    Event timedEvent(String key);

    /**
     * Get current User Profile.
     * Note that data is not downloaded from server. Only properties set in current app
     * installation are returned in {@link User} object.
     * Note that even when the {@link ly.count.sdk.java.internal.CoreFeature#UserProfiles} is disabled,
     * this method still returns an object, yet any modifications to it won't result in any data stored
     * or sent to the server/
     *
     * //@ee Feature is not available in Countly Lite Edition
     *
     * @return current {@link User} instance
     */
    User user();

    /**
     * Add parameter to this session which will be sent along with next request.
     *
     * @param key name of parameter
     * @param value value of parameter
     * @return this instance for method chaining.
     */
    Usage addParam(String key, Object value);

    /**
     * Send Crash Report to the server.
     *
     * @param t {@link Throwable} to log
     * @param fatal whether this crash report should be displayed as fatal in dashboard or not
     * @return this instance for method chaining.
     * @deprecated please use {@link Countly#crashes()} instead via "instance()" call
     */
    Usage addCrashReport(Throwable t, boolean fatal);

    /**
     * Send Crash Report to the server.
     *
     * @param t {@link Throwable} to log
     * @param fatal whether this crash report should be displayed as fatal in dashboard or not
     * @param name (optional, can be {@code null}) name of the report, falls back to first line of stack trace by default
     * @param segments (optional, can be {@code null}) additional crash segments map
     * @param logs (optional, can be {@code null}) additional log lines (separated by \n) or comment about this crash report
     * @return this instance for method chaining.
     * @deprecated please use {@link Countly#crashes()} instead via "instance()" call
     */
    Usage addCrashReport(Throwable t, boolean fatal, String name, Map<String, String> segments, String... logs);

    /**
     * Send location information to the server.
     *
     * @param latitude geographical latitude of the user
     * @param longitude geographical longitude of the user
     * @return this instance for method chaining.
     * @deprecated use {@link Countly#location()} via "instance()" call
     */
    Usage addLocation(double latitude, double longitude);

    /**
     * Start new view.
     * In case previous view in this session is not ended yet, it will be ended automatically.
     * In case session ends and last view haven't been ended yet, it will be ended automatically.
     * Creates begin request if this session hasn't yet been begun.
     *
     * @param name String representing name of this View
     * @param start whether this view is first in current application launch
     * @return new but already started {@link View}, you're responsible for its ending by calling {@link View#stop(boolean)}
     * @deprecated use {@link ModuleViews.Views#startView(String)} instead via {@link Countly#views()}
     */
    View view(String name, boolean start);

    /**
     * Identical to {@link #view(String, boolean)}, but without {@code start} parameter which
     * is determined automatically based on whether this view is first in this session.
     * Creates begin request if this session hasn't yet been begun.
     *
     * @param name String representing name of this View
     * @return new but already started {@link View}, you're responsible for its ending by calling {@link View#stop(boolean)}
     * @deprecated use {@link ModuleViews.Views#startView(String)} instead via {@link Countly#views()}
     */
    View view(String name);

    /**
     * @param id new user / device id string, cannot be empty
     * @deprecated Login function to set device (user) id on Countly server to the string specified here.
     * Closes current session, then starts new one automatically if Config#autoSessionsTracking is on, acquires device id.
     */
    Usage login(String id);

    /**
     * @deprecated Logout function to make current user anonymous (that is with random id according to
     * {@link Config#deviceIdStrategy} and such). Obviously makes sense only after a call to {@link #login(String)},
     * so it throws error or does nothing (depending on Config#testMode) if current id wasn't set using {@link #login(String)}.
     *
     * Closes current session.
     */
    Usage logout();

    /**
     * This method returns the Device ID that is currently used by the SDK.
     *
     * @return Device ID string
     * @deprecated use {@link ModuleDeviceIdCore.DeviceId#getID()}
     */
    String getDeviceId();

    /**
     * Change device id with merging profiles on server, just set device id to new one.
     *
     * @param id new user / device id string, cannot be empty
     * @deprecated use {@link ModuleDeviceIdCore.DeviceId#changeWithMerge(String)}
     */
    Usage changeDeviceIdWithMerge(String id);

    /**
     * Change device id without merging profiles on server, just set device id to new one.
     *
     * @param id new user / device id string, cannot be empty
     * @deprecated use {@link ModuleDeviceIdCore.DeviceId#changeWithoutMerge(String)}
     */
    Usage changeDeviceIdWithoutMerge(String id);
}
