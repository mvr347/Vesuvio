package net.lovelace.vesuvio.engine;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owned executor with bounded admission. Never runs rejected work on the submitting thread. */
public final class ManagedExecutor extends AbstractExecutorService {
    private final ExecutorService delegate;
    private final Semaphore admission;

    public ManagedExecutor(String name, boolean virtual, int capacity, int parallelism, ClassLoader loader) {
        if (capacity < 1 || parallelism < 1) throw new IllegalArgumentException("Недопустимый лимит задач");
        admission = new Semaphore(capacity);
        ThreadFactory base = virtual ? Thread.ofVirtual().name(name, 0).factory()
                : Thread.ofPlatform().daemon(true).name(name, 0).factory();
        ThreadFactory factory = task -> {
            Thread thread = base.newThread(task);
            thread.setContextClassLoader(loader);
            return thread;
        };
        delegate = virtual ? Executors.newThreadPerTaskExecutor(factory)
                : new ThreadPoolExecutor(parallelism, parallelism, 0, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(capacity), factory, new ThreadPoolExecutor.AbortPolicy());
    }

    @Override public void execute(Runnable task) {
        java.util.Objects.requireNonNull(task);
        if (!admission.tryAcquire()) throw new RejectedExecutionException("Лимит фоновых задач исчерпан");
        Admitted wrapped = new Admitted(task);
        try { delegate.execute(wrapped); }
        catch (RuntimeException | Error failure) { wrapped.release(); throw failure; }
    }

    private final class Admitted implements Runnable {
        private final Runnable task;
        private final AtomicBoolean released = new AtomicBoolean();
        private Admitted(Runnable task) { this.task = task; }
        private void release() { if (released.compareAndSet(false, true)) admission.release(); }
        @Override public void run() { try { task.run(); } finally { release(); } }
    }

    @Override public void shutdown() { delegate.shutdown(); }
    @Override public List<Runnable> shutdownNow() {
        List<Runnable> pending = delegate.shutdownNow();
        return pending.stream().map(task -> {
            if (task instanceof ManagedExecutor.Admitted admitted) {
                admitted.release();
                if (admitted.task instanceof Future<?> future) future.cancel(false);
                return admitted.task;
            }
            return task;
        }).toList();
    }
    @Override public boolean isShutdown() { return delegate.isShutdown(); }
    @Override public boolean isTerminated() { return delegate.isTerminated(); }
    @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }

    /** Stop accepting tasks and wait before the resources used by workers are closed. */
    public static void stop(ExecutorService executor) {
        if (executor == null) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    java.util.logging.Logger.getLogger("Vesuvio").warning("Фоновые задачи не завершились за отведённое время");
                }
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
