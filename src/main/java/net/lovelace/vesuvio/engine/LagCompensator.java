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
     *
     * Compares against an exponential moving average rather than the single immediately-previous
     * sample. A raw previous-sample comparison meant a connection with rhythmic jitter (or a
     * deliberately ping-toggling client) could trigger "spike" on nearly every packet, handing
     * out the loosened detection thresholds below almost permanently. The EMA settles within a
     * few samples of sustained oscillation, so only a genuine, fresh latency jump still counts.
     */
    public boolean isNetworkSpike(Player player) {
        int currentPing = player.getPing();
        java.util.concurrent.atomic.AtomicBoolean spike = new java.util.concurrent.atomic.AtomicBoolean(false);

        // Single atomic read-modify-write per player key, so concurrent calls (click/attack/reach
        // checks can all run this on different virtual threads for the same player) can't race
        // on the EMA baseline.
        pingHistory.compute(player.getUniqueId(), (uuid, last) -> {
            double ema = currentPing;
            if (last != null) {
                double deviation = Math.abs(currentPing - last.emaPing());
                if (currentPing > 350 || deviation > config.getMaxPingSpikeMs()) {
                    spike.set(true);
                }
                ema = last.emaPing() + 0.25 * (currentPing - last.emaPing());
            }
            return new PingRecord(currentPing, System.currentTimeMillis(), ema);
        });

        return spike.get();
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
