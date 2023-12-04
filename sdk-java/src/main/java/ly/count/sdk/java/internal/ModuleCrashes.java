package ly.count.sdk.java.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import ly.count.sdk.java.Countly;
import ly.count.sdk.java.Crash;
import ly.count.sdk.java.CrashProcessor;

/**
 * Crash reporting functionality
 */

public class ModuleCrashes extends ModuleBase {

    protected long started = 0;
    private boolean crashed = false;

    protected InternalConfig config;
    private Thread.UncaughtExceptionHandler previousHandler = null;
    protected CrashProcessor crashProcessor = null;
    protected List<String> logs = new ArrayList<>();

    Crashes crashInterface;
    String legacyName = null;

    @Override
    public void init(InternalConfig config) {
        super.init(config);
        this.config = config;
        if (config.getCrashProcessorClass() != null) {
            try {
                Class cls = Class.forName(config.getCrashProcessorClass());
                crashProcessor = (CrashProcessor) cls.getConstructors()[0].newInstance();
            } catch (Throwable t) {
                L.e("[ModuleCrash] Cannot instantiate CrashProcessor" + t);
            }
        }
        crashInterface = new Crashes();
    }

    @Override
    public void stop(InternalConfig config, boolean clear) {
        try {
            if (previousHandler != null) {
                Thread.setDefaultUncaughtExceptionHandler(previousHandler);
            }
            if (clear) {
                config.sdk.sdkStorage.storablePurge(config, CrashImpl.getStoragePrefix());
            }
        } catch (Throwable t) {
            L.e("[ModuleCrash] Exception while stopping crash reporting" + t);
        }
        logs.clear();
        legacyName = null;
        crashInterface = null;
    }

    @Override
    public void initFinished(final InternalConfig config) {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();

        if (internalConfig.sdk.hasConsentForFeature(CoreFeature.CrashReporting) && config.isUnhandledCrashReportingEnabled()) {
            registerUncaughtExceptionHandler();
        }

        started = System.nanoTime();
    }

    private void registerUncaughtExceptionHandler() {
        final Thread.UncaughtExceptionHandler handler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            // needed since following UncaughtExceptionHandler can keep reference to this one
            crashed = true;

            if (isActive()) {
                recordExceptionInternal(throwable, false, null);
            }

            if (handler != null) {
                handler.uncaughtException(thread, throwable);
            }
        });
    }

    protected void recordExceptionInternal(Throwable t, boolean handled, Map<String, Object> segments) {
        if (config.isBackendModeEnabled()) {
            L.w("[ModuleCrash] recordExceptionInternal, Skipping crash, backend mode is enabled!");
            return;
        }

        if (t == null) {
            L.e("[ModuleCrash] recordExceptionInternal, Throwable cannot be null");
            return;
        }
        onCrash(config, new CrashImpl(L).addThrowable(t).setFatal(!handled).addSegments(segments));
    }

    public CrashImpl onCrash(InternalConfig config, CrashImpl crash) {
        long running = started == 0 ? 0 : TimeUtils.nsToMs(System.nanoTime() - started);
        crash.putMetrics(running);

        if (!crash.getData().has("_os")) {
            L.w("[ModuleCrash] onCrash, While recording an exception 'OS name' was either null or empty");
        }

        if (!crash.getData().has("_app_version")) {
            L.w("[ModuleCrash] onCrash, While recording an exception 'App version' was either null or empty");
        }

        if (!logs.isEmpty()) {
            crash.setLogs(logs.toArray(new String[0]));
            logs.clear();
        }

        if (Utils.isEmptyOrNull(legacyName)) {
            crash.setName(legacyName);
            legacyName = null;
        }

        L.i("[ModuleCrash] onCrash: " + crash.getJSON());

        if (crashProcessor != null) {
            try {
                Crash result = crashProcessor.process(crash);

                if (result == null) {
                    L.i("[ModuleCrash] Crash is set to be ignored by CrashProcessor#process(Crash) " + crashProcessor);
                    Storage.remove(config, crash);
                    return null;
                }
            } catch (Throwable t) {
                L.e("[ModuleCrash] Error when calling CrashProcessor#process(Crash)" + t);
            }
        }
        if (!Storage.push(config, crash)) {
            L.e("[ModuleCrash] Couldn't persist a crash, so dumping it here: " + crash.getJSON());
        } else {
            SDKCore.instance.onSignal(config, SDKCore.Signal.Crash.getIndex(), crash.storageId().toString());
        }
        return crash;
    }

    private void addBreadcrumbInternal(String record) {
        if (Utils.isEmptyOrNull(record)) {
            L.e("[ModuleCrash] addBreadcrumbInternal, record cannot be null or empty");
            return;
        }

        if (logs.size() >= config.getMaxBreadcrumbCount()) {
            logs.remove(0);
        }

        logs.add(record);
    }

    public class Crashes {

        /**
         * Add crash breadcrumb like log record to the log that will be sent together with crash report
         *
         * @param record String a bread crumb for the crash report
         */
        public void addCrashBreadcrumb(String record) {
            synchronized (Countly.instance()) {
                L.i("[Crashes] Adding crash breadcrumb");
                addBreadcrumbInternal(record);
            }
        }

        /**
         * Log handled exception to report it to server as non-fatal crash
         *
         * @param exception Throwable to log
         */
        public void recordHandledException(Throwable exception) {
            synchronized (Countly.instance()) {
                recordExceptionInternal(exception, true, null);
            }
        }

        /**
         * Log unhandled exception to report it to server as fatal crash
         *
         * @param exception Throwable to log
         */
        public void recordUnhandledException(Throwable exception) {
            synchronized (Countly.instance()) {
                recordExceptionInternal(exception, false, null);
            }
        }

        /**
         * Log handled exception to report it to server as non-fatal crash
         *
         * @param exception Throwable to log
         */
        public void recordHandledException(final Throwable exception, final Map<String, Object> customSegmentation) {
            synchronized (Countly.instance()) {
                recordExceptionInternal(exception, true, customSegmentation);
            }
        }

        /**
         * Log unhandled exception to report it to server as fatal crash
         *
         * @param exception Throwable to log
         */
        public void recordUnhandledException(final Throwable exception, final Map<String, Object> customSegmentation) {
            synchronized (Countly.instance()) {
                recordExceptionInternal(exception, false, customSegmentation);
            }
        }
    }
}
