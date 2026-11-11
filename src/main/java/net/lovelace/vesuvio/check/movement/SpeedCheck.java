package net.lovelace.vesuvio.check.movement;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.engine.EnvironmentSnapshot;
import org.bukkit.Material;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Horizontal movement check built on a friction/momentum prediction rather than a flat speed cap.
 *
 * <h2>Why the flat cap had to go</h2>
 * The previous implementation compared each tick's horizontal distance against a constant 0.65
 * blocks/tick. Vanilla sprint speed is about 0.28 b/t and even a sprint-jump only peaks near 0.6
 * for the single tick of the jump, so a constant 0.65 allowance let a player hold roughly twice
 * sprint speed indefinitely without ever crossing the line. Raising the number is not the fix
 * either: a single legitimate ice sprint-jump does briefly exceed any cap low enough to be useful.
 * A constant threshold cannot separate "went fast for one tick" from "is going fast forever",
 * because the thing that distinguishes them is not speed, it is <em>momentum</em>.
 *
 * <h2>The model</h2>
 * Minecraft's horizontal movement is a first-order recurrence: each tick the previous speed is
 * multiplied by the surface friction and then a bounded acceleration is added.
 *
 * <pre>speed(t) = speed(t-1) * friction + acceleration</pre>
 *
 * That gives a steady state of {@code acceleration / (1 - friction)} - about 0.286 b/t for a
 * sprinting player on normal ground, which is exactly vanilla sprint speed. Predicting from the
 * player's own previous speed means a burst is allowed (it must have come from somewhere the model
 * accounts for) while a sustained excess is not, because the model's ceiling decays back down and
 * the cheat's does not.
 *
 * <h2>Debt instead of instant flags</h2>
 * Excess over the prediction accumulates into a debt that decays whenever the player moves
 * legitimately. A single mispredicted tick - a bounce, a piston, a plugin-driven push we did not
 * model - costs a little debt and is paid off within a second. Only a player who is consistently
 * out-running their own momentum accumulates enough to flag. This is what keeps the check
 * sensitive to small, sustained speed multipliers without turning every unusual block interaction
 * into a false positive.
 *
 * Author: Lovelace
 */
public final class SpeedCheck {

    // --- Friction constants (vanilla) ---
    /** Air drag applied to horizontal momentum every tick. */
    private static final double AIR_FRICTION = 0.91;
    /** Default block slipperiness; the effective ground friction is slipperiness * air drag. */
    private static final double DEFAULT_SLIPPERINESS = 0.6;
    private static final double ICE_SLIPPERINESS = 0.98;
    private static final double BLUE_ICE_SLIPPERINESS = 0.989;
    private static final double SLIME_SLIPPERINESS = 0.8;

    // --- Acceleration constants (vanilla, per tick) ---
    private static final double GROUND_ACCEL = 0.1;
    private static final double SPRINT_MULTIPLIER = 1.3;
    private static final double AIR_ACCEL = 0.026;
    /** Extra horizontal impulse a sprint-jump grants in the facing direction. */
    private static final double SPRINT_JUMP_BOOST = 0.2;

    /**
     * Slack granted on top of the model. Applied to the <em>acceleration</em> term only, never to
     * the whole prediction: because the prediction feeds back through friction each tick, a
     * multiplier on the total compounds into the steady state. Multiplying the full prediction by
     * a seemingly modest 1.18 raises the sustainable speed to roughly 0.60 b/t - over twice
     * vanilla sprint, which is the very hole this check exists to close. Scaling only the
     * acceleration keeps the slack bounded: the sustainable ceiling works out near 1.3x sprint.
     */
    private static final double ACCEL_TOLERANCE = 1.10;
    private static final double ABSOLUTE_SLACK = 0.03;

    /** Debt at which a flag is raised, and how fast a clean tick pays it down. */
    private static final double DEBT_FLAG_THRESHOLD = 0.85;
    private static final double DEBT_DECAY_PER_TICK = 0.045;
    private static final double DEBT_CEILING = 2.5;

    /**
     * Position packets further apart than this cover more than one tick of movement (idle player
     * resuming, post-lag flush), so the single-tick prediction does not apply to them.
     */
    private static final double MAX_TICK_GAP_MS = 120.0;

    /**
     * Legacy entry point without vertical information: the current packet's ground flag is taken to equal the previous one and
     * the player is assumed not to be rising.
     */
    public CheckResult check(UserData data, double deltaX, double deltaZ,
                             double elapsedMs, double lagTolerance) {
        return check(data, deltaX, deltaZ, 0.0, data == null || data.isLastOnGround(), elapsedMs, lagTolerance);
    }

    /**
     * @param deltaY       vertical change of this packet (a jump is a ground flag at t-1 plus a rise or leaving the ground)
     * @param onGroundNow  ground flag of THIS packet; becomes the t-1 flag of the next one
     * @param elapsedMs    real time since the previous position packet
     * @param lagTolerance multiplier from the lag compensator
     */
    public CheckResult check(UserData data, double deltaX, double deltaZ, double deltaY, boolean onGroundNow,
                             double elapsedMs, double lagTolerance) {
        if (data == null) return CheckResult.pass("Speed");

        EnvironmentSnapshot env = data.getEnvironment();
        // No fresh main-thread snapshot means we cannot tell ice from dirt or a boat from a sprint.
        // Judging movement on unknown terrain is how false positives are made; skip instead.
        if (!env.isFresh(System.currentTimeMillis(), 500L)) {
            data.clearPredictedVector();
            data.setPrevHorizontalSpeed(Math.hypot(deltaX, deltaZ));
            data.shiftSpeedGround(onGroundNow);
            return CheckResult.pass("Speed");
        }

        double hDist = Math.hypot(deltaX, deltaZ);

        if (env.isMovementExempt() || env.levitation() || data.hasRecentVelocity() || env.swimming() || env.inWater() || env.inLava() || env.nearClimbable()) {
            data.clearPredictedVector();
            data.setPrevHorizontalSpeed(hDist);
            data.setSpeedPredictionDebt(0.0);
            data.resetSpeedStreak();
            data.shiftSpeedGround(onGroundNow);
            return CheckResult.pass("Speed");
        }

        // A gap that does not correspond to one tick carries more than one tick of movement.
        // Reset momentum rather than predicting from a distance we cannot attribute to a tick.
        if (elapsedMs > MAX_TICK_GAP_MS || elapsedMs <= 0) {
            data.clearPredictedVector();
            data.setPrevHorizontalSpeed(hDist);
            data.shiftSpeedGround(onGroundNow);
            return CheckResult.pass("Speed");
        }

        double prev = data.getPrevHorizontalSpeed();
        data.setPrevHorizontalSpeed(hDist);

        // Vanilla reads the ground flag at the START of a tick: acceleration of this tick by the flag of packet t-1, the
        // friction that decayed the previous displacement by the flag of packet t-2. A claimed ground state also needs a
        // solid block nearby, so spoofing onGround in mid-air does not buy ground acceleration.
        boolean groundAtStart = data.isSpeedGround1() && env.solidBelow();
        boolean groundAtPrevStart = data.isSpeedGround2();
        // Jump: was on the ground at the start of the tick and now rises or has left it. Under a 2-block ceiling the rise is
        // tiny but the horizontal impulse is the same.
        boolean jumped = groundAtStart && (deltaY > 0.0 || !onGroundNow);
        data.shiftSpeedGround(onGroundNow);

        long nowMillis = System.currentTimeMillis();
        double slip = slipperinessOf(env.blockBelow());
        double carrySlip = data.slipperyMax(slip, nowMillis);
        double accelSlip = data.slipperyMin(slip, nowMillis);

        Prediction prediction = predictMaxSpeed(env, prev, groundAtStart, groundAtPrevStart, jumped, carrySlip, accelSlip);
        double predicted = prediction.total();
        boolean onGround = groundAtStart;

        double allowed = prediction.carried()
                + prediction.accel() * ACCEL_TOLERANCE
                + ABSOLUTE_SLACK * Math.max(1.0, lagTolerance);
        double debt = data.getSpeedPredictionDebt();
        double excess = Math.max(0, hDist - allowed);
        boolean stableGround = onGround && groundAtPrevStart && !jumped && data.getGroundTicks() >= 3;
        boolean collision = env.blockedPosX() || env.blockedNegX() || env.blockedPosZ() || env.blockedNegZ();
        if (data.hasPredictedVector() && stableGround && !collision && hDist > 0.03
                && env.blockBelow() != Material.SLIME_BLOCK) {
            double residual = net.lovelace.vesuvio.engine.HorizontalMotionModel.residual(
                    data.getPredictedPreviousX(), data.getPredictedPreviousZ(), deltaX, deltaZ,
                    carrySlip * AIR_FRICTION,
                    prediction.accel() * ACCEL_TOLERANCE, 0, ABSOLUTE_SLACK * Math.max(1, lagTolerance));
            excess = Math.max(excess, residual);
        }
        data.setPredictedVector(deltaX, deltaZ);

        if (excess > 0) {
            debt = Math.min(DEBT_CEILING, debt + excess);
            data.setSpeedPredictionDebt(debt);

            if (debt >= DEBT_FLAG_THRESHOLD) {
                data.incrementSpeedStreak();
                double confidence = Math.min(0.99, 0.76 + Math.min(0.20, debt * 0.12));
                double vl = Math.min(6.0, 1.2 + debt * 2.0);

                // Pay the debt down to just under the line so a sustained speed hack produces a
                // steady stream of flags instead of one per packet after the first crossing.
                data.setSpeedPredictionDebt(DEBT_FLAG_THRESHOLD * 0.4);

                Map<String, Object> details = new HashMap<>();
                details.put("horizontalDistance", hDist);
                details.put("predicted", predicted);
                details.put("allowed", allowed);
                details.put("excess", excess);
                details.put("debt", debt);
                details.put("onGround", onGround);
                details.put("jumped", jumped);
                details.put("blockBelow", env.blockBelow().name());
                details.put("streak", data.getSpeedStreak());

                return CheckResult.flag("Speed", confidence, vl,
                        String.format(Locale.US,
                                "Превышена модель скорости (%.3f блока/такт при максимуме %.3f на %s, накопленное превышение %.2f)",
                                hDist, allowed, env.blockBelow().name().toLowerCase(Locale.ROOT), debt),
                        details);
            }
        } else {
            data.addSpeedPredictionDebt(-DEBT_DECAY_PER_TICK);
            data.decrementSpeedStreak();
        }

        return CheckResult.pass("Speed");
    }

    /**
     * Upper bound on this tick's horizontal speed given the player's previous speed and the
     * surface they are on. Deliberately optimistic at every branch: the check's job is to catch
     * movement that no legitimate combination of effects could produce, so where a modifier's
     * exact contribution is uncertain the model grants the larger value.
     */
    /**
     * Split prediction: {@code carried} is the momentum surviving friction (plus any one-off
     * impulse), {@code accel} is what the player may add this tick under their own power. They are
     * kept apart so tolerance can be applied to the second without compounding through the first.
     */
    private record Prediction(double carried, double accel) {
        double total() { return carried + accel; }
    }

    private Prediction predictMaxSpeed(EnvironmentSnapshot env, double prevSpeed, boolean groundAtStart,
                                       boolean groundAtPrevStart, boolean jumped, double carrySlip, double accelSlip) {
        double accel;
        if (groundAtStart) {
            double friction = accelSlip * AIR_FRICTION;
            // Vanilla scales ground acceleration by 0.16277 / friction^3, which is what keeps a
            // player's top speed roughly constant across surfaces while ice lets momentum carry.
            double attribute = Double.isFinite(env.movementSpeed()) && env.movementSpeed() >= 0 ? env.movementSpeed() : GROUND_ACCEL;
            // The resolved attribute already contains Speed/Slowness, gear and sprint modifiers.
            // Grant the possible sprint transition when the main-thread snapshot is one tick old.
            accel = attribute * (env.sprinting() ? 1.0 : SPRINT_MULTIPLIER)
                    * (0.16277136 / (friction * friction * friction));
        } else {
            accel = AIR_ACCEL * SPRINT_MULTIPLIER;
            // Speed / Slowness style attribute modifiers are multiplicative on movement speed.
            if (env.speedAmplifier() >= 0) {
                accel *= 1.0 + 0.20 * (env.speedAmplifier() + 1);
            }
        }

        // Water movement: Depth Strider restores land-like control, Dolphin's Grace multiplies
        // swim speed outright. Both are generous here by design.
        if (env.inWater()) {
            accel *= 1.0 + 0.30 * Math.min(3, env.depthStrider());
            if (env.dolphinsGrace()) accel *= 2.0;
        }

        // Soul Speed only applies on soul sand / soul soil, where it more than cancels the
        // surface's own slowdown.
        Material below = env.blockBelow();
        if (env.soulSpeed() > 0 && (below == Material.SOUL_SAND || below == Material.SOUL_SOIL)) {
            accel *= 1.0 + 0.30 * env.soulSpeed();
        }

        // The previous displacement was decayed at the end of the previous tick by the friction of the state it STARTED in.
        double friction = groundAtPrevStart ? carrySlip * AIR_FRICTION : AIR_FRICTION;
        double carried = prevSpeed * friction;

        // A sprint-jump grants a one-off forward impulse in the tick of the jump itself - not a per-air-tick bonus, which
        // would raise the airborne steady state past two blocks per tick and blind the check in the air.
        if (jumped || env.blockBelow() == Material.SLIME_BLOCK) {
            carried += SPRINT_JUMP_BOOST;
        }

        return new Prediction(carried, accel);
    }

    private static double slipperinessOf(Material below) {
        return switch (below) {
            case ICE, FROSTED_ICE, PACKED_ICE -> ICE_SLIPPERINESS;
            case BLUE_ICE -> BLUE_ICE_SLIPPERINESS;
            case SLIME_BLOCK -> SLIME_SLIPPERINESS;
            default -> DEFAULT_SLIPPERINESS;
        };
    }
}
