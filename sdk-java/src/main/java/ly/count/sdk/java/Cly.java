package ly.count.sdk.java;

import java.util.Map;

import ly.count.sdk.java.internal.CtxCore;
import ly.count.sdk.java.internal.Log;
import ly.count.sdk.java.internal.SDKInterface;

public abstract class Cly implements Usage {
    protected static Cly cly;
    protected CtxCore ctx;
    protected SDKInterface sdkInterface;

    //private static final Log.Module L = Log.module("Cly");
    protected static Log L = null;

    protected Cly(Log logger) {
        cly = this;
        L = logger;
    }

    protected static Session session(CtxCore ctx) {
        return cly.sdkInterface.session(ctx, null);
    }

    protected static Session getSession() {
        return cly.sdkInterface.getSession();
    }

    @Override
    public Event event(String key) {
        L.d("event: key = " + key);
        return ((Session) sdkInterface.session(ctx, null)).event(key);
    }

    @Override
    public Event timedEvent(String key) {
        L.d("timedEvent: key = " + key);
        return ((Session) sdkInterface.session(ctx, null)).timedEvent(key);
    }

    /**
     * Get current User Profile object.
     *
     * @see User#edit() to get {@link UserEditor} object
     * @see UserEditor#commit() to submit changes to the server
     * @return current User Profile instance
     */
    @Override
    public User user() {
        L.d("user");
        return ((Session) sdkInterface.session(ctx, null)).user();
    }

    @Override
    public Usage addParam(String key, Object value) {
        L.d("addParam: key = " + key + " value = " + value);
        return ((Session) sdkInterface.session(ctx, null)).addParam(key, value);
    }

    @Override
    public Usage addCrashReport(Throwable t, boolean fatal) {
        L.d("addCrashReport: t = " + t + " fatal = " + fatal);
        return ((Session) sdkInterface.session(ctx, null)).addCrashReport(t, fatal);
    }

    @Override
    public Usage addCrashReport(Throwable t, boolean fatal, String name, Map<String, String> segments, String... logs) {
        L.d("addCrashReport: t = " + t + " fatal = " + fatal + " name = " + name + " segments = " + segments + " logs = " + logs);
        return ((Session) sdkInterface.session(ctx, null)).addCrashReport(t, fatal, name, segments, logs);
    }

    @Override
    public Usage addLocation(double latitude, double longitude) {
        L.d("addLocation: latitude = " + latitude + " longitude = " + longitude);
        return ((Session) sdkInterface.session(ctx, null)).addLocation(latitude, longitude);
    }

    @Override
    public View view(String name, boolean start) {
        L.d("view: name = " + name + " start = " + start);
        return ((Session) sdkInterface.session(ctx, null)).view(name, start);
    }

    @Override
    public View view(String name) {
        L.d("view: name = " + name);
        return ((Session) sdkInterface.session(ctx, null)).view(name);
    }
}
