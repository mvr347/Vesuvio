package net.lovelace.vesuvio.check.movement;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.engine.EnvironmentSnapshot;

import java.util.HashMap;
import java.util.Map;

/**
 * Advanced Movement: NoFall Ground Spoof Detection.
 * Catches packets claiming onGround=true while falling through air.
 *
 * <p>The "is there anything solid under the player" question is answered from the main-thread
 * {@link EnvironmentSnapshot} rather than by reading the world from this thread; see
 * {@code engine.EnvironmentSnapshotService}.
 *
 * Author: Lovelace
 */
public final class NoFallCheck {

    public CheckResult check(UserData data, double deltaY, boolean onGround) {
        if (data == null) return CheckResult.pass("NoFall");

        EnvironmentSnapshot env = data.getEnvironment();
        if (!env.isFresh(System.currentTimeMillis(), 500L)) {
            return CheckResult.pass("NoFall");
        }
        if (env.isMovementExempt()) {
            data.resetNoFallStreak();
            return CheckResult.pass("NoFall");
        }
        // Liquids, cobwebs and climbables all legitimately cancel fall damage and are already
        // folded into solidBelow, but slow-falling and levitation change the fall itself.
        if (env.levitation() || env.slowFalling()) {
            data.resetNoFallStreak();
            return CheckResult.pass("NoFall");
        }

        // If client claims onGround = true, but falling fast with negative deltaY
        if (onGround && deltaY < -0.45 && !env.solidBelow()) {
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

        return CheckResult.pass("NoFall");
    }
}
