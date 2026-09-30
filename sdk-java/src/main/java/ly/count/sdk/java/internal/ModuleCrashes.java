package ly.count.sdk.java.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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
    private final Object crashHandlerLock = new Object();
    //guarded by crashHandlerLock
    private Thread.UncaughtExceptionHandler previousHandler = null;
    //guarded by crashHandlerLock
    private Thread.UncaughtExceptionHandler installedHandler = null;
    //written under crashHandlerLock
    private volatile boolean stopped = false;
    protected CrashProcessor crashProcessor = null;
    protected List<String> logs = new ArrayList<>();

    Crashes crashInterface;

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
            restoreUncaughtExceptionHandler();
            if (clear) {
                config.sdk.sdkStorage.storablePurge(config, CrashImpl.getStoragePrefix());
            }
        } catch (Throwable t) {
            L.e("[ModuleCrash] Exception while stopping crash reporting" + t);
        }
        logs.clear();
        crashInterface = null;
    }

    @Override
    public void initFinished(final InternalConfig config) {
        installUncaughtExceptionHandlerIfEnabled("initFinished");
        started = System.nanoTime();
    }

    /**
     * Installs the uncaught exception handler when a server response enabled automatic crash
     * reporting and it is not installed yet.
     *
     * @param config configuration of the running SDK
     */
    @Override
    protected void onSdkConfigurationChanged(InternalConfig config) {
        installUncaughtExceptionHandlerIfEnabled("onSdkConfigurationChanged");
    }

    /**
     * Installs the uncaught exception handler once, while crash reporting has consent and the SDK
     * behavior settings enable both crash reporting and automatic crash reporting. An installed
     * handler stays until the module stops and checks both settings again for every crash.
     *
     * @param caller the calling function, for the log
     */
    private void installUncaughtExceptionHandlerIfEnabled(@Nonnull String caller) {
        if (stopped) {
            return;
        }

        if (!isAutomaticCrashReportingEnabled()) {
            L.d("[ModuleCrash] " + caller + ", automatic crash reporting is disabled, the uncaught exception handler is not installed");
            return;
        }

        if (!internalConfig.sdk.hasConsentForFeature(CoreFeature.CrashReporting)) {
            L.d("[ModuleCrash] " + caller + ", crash reporting has no consent, the uncaught exception handler is not installed");
            return;
        }

        boolean installed = false;
        synchronized (crashHandlerLock) {
            if (installedHandler == null && !stopped) {
                previousHandler = Thread.getDefaultUncaughtExceptionHandler();
                installedHandler = createUncaughtExceptionHandler(previousHandler);
                Thread.setDefaultUncaughtExceptionHandler(installedHandler);
                installed = true;
            }
        }

        if (installed) {
            L.d("[ModuleCrash] " + caller + ", installed the uncaught exception handler");
        }
    }

    /**
     * Whether the SDK behavior settings enable both crash reporting and automatic crash reporting.
     *
     * @return {@code true} when unhandled crashes may be recorded
     */
    private boolean isAutomaticCrashReportingEnabled() {
        ConfigurationProvider configProvider = internalConfig.getConfigurationProvider();
        return configProvider.getCrashReportingEnabled() && configProvider.getAutomaticCrashReportingEnabled();
    }

    /**
     * Builds the handler that records an unhandled crash, while the module runs and the SDK behavior
     * settings enable automatic crash reporting, and then always hands the crash to the handler it replaced.
     *
     * @param previous the handler to hand every crash to, {@code null} for none
     * @return the handler
     */
    @Nonnull
    private Thread.UncaughtExceptionHandler createUncaughtExceptionHandler(@Nullable final Thread.UncaughtExceptionHandler previous) {
        return (thread, throwable) -> {
            // needed since following UncaughtExceptionHandler can keep reference to this one
            crashed = true;

            try {
                if (isActive() && !stopped && isAutomaticCrashReportingEnabled()) {
                    recordExceptionInternal(throwable, false, null, null);
                }
            } catch (Throwable recordFailure) {
                L.e("[ModuleCrash] uncaughtException, failed to record the crash, [" + recordFailure + "]");
            } finally {
                if (previous != null) {
                    previous.uncaughtException(thread, throwable);
                }
            }
        };
    }

    /**
     * Stops the installed handler from recording and puts back the handler it replaced, if it is
     * still the default one. A handler installed over it keeps it in the chain, where it only hands
     * crashes on.
     */
    private void restoreUncaughtExceptionHandler() {
        String outcome = null;
        synchronized (crashHandlerLock) {
            stopped = true;
            if (installedHandler != null) {
                if (Thread.getDefaultUncaughtExceptionHandler() == installedHandler) {
                    Thread.setDefaultUncaughtExceptionHandler(previousHandler);
                    outcome = "restored the uncaught exception handler it replaced";
                } else {
                    outcome = "another uncaught exception handler was installed over this one, which stays in the chain and only hands crashes on";
                }
                installedHandler = null;
                previousHandler = null;
            }
        }

        if (outcome != null) {
            L.d("[ModuleCrash] stop, " + outcome);
        }
    }

    protected void recordExceptionInternal(Throwable t, boolean handled, Map<String, Object> segments, String legacyCrashName) {
        ConfigurationProvider configProvider = internalConfig.getConfigurationProvider();
        if (!configProvider.getTrackingEnabled() || !configProvider.getCrashReportingEnabled()) {
            L.d("[ModuleCrash] recordExceptionInternal, crash reporting disabled by SDK behavior settings; ignoring");
            return;
        }
        if (config.isBackendModeEnabled()) {
            L.w("[ModuleCrash] recordExceptionInternal, Skipping crash, backend mode is enabled!");
            return;
        }

        if (t == null) {
            L.e("[ModuleCrash] recordExceptionInternal, Throwable cannot be null");
            return;
        }

        CrashImpl crash = new CrashImpl(L).addThrowable(t).setFatal(!handled).addSegments(segments);

        // Guard was inverted: a real name was never applied and an empty one was sent as '_name:""'.
        if (!Utils.isEmptyOrNull(legacyCrashName)) {
            crash.setName(legacyCrashName);
        }

        onCrash(config, crash);
    }

    public CrashImpl onCrash(InternalConfig config, CrashImpl crash) {
        long running = started == 0 ? 0 : TimeUtils.nsToMs(System.nanoTime() - started);
        crash.putMetrics(running / TimeUtils.MS_IN_SECOND);

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

        //after the processor, so it matches against the whole crash and what it adds is limited too
        crash.applyInternalLimits(config.getConfigurationProvider(), "[ModuleCrash] onCrash");
        if (!Storage.push(config, crash)) {
            L.e("[ModuleCrash] Couldn't persist a crash, so dumping it here: " + crash.getJSON());
        } else {
            SDKCore.instance.onSignal(config, SDKCore.Signal.Crash.getIndex(), crash.storageId().toString());
        }
        return crash;
    }

    protected void addBreadcrumbInternal(String record) {
        if (Utils.isEmptyOrNull(record)) {
            L.e("[ModuleCrash] addBreadcrumbInternal, record cannot be null or empty");
            return;
        }

        ConfigurationProvider configProvider = internalConfig.getConfigurationProvider();
        String breadcrumb = UtilsInternalLimits.truncateValue(record, configProvider.getMaxValueSize(), L, "[ModuleCrash] addBreadcrumbInternal");

        //a loop, as the limit can shrink while breadcrumbs are kept
        int maxBreadcrumbCount = configProvider.getMaxBreadcrumbCount();
        while (!logs.isEmpty() && logs.size() >= maxBreadcrumbCount) {
            logs.remove(0);
        }

        logs.add(breadcrumb);
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
                recordExceptionInternal(exception, true, null, null);
            }
        }

        /**
         * Log unhandled exception to report it to server as fatal crash
         *
         * @param exception Throwable to log
         */
        public void recordUnhandledException(Throwable exception) {
            synchronized (Countly.instance()) {
                recordExceptionInternal(exception, false, null, null);
            }
        }

        /**
         * Log handled exception to report it to server as non-fatal crash
         *
         * @param exception Throwable to log
         */
        public void recordHandledException(final Throwable exception, final Map<String, Object> customSegmentation) {
            synchronized (Countly.instance()) {
                recordExceptionInternal(exception, true, customSegmentation, null);
            }
        }

        /**
         * Log unhandled exception to report it to server as fatal crash
         *
         * @param exception Throwable to log
         */
        public void recordUnhandledException(final Throwable exception, final Map<String, Object> customSegmentation) {
            synchronized (Countly.instance()) {
                recordExceptionInternal(exception, false, customSegmentation, null);
            }
        }
    }
}
