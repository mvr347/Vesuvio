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

    /** Evidence (one point per tick of travelling inside a block) needed before anything is considered wrong. */
    private static final double REQUIRED_SCORE = 5.5;

    /** Minimum number of observed ticks behind the score, so one lagged burst cannot reach it alone. */
    private static final int REQUIRED_EVENTS = 6;

    /** Half-life of the evidence, so a pause drains it instead of resetting it. */
    private static final double HALF_LIFE_MS = 1500.0;

    /** After a flag the same episode is not flagged again for this long: one stuck situation is one finding, not a burst. */
    private static final long EPISODE_COOLDOWN_MS = 4000L;

    /**
     * Block data is only as fresh as the last scan. For this long after a block changed near the player (their own break, a
     * regenerating or restored block, a generator, a piston, an explosion) a reading of "inside a block" is not trusted.
     */
    private static final long BLOCK_CHANGE_GRACE_MS = 1500L;

    /**
     * Horizontal movement per tick required to call it "moving under own power" rather than being
     * ejected. Vanilla's push-out is gentle (about 0.1 blocks per tick), so ejection can last several ticks and
     * is told apart by the block-change grace above, not by this value alone.
     */
    private static final double MIN_HORIZONTAL = 0.06;

    public CheckResult check(UserData data, double deltaX, double deltaZ) {
        if (data == null) return CheckResult.pass("Phase");

        long nowMillis = System.currentTimeMillis();
        EnvironmentSnapshot env = data.getEnvironment();
        if (!env.isFresh(nowMillis, 500L)) {
            data.getPhaseEvidence().reset();
            return CheckResult.pass("Phase");
        }

        // Vehicles, spectators and creative flight all put a player inside geometry legitimately.
        // Climbables and cobwebs are excluded upstream by the occluding test, but a player being
        // pushed by external velocity can be shoved into a block, so that is exempt here too.
        if (env.isMovementExempt() || data.hasRecentVelocity()) {
            data.getPhaseEvidence().reset();
            return CheckResult.pass("Phase");
        }

        if (!env.insideSolidBlock()) {
            data.getPhaseEvidence().reset();
            return CheckResult.pass("Phase");
        }

        // A block changed near the player a moment ago: the "inside" reading may come from a scan taken before the change.
        if (nowMillis - data.getLastNearbyBlockChangeMillis() < BLOCK_CHANGE_GRACE_MS) {
            data.getPhaseEvidence().reset();
            return CheckResult.pass("Phase");
        }

        if (nowMillis < data.getPhaseCooldownUntilMillis()) {
            return CheckResult.pass("Phase");
        }

        long nowNanos = System.nanoTime();
        double horizontal = Math.hypot(deltaX, deltaZ);
        if (horizontal < MIN_HORIZONTAL) {
            // Stuck inside a block but not travelling: this is what being trapped or ejected looks
            // like, not what phasing looks like. Refund part of the evidence rather than reset it,
            // so a cheat that pauses between moves does not fully clear its history.
            data.getPhaseEvidence().relieve(0.5, nowNanos, HALF_LIFE_MS);
            return CheckResult.pass("Phase");
        }

        double score = data.getPhaseEvidence().reward(1.0, nowNanos, HALF_LIFE_MS);
        if (score < REQUIRED_SCORE || data.getPhaseEvidence().events() < REQUIRED_EVENTS) {
            return CheckResult.pass("Phase");
        }

        data.getPhaseEvidence().reset();
        data.setPhaseCooldownUntilMillis(nowMillis + EPISODE_COOLDOWN_MS);

        Map<String, Object> details = new HashMap<>();
        details.put("horizontalSpeed", horizontal);
        details.put("evidence", score);
        details.put("blockBelow", env.blockBelow().name());
        details.put("msSinceBlockChange", nowMillis - data.getLastNearbyBlockChangeMillis());
        details.put("snapshotAgeMs", nowMillis - env.captureMillis());

        return CheckResult.flag("Phase", 0.95, 4.0,
                String.format(Locale.US,
                        "Движение внутри сплошного блока (%.2f блока/такт, накоплено улик: %.1f)",
                        horizontal, score),
                details);
    }
}
