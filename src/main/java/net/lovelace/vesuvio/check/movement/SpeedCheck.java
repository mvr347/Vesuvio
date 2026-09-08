package net.lovelace.vesuvio.check.movement;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.data.UserData;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
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

        // Ice / Slime allowance (FROSTED_ICE is the block Frost Walker itself creates - without
        // it, running across ice a player just froze with a Frost Walker boot legitimately looked
        // identical to a Speed violation)
        Location loc = player.getLocation();
        Material below = loc.clone().subtract(0, 0.5, 0).getBlock().getType();
        if (below == Material.ICE || below == Material.PACKED_ICE || below == Material.BLUE_ICE
                || below == Material.FROSTED_ICE || below == Material.SLIME_BLOCK) {
            maxAllowed += 0.45;
        }

        // Soul Speed allowance - boots enchant grants a real, substantial speed boost while
        // walking on soul sand/soil, scaling with level.
        if (below == Material.SOUL_SAND || below == Material.SOUL_SOIL) {
            int soulSpeedLevel = enchantLevel(player, org.bukkit.inventory.EquipmentSlot.FEET, Enchantment.SOUL_SPEED);
            if (soulSpeedLevel > 0) {
                maxAllowed += 0.20 + soulSpeedLevel * 0.12;
            }
        }

        // Depth Strider (boots) + Dolphin's Grace (potion, granted by swimming near dolphins)
        // both substantially raise underwater swim speed - without accounting for them, a
        // player using either legitimately could exceed the plain-water baseline and false-flag.
        if (player.isInWater()) {
            int depthStriderLevel = enchantLevel(player, org.bukkit.inventory.EquipmentSlot.FEET, Enchantment.DEPTH_STRIDER);
            maxAllowed += depthStriderLevel * 0.18;
            if (player.hasPotionEffect(PotionEffectType.DOLPHINS_GRACE)) {
                maxAllowed += 0.35;
            }
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

    private int enchantLevel(Player player, org.bukkit.inventory.EquipmentSlot slot, Enchantment enchantment) {
        if (player.getEquipment() == null) return 0;
        ItemStack item = player.getEquipment().getItem(slot);
        if (item == null || item.getType().isAir()) return 0;
        return item.getEnchantmentLevel(enchantment);
    }
}
