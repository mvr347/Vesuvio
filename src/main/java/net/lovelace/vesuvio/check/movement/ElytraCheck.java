package net.lovelace.vesuvio.check.movement;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.engine.EnvironmentSnapshot;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Elytra glide sanity check.
 *
 * <h2>Why gliding was a blanket exemption before this</h2>
 * Every other movement check ({@code EnvironmentSnapshot#isMovementExempt()}) treats gliding as
 * exempt outright, because none of them model glide physics - Speed/Fly are built around
 * ground/air walking and jumping, not the pitch-driven lift/drag recurrence elytra uses. That left
 * gliding as a total blind spot: a player could fly indefinitely on elytra with zero detection.
 *
 * <h2>Why this is deliberately coarse, not a full physics recreation</h2>
 * Vanilla's per-tick glide recurrence (pitch-dependent lift, 0.99/0.98 drag, the firework boost's
 * "lerp toward look-direction * 1.5 over the rocket's flight duration") is precise but easy to get
 * subtly wrong without a live client to verify against - and a wrong model here creates exactly
 * the kind of false positive this project has already spent significant effort removing from
 * Blink. So this check deliberately narrows its scope to the one pattern that both matters most
 * and is safest to detect: <em>sustained flight with zero firework use</em>.
 *
 * <p>A legitimate long elytra flight needs periodic rocket boosts to sustain altitude or speed -
 * unboosted vanilla glide always loses net altitude over any multi-second window, and its
 * (well-documented) terminal dive speed tops out around 3.9 blocks/tick (~78 m/s, reached diving
 * straight down). The classic ElytraFly hack instead grants unlimited altitude/speed with zero
 * rocket consumption. Both signals below apply only while no boost was used recently; once a
 * player has actually launched a rocket, both are skipped rather than trying to bound
 * rocket-chained speed precisely, since that is both harder to model safely and a legitimate,
 * if extreme, way to fly fast.
 *
 * Author: Lovelace
 */
public final class ElytraCheck {

    /** Window over which net vertical change is judged. */
    private static final long CLIMB_WINDOW_MS = 3000L;

    /**
     * Net climb tolerated within the window with no recent boost. Generous: the "looking up" term
     * in vanilla's own glide formula lets a shallow climb persist briefly, and terrain-following
     * dives have some noise.
     */
    private static final double CLIMB_TOLERANCE_BLOCKS = 2.0;

    /**
     * Absolute 3D speed ceiling for <em>unboosted</em> glide, in blocks/tick. Vanilla's own
     * terminal dive speed (straight down, no rocket) works out to about 3.92 blocks/tick from the
     * documented 0.08 gravity / 0.98 vertical drag recurrence; this allows roughly 1.5x that as
     * slack for the model's imprecision rather than trying to replicate the recurrence exactly.
     */
    private static final double MAX_UNBOOSTED_SPEED_PER_TICK = 6.0;

    private static final double DEBT_FLAG_THRESHOLD = 3.0;
    private static final double DEBT_DECAY_PER_TICK = 0.15;
    private static final double DEBT_CEILING = 12.0;

    private static final int REQUIRED_STREAK = 2;

    public CheckResult check(UserData data, double deltaX, double deltaY, double deltaZ) {
        return check(data, deltaX, deltaY, deltaZ, System.currentTimeMillis());
    }

    /**
     * Clock-injectable form. The climb window spans several real seconds, so supplying the clock
     * explicitly is what makes that behaviour testable without real sleeps (same reasoning as
     * {@link BlinkCheck#check}/{@link TimerCheck#check}).
     */
    public CheckResult check(UserData data, double deltaX, double deltaY, double deltaZ, long nowMillis) {
        if (data == null) return CheckResult.pass("Elytra");

        EnvironmentSnapshot env = data.getEnvironment();
        if (!env.isFresh(System.currentTimeMillis(), 500L)) {
            return CheckResult.pass("Elytra");
        }

        // This check exists specifically for the one state every other movement check exempts -
        // see EnvironmentSnapshot#isMovementExempt(). Game modes with free flight, and being in a
        // vehicle or dead, are still not this check's business even while technically "gliding"
        // could overlap them in edge cases, so exclude those explicitly rather than relying on
        // gliding() alone.
        if (!env.gliding() || env.exemptGameMode() || env.allowFlight() || env.insideVehicle() || env.dead()) {
            data.resetElytraWindow(0L);
            data.setElytraSpeedDebt(0.0);
            data.resetElytraViolationStreak();
            return CheckResult.pass("Elytra");
        }

        boolean boosted = data.hasRecentElytraBoost();

        CheckResult climbResult = checkClimb(data, deltaY, nowMillis, boosted);
        if (climbResult.isFlag()) return climbResult;

        return checkSpeed(data, deltaX, deltaY, deltaZ, boosted);
    }

    private CheckResult checkClimb(UserData data, double deltaY, long now, boolean boosted) {
        if (data.getElytraWindowStartMillis() == 0L) {
            data.resetElytraWindow(now);
            return CheckResult.pass("Elytra");
        }

        data.addElytraWindowDeltaY(deltaY);

        if (now - data.getElytraWindowStartMillis() < CLIMB_WINDOW_MS) {
            return CheckResult.pass("Elytra");
        }

        double netClimb = data.getElytraWindowDeltaY();
        data.resetElytraWindow(now);

        if (boosted || netClimb <= CLIMB_TOLERANCE_BLOCKS) {
            return CheckResult.pass("Elytra");
        }

        Map<String, Object> details = new HashMap<>();
        details.put("netClimbBlocks", netClimb);
        details.put("windowMs", CLIMB_WINDOW_MS);

        return CheckResult.flag("Elytra", 0.90, 5.0,
                String.format(Locale.US,
                        "Sustained elytra climb with no firework use (+%.2fb over %.1fs)",
                        netClimb, CLIMB_WINDOW_MS / 1000.0),
                details);
    }

    private CheckResult checkSpeed(UserData data, double deltaX, double deltaY, double deltaZ, boolean boosted) {
        if (boosted) {
            data.setElytraSpeedDebt(0.0);
            data.resetElytraViolationStreak();
            return CheckResult.pass("Elytra");
        }

        double speed = Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);

        if (speed <= MAX_UNBOOSTED_SPEED_PER_TICK) {
            data.addElytraSpeedDebt(-DEBT_DECAY_PER_TICK);
            data.resetElytraViolationStreak();
            return CheckResult.pass("Elytra");
        }

        double excess = speed - MAX_UNBOOSTED_SPEED_PER_TICK;
        double debt = Math.min(DEBT_CEILING, data.getElytraSpeedDebt() + excess);
        data.setElytraSpeedDebt(debt);

        if (debt < DEBT_FLAG_THRESHOLD) {
            return CheckResult.pass("Elytra");
        }

        data.incrementElytraViolationStreak();
        if (data.getElytraViolationStreak() < REQUIRED_STREAK) {
            return CheckResult.pass("Elytra");
        }

        data.setElytraSpeedDebt(DEBT_FLAG_THRESHOLD * 0.4);

        double confidence = Math.min(0.97, 0.80 + debt / 40.0);
        double vl = Math.min(6.0, 1.5 + debt * 0.4);

        Map<String, Object> details = new HashMap<>();
        details.put("speedPerTick", speed);
        details.put("ceiling", MAX_UNBOOSTED_SPEED_PER_TICK);
        details.put("debt", debt);

        return CheckResult.flag("Elytra", confidence, vl,
                String.format(Locale.US,
                        "Unboosted glide speed beyond vanilla's terminal dive (%.2fb/t vs %.2fb/t ceiling, debt %.1f)",
                        speed, MAX_UNBOOSTED_SPEED_PER_TICK, debt),
                details);
    }
}
