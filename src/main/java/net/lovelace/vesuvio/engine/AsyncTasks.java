package net.lovelace.vesuvio.engine;

import java.util.concurrent.*;
import java.util.function.Supplier;

/** Converts admission rejection into an exceptional future, never into a detection result. */
public final class AsyncTasks {
    private AsyncTasks() {}
    public static <T> CompletableFuture<T> supply(Supplier<T> task, Executor executor) {
        try { return CompletableFuture.supplyAsync(task, executor); }
        catch (RejectedExecutionException rejected) { return CompletableFuture.failedFuture(rejected); }
    }
    public static CompletableFuture<Void> run(Runnable task, Executor executor) {
        return supply(() -> { task.run(); return null; }, executor);
    }
}
