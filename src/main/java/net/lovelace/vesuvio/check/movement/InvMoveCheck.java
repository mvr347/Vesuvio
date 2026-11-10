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

    /** Evidence needed (a tick of moving with the container open is worth 1.0); replaces the old "3 in a row" streak. */
    private static final double REQUIRED_SCORE = 2.7;
    private static final int REQUIRED_EVENTS = 3;
    private static final double HALF_LIFE_MS = 1500.0;

    /** Momentum from before the window opened (a sprint, a jump) carries on for a moment; it is not moving "with" the menu. */
    private static final long OPEN_GRACE_MS = 400L;

    public CheckResult check(UserData data, double deltaX, double deltaZ, double deltaY) {
        if (data == null) return CheckResult.pass("InvMove");

        long nowMillis = System.currentTimeMillis();
        EnvironmentSnapshot env = data.getEnvironment();
        if (!env.isFresh(nowMillis, 500L)) {
            return CheckResult.pass("InvMove");
        }

        if (env.isMovementExempt() || data.hasRecentVelocity()) {
            data.getInvMoveEvidence().reset();
            return CheckResult.pass("InvMove");
        }

        if (!env.containerOpen()) {
            data.setContainerOpenSinceMillis(0L);
            data.getInvMoveEvidence().reset();
            return CheckResult.pass("InvMove");
        }

        long openSince = data.getContainerOpenSinceMillis();
        if (openSince == 0L) {
            data.setContainerOpenSinceMillis(nowMillis);
            return CheckResult.pass("InvMove");
        }
        if (nowMillis - openSince < OPEN_GRACE_MS) {
            return CheckResult.pass("InvMove");
        }

        // The environment moves a player on its own: currents, ice, falling. None of it is the player moving with a menu open.
        if (env.inWater() || env.inLava() || env.swimming() || env.climbing() || isSlippery(env.blockBelow())
                || Math.abs(deltaY) > 0.2) {
            return CheckResult.pass("InvMove");
        }

        // Moving is judged by the distance covered, not by the sprint flag: the flag stays set on the server for a few ticks
        // after the client has already stopped.
        double horizontalSq = deltaX * deltaX + deltaZ * deltaZ;
        long nowNanos = System.nanoTime();
        if (horizontalSq > 0.04) {
            double score = data.getInvMoveEvidence().reward(1.0, nowNanos, HALF_LIFE_MS);
            if (score >= REQUIRED_SCORE && data.getInvMoveEvidence().events() >= REQUIRED_EVENTS) {
                data.getInvMoveEvidence().reset();
                Map<String, Object> details = new HashMap<>();
                details.put("invType", env.openInventoryType());
                details.put("horizontalSpeed", Math.sqrt(horizontalSq));
                details.put("deltaY", deltaY);
                details.put("sprinting", env.sprinting());
                details.put("evidence", score);
                details.put("openForMs", nowMillis - openSince);

                return CheckResult.flag(
                        "InvMove",
                        0.92,
                        8.0,
                        String.format(Locale.US, "Движение с открытым контейнером %s (скорость: %.2f блока/такт)",
                                env.openInventoryType(), Math.sqrt(horizontalSq)),
                        details
                );
            }
        } else {
            data.getInvMoveEvidence().relieve(0.5, nowNanos, HALF_LIFE_MS);
        }

        return CheckResult.pass("InvMove");
    }

    /** Surfaces a player keeps sliding on after they stop pressing keys. */
    private static boolean isSlippery(org.bukkit.Material below) {
        return switch (below) {
            case ICE, PACKED_ICE, BLUE_ICE, FROSTED_ICE, SLIME_BLOCK, HONEY_BLOCK -> true;
            default -> false;
        };
    }
}
