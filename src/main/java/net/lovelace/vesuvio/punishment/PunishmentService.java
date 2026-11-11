package net.lovelace.vesuvio.punishment;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.lovelace.vesuvio.check.selflearning.SelfLearningManager;
import net.lovelace.vesuvio.config.ConfigManager;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.storage.DatabaseManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Locale;

/**
 * Turns a player's violation level into an action: walks the configured punishment rules (highest threshold first), formats
 * the command and executes it on the main thread, or queues a ban into the next wave. Extracted from CheckPipeline unchanged.
 *
 * Author: Lovelace
 */
public final class PunishmentService {

    private final Plugin plugin;
    private final ConfigManager config;
    private final DatabaseManager databaseManager;
    private final PunishmentWaveManager waveManager;
    private final SelfLearningManager selfLearning;
    private final net.lovelace.vesuvio.evasion.BanEvasionManager banEvasionManager;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public PunishmentService(Plugin plugin, ConfigManager config, DatabaseManager databaseManager,
                             PunishmentWaveManager waveManager, SelfLearningManager selfLearning,
                             net.lovelace.vesuvio.evasion.BanEvasionManager banEvasionManager) {
        this.plugin = plugin;
        this.config = config;
        this.databaseManager = databaseManager;
        this.waveManager = waveManager;
        this.selfLearning = selfLearning;
        this.banEvasionManager = banEvasionManager;
    }

    /** Schedules the evaluation on the main thread; a no-op when punishments are off. */
    public void evaluate(Player player, UserData data) {
        if (!config.mayPunish()) return;
        long epoch = config.runtime().actionEpoch();
        long reset = data.getMovementResetNanos();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!config.runtime().acceptsAction(epoch)
                    || data.getMovementResetNanos() != reset || data.hasRecentTeleport()) return;
            // A burst can flag several checks in one tick: execute at most one action per second.
            if (!data.tryPunishment(System.nanoTime())) return;
            double currentVl = data.getVl();
            for (ConfigManager.PunishmentRule rule : config.getPunishmentRules()) {
                if (currentVl < rule.vlThreshold() || rule.command() == null || rule.command().isBlank()) continue;
                String formatted = rule.command().replace("%player%", player.getName())
                        .replace("%vl%", String.format(Locale.US, "%.0f", currentVl))
                        .replace("%risk%", String.format(Locale.US, "%.0f", data.getRiskIndex()))
                        .replace("%ml%", String.format(Locale.US, "%.1f", data.getLastMLProbability() * 100));
                // Wave mode dedups itself via waveManager.isQueued; every other action fires once per session.
                boolean waveMode = "ban".equalsIgnoreCase(rule.action()) && config.isWavePunishmentEnabled() && waveManager != null;
                if (!waveMode && !data.tryLatchPunished()) return;
                if ("ban".equalsIgnoreCase(rule.action())) {
                    selfLearning.getAutoDatasetCollector().collectCheatSample(player, data);
                    if (banEvasionManager != null && config.isBanEvasionEnabled()) {
                        String ip = player.getAddress() != null && player.getAddress().getAddress() != null
                                ? player.getAddress().getAddress().getHostAddress() : null;
                        banEvasionManager.recordBanFingerprint(player.getUniqueId(), player.getName(), ip,
                                data.getClientBrand(), data.getClickBuffer().getSignature(), formatted);
                    }
                }
                if ("ban".equalsIgnoreCase(rule.action()) && config.isWavePunishmentEnabled() && waveManager != null) {
                    if (!waveManager.isQueued(player.getUniqueId())) {
                        waveManager.queuePunishment(player.getUniqueId(), player.getName(), formatted, "Волна наказаний: использование читов");
                    }
                    return;
                }
                boolean executed;
                if ("kick".equalsIgnoreCase(rule.action())) {
                    if (!player.isOnline()) { data.resetPunishedLatch(); return; }
                    String reason = formatted;
                    for (String prefix : new String[]{"kick " + player.getName() + " ", "minecraft:kick " + player.getName() + " "}) {
                        if (reason.regionMatches(true, 0, prefix, 0, prefix.length())) { reason = reason.substring(prefix.length()); break; }
                    }
                    player.kick(mm.deserialize(reason));
                    executed = true;
                } else {
                    executed = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), toLegacyCommand(formatted));
                }
                if (!executed) data.resetPunishedLatch();
                if (executed) databaseManager.logPunishmentAsync(new net.lovelace.vesuvio.storage.PunishmentRecord(
                        player.getUniqueId(), player.getName(), rule.action(), formatted, System.currentTimeMillis()));
                return;
            }
        });
    }

    private String toLegacyCommand(String input) {
        if (input == null || input.isBlank()) return "";
        try {
            if (input.contains("<") && input.contains(">")) {
                return LegacyComponentSerializer.legacySection().serialize(mm.deserialize(input));
            }
        } catch (Throwable ignored) {}
        return input;
    }
}
