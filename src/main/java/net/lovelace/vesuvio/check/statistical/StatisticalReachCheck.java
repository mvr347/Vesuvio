package net.lovelace.vesuvio.check.statistical;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.engine.HitboxHistoryTracker;
import net.lovelace.vesuvio.engine.LagCompensator;
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
 * Author: Lovelace
 */
public final class StatisticalReachCheck {

    private final double maxBaseReach;

    public StatisticalReachCheck(double maxBaseReach) {
        this.maxBaseReach = maxBaseReach > 0 ? maxBaseReach : 3.05;
    }

    public StatisticalReachCheck() {
        this(3.05);
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

        int ping = Math.max(0, attacker.getPing());

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
