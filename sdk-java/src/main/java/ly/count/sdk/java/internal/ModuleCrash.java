package ly.count.sdk.java.internal;

import java.util.Map;

import ly.count.sdk.java.Crash;
import ly.count.sdk.java.CrashProcessor;

/**
 * Crash reporting functionality
 */

public class ModuleCrash extends ModuleBase {
    

    protected long started = 0;
    private boolean limited = false;
    private boolean crashed = false;

    protected InternalConfig config;
    private Thread.UncaughtExceptionHandler previousHandler = null;
    protected CrashProcessor crashProcessor = null;

    @Override
    public void init(InternalConfig config, Log logger) {
        super.init(config, logger);
        this.config = config;
        limited = config.isLimited();
        if (config.getCrashProcessorClass() != null) {
            try {
                Class cls = Class.forName(config.getCrashProcessorClass());
                crashProcessor = (CrashProcessor) cls.getConstructors()[0].newInstance();
            } catch (Throwable t) {
                L.e("[ModuleCrash] Cannot instantiate CrashProcessor" + t);
            }
        }
    }

    @Override
    public void stop(CtxCore ctx, boolean clear) {
        try {
            if (previousHandler != null) {
                Thread.setDefaultUncaughtExceptionHandler(previousHandler);
            }
            if (clear) {
                ctx.getSDK().storablePurge(ctx, CrashImplCore.getStoragePrefix());
            }
        } catch (Throwable t) {
            L.e("[ModuleCrash] Exception while stopping crash reporting" + t);
        }
    }

    @Override
    public void onContextAcquired(final CtxCore ctx) {
        if (!limited) {
            previousHandler = Thread.getDefaultUncaughtExceptionHandler();
            final Thread.UncaughtExceptionHandler handler = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread thread, Throwable throwable) {
                    // needed since following UncaughtExceptionHandler can keep reference to this one
                    crashed = true;

                    if (isActive()) {
                        onCrash(ctx, throwable, true, null, null);
                    }

                    if (handler != null) {
                        handler.uncaughtException(thread, throwable);
                    }
                }
            });
            started = System.nanoTime();
        }
    }

    @Override
    public Integer getFeature() {
        return CoreFeature.CrashReporting.getIndex();
    }

    public CrashImplCore onCrash(CtxCore ctx, Throwable t, boolean fatal, String name, Map<String, String> segments, String... logs) {

        if(ctx.getConfig().isBackendModeEnabled()) {
            L.w("[ModuleCrash] onCrash: Skipping crash, backend mode is enabled!");
            return null;
        }

        if (t == null) {
            L.e("[ModuleCrash] Throwable cannot be null");
            return  null;
        }
        return onCrash(ctx, new CrashImplCore(L).addThrowable(t).setFatal(fatal).setName(name).setSegments(segments).setLogs(logs));
    }

    public CrashImplCore onCrash(CtxCore ctx, CrashImplCore crash) {
        long running = started == 0 ? 0 : DeviceCore.dev.nsToMs(System.nanoTime() - started);
        crash.putMetricsCore(ctx, running);

        if (!crash.getData().has("_os")){
            L.w("[ModuleCrash] onCrash, While recording an exception 'OS name' was either null or empty");
        }

        if (!crash.getData().has("_app_version")){
            L.w("[ModuleCrash] onCrash, While recording an exception 'App version' was either null or empty");
        }


        L.i("[ModuleCrash] onCrash: " + crash.getJSON());

        if (crashProcessor != null) {
            try {
                Crash result = crashProcessor.process(crash);

                if (result == null) {
                    L.i("[ModuleCrash] Crash is set to be ignored by CrashProcessor#process(Crash) " + crashProcessor);
                    Storage.remove(ctx, crash);
                    return null;
                }

            } catch (Throwable t) {
                L.e("[ModuleCrash] Error when calling CrashProcessor#process(Crash)" + t);
            }
        }
        if (!Storage.push(ctx, crash)) {
            L.e("[ModuleCrash] Couldn't persist a crash, so dumping it here: " + crash.getJSON());
        } else {
            SDKCore.instance.onSignal(ctx, SDKCore.Signal.Crash.getIndex(), crash.storageId().toString());
        }
        return crash;
    }

    public static void putCrashIntoParams(CrashImplCore crash, Params params) {
        params.add("crash", crash.getJSON());
    }

    public enum CrashType {
        STACK_OVERFLOW, DIVISION_BY_ZERO, OOM, RUNTIME_EXCEPTION, NULLPOINTER_EXCEPTION, ANR
    }

}
