package ly.count.sdk.java.ui;

import ly.count.sdk.java.internal.Log;
import ly.count.sdk.java.internal.SDKCore;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * The module's logging shim, which is silent until the core SDK installs a logger and then simply
 * forwards to it. Driven against {@link SDKCore#logger()} directly, with no JavaFX toolkit needed.
 */
@RunWith(JUnit4.class)
public class UiLogTests {

    @After
    public void afterTest() {
        // SDKCore.instance is a process-wide static; never leak an installed logger into another
        // test class running later in the same JVM.
        TestSdkCoreAccessor.clear();
    }

    /**
     * Before the SDK is initialized every level is a silent no-op (nothing to log through and
     * nothing must throw); once a logger is installed, every level forwards its message to it
     * unchanged.
     */
    @Test
    public void logging_isSilentWithoutALoggerAndForwardsOnceOneIsInstalled() {
        // No SDKCore instance at all yet: SDKCore.logger() returns null, so every level below must
        // simply do nothing rather than throw.
        UiLog.v("verbose before init");
        UiLog.d("debug before init");
        UiLog.i("info before init");
        UiLog.w("warn before init");
        UiLog.e("error before init");

        Log logger = mock(Log.class);
        TestSdkCoreAccessor.install(logger);

        UiLog.v("verbose message");
        UiLog.d("debug message");
        UiLog.i("info message");
        UiLog.w("warn message");
        UiLog.e("error message");

        verify(logger).v("verbose message");
        verify(logger).d("debug message");
        verify(logger).i("info message");
        verify(logger).w("warn message");
        verify(logger).e("error message");
        verifyNoMoreInteractions(logger);
    }

    /**
     * Test-only accessor that installs (or clears) the SDK's static logger the same way a real
     * {@code Countly.init(...)} would, but without running any of the rest of SDK startup, so
     * {@link UiLog} can be exercised against both states.
     */
    private static final class TestSdkCoreAccessor extends SDKCore {

        static void install(Log logger) {
            // The SDKCore() constructor sets the protected static SDKCore.instance to this new
            // object; setting L on it is then exactly what SDKCore.logger() reads back.
            new TestSdkCoreAccessor().L = logger;
        }

        static void clear() {
            instance = null;
        }
    }
}
