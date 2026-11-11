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
        return check(data, deltaX, deltaZ, deltaY, -1.0);
    }

    /**
     * @param rttMs transaction round-trip time (never {@code Player#getPing()}, which a client can spoof), or negative if unknown
     */
    public CheckResult check(UserData data, double deltaX, double deltaZ, double deltaY, double rttMs) {
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

        // The server applies a close at once but the snapshot is a tick old; the CLOSE_WINDOW packet / InventoryCloseEvent mark
        // it the moment it happens.
        boolean closedAfterSnapshot = data.containerClosedAfter(env.captureMillis());
        if (!env.containerOpen() || closedAfterSnapshot) {
            data.setContainerOpenSinceMillis(0L);
            data.setInvMovePrevHorizontal(Math.hypot(deltaX, deltaZ));
            data.getInvMoveEvidence().reset();
            return CheckResult.pass("InvMove");
        }

        long openSince = data.getContainerOpenSinceMillis();
        if (openSince == 0L) {
            data.setContainerOpenSinceMillis(nowMillis);
            return CheckResult.pass("InvMove");
        }
        if (env.containerOpenSinceMillis() != 0L) openSince = Math.min(openSince, env.containerOpenSinceMillis());
        // The jump or sprint that began before the menu opened keeps going for the time it takes the client to learn it
        // opened: grace of at least 400 ms, longer for a high round-trip time.
        double graceMs = Math.max(OPEN_GRACE_MS, 2.0 * Math.max(0.0, rttMs) + 150.0);
        if (nowMillis - openSince < graceMs) {
            data.setInvMovePrevHorizontal(Math.hypot(deltaX, deltaZ));
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
        // Evidence only when the speed is NOT falling: leftover inertia decays every tick (ground friction 0.546, air 0.91),
        // while a player who is steering keeps it up. Falling speed counts as nothing in either direction.
        double horizontal = Math.sqrt(horizontalSq);
        double previousHorizontal = data.getInvMovePrevHorizontal();
        data.setInvMovePrevHorizontal(horizontal);
        boolean slowing = horizontal < previousHorizontal * 0.97;
        if (horizontalSq > 0.04 && slowing) {
            return CheckResult.pass("InvMove");
        }
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
