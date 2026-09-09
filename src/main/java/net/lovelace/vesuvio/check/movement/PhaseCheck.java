package net.lovelace.vesuvio.check.movement;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.engine.EnvironmentSnapshot;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Phase / Clip detection: a player occupying the inside of a solid block.
 *
 * <p>A gap in our coverage found by comparing against the hack taxonomy of a long-running
 * commercial anticheat - we had Fly, Speed, Step and NoFall, but nothing that noticed a player
 * simply standing inside a wall. Vanilla never leaves a player there: the movement code resolves
 * collisions every tick and, in the rare cases a block appears around a player, pushes them out.
 * A player who stays inside an occluding cube while moving under their own power is not in a state
 * the game can produce.
 *
 * <h2>Keeping it narrow</h2>
 * The block test upstream (see {@code EnvironmentSnapshotService}) uses {@code isOccluding()} and
 * {@code !isPassable()}, which excludes every partial or walkable shape a player can legitimately
 * be inside - slabs, stairs, doors, trapdoors, fences, carpets, snow layers, plants. That leaves
 * only full cubes.
 *
 * <p>Even so, a single reading is never enough. A block can legitimately be placed into a player's
 * space, a piston can push one there, a plugin can teleport them, and a chunk can load around them
 * - in all of those the player is inside a block for a moment through no fault of their own, and
 * vanilla ejects them shortly after. So the check requires the state to persist <em>and</em> the
 * player to keep moving horizontally while in it: someone being ejected drifts out, someone
 * phasing keeps walking.
 *
 * Author: Lovelace
 */
public final class PhaseCheck {

    /** Consecutive ticks inside a solid block before anything is considered wrong. */
    private static final int REQUIRED_TICKS = 6;

    /**
     * Horizontal movement per tick required to call it "moving under own power" rather than being
     * ejected. Vanilla's push-out is gentle and mostly resolves within a tick or two.
     */
    private static final double MIN_HORIZONTAL = 0.06;

    public CheckResult check(UserData data, double deltaX, double deltaZ) {
        if (data == null) return CheckResult.pass("Phase");

        EnvironmentSnapshot env = data.getEnvironment();
        if (!env.isFresh(System.currentTimeMillis(), 500L)) {
            data.resetPhaseTicks();
            return CheckResult.pass("Phase");
        }

        // Vehicles, spectators and creative flight all put a player inside geometry legitimately.
        // Climbables and cobwebs are excluded upstream by the occluding test, but a player being
        // pushed by external velocity can be shoved into a block, so that is exempt here too.
        if (env.isMovementExempt() || data.hasRecentVelocity()) {
            data.resetPhaseTicks();
            return CheckResult.pass("Phase");
        }

        if (!env.insideSolidBlock()) {
            data.resetPhaseTicks();
            return CheckResult.pass("Phase");
        }

        double horizontal = Math.hypot(deltaX, deltaZ);
        if (horizontal < MIN_HORIZONTAL) {
            // Stuck inside a block but not travelling: this is what being trapped or ejected looks
            // like, not what phasing looks like. Decay rather than reset, so a cheat that pauses
            // between moves does not fully clear its history.
            data.decrementPhaseTicks();
            return CheckResult.pass("Phase");
        }

        data.incrementPhaseTicks();
        if (data.getPhaseTicks() < REQUIRED_TICKS) {
            return CheckResult.pass("Phase");
        }

        data.resetPhaseTicks();

        Map<String, Object> details = new HashMap<>();
        details.put("horizontalSpeed", horizontal);
        details.put("ticksInside", REQUIRED_TICKS);
        details.put("blockBelow", env.blockBelow().name());

        return CheckResult.flag("Phase", 0.95, 4.0,
                String.format(Locale.US,
                        "Travelling inside a solid block (%.2fb/t sustained for %d ticks)",
                        horizontal, REQUIRED_TICKS),
                details);
    }
}
