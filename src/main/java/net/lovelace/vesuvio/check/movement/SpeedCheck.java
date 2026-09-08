package net.lovelace.vesuvio.check.movement;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.data.UserData;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;

/**
 * Advanced Movement: Horizontal Speed & Friction Analyzer.
 * Author: Lovelace
 */
public final class SpeedCheck {

    public CheckResult check(Player player, UserData data, double deltaX, double deltaZ) {
        if (player == null || data == null) return CheckResult.pass("Speed");

        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            data.resetSpeedStreak();
            return CheckResult.pass("Speed");
        }
        if (player.getAllowFlight() || player.isFlying() || player.isGliding() || player.isInsideVehicle()) {
            data.resetSpeedStreak();
            return CheckResult.pass("Speed");
        }
        if (data.hasRecentVelocity()) {
            data.resetSpeedStreak();
            return CheckResult.pass("Speed");
        }

        double hDist = Math.hypot(deltaX, deltaZ);
        if (hDist < 0.20) {
            data.decrementSpeedStreak();
            return CheckResult.pass("Speed");
        }

        double maxAllowed = 0.65; // High base allowance (covers sprint-jump)

        // Speed potion allowance
        PotionEffect speedEffect = player.getPotionEffect(PotionEffectType.SPEED);
        if (speedEffect != null) {
            maxAllowed += (speedEffect.getAmplifier() + 1) * 0.15;
        }

        // Ice / Slime allowance
        Location loc = player.getLocation();
        Material below = loc.clone().subtract(0, 0.5, 0).getBlock().getType();
        if (below == Material.ICE || below == Material.PACKED_ICE || below == Material.BLUE_ICE || below == Material.SLIME_BLOCK) {
            maxAllowed += 0.45;
        }

        // Dynamic sensitivity
        double sensitivity = data.getSensitivityMultiplier();
        if (sensitivity > 1.2) {
            maxAllowed -= 0.04;
        }

        if (hDist > maxAllowed) {
            data.incrementSpeedStreak();
            if (data.getSpeedStreak() >= 3) {
                double excess = hDist - maxAllowed;
                double confidence = Math.min(0.99, 0.75 + excess * 1.5);
                double vl = Math.max(1.0, excess * 8.0);

                Map<String, Object> details = new HashMap<>();
                details.put("horizontalDistance", hDist);
                details.put("maxAllowed", maxAllowed);
                details.put("excess", excess);
                details.put("streak", data.getSpeedStreak());

                return CheckResult.flag("Speed", confidence, vl,
                        String.format("Exceeded movement speed (Speed: %.2fb/t, Max: %.2fb/t, Excess: +%.2f)", hDist, maxAllowed, excess),
                        details);
            }
        } else {
            data.decrementSpeedStreak();
        }

        return CheckResult.pass("Speed");
    }
}
