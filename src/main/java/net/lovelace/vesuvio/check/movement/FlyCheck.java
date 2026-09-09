package net.lovelace.vesuvio.check.movement;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.engine.EnvironmentSnapshot;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Advanced Movement: Fly, AirJump, Hover & Sustained-Flight Detection.
 *
 * Vanilla gravity accelerates a falling player downward by ~0.08 blocks/tick (with ~0.98 drag),
 * so a legitimate airborne player's vertical velocity (deltaY) always drifts toward more negative
 * values the longer they stay off the ground. Fly clients that hold altitude or cancel/override
 * gravity break this invariant: deltaY stops decreasing even though airTicks keeps climbing.
 *
 * <h2>Judging jumps against the player's own trajectory, not a magic number</h2>
 * The mid-air patterns below never compare a player's vertical speed against a fixed constant.
 * Vanilla's own jump decays slowly - drag is 0.98, so a plain unboosted jump is still ascending at
 * ~0.25 b/t three ticks in and ~0.16 b/t four ticks in - and any legitimate source of extra jump
 * height (the Jump Boost potion, or an item carrying a {@code minecraft:attribute_modifiers} jump
 * strength bonus, e.g. a custom sword or boots from the server's own item plugins) only pushes
 * those numbers higher. A fixed threshold picked to sit above one profile sits inside the other,
 * so the only way to judge "is gravity being applied" correctly for every legitimate jump height at
 * once is to compare each tick against the <em>player's own previous tick</em>: whatever their
 * velocity was a moment ago, gravity must have reduced it by roughly {@link #GRAVITY} * {@link
 * #DRAG} since then, regardless of how large the initial jump was or where it came from. That
 * invariant holds for every legitimate jump - vanilla, potion-boosted, or item-boosted - and breaks
 * for every one of them the instant a Fly/AirJump client refuses to let velocity fall.
 *
 * <p>The one moment this self-referential check cannot apply is the very first airborne tick,
 * where there is no prior in-air sample yet - that tick is the jump impulse itself. It is bounded
 * instead against {@link EnvironmentSnapshot#jumpStrength()}, the player's live jump-strength
 * attribute (which already includes any item's modifier, resolved by the server the same way it
 * resolves a weapon's attack-damage bonus), plus the Jump Boost potion's own separate additive
 * bonus. This is what lets a legitimately high first jump - from a custom item, not just a potion -
 * through without weakening the check for everyone else: the ceiling is "what this player's own
 * gear entitles them to," not "what vanilla players get."
 *
 * <p>All world and player state comes from the main-thread {@link EnvironmentSnapshot} rather than
 * live Bukkit calls, because this check runs on a virtual thread (see
 * {@code engine.EnvironmentSnapshotService} for why that distinction matters).
 *
 * Author: Lovelace
 */
public final class FlyCheck {

    // Sustained-flight: player has been airborne this many ticks without gravity ever winning.
    private static final int SUSTAINED_AIR_TICKS = 12;
    // Hover: near-zero vertical movement while airborne.
    private static final int HOVER_AIR_TICKS = 8;
    private static final double GRAVITY_TOLERANCE = 0.02; // allowed slack in gravity comparison

    // Vanilla per-tick vertical physics, used only to predict the NEXT tick from the player's own
    // PREVIOUS tick - never as an absolute ceiling (see class doc).
    private static final double GRAVITY = 0.08;
    private static final double DRAG = 0.98;

    // Gravity-consistency tolerance: fixed slack absorbs packet/tick rounding noise near the jump
    // apex where velocity is small; the ratio term scales with the velocity itself so a large
    // (potion- or item-boosted) jump gets proportionally the same benefit of the doubt as a small
    // one, rather than the fixed slack becoming comparatively tiny at high velocity.
    //
    // The ratio has a hard ceiling: gravity's own per-tick decrement is
    // prevDeltaY*(1-DRAG) + GRAVITY*DRAG, i.e. it grows with velocity at (1-DRAG) = 2% of prevDeltaY
    // plus a constant. A tolerance ratio at or above that 2% would mean a hack holding a large
    // velocity PERFECTLY CONSTANT - no decay at all - stays inside tolerance forever once velocity
    // is high enough, because the proportional slack outgrows the very decrement it is supposed to
    // be forgiving rounding error around. Kept safely under that ceiling so the required decrement
    // always exceeds the tolerance for every velocity, not just small ones.
    private static final double CONSISTENCY_TOLERANCE_FIXED = 0.03;
    private static final double CONSISTENCY_TOLERANCE_RATIO = 0.015;
    private static final int CONSISTENCY_REQUIRED_STREAK = 2;

    // First-tick jump-impulse sanity bound. Generous on purpose: this pattern exists to catch an
    // instantly huge fake "jump" (an impulse no legitimate attribute value could produce), not to
    // second-guess a real one - the gravity-consistency pattern above is what actually polices the
    // rest of the arc, tick by tick, against whatever this first tick turns out to be.
    private static final double JUMP_IMPULSE_TOLERANCE_RATIO = 1.25;
    private static final double JUMP_IMPULSE_TOLERANCE_FIXED = 0.05;
    // Vanilla Jump Boost adds ~0.1 b/t of extra impulse per amplifier level. The snapshot only
    // carries whether the effect is present, not its amplifier, so this grants headroom for a
    // generously high level rather than trying to read the exact amplifier from a boolean.
    private static final double JUMP_BOOST_MAX_BONUS = 0.1 * 4;

    public CheckResult check(UserData data, double deltaX, double deltaY, double deltaZ, boolean onGround) {
        if (data == null) return CheckResult.pass("Fly");

        EnvironmentSnapshot env = data.getEnvironment();
        // Without a fresh snapshot we cannot tell air from water, a ladder from a wall, or a
        // creative flight from a hack. Skip rather than guess.
        if (!env.isFresh(System.currentTimeMillis(), 500L)) {
            data.setPrevAirDeltaY(deltaY);
            return CheckResult.pass("Fly");
        }

        // Bypasses
        if (env.exemptGameMode()) {
            data.resetAirTicks();
            data.resetFlyStreak();
            data.setPrevAirDeltaY(0.0);
            return CheckResult.pass("Fly");
        }
        if (env.isMovementExempt()) {
            data.resetAirTicks();
            data.resetFlyStreak();
            data.setPrevAirDeltaY(0.0);
            return CheckResult.pass("Fly");
        }
        if (data.hasRecentVelocity()) {
            data.resetFlyStreak();
            data.setPrevAirDeltaY(deltaY);
            return CheckResult.pass("Fly");
        }
        if (env.levitation() || env.slowFalling()) {
            data.resetFlyStreak();
            data.setPrevAirDeltaY(deltaY);
            return CheckResult.pass("Fly");
        }

        // Liquids float the player and break the gravity invariant legitimately.
        if (env.inWater() || env.inLava() || env.swimming()) {
            data.resetAirTicks();
            data.resetFlyStreak();
            data.setPrevAirDeltaY(0.0);
            return CheckResult.pass("Fly");
        }

        // Climbing state (ladder/vine/scaffolding) and cobweb also legitimately break the gravity
        // invariant. Deliberately NOT a "any solid block nearby" scan: that used to exempt the
        // whole check within 1 block of any wall/floor/ceiling, which is most of a built server
        // (bases, cities, mob farms) - a real Fly hack flown next to any structure went completely
        // undetected.
        if (env.climbing() || env.inCobweb() || onGround) {
            data.resetAirTicks();
            data.decrementFlyStreak();
            data.setPrevAirDeltaY(0.0);
            return CheckResult.pass("Fly");
        }

        double prevDeltaY = data.getPrevAirDeltaY();
        data.incrementAirTicks();
        int airTicks = data.getAirTicks();

        boolean hasJumpBoost = env.jumpBoost();
        double jumpStrength = env.jumpStrength() > 0 ? env.jumpStrength() : 0.42;

        // -----------------------------------------------------------------
        // Pattern 1: Sustained Flight - gravity never wins over 12+ air ticks
        // -----------------------------------------------------------------
        if (airTicks >= SUSTAINED_AIR_TICKS) {
            boolean notFalling = deltaY > -0.05;
            boolean gravityNotApplied = deltaY >= (prevDeltaY - GRAVITY_TOLERANCE);
            if (notFalling && gravityNotApplied && !hasJumpBoost) {
                data.incrementFlyStreak();
                if (data.getFlyStreak() >= 3) {
                    Map<String, Object> details = new HashMap<>();
                    details.put("deltaY", deltaY);
                    details.put("prevDeltaY", prevDeltaY);
                    details.put("airTicks", airTicks);
                    details.put("subType", "SustainedFlight");
                    data.setPrevAirDeltaY(deltaY);
                    return CheckResult.flag("Fly", 0.95, 2.8,
                            String.format(Locale.US, "Sustained flight - gravity not applied (ΔY: %.3f, AirTicks: %d)", deltaY, airTicks),
                            details);
                }
            } else {
                data.decrementFlyStreak();
            }
        }

        // Pattern 2: Hover / Horizontal Glide in air
        if (airTicks >= HOVER_AIR_TICKS && Math.abs(deltaY) < 0.08) {
            data.incrementFlyStreak();
            if (data.getFlyStreak() >= 2) {
                Map<String, Object> details = new HashMap<>();
                details.put("deltaY", deltaY);
                details.put("airTicks", airTicks);
                details.put("subType", "Hover");
                data.setPrevAirDeltaY(deltaY);
                return CheckResult.flag("Fly", 0.94, 2.2,
                        String.format(Locale.US, "Unnatural hovering (ΔY: %.3f, AirTicks: %d)", deltaY, airTicks), details);
            }
        }

        // -----------------------------------------------------------------
        // Pattern 3: Jump-Impulse Sanity - the very first airborne tick has no prior in-air
        // sample to check gravity consistency against (it IS the jump impulse), so it is bounded
        // instead by what the player's own jump-strength attribute could legitimately produce.
        // Reading the live attribute (not a hardcoded 0.42) is what lets a genuinely
        // jump-boosting item through here without opening the door to an arbitrary fake impulse.
        // -----------------------------------------------------------------
        if (airTicks == 1) {
            double maxImpulse = jumpStrength * JUMP_IMPULSE_TOLERANCE_RATIO + JUMP_IMPULSE_TOLERANCE_FIXED;
            if (hasJumpBoost) {
                maxImpulse += JUMP_BOOST_MAX_BONUS;
            }
            if (deltaY > maxImpulse) {
                data.incrementFlyStreak();
                if (data.getFlyStreak() >= 2) {
                    Map<String, Object> details = new HashMap<>();
                    details.put("deltaY", deltaY);
                    details.put("jumpStrength", jumpStrength);
                    details.put("maxImpulse", maxImpulse);
                    details.put("subType", "JumpImpulse");
                    data.setPrevAirDeltaY(deltaY);
                    return CheckResult.flag("Fly", 0.93, 2.6,
                            String.format(Locale.US, "Jump impulse exceeds jump-strength attribute (ΔY: %.2f, max: %.2f)", deltaY, maxImpulse),
                            details);
                }
            } else {
                data.decrementFlyStreak();
            }
        }

        // -----------------------------------------------------------------
        // Pattern 4: Gravity Consistency (replaces the old flat-threshold AirJump/Ascension
        // patterns, which compared deltaY against fixed 0.08/0.15 constants - a comparison that
        // fired on every ordinary jump, since a plain unboosted jump is still above both numbers
        // several ticks in, and fired even harder on any legitimate jump-height boost. Comparing
        // against the player's OWN previous tick instead is correct at any jump height: gravity
        // must reduce velocity by ~GRAVITY*DRAG from wherever the player actually was a moment
        // ago, whatever that starting point was and wherever it came from.
        // -----------------------------------------------------------------
        if (airTicks >= 2) {
            double predicted = (prevDeltaY - GRAVITY) * DRAG;
            double tolerance = CONSISTENCY_TOLERANCE_FIXED + Math.abs(prevDeltaY) * CONSISTENCY_TOLERANCE_RATIO;
            double allowed = predicted + tolerance;
            if (deltaY > allowed) {
                data.incrementFlyStreak();
                if (data.getFlyStreak() >= CONSISTENCY_REQUIRED_STREAK) {
                    // A net increase over the previous tick is a fresh upward kick (a "double
                    // jump"); anything else is gravity simply failing to slow the player down.
                    String subType = deltaY > prevDeltaY + tolerance ? "AirJump" : "Ascension";
                    double excess = deltaY - allowed;
                    double confidence = Math.min(0.98, 0.90 + excess * 2.0);
                    double vl = Math.min(4.0, 2.0 + excess * 6.0);

                    Map<String, Object> details = new HashMap<>();
                    details.put("deltaY", deltaY);
                    details.put("prevDeltaY", prevDeltaY);
                    details.put("predicted", predicted);
                    details.put("allowed", allowed);
                    details.put("airTicks", airTicks);
                    details.put("subType", subType);
                    data.setPrevAirDeltaY(deltaY);
                    return CheckResult.flag("Fly", confidence, vl,
                            String.format(Locale.US, "Gravity not consistently applied (ΔY: %.3f, expected ≤%.3f, AirTicks: %d)",
                                    deltaY, allowed, airTicks),
                            details);
                }
            } else {
                data.decrementFlyStreak();
            }
        }

        data.setPrevAirDeltaY(deltaY);
        return CheckResult.pass("Fly");
    }
}
