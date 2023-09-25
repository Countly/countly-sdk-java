package ly.count.sdk.java.internal;

import java.util.concurrent.atomic.AtomicReference;
import ly.count.sdk.java.Countly;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.mockito.Mockito.mock;

@RunWith(JUnit4.class)
public class ImmediateRequestTest {
    Log L = mock(Log.class);

    /**
     * Immediate request maker "doWork" function
     * Immediate Request Generator is default and endpoint and data are not valid, and app key, server url is default
     * should return null because response is not okay
     *
     * @throws InterruptedException
     */
    @Test
    public void doWork_null() throws InterruptedException {
        Countly.instance().init(TestUtils.getBaseConfig());
        AtomicReference<Boolean> callbackResult = new AtomicReference<>(true);

        ImmediateRequestMaker immediateRequestMaker = new ImmediateRequestMaker();
        immediateRequestMaker.doWork("test_event", "/o?", SDKCore.instance.networking.getTransport(), false, true,
            (result) -> {
                if (result == null) {
                    callbackResult.set(false);
                }
            }, L);

        Thread.sleep(2000); // wait for background thread to finish
        Assert.assertFalse(callbackResult.get()); // check if callback was called and response is null

        Countly.stop(true);
    }
}
