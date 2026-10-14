package net.lovelace.vesuvio.engine;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** One worker per connection: packet checks must observe the order received on Netty. */
public final class OrderedTaskQueue {
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    private boolean running;

    public synchronized void submit(Executor executor, Runnable task) {
        if (tasks.size() >= 256) throw new RejectedExecutionException("Очередь проверок игрока заполнена");
        tasks.addLast(task);
        if (!running) {
            running = true;
            try {
                executor.execute(this::drain);
            } catch (RuntimeException ex) {
                running = false;
                tasks.clear();
                throw ex;
            }
        }
    }

    private void drain() {
        while (true) {
            Runnable task;
            synchronized (this) {
                task = tasks.pollFirst();
                if (task == null) { running = false; return; }
            }
            try {
                task.run();
            } catch (Throwable ex) {
                // A failed check must not leave this player's worker permanently marked running.
                java.util.logging.Logger.getLogger("Vesuvio").log(
                        java.util.logging.Level.WARNING, "Ошибка пакетной проверки", ex);
            }
        }
    }
}
