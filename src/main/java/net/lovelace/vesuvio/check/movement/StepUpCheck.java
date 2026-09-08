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
 * Detects CheatUtils Step / Step Up hacks.
 * Vanilla Minecraft max step height is 0.6 blocks.
 * Stepping up >= 0.61 blocks in a single packet without a jump arc is a violation.
 *
 * Author: Lovelace
 */
public final class StepUpCheck {

    public CheckResult check(Player player, UserData data, double deltaY, boolean onGround) {
        if (player == null || data == null) return CheckResult.pass("StepUp");

        // Bypasses
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            data.resetStepStreak();
            return CheckResult.pass("StepUp");
        }
        if (player.getAllowFlight() || player.isFlying() || player.isGliding() || player.isInsideVehicle()) {
            data.resetStepStreak();
            return CheckResult.pass("StepUp");
        }
        if (data.hasRecentVelocity()) {
            data.resetStepStreak();
            return CheckResult.pass("StepUp");
        }
        if (player.hasPotionEffect(PotionEffectType.LEVITATION)) {
            data.resetStepStreak();
            return CheckResult.pass("StepUp");
        }
        if (player.isInWater() || player.isInLava()) {
            data.resetStepStreak();
            return CheckResult.pass("StepUp");
        }

        // Only check positive upward movement
        if (deltaY <= 0.60) {
            data.resetStepStreak();
            return CheckResult.pass("StepUp");
        }

        Location loc = player.getLocation();
        if (isNearClimber(loc)) {
            data.resetStepStreak();
            return CheckResult.pass("StepUp");
        }

        // Vanilla step height is 0.6. Slabs are 0.5, stairs are 0.5.
        // CheatUtils Step typically ascends 1.0, 1.25, 1.5, or 2.0 blocks instantly with onGround=true
        if (onGround && deltaY > 0.60) {
            data.incrementStepStreak();
            if (data.getStepStreak() >= 1) {
                Map<String, Object> details = new HashMap<>();
                details.put("deltaY", deltaY);
                details.put("onGround", onGround);
                details.put("streak", data.getStepStreak());

                double confidence = Math.min(1.0, 0.85 + (deltaY - 0.6) * 0.2);
                return CheckResult.flag(
                        "StepUp",
                        12.0,
                        confidence,
                        String.format(Locale.US, "Unnatural step height (ΔY: %.2fb > 0.6b limit)", deltaY),
                        details
                );
            }
        } else {
            data.resetStepStreak();
        }

        return CheckResult.pass("StepUp");
    }

    private boolean isNearClimber(Location loc) {
        if (loc.getWorld() == null) return false;
        int bx = loc.getBlockX();
        int by = loc.getBlockY();
        int bz = loc.getBlockZ();

        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 2; y++) {
                for (int z = -1; z <= 1; z++) {
                    Block b = loc.getWorld().getBlockAt(bx + x, by + y, bz + z);
                    Material mat = b.getType();
                    if (mat == Material.LADDER || mat == Material.VINE ||
                        mat == Material.SCAFFOLDING || mat == Material.TWISTING_VINES ||
                        mat == Material.WEEPING_VINES || mat == Material.COBWEB ||
                        mat == Material.SLIME_BLOCK || mat == Material.HONEY_BLOCK) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
