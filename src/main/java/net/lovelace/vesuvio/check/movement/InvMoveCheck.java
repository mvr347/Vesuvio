package net.lovelace.vesuvio.check.movement;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.engine.EnvironmentSnapshot;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Detects CheatUtils Inv Move hack (moving/sprinting/jumping while an inventory GUI is open).
 *
 * <p>Reads the open-inventory type and sprint state from the main-thread
 * {@link EnvironmentSnapshot}: {@code Player#getOpenInventory()} is not safe to call from the
 * virtual thread this check runs on, and could see a half-swapped view mid-open.
 *
 * Author: Lovelace
 */
public final class InvMoveCheck {

    public CheckResult check(UserData data, double deltaX, double deltaZ, double deltaY) {
        if (data == null) return CheckResult.pass("InvMove");

        EnvironmentSnapshot env = data.getEnvironment();
        if (!env.isFresh(System.currentTimeMillis(), 500L)) {
            return CheckResult.pass("InvMove");
        }

        if (env.isMovementExempt() || data.hasRecentVelocity()) {
            data.resetInvMoveStreak();
            return CheckResult.pass("InvMove");
        }

        if (!env.containerOpen()) {
            data.resetInvMoveStreak();
            return CheckResult.pass("InvMove");
        }

        double horizontalSq = deltaX * deltaX + deltaZ * deltaZ;
        boolean isMoving = horizontalSq > 0.04 || env.sprinting() || Math.abs(deltaY) > 0.2;

        if (isMoving) {
            data.incrementInvMoveStreak();
            if (data.getInvMoveStreak() >= 3) {
                Map<String, Object> details = new HashMap<>();
                details.put("invType", env.openInventoryType());
                details.put("horizontalSpeed", Math.sqrt(horizontalSq));
                details.put("deltaY", deltaY);
                details.put("sprinting", env.sprinting());
                details.put("streak", data.getInvMoveStreak());

                return CheckResult.flag(
                        "InvMove",
                        0.92,
                        8.0,
                        String.format(Locale.US, "Movement with open %s container (speed: %.2fb/t)",
                                env.openInventoryType(), Math.sqrt(horizontalSq)),
                        details
                );
            }
        } else {
            data.resetInvMoveStreak();
        }

        return CheckResult.pass("InvMove");
    }
}
