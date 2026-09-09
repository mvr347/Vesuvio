package net.lovelace.vesuvio.check.movement;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.engine.EnvironmentSnapshot;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Detects CheatUtils Step / Step Up hacks.
 * Vanilla Minecraft max step height is 0.6 blocks.
 * Stepping up >= 0.61 blocks in a single packet without a jump arc is a violation.
 *
 * <p>The climbable/bouncy-block scan comes from the main-thread {@link EnvironmentSnapshot}; see
 * {@code engine.EnvironmentSnapshotService}.
 *
 * Author: Lovelace
 */
public final class StepUpCheck {

    public CheckResult check(UserData data, double deltaY, boolean onGround) {
        if (data == null) return CheckResult.pass("StepUp");

        EnvironmentSnapshot env = data.getEnvironment();
        if (!env.isFresh(System.currentTimeMillis(), 500L)) {
            return CheckResult.pass("StepUp");
        }

        // Bypasses
        if (env.isMovementExempt()) {
            data.resetStepStreak();
            return CheckResult.pass("StepUp");
        }
        if (data.hasRecentVelocity()) {
            data.resetStepStreak();
            return CheckResult.pass("StepUp");
        }
        if (env.levitation()) {
            data.resetStepStreak();
            return CheckResult.pass("StepUp");
        }
        if (env.inWater() || env.inLava() || env.swimming()) {
            data.resetStepStreak();
            return CheckResult.pass("StepUp");
        }

        // Only check positive upward movement
        if (deltaY <= 0.60) {
            data.resetStepStreak();
            return CheckResult.pass("StepUp");
        }

        if (env.nearClimbable() || env.climbing()) {
            data.resetStepStreak();
            return CheckResult.pass("StepUp");
        }

        // Vanilla step height is 0.6. Slabs are 0.5, stairs are 0.5.
        // CheatUtils Step typically ascends 1.0, 1.25, 1.5, or 2.0 blocks instantly with onGround=true
        if (onGround) {
            data.incrementStepStreak();
            Map<String, Object> details = new HashMap<>();
            details.put("deltaY", deltaY);
            details.put("onGround", true);
            details.put("streak", data.getStepStreak());

            double confidence = Math.min(1.0, 0.85 + (deltaY - 0.6) * 0.2);
            return CheckResult.flag(
                    "StepUp",
                    confidence,
                    12.0,
                    String.format(Locale.US, "Unnatural step height (ΔY: %.2fb > 0.6b limit)", deltaY),
                    details
            );
        }

        data.resetStepStreak();
        return CheckResult.pass("StepUp");
    }
}
