package net.lovelace.vesuvio.engine;

import net.lovelace.vesuvio.config.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * False Positive Shield & Network Lag Compensator.
 * Monitors server TPS and player network latency to prevent false flags
 * caused by TCP packet choke, Wi-Fi jitter, and tick drops.
 *
 * Author: Lovelace
 */
public final class LagCompensator {

    private final ConfigManager config;

    private final Map<UUID, PingRecord> pingHistory = new ConcurrentHashMap<>();

    public LagCompensator(ConfigManager config) {
        this.config = config;
    }

    /**
     * Checks if current server TPS is under the acceptable threshold.
     */
    public boolean isServerLagging() {
        try {
            double[] tps = Bukkit.getTPS();
            if (tps.length > 0 && tps[0] < config.getMinServerTps()) {
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * Evaluates whether the player recently experienced a sharp network latency spike.
     */
    public boolean isNetworkSpike(Player player) {
        int currentPing = player.getPing();
        PingRecord last = pingHistory.put(player.getUniqueId(), new PingRecord(currentPing, System.currentTimeMillis()));

        if (last != null) {
            int deltaPing = Math.abs(currentPing - last.ping());
            // High ping (> 350ms) or rapid spike (> 120ms within 3 seconds)
            if (currentPing > 350 || deltaPing > config.getMaxPingSpikeMs()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Dynamic multiplier that relaxes detection sensitivity during lag.
     * Normal = 1.0, Mild lag = 1.35, Severe lag = 2.0.
     */
    public double getLagToleranceMultiplier(Player player) {
        if (!config.isLagCompensationEnabled()) return 1.0;

        double tolerance = 1.0;

        if (isServerLagging()) {
            tolerance *= 1.45;
        }

        if (isNetworkSpike(player)) {
            tolerance *= 1.50;
        }

        return Math.min(2.5, tolerance);
    }

    public void remove(UUID uuid) {
        pingHistory.remove(uuid);
    }
}
