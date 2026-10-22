package ly.count.sdk.java.internal;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import ly.count.sdk.java.Countly;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.mockito.Mockito.mock;

/**
 * The checks {@link DefaultNetworking} keeps while it drains the request queue.
 */
@RunWith(JUnit4.class)
public class DefaultNetworkingRecheckTests {

    @Before
    public void beforeTest() {
        TestUtils.createCleanTestState();
    }

    @After
    public void afterTest() {
        Countly.instance().halt();
    }

    /**
     * A drain that fails still runs the check asked for while it ran, so a request queued meanwhile
     * does not wait for the next check.
     */
    @Test
    public void aFailingDrain_stillRunsTheCheckAskedForMeanwhile() throws Exception {
        Countly.instance().init(TestUtils.getBaseConfig());
        InternalConfig config = SDKCore.instance.config;
        CountDownLatch firstDrainRuns = new CountDownLatch(1);
        CountDownLatch checkAskedFor = new CountDownLatch(1);
        CountDownLatch nextDrainRan = new CountDownLatch(1);
        AtomicInteger drains = new AtomicInteger();
        DefaultNetworking networking = new DefaultNetworking() {
            /**
             * Fails the first drain once a check was asked for while it ran, and records the next.
             */
            @Override
            protected Tasks.Task<Boolean> submit(InternalConfig config, boolean followsOwnTransportWork) {
                return new Tasks.Task<Boolean>(Tasks.ID_STRICT) {
                    /**
                     * Fails the first time, after the check was asked for, and records the next.
                     */
                    @Override
                    public Boolean call() throws Exception {
                        if (drains.incrementAndGet() == 1) {
                            firstDrainRuns.countDown();
                            Assert.assertTrue(checkAskedFor.await(10, TimeUnit.SECONDS));
                            throw new IllegalStateException("the first drain fails");
                        }
                        nextDrainRan.countDown();
                        return false;
                    }
                };
            }
        };
        networking.init(config, mock(IStorageForRequestQueue.class));
        try {
            networking.check(config);
            Assert.assertTrue(firstDrainRuns.await(10, TimeUnit.SECONDS));
            networking.check(config);
            checkAskedFor.countDown();

            Assert.assertTrue("the check asked for during the failing drain never ran", nextDrainRan.await(10, TimeUnit.SECONDS));
        } finally {
            networking.stop(config);
        }
    }
}
