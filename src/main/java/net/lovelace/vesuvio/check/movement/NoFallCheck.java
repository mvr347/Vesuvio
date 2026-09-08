package net.lovelace.vesuvio.check.movement;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.data.UserData;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;

/**
 * Advanced Movement: NoFall Ground Spoof Detection.
 * Catches packets claiming onGround=true while falling through air.
 *
 * Author: Lovelace
 */
public final class NoFallCheck {

    public CheckResult check(Player player, UserData data, double deltaY, boolean onGround) {
        if (player == null || data == null) return CheckResult.pass("NoFall");

        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            data.resetNoFallStreak();
            return CheckResult.pass("NoFall");
        }
        if (player.getAllowFlight() || player.isFlying() || player.isGliding() || player.isInsideVehicle()) {
            data.resetNoFallStreak();
            return CheckResult.pass("NoFall");
        }

        // If client claims onGround = true, but falling fast with negative deltaY
        if (onGround && deltaY < -0.45) {
            Location loc = player.getLocation();
            if (!hasSolidBelow(loc)) {
                data.incrementNoFallStreak();
                if (data.getNoFallStreak() >= 2) {
                    Map<String, Object> details = new HashMap<>();
                    details.put("deltaY", deltaY);
                    details.put("claimedOnGround", true);
                    details.put("streak", data.getNoFallStreak());

                    return CheckResult.flag("NoFall", 0.96, 3.0,
                            String.format("Spoofed ground state while falling (ΔY: %.2f)", deltaY),
                            details);
                }
            } else {
                data.resetNoFallStreak();
            }
        } else {
            data.resetNoFallStreak();
        }

        return CheckResult.pass("NoFall");
    }

    private boolean hasSolidBelow(Location loc) {
        if (loc.getWorld() == null) return true;
        int bx = loc.getBlockX();
        int by = loc.getBlockY();
        int bz = loc.getBlockZ();

        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                // Check block beneath feet and at feet
                Block b0 = loc.getWorld().getBlockAt(bx + x, by, bz + z);
                Block b1 = loc.getWorld().getBlockAt(bx + x, by - 1, bz + z);
                if (isSolidOrClimbable(b0.getType()) || isSolidOrClimbable(b1.getType())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isSolidOrClimbable(Material m) {
        return m.isSolid() || m == Material.LADDER || m == Material.VINE 
                || m == Material.SCAFFOLDING || m == Material.WATER 
                || m == Material.LAVA || m == Material.COBWEB;
    }
}
