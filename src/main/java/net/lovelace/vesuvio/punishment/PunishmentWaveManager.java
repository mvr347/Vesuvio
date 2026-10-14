package net.lovelace.vesuvio.punishment;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.lovelace.vesuvio.config.ConfigManager;
import net.lovelace.vesuvio.storage.DatabaseManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Advanced Punishment Wave Manager («Lava Wave»).
 * Queues ban punishments instead of banning instantly, preventing cheat developers
 * from iteratively testing bypass configurations against the server.
 *
 * Author: Lovelace
 */
public final class PunishmentWaveManager {

    private static final Logger LOGGER = Logger.getLogger("Vesuvio-Wave");

    public record QueuedPunishment(
            UUID uuid,
            String username,
            String command,
            String reason,
            long timestamp
    ) {}

    private final Plugin plugin;
    private final ConfigManager config;
    private final DatabaseManager databaseManager;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private net.lovelace.vesuvio.integration.hunt.LoveHuntHook loveHuntHook;

    private final Map<UUID, QueuedPunishment> queue = new ConcurrentHashMap<>();

    public PunishmentWaveManager(Plugin plugin, ConfigManager config, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.config = config;
        this.databaseManager = databaseManager;
    }

    public void setLoveHuntHook(net.lovelace.vesuvio.integration.hunt.LoveHuntHook loveHuntHook) {
        this.loveHuntHook = loveHuntHook;
    }

    public void queuePunishment(UUID uuid, String username, String command, String reason) {
        if (!config.mayPunish()) return;
        queue.put(uuid, new QueuedPunishment(uuid, username, command, reason, System.currentTimeMillis()));
        LOGGER.info(String.format("[Vesuvio] Игрок %s добавлен в волну наказаний. Игроков в очереди: %d", username, queue.size()));
        if (loveHuntHook != null) {
            loveHuntHook.createLavaBounty(uuid, username, reason);
        }
    }

    public boolean isQueued(UUID uuid) {
        return queue.containsKey(uuid);
    }

    public Collection<QueuedPunishment> getQueued() {
        return Collections.unmodifiableCollection(queue.values());
    }

    public int getQueueSize() {
        return queue.size();
    }

    public void clearQueue() {
        queue.clear();
    }

    /**
     * Executes the Lava Wave, banning all queued cheaters simultaneously.
     */
    public int executeWave() {
        if (!config.mayPunish() || queue.isEmpty()) return 0;
        long actionEpoch = config.runtime().actionEpoch();

        List<QueuedPunishment> toExecute = new ArrayList<>(queue.values());
        queue.clear();

        // Must run command execution on Bukkit main thread
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!config.runtime().acceptsAction(actionEpoch)) return;
            int executed = 0;
            for (QueuedPunishment p : toExecute) {
                if (!config.runtime().acceptsAction(actionEpoch)) break;
                if (p.command() != null && !p.command().isBlank()) {
                    String cmd = p.command();
                    if (cmd.contains("<") && cmd.contains(">")) {
                        try {
                            cmd = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(mm.deserialize(cmd));
                        } catch (Throwable ignored) {}
                    }
                    if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)) continue;
                    executed++;
                } else continue;

                databaseManager.logPunishmentAsync(new net.lovelace.vesuvio.storage.PunishmentRecord(
                        p.uuid(),
                        p.username(),
                        "WAVE_BAN",
                        p.reason(),
                        System.currentTimeMillis()
                ));
            }

            if (config.isWaveBroadcastEnabled() && executed > 0) {
                String announcement = String.format(Locale.US,
                        "<newline><gradient:#ff4500:#ff8c00><b>🌋 [Vesuvio] Волна банов Lava Wave изверглась!</b></gradient><newline>"
                        + "<gray>Массовая зачистка античита заблокировала <red><b>%d</b></red> нарушителей. Играйте честно!</gray><newline>",
                        executed);
                Bukkit.broadcast(mm.deserialize(announcement));
            }
        });

        return toExecute.size();
    }
}
