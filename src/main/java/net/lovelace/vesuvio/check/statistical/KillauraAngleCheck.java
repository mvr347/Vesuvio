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

            data.resetPerfectAimStreak();
            return CheckResult.flag("KillauraAngle", 0.96, 3.0, explanation, details);
        }

        // -------------------------------------------------------------
        // 4. Perfect-Aim Streak: many consecutive hits landing dead-center on the hitbox
        // (sub-degree crosshair error) is not humanly sustainable across a real fight -
        // catches basic/free KillAura variants that snap-lock exactly onto the target's
        // center every single tick instead of the natural jitter of manual tracking.
        // -------------------------------------------------------------
        if (angleDegrees < 0.6) {
            data.incrementPerfectAimStreak();
            if (data.getPerfectAimStreak() >= 6) {
                Map<String, Object> details = new HashMap<>();
                details.put("angle", angleDegrees);
                details.put("streak", data.getPerfectAimStreak());
                details.put("target", target.getName() != null ? target.getName() : target.getType().name());

                String explanation = String.format(Locale.US,
                        "Inhuman aim-lock precision streak: %d consecutive sub-degree hits (Angle: %.3f°)",
                        data.getPerfectAimStreak(), angleDegrees);

                data.resetPerfectAimStreak();
                return CheckResult.flag("PerfectAimLock", 0.89, 2.0, explanation, details);
            }
        } else {
            data.resetPerfectAimStreak();
        }

        // -------------------------------------------------------------
        // 5. Hit-Rotation Consistency ("StaticAimTracking"): a real-world gap discovered while
        // testing a killaura that never had to turn at all, because both players stood still -
        // none of the rotation-delta checks above (or GCDAim/StatisticalAim) can see anything
        // wrong there, since a genuinely stationary target legitimately needs zero rotation.
        // This check instead asks: across two consecutive attacks, did the aim direction the
        // target ACTUALLY required change meaningfully (i.e. the target moved relative to the
        // attacker), while the attacker's OWN look yaw/pitch stayed essentially frozen, and they
        // still landed a clean hit? A human tracking a moving target with a truly unmoving
        // camera can't keep hitting it - that combination is the signature of aim/target
        // assistance that recalculates the correct angle without generating the mouse input a
        // real player tracking movement would produce. Mirrors the "hit-rotation check" technique
        // used by GrimAC/Vulcan-class anticheats.
        // -------------------------------------------------------------
        float currentYaw = attacker.getLocation().getYaw();
        float currentPitch = attacker.getLocation().getPitch();
        float requiredYaw = requiredYaw(toTarget.getX(), toTarget.getZ());
        float requiredPitch = requiredPitch(toTarget.getX(), toTarget.getY(), toTarget.getZ());

        if (data.hasLastAttackSnapshot()) {
            double targetAngularDelta = angularDiff(data.getLastAttackRequiredYaw(), requiredYaw)
                    + angularDiff(data.getLastAttackRequiredPitch(), requiredPitch);
            double playerAngularDelta = angularDiff(data.getLastAttackYaw(), currentYaw)
                    + angularDiff(data.getLastAttackPitch(), currentPitch);

            if (targetAngularDelta > 4.0 && playerAngularDelta < 0.5 && angleDegrees < 10.0) {
                data.incrementStaticTrackingStreak();
                if (data.getStaticTrackingStreak() >= 3) {
                    Map<String, Object> details = new HashMap<>();
                    details.put("targetAngularDelta", targetAngularDelta);
                    details.put("playerAngularDelta", playerAngularDelta);
                    details.put("angle", angleDegrees);
                    details.put("streak", data.getStaticTrackingStreak());

                    String explanation = String.format(Locale.US,
                            "Tracked a moving target (Δ%.1f°) with a frozen camera (Δ%.2f°) while still hitting (Angle: %.1f°)",
                            targetAngularDelta, playerAngularDelta, angleDegrees);

                    data.resetStaticTrackingStreak();
                    data.setLastAttackSnapshot(currentYaw, currentPitch, requiredYaw, requiredPitch);
                    return CheckResult.flag("StaticAimTracking", 0.90, 2.3, explanation, details);
                }
            } else {
                data.resetStaticTrackingStreak();
            }
        }
        data.setLastAttackSnapshot(currentYaw, currentPitch, requiredYaw, requiredPitch);

        return CheckResult.pass("KillauraAngle");
    }

    /** Shortest angular distance between two degree values, wrapped to [0, 180]. */
    public static double angularDiff(float a, float b) {
        double raw = Math.abs(a - b) % 360.0;
        return raw > 180.0 ? 360.0 - raw : raw;
    }

    /**
     * Yaw (degrees) that would face a direction vector (dx, dy, dz) dead-on, matching the exact
     * convention CraftBukkit's own Location#setDirection uses (verified against its source):
     * yaw = atan2(-dx, dz).
     */
    public static float requiredYaw(double dx, double dz) {
        if (dx == 0 && dz == 0) return 0f;
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** Pitch (degrees) that would face a direction vector dead-on: pitch = atan2(-dy, hypot(dx,dz)). */
    public static float requiredPitch(double dx, double dy, double dz) {
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (dx == 0 && dz == 0) {
            return dy > 0 ? -90f : 90f;
        }
        return (float) Math.toDegrees(Math.atan2(-dy, horizontal));
    }
}
