package net.lovelace.vesuvio.check.movement;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.data.UserData;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Detects CheatUtils Inv Move hack (moving/sprinting/jumping while an inventory GUI is open).
 *
 * Author: Lovelace
 */
public final class InvMoveCheck {

    public CheckResult check(Player player, UserData data, double deltaX, double deltaZ, double deltaY) {
        if (player == null || data == null) return CheckResult.pass("InvMove");

        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            data.resetInvMoveStreak();
            return CheckResult.pass("InvMove");
        }
        if (player.isInsideVehicle() || player.isGliding() || data.hasRecentVelocity()) {
            data.resetInvMoveStreak();
            return CheckResult.pass("InvMove");
        }

        var openInv = player.getOpenInventory();
        if (openInv == null) {
            data.resetInvMoveStreak();
            return CheckResult.pass("InvMove");
        }

        InventoryType type = openInv.getType();
        // CRAFTING is the default 2x2 player inventory view when closed
        if (type == InventoryType.CRAFTING) {
            data.resetInvMoveStreak();
            return CheckResult.pass("InvMove");
        }

        double horizontalSq = deltaX * deltaX + deltaZ * deltaZ;
        boolean isMoving = horizontalSq > 0.04 || player.isSprinting() || Math.abs(deltaY) > 0.2;

        if (isMoving) {
            data.incrementInvMoveStreak();
            if (data.getInvMoveStreak() >= 3) {
                Map<String, Object> details = new HashMap<>();
                details.put("invType", type.name());
                details.put("horizontalSpeed", Math.sqrt(horizontalSq));
                details.put("deltaY", deltaY);
                details.put("sprinting", player.isSprinting());
                details.put("streak", data.getInvMoveStreak());

                return CheckResult.flag(
                        "InvMove",
                        8.0,
                        0.92,
                        String.format(Locale.US, "Movement with open %s container (speed: %.2fb/t)", type.name(), Math.sqrt(horizontalSq)),
                        details
                );
            }
        } else {
            data.resetInvMoveStreak();
        }

        return CheckResult.pass("InvMove");
    }
}
