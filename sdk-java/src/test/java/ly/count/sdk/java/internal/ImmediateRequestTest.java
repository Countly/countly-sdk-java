package ly.count.sdk.java.internal;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import ly.count.sdk.java.Countly;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.mockito.Mockito.mock;

@RunWith(JUnit4.class)
public class ImmediateRequestTest {
    Log L = mock(Log.class);

    @After
    public void stop() {
        Countly.stop(true);
    }

    /**
     * Immediate request maker "doWork" function
     * Immediate Request Generator is default and endpoint and data are not valid, and app key, server url is default
     * should return null because response is not okay
     *
     * @throws InterruptedException if thread is interrupted
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
    }

    /**
     * Immediate request maker "doWork" function
     * Immediate Request Generator is override and returns desired value
     * should return desired value
     *
     * @throws InterruptedException if thread is interrupted
     */
    @Test
    public void doWork() throws InterruptedException {
        Countly.instance().init(TestUtils.getBaseConfig());

        JSONObject requestResult = new JSONObject();
        requestResult.put("result", "Success");
        requestResult.put("count", 6);

        ImmediateRequestI requestMaker = (requestData, customEndpoint, cp, requestShouldBeDelayed, networkingIsEnabled, callback, log) -> {
            Assert.assertEquals("test_event", requestData);
            Assert.assertEquals("/o?", customEndpoint);
            Assert.assertTrue(networkingIsEnabled);
            Assert.assertFalse(requestShouldBeDelayed);
            callback.callback(requestResult);
        };

        SDKCore.instance.config.immediateRequestGenerator = () -> requestMaker;
        ImmediateRequestI requestMakerGenerated = SDKCore.instance.config.immediateRequestGenerator.createImmediateRequestMaker();

        AtomicInteger reqValidator = new AtomicInteger(0);
        requestMakerGenerated.doWork("test_event", "/o?", SDKCore.instance.networking.getTransport(), false, true,
            (result) -> {
                Assert.assertEquals(requestResult, result);
                reqValidator.set(requestResult.getInt("count"));
            }, L);

        Thread.sleep(2000); // wait for background thread to finish
        Assert.assertEquals(6, reqValidator.get()); // check if callback was called and response is null
    }
}
