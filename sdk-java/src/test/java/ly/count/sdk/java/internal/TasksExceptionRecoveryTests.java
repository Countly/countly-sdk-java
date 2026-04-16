package ly.count.sdk.java.internal;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests that the Tasks executor properly recovers from exceptions thrown by tasks.
 * These tests verify the fix for issue #264 where an uncaught exception in a task
 * would leave the "running" state permanently set, causing a networking deadlock.
 */
@RunWith(JUnit4.class)
public class TasksExceptionRecoveryTests {
    private Tasks tasks;

    @Before
    public void setUp() {
        tasks = new Tasks("test-recovery", null);
    }

    @After
    public void tearDown() {
        tasks.shutdown();
    }

    /**
     * When a task throws a RuntimeException, the executor should recover
     * and isRunning() should return false after the task completes.
     * This is the core scenario from issue #264.
     */
    @Test
    public void taskRuntimeException_executorRecovers() throws Exception {
        tasks.run(new Tasks.Task<Boolean>(0L) {
            @Override
            public Boolean call() {
                throw new RuntimeException("Simulated JSONException from non-JSON response");
            }
        });

        Thread.sleep(200);
        Assert.assertFalse("Executor should not be stuck in running state after RuntimeException", tasks.isRunning());
    }

    /**
     * After a task throws an exception, the executor should be able to
     * successfully run subsequent tasks.
     */
    @Test
    public void taskException_subsequentTaskSucceeds() throws Exception {
        // First task: throws exception
        tasks.run(new Tasks.Task<Boolean>(0L) {
            @Override
            public Boolean call() {
                throw new RuntimeException("Simulated failure");
            }
        });

        Thread.sleep(200);

        // Second task: should succeed
        final boolean[] secondTaskRan = { false };
        tasks.run(new Tasks.Task<Boolean>(0L) {
            @Override
            public Boolean call() {
                secondTaskRan[0] = true;
                return true;
            }
        });

        Thread.sleep(200);
        Assert.assertTrue("Subsequent task should have executed after previous task threw exception", secondTaskRan[0]);
        Assert.assertFalse("Executor should not be running after second task completes", tasks.isRunning());
    }

    /**
     * When a task throws a NullPointerException (e.g., from SDKCore.instance being null),
     * the executor should recover.
     */
    @Test
    public void taskNullPointerException_executorRecovers() throws Exception {
        tasks.run(new Tasks.Task<Boolean>(0L) {
            @Override
            public Boolean call() {
                String nullStr = null;
                nullStr.length(); // throws NPE
                return true;
            }
        });

        Thread.sleep(200);
        Assert.assertFalse("Executor should recover from NullPointerException", tasks.isRunning());
    }

    /**
     * When a task with a non-zero ID throws an exception, it should be
     * removed from the pending map so new tasks with the same ID can be submitted.
     */
    @Test
    public void taskWithId_exceptionClearsPending() throws Exception {
        Long taskId = 42L;

        // First task with ID: throws exception
        tasks.run(new Tasks.Task<Boolean>(taskId) {
            @Override
            public Boolean call() {
                throw new RuntimeException("Simulated failure");
            }
        });

        Thread.sleep(200);

        // Second task with same ID: should be accepted and run (not deduplicated against the failed one)
        final boolean[] secondTaskRan = { false };
        Future<Boolean> future = tasks.run(new Tasks.Task<Boolean>(taskId) {
            @Override
            public Boolean call() {
                secondTaskRan[0] = true;
                return true;
            }
        });

        Thread.sleep(200);
        Assert.assertTrue("Task with same ID should run after previous one failed", secondTaskRan[0]);
        Assert.assertFalse("Executor should not be running", tasks.isRunning());
    }

    /**
     * When a callback throws an exception, the executor should still recover
     * and not deadlock. The callback runs inside the try block, so its exception
     * is caught by the finally block.
     */
    @Test
    public void callbackException_executorRecovers() throws Exception {
        tasks.run(new Tasks.Task<Boolean>(0L) {
            @Override
            public Boolean call() {
                return true;
            }
        }, result -> {
            throw new RuntimeException("Simulated callback failure");
        });

        Thread.sleep(200);
        Assert.assertFalse("Executor should recover from callback exception", tasks.isRunning());
    }

    /**
     * After a callback throws an exception, subsequent tasks should still execute.
     */
    @Test
    public void callbackException_subsequentTaskSucceeds() throws Exception {
        // First task: succeeds but callback throws
        tasks.run(new Tasks.Task<Boolean>(0L) {
            @Override
            public Boolean call() {
                return true;
            }
        }, result -> {
            throw new RuntimeException("Callback failure");
        });

        Thread.sleep(200);

        // Second task: should succeed
        final boolean[] secondTaskRan = { false };
        tasks.run(new Tasks.Task<Boolean>(0L) {
            @Override
            public Boolean call() {
                secondTaskRan[0] = true;
                return true;
            }
        });

        Thread.sleep(200);
        Assert.assertTrue("Task should run after previous callback threw exception", secondTaskRan[0]);
    }

    /**
     * Multiple consecutive failing tasks should not accumulate stuck state.
     * The executor should recover after each one.
     */
    @Test
    public void multipleConsecutiveFailures_executorRecovers() throws Exception {
        for (int i = 0; i < 5; i++) {
            tasks.run(new Tasks.Task<Boolean>(0L) {
                @Override
                public Boolean call() {
                    throw new RuntimeException("Failure #" + System.currentTimeMillis());
                }
            });
        }

        Thread.sleep(500);
        Assert.assertFalse("Executor should recover after multiple consecutive failures", tasks.isRunning());

        // Verify executor still works
        final boolean[] taskRan = { false };
        tasks.run(new Tasks.Task<Boolean>(0L) {
            @Override
            public Boolean call() {
                taskRan[0] = true;
                return true;
            }
        });

        Thread.sleep(200);
        Assert.assertTrue("Executor should still work after multiple failures", taskRan[0]);
    }

    /**
     * When a task throws a checked Exception (e.g. IOException), the executor
     * should recover. The original bug equally applied to checked exceptions since
     * the cleanup code was not in a finally block. The ExecutorService wraps
     * checked exceptions in ExecutionException, and running must still be reset.
     */
    @Test
    public void taskCheckedException_executorRecovers() throws Exception {
        tasks.run(new Tasks.Task<Boolean>(0L) {
            @Override
            public Boolean call() throws Exception {
                throw new IOException("Simulated I/O failure during request");
            }
        });

        Thread.sleep(200);
        Assert.assertFalse("Executor should recover from checked IOException", tasks.isRunning());

        // Verify executor still works after checked exception
        final boolean[] taskRan = { false };
        tasks.run(new Tasks.Task<Boolean>(0L) {
            @Override
            public Boolean call() {
                taskRan[0] = true;
                return true;
            }
        });

        Thread.sleep(200);
        Assert.assertTrue("Subsequent task should run after checked exception", taskRan[0]);
    }

    /**
     * Deterministic test for volatile correctness of the "running" field.
     * Uses a CountDownLatch instead of Thread.sleep to verify that the calling
     * thread sees running=null immediately after the task signals completion.
     * Without volatile, a stale cached value could cause isRunning() to return
     * true even though the executor thread already set running=null.
     */
    @Test
    public void volatileCorrectness_isRunningVisibleAcrossThreads() throws Exception {
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch taskCanFinish = new CountDownLatch(1);

        // Submit a task that signals when it starts, then waits for permission to finish
        tasks.run(new Tasks.Task<Boolean>(0L) {
            @Override
            public Boolean call() throws Exception {
                taskStarted.countDown();
                taskCanFinish.await(5, TimeUnit.SECONDS);
                return true;
            }
        });

        // Wait for the task to start executing on the executor thread
        Assert.assertTrue("Task should have started", taskStarted.await(2, TimeUnit.SECONDS));

        // While the task is running, isRunning() must be true
        Assert.assertTrue("isRunning() should be true while task is executing", tasks.isRunning());

        // Allow the task to finish
        taskCanFinish.countDown();

        // Submit a no-op task and wait for it — this guarantees the previous task
        // (including its finally block) has fully completed
        tasks.await();

        // Without volatile, this read from the test thread could see the stale value
        Assert.assertFalse(
            "isRunning() should be false immediately after task completes (volatile visibility)",
            tasks.isRunning()
        );
    }
}
