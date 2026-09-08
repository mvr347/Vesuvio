package net.lovelace.vesuvio.check.movement;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.data.UserData;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Advanced Movement: Fly, AirJump, Hover & Sustained-Flight Detection.
 *
 * Vanilla gravity accelerates a falling player downward by ~0.08 blocks/tick (with ~0.98 drag),
 * so a legitimate airborne player's vertical velocity (deltaY) always drifts toward more negative
 * values the longer they stay off the ground. Fly clients that hold altitude or cancel/override
 * gravity break this invariant: deltaY stops decreasing even though airTicks keeps climbing.
 *
 * Author: Lovelace
 */
public final class FlyCheck {

    // Sustained-flight: player has been airborne this many ticks without gravity ever winning.
    private static final int SUSTAINED_AIR_TICKS = 12;
    // Hover: near-zero vertical movement while airborne.
    private static final int HOVER_AIR_TICKS = 8;
    private static final double GRAVITY_TOLERANCE = 0.02; // allowed slack in gravity comparison

    public CheckResult check(Player player, UserData data, double deltaX, double deltaY, double deltaZ, boolean onGround) {
        if (player == null || data == null) return CheckResult.pass("Fly");

        // Bypasses
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            data.resetAirTicks();
            data.resetFlyStreak();
            data.setPrevAirDeltaY(0.0);
            return CheckResult.pass("Fly");
        }
        if (player.getAllowFlight() || player.isFlying() || player.isGliding() || player.isInsideVehicle()) {
            data.resetAirTicks();
            data.resetFlyStreak();
            data.setPrevAirDeltaY(0.0);
            return CheckResult.pass("Fly");
        }
        if (data.hasRecentVelocity()) {
            data.resetFlyStreak();
            data.setPrevAirDeltaY(deltaY);
            return CheckResult.pass("Fly");
        }
        if (player.hasPotionEffect(PotionEffectType.LEVITATION) || player.hasPotionEffect(PotionEffectType.SLOW_FALLING)) {
            data.resetFlyStreak();
            data.setPrevAirDeltaY(deltaY);
            return CheckResult.pass("Fly");
        }

        Location loc = player.getLocation();

        // Check if player is in liquid
        if (player.isInWater() || player.isInLava()) {
            data.resetAirTicks();
            data.resetFlyStreak();
            data.setPrevAirDeltaY(0.0);
            return CheckResult.pass("Fly");
        }

        // Check near solid blocks
        boolean nearSolid = isNearSolid(loc);
        if (nearSolid || onGround) {
            data.resetAirTicks();
            data.decrementFlyStreak();
            data.setPrevAirDeltaY(0.0);
            return CheckResult.pass("Fly");
        }

        double prevDeltaY = data.getPrevAirDeltaY();
        data.incrementAirTicks();
        int airTicks = data.getAirTicks();

        boolean hasJumpBoost = player.hasPotionEffect(PotionEffectType.JUMP_BOOST);

        // -----------------------------------------------------------------
        // Pattern 1: Sustained Flight - gravity never wins over 12+ air ticks
        // -----------------------------------------------------------------
        if (airTicks >= SUSTAINED_AIR_TICKS) {
            boolean notFalling = deltaY > -0.05;
            boolean gravityNotApplied = deltaY >= (prevDeltaY - GRAVITY_TOLERANCE);
            if (notFalling && gravityNotApplied && !hasJumpBoost) {
                data.incrementFlyStreak();
                if (data.getFlyStreak() >= 3) {
                    Map<String, Object> details = new HashMap<>();
                    details.put("deltaY", deltaY);
                    details.put("prevDeltaY", prevDeltaY);
                    details.put("airTicks", airTicks);
                    details.put("subType", "SustainedFlight");
                    data.setPrevAirDeltaY(deltaY);
                    return CheckResult.flag("Fly", 0.95, 2.8,
                            String.format(Locale.US, "Sustained flight - gravity not applied (ΔY: %.3f, AirTicks: %d)", deltaY, airTicks),
                            details);
                }
            } else {
                data.decrementFlyStreak();
            }
        }

        // Pattern 2: Hover / Horizontal Glide in air
        if (airTicks >= HOVER_AIR_TICKS && Math.abs(deltaY) < 0.08) {
            data.incrementFlyStreak();
            if (data.getFlyStreak() >= 2) {
                Map<String, Object> details = new HashMap<>();
                details.put("deltaY", deltaY);
                details.put("airTicks", airTicks);
                details.put("subType", "Hover");
                data.setPrevAirDeltaY(deltaY);
                return CheckResult.flag("Fly", 0.94, 2.2,
                        String.format(Locale.US, "Unnatural hovering (ΔY: %.3f, AirTicks: %d)", deltaY, airTicks), details);
            }
        }

        // Pattern 3: AirJump (mid-air jump while falling or floating)
        if (airTicks >= 3 && deltaY > 0.08 && !hasJumpBoost) {
            data.incrementFlyStreak();
            if (data.getFlyStreak() >= 2) {
                Map<String, Object> details = new HashMap<>();
                details.put("deltaY", deltaY);
                details.put("airTicks", airTicks);
                details.put("subType", "AirJump");
                data.setPrevAirDeltaY(deltaY);
                return CheckResult.flag("Fly", 0.95, 2.5,
                        String.format(Locale.US, "Mid-air jump (ΔY: +%.2f, AirTicks: %d)", deltaY, airTicks), details);
            }
        }

        // Pattern 4: Unnatural upward ascension
        if (airTicks >= 5 && deltaY > 0.15 && !hasJumpBoost) {
            data.incrementFlyStreak();
            if (data.getFlyStreak() >= 2) {
                Map<String, Object> details = new HashMap<>();
                details.put("deltaY", deltaY);
                details.put("airTicks", airTicks);
                details.put("subType", "Ascension");
                data.setPrevAirDeltaY(deltaY);
                return CheckResult.flag("Fly", 0.96, 3.0,
                        String.format(Locale.US, "Mid-air ascension (ΔY: +%.2f, AirTicks: %d)", deltaY, airTicks), details);
            }
        }

        data.setPrevAirDeltaY(deltaY);
        return CheckResult.pass("Fly");
    }

    private boolean isNearSolid(Location loc) {
        if (loc.getWorld() == null) return true;
        int bx = loc.getBlockX();
        int by = loc.getBlockY();
        int bz = loc.getBlockZ();

        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                for (int y = -1; y <= 1; y++) {
                    Block b = loc.getWorld().getBlockAt(bx + x, by + y, bz + z);
                    Material m = b.getType();
                    if (m.isSolid() || m == Material.LADDER || m == Material.VINE
                            || m == Material.SCAFFOLDING || m == Material.COBWEB
                            || m == Material.WATER || m == Material.LAVA) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
