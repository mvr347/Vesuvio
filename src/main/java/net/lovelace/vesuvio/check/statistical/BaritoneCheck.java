package net.lovelace.vesuvio.check.statistical;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.engine.EnvironmentSnapshot;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Baritone (and similar pathfinding-bot) detection.
 *
 * <h2>Why a heuristic instead of the ONNX model this ideally deserves</h2>
 * A dedicated model is the right long-term answer - Baritone's kinematics are distinctive enough
 * to learn well - but training one needs labelled Baritone sessions and a data pipeline this
 * environment has neither of. Shipping a model trained on nothing, or guessed weights, would be
 * worse than not having one. This implements the single most reliable piece of the described
 * signature directly instead: a pathfinder computes the exact heading its next waypoint requires
 * and holds it, where a human's aim never stops drifting by small amounts even when trying to walk
 * in a straight line.
 *
 * <h2>The signal</h2>
 * While a player is moving under their own power (not exempt, not gliding, not in a vehicle),
 * compare their actual look yaw against the yaw that would point exactly at their own horizontal
 * direction of travel - the same {@code requiredYaw} formula {@code KillauraAngleCheck} already
 * uses and validates for combat, applied here to movement instead. A human who happens to be
 * looking roughly where they walk still wobbles by a measurable amount tick to tick (hand tremor,
 * minor unconscious corrections); this is exactly the same "sustained sub-degree precision is not
 * humanly sustainable" reasoning {@code KillauraAngleCheck}'s own PerfectAimLock sub-check already
 * relies on, just applied to travel direction instead of a combat target.
 *
 * Author: Lovelace
 */
public final class BaritoneCheck {

    /** Minimum horizontal speed before a travel direction is trusted enough to compare against. */
    private static final double MIN_SPEED = 0.08;

    /** Alignment error, in degrees, tight enough to be suspicious rather than "walking straight". */
    private static final double MAX_ALIGNMENT_ERROR = 0.4;

    /** Consecutive ticks of sustained sub-threshold alignment before flagging (~2.5s at 20 TPS). */
    private static final int REQUIRED_STREAK = 50;

    public CheckResult check(Player player, UserData data, double deltaX, double deltaZ) {
        if (player == null || data == null) return CheckResult.pass("Baritone");

        EnvironmentSnapshot env = data.getEnvironment();
        if (!env.isFresh(System.currentTimeMillis(), 500L) || env.isMovementExempt() || data.hasRecentVelocity()) {
            data.resetBaritoneStreak();
            return CheckResult.pass("Baritone");
        }

        double speed = Math.hypot(deltaX, deltaZ);
        if (speed < MIN_SPEED) {
            data.resetBaritoneStreak();
            return CheckResult.pass("Baritone");
        }

        float currentYaw = player.getLocation().getYaw();
        float idealYaw = KillauraAngleCheck.requiredYaw(deltaX, deltaZ);
        double error = KillauraAngleCheck.angularDiff(currentYaw, idealYaw);

        if (error >= MAX_ALIGNMENT_ERROR) {
            data.resetBaritoneStreak();
            return CheckResult.pass("Baritone");
        }

        data.incrementBaritoneStreak();
        if (data.getBaritoneStreak() < REQUIRED_STREAK) {
            return CheckResult.pass("Baritone");
        }

        data.resetBaritoneStreak();

        Map<String, Object> details = new HashMap<>();
        details.put("alignmentError", error);
        details.put("speed", speed);
        details.put("streakTicks", REQUIRED_STREAK);

        return CheckResult.flag("Baritone", 0.85, 4.0,
                String.format(Locale.US,
                        "Travel heading held within %.2f° of exact path-facing for %d ticks (pathfinder-precision alignment)",
                        error, REQUIRED_STREAK),
                details);
    }
}
