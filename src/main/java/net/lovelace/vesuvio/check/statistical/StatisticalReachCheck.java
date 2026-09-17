package net.lovelace.vesuvio.check.statistical;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.engine.HitboxHistoryTracker;
import net.lovelace.vesuvio.engine.LagCompensator;
import net.lovelace.vesuvio.engine.TransactionManager;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Latency-Compensated Raycast & Historical Hitbox Reach Analyzer (inspired by GrimAC).
 * Calculates exact distance from attacker's eye position to the target's rewound
 * bounding box snapshot at the attacker's latency offset.
 *
 * Prevents false positives caused by ping disparity, movement interpolation, and tick drops.
 *
 * <h2>Why the rewind/allowance latency has to come from TransactionManager</h2>
 * {@code Player#getPing()} is the client-reported keepalive latency and is directly spoofable -
 * a client can report whatever it wants there with no protocol-level consequence. Both numbers
 * this check derives from "ping" work in the cheater's favour if inflated: a larger value rewinds
 * the target's hitbox further into the past (more slack against a moving target) and adds a
 * generous reach buffer once past a threshold. A spoofed high ping therefore does not just fail to
 * help detection here, it actively loosens the check. TransactionManager's RTT comes from the
 * client's own Ping/Pong and Window Confirmation answers on an ordered connection and cannot be
 * misreported the same way - see CLAUDE.md. It is used whenever a sample exists; getPing() is only
 * the fallback for the handful of ticks right after join, before the first transaction round-trip
 * completes.
 *
 * Author: Lovelace
 */
public final class StatisticalReachCheck {

    private final double maxBaseReach;
    private final TransactionManager transactionManager;

    public StatisticalReachCheck(double maxBaseReach, TransactionManager transactionManager) {
        this.maxBaseReach = maxBaseReach > 0 ? maxBaseReach : 3.05;
        this.transactionManager = transactionManager;
    }

    /** Legacy constructor without transaction RTT, kept for existing tests/call sites. */
    public StatisticalReachCheck(double maxBaseReach) {
        this(maxBaseReach, null);
    }

    public StatisticalReachCheck() {
        this(3.05, null);
    }

    /**
     * Picks the latency this check judges the attacker against. Extracted as pure static math so
     * the spoof-resistance property is directly testable without a live {@link Player}: a client
     * can report any {@code reportedPing} it likes, but a real transaction sample - when one
     * exists - always wins over it.
     *
     * @param transactionRttMs {@link TransactionManager#getTransactionPing}'s result, or a
     *                         negative value when no sample exists yet (just joined)
     * @param reportedPing     {@code Player#getPing()} - client-reported, spoofable, used only as
     *                         the fallback for the handful of ticks before the first transaction
     *                         round-trip completes
     */
    public static int resolveEffectivePing(double transactionRttMs, int reportedPing) {
        return transactionRttMs >= 0 ? (int) Math.round(transactionRttMs) : Math.max(0, reportedPing);
    }

    public CheckResult check(Player attacker,
                             org.bukkit.entity.Entity target,
                             UserData attackerData,
                             HitboxHistoryTracker hitboxTracker,
                             LagCompensator lagCompensator) {
        if (attacker == null || target == null) {
            return CheckResult.pass("Reach");
        }

        // Bypass for creative / spectator mode
        if (attacker.getGameMode() == GameMode.CREATIVE || attacker.getGameMode() == GameMode.SPECTATOR) {
            return CheckResult.pass("Reach");
        }

        double transactionRtt = transactionManager != null
                ? transactionManager.getTransactionPing(attacker.getUniqueId()) : -1;
        int ping = resolveEffectivePing(transactionRtt, attacker.getPing());

        // Rewind target hitbox if player, or use bounding box for mobs/entities
        HitboxHistoryTracker.BoxSnapshot targetBox;
        if (target instanceof Player targetPlayer && hitboxTracker != null) {
            targetBox = hitboxTracker.getRewoundBox(targetPlayer, ping);
        } else {
            var bb = target.getBoundingBox();
            targetBox = new HitboxHistoryTracker.BoxSnapshot(
                    bb.getMinX(), bb.getMinY(), bb.getMinZ(),
                    bb.getMaxX(), bb.getMaxY(), bb.getMaxZ(),
                    System.nanoTime()
            );
        }

        // Attacker eye vector
        Location eyeLoc = attacker.getEyeLocation();
        double distance = targetBox.distanceTo(eyeLoc.getX(), eyeLoc.getY(), eyeLoc.getZ());

        // Lag and network spike compensation
        double lagTolerance = (lagCompensator != null) ? lagCompensator.getLagToleranceMultiplier(attacker) : 1.0;
        double pingBuffer = (ping > 150) ? 0.10 : 0.0;
        double allowedReach = (maxBaseReach + pingBuffer) * lagTolerance;

        // Dynamic sensitivity scaling based on player Trust and Risk
        double sensitivity = (attackerData != null) ? attackerData.getSensitivityMultiplier() : 1.0;
        if (sensitivity > 1.2) {
            allowedReach -= 0.05; // Slightly stricter for high-risk / low-trust players
        }

        if (distance > allowedReach) {
            double excess = distance - allowedReach;
            double confidence = Math.min(0.99, 0.75 + (excess * 0.65));
            double vl = Math.max(1.0, (distance - maxBaseReach) * 6.0);

            Map<String, Object> details = new HashMap<>();
            details.put("distance", distance);
            details.put("maxAllowed", allowedReach);
            details.put("excess", excess);
            details.put("ping", ping);
            details.put("pingSource", transactionRtt >= 0 ? "transaction" : "reported");
            details.put("target", target.getName());
            details.put("lagMultiplier", lagTolerance);

            String explanation = String.format(Locale.US,
                    "Reach distance exceeded (Distance: %.2fm, Max: %.2fm, Ping: %dms, Excess: +%.2fm)",
                    distance, allowedReach, ping, excess);

            return CheckResult.flag("Reach", confidence, vl, explanation, details);
        }

        return CheckResult.pass("Reach");
    }
}
