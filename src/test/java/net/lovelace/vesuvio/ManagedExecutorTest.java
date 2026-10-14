package net.lovelace.vesuvio;

import net.lovelace.vesuvio.engine.AsyncTasks;
import net.lovelace.vesuvio.engine.ManagedExecutor;
import net.lovelace.vesuvio.engine.OrderedTaskQueue;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.ArrayList;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ManagedExecutorTest {
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void respectsThreadModeAndPluginLoader(boolean virtual) throws Exception {
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {};
        try (var executor = new ManagedExecutor("test-worker-", virtual, 8, 2, loader)) {
            executor.submit(() -> {
                assertEquals(virtual, Thread.currentThread().isVirtual());
                assertSame(loader, Thread.currentThread().getContextClassLoader());
            }).get(5, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void rejectsOverloadWithoutExecutingOnCallerAndRejectsAfterShutdown(boolean virtual) throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = new ManagedExecutor("test-cap-", virtual, 1, 1, getClass().getClassLoader());
        try {
            var task = executor.submit(() -> {
                started.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            var rejected = AsyncTasks.supply(() -> { fail("Rejected task ran"); return 1; }, executor);
            assertInstanceOf(RejectedExecutionException.class, assertThrows(CompletionException.class, rejected::join).getCause());
            release.countDown();
            task.get(5, TimeUnit.SECONDS);
            // A future can complete a moment before the wrapper returns its admission permit.
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> fail("Ran after shutdown")));
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void orderedPlayerQueueStaysFifo(boolean virtual) throws Exception {
        try (var executor = new ManagedExecutor("test-order-", virtual, 8, 2, getClass().getClassLoader())) {
            var queue = new OrderedTaskQueue();
            var done = new CountDownLatch(100);
            var received = new ArrayList<Integer>();
            for (int i = 0; i < 100; i++) {
                final int number = i;
                queue.submit(executor, () -> { received.add(number); done.countDown(); });
            }
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(java.util.stream.IntStream.range(0, 100).boxed().toList(), received);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void shutdownInterruptsOwnedWorker(boolean virtual) throws Exception {
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var executor = new ManagedExecutor("test-stop-", virtual, 2, 1, getClass().getClassLoader());
        executor.execute(() -> {
            started.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException e) { interrupted.countDown(); Thread.currentThread().interrupt(); }
        });
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            executor.shutdownNow();
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }
}
