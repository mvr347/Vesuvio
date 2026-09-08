package net.lovelace.vesuvio.check.movement;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.data.UserData;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
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

        // Check climbing state (ladder/vine/scaffolding) and cobweb - these legitimately break
        // the gravity invariant. Deliberately NOT a "any solid block nearby" scan: that used to
        // exempt the whole check within 1 block of any wall/floor/ceiling, which is most of a
        // built server (bases, cities, mob farms) - a real Fly hack flown next to any structure
        // went completely undetected. Water/lava are already excluded above.
        if (player.isClimbing() || isInCobweb(loc) || onGround) {
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

    private boolean isInCobweb(Location loc) {
        if (loc.getWorld() == null) return false;
        Material feet = loc.getBlock().getType();
        Material head = loc.clone().add(0, 1, 0).getBlock().getType();
        return feet == Material.COBWEB || head == Material.COBWEB;
    }
}
