package net.lovelace.vesuvio.check.statistical;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.data.UserData;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Advanced Combat: Field-of-View (FOV), Line-of-Sight, and Crosshair Ray Analyzer.
 * Inspired by GrimAC, Reflex, and Vulcan.
 *
 * Catches:
 * 1. WallHit: Attacking entities through solid blocks/obstacles.
 * 2. SilentAim: Attack packet sent while player crosshair ray completely misses entity hitbox.
 * 3. KillauraAngle: Attacking entities outside human field of view (> 75°).
 *
 * Author: Lovelace
 */
public final class KillauraAngleCheck {

    public CheckResult check(Player attacker, Entity target, UserData data) {
        if (attacker == null || target == null) return CheckResult.pass("KillauraAngle");

        Location eyeLoc = attacker.getEyeLocation();
        Location targetCenter = target.getLocation().clone().add(0, target.getHeight() * 0.5, 0);

        Vector toTarget = targetCenter.toVector().subtract(eyeLoc.toVector());
        double distSq = toTarget.lengthSquared();
        if (distSq < 0.25) {
            // Right inside target body, angle is ambiguous
            return CheckResult.pass("KillauraAngle");
        }

        double distance = Math.sqrt(distSq);
        Vector eyeDir = eyeLoc.getDirection().normalize();
        Vector toTargetNorm = toTarget.clone().normalize();

        double dot = Math.max(-1.0, Math.min(1.0, eyeDir.dot(toTargetNorm)));
        double angleDegrees = Math.toDegrees(Math.acos(dot));

        // -------------------------------------------------------------
        // 1. Line-of-Sight / WallHit Check (GrimAC / Vulcan technique)
        // -------------------------------------------------------------
        if (distance > 0.8) {
            RayTraceResult blockHit = attacker.getWorld().rayTraceBlocks(
                    eyeLoc, toTargetNorm, distance, FluidCollisionMode.NEVER, true
            );
            if (blockHit != null && blockHit.getHitBlock() != null) {
                Block b = blockHit.getHitBlock();
                if (b.getType().isOccluding() && !b.isPassable()) {
                    double distToBlock = blockHit.getHitPosition().distance(eyeLoc.toVector());
                    if (distToBlock < distance - 0.35) {
                        Map<String, Object> details = new HashMap<>();
                        details.put("block", b.getType().name());
                        details.put("distToBlock", distToBlock);
                        details.put("distToTarget", distance);

                        String explanation = String.format(Locale.US,
                                "Attack through solid obstacle %s (Block: %.2fm, Target: %.2fm)",
                                b.getType().name(), distToBlock, distance);

                        return CheckResult.flag("WallHit", 0.98, 3.5, explanation, details);
                    }
                }
            }
        }

        // -------------------------------------------------------------
        // 2. Crosshair Ray AABB Intersection (Silent Aim detection)
        // -------------------------------------------------------------
        int ping = Math.max(0, attacker.getPing());
        double pingBuffer = (ping > 120) ? 0.20 : 0.08;
        BoundingBox targetBox = target.getBoundingBox().clone().expand(0.28 + pingBuffer);
        RayTraceResult crosshairHit = targetBox.rayTrace(eyeLoc.toVector(), eyeDir, 6.0);

        // If crosshair ray does not intersect expanded hitbox and angle is significantly off
        if (crosshairHit == null && angleDegrees > 45.0) {
            Map<String, Object> details = new HashMap<>();
            details.put("angle", angleDegrees);
            details.put("target", target.getName() != null ? target.getName() : target.getType().name());
            details.put("ping", ping);

            String explanation = String.format(Locale.US,
                    "Crosshair completely off target hitbox (Angle: %.1f°, Ping: %dms)",
                    angleDegrees, ping);

            return CheckResult.flag("SilentAim", 0.94, 2.5, explanation, details);
        }

        // -------------------------------------------------------------
        // 3. Absolute Peripheral FOV Limit (360 Killaura)
        // -------------------------------------------------------------
        if (angleDegrees > 75.0) {
            Map<String, Object> details = new HashMap<>();
            details.put("angle", angleDegrees);
            details.put("target", target.getName() != null ? target.getName() : target.getType().name());

            String explanation = String.format(Locale.US,
                    "Attack outside field of view (Angle: %.1f°, Max: 75.0°)", angleDegrees);

            return CheckResult.flag("KillauraAngle", 0.96, 3.0, explanation, details);
        }

        return CheckResult.pass("KillauraAngle");
    }
}
