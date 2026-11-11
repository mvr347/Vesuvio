package net.lovelace.vesuvio.engine;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Periodic sweep that drops per-player state of everybody who is no longer online.
 *
 * <p>Quit handlers remove a player's state once, but a check task that was already queued on a virtual thread can reach
 * {@code LagCompensator#pingHistory.compute} or {@code UserDataManager#getOrCreate} after the quit and create the entry again,
 * and nothing would ever remove it. Every holder of per-player maps registers its {@code retainOnline} here and the task runs
 * on the main thread every 30 seconds, so such an entry lives for at most one sweep instead of until the next restart.
 */
public final class StateJanitor implements Runnable {

    private static final Logger LOGGER = Logger.getLogger("Vesuvio");

    private final Supplier<Set<UUID>> online;
    private final List<Consumer<Set<UUID>>> sinks;

    public StateJanitor(Supplier<Set<UUID>> online, List<Consumer<Set<UUID>>> sinks) {
        this.online = online;
        this.sinks = List.copyOf(sinks);
    }

    @Override
    public void run() {
        Set<UUID> snapshot = Set.copyOf(online.get());
        for (Consumer<Set<UUID>> sink : sinks) {
            try {
                sink.accept(snapshot);
            } catch (RuntimeException e) {
                // One broken holder must not stop the others from being swept.
                LOGGER.log(Level.WARNING, "[Vesuvio] Очистка состояния игроков не удалась для одного из хранилищ", e);
            }
        }
    }
}
