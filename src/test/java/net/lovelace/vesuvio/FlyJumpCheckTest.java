package net.lovelace.vesuvio;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.check.combat.AutoCriticalsCheck;
import net.lovelace.vesuvio.check.movement.FlyCheck;
import net.lovelace.vesuvio.data.UserData;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage for FlyCheck's and AutoCriticalsCheck's jump-height reasoning.
 *
 * <p>These checks used to compare a jump's vertical speed against fixed constants (0.08, 0.15,
 * 0.09 blocks/tick) tuned for the vanilla jump-strength attribute default of 0.42. That broke two
 * ways at once: the flat thresholds fired on completely ordinary, unboosted jumps (a plain jump is
 * still ascending at ~0.25 b/t three ticks in - see {@link #flyNeverFlagsAnOrdinaryUnboostedJump}
 * for the regression this guards), and any legitimate source of extra jump height - the Jump Boost
 * potion, or an item carrying a {@code minecraft:attribute_modifiers} jump-strength bonus (a custom
 * sword, boots, or anything else a server's own item plugins grant) - pushed a real jump even
 * further past those same numbers, making a false flag more likely, not less, for exactly the
 * players who legitimately jump highest.
 *
 * <p>Both checks now read the player's live {@code Attribute.JUMP_STRENGTH} value from the
 * environment snapshot - the same value the vanilla server itself uses to decide how hard a jump
 * pushes the player up, already including any item's modifier - and judge each player against
 * what their own gear actually entitles them to instead of a hardcoded vanilla number.
 */
class FlyJumpCheckTest {

    // --- Vanilla per-tick physics, used to build realistic jump-decay sequences. ---
    private static final double GRAVITY = 0.08;
    private static final double DRAG = 0.98;

    /** Simulates one full jump-to-apex-and-past-it sequence, decaying v0 by vanilla physics. */
    private static double[] jumpSequence(double v0, int ticks) {
        double[] seq = new double[ticks];
        double v = v0;
        for (int i = 0; i < ticks; i++) {
            seq[i] = v;
            v = (v - GRAVITY) * DRAG;
        }
        return seq;
    }

    private static void feedJump(FlyCheck check, UserData data, double[] deltaYs) {
        boolean onGround = false;
        for (double dy : deltaYs) {
            CheckResult r = check.check(data, 0, dy, 0, onGround);
            assertFalse(r.isFlag(), "unexpected flag at ΔY=" + dy + ": " + r.explanation());
        }
    }

    // ------------------------------------------------------------------
    // Fly: gravity-consistency / jump-impulse patterns
    // ------------------------------------------------------------------

    @Test
    void flyNeverFlagsAnOrdinaryUnboostedJump() {
        FlyCheck check = new FlyCheck();
        UserData data = new UserData(UUID.randomUUID(), "Jumper");
        data.setEnvironment(TestSnapshots.builder().jumpStrength(0.42).build());

        // Several jumps back to back - the old flat-threshold patterns (deltaY > 0.08 at
        // airTicks>=3, deltaY > 0.15 at airTicks>=5) fired on every one of these, since a plain
        // vanilla jump is still well above both numbers that many ticks in.
        for (int jump = 0; jump < 5; jump++) {
            feedJump(check, data, jumpSequence(0.42, 10));
            // Land: resets air state for the next jump.
            check.check(data, 0, -0.5, 0, true);
        }
    }

    @Test
    void flyNeverFlagsAJumpBoostedJump() {
        FlyCheck check = new FlyCheck();
        UserData data = new UserData(UUID.randomUUID(), "Boosted");
        // Jump Boost level 2: vanilla adds ~0.1 per amplifier level to the impulse.
        data.setEnvironment(TestSnapshots.builder().jumpStrength(0.42).jumpBoost(true).build());

        feedJump(check, data, jumpSequence(0.42 + 0.2, 10));
    }

    @Test
    void flyNeverFlagsAnItemBoostedHighJump() {
        // This is the concrete scenario reported: a sword (or boots, or any gear) carrying a
        // jump-strength attribute modifier that legitimately sends the player much higher than
        // vanilla. The live attribute value already includes the item's contribution.
        FlyCheck check = new FlyCheck();
        UserData data = new UserData(UUID.randomUUID(), "SwordJumper");
        data.setEnvironment(TestSnapshots.builder().jumpStrength(1.4).build());

        feedJump(check, data, jumpSequence(1.4, 14));
    }

    @Test
    void flyNeverFlagsAnItemReducedJump() {
        // The same attribute can legitimately be lowered too (heavy armor, a debuff item).
        FlyCheck check = new FlyCheck();
        UserData data = new UserData(UUID.randomUUID(), "HeavyArmor");
        data.setEnvironment(TestSnapshots.builder().jumpStrength(0.18).build());

        feedJump(check, data, jumpSequence(0.18, 6));
    }

    @Test
    void flyDetectsAFreshMidAirKick() {
        FlyCheck check = new FlyCheck();
        UserData data = new UserData(UUID.randomUUID(), "DoubleJumper");
        data.setEnvironment(TestSnapshots.builder().jumpStrength(0.42).build());

        // A normal jump decaying for a few ticks...
        double[] normal = jumpSequence(0.42, 4);
        for (double dy : normal) {
            check.check(data, 0, dy, 0, false);
        }
        // ...then a fresh upward kick that gravity cannot explain from the previous tick. Needs to
        // repeat once (the streak requirement) before it flags.
        boolean flagged = false;
        for (int i = 0; i < 5 && !flagged; i++) {
            CheckResult r = check.check(data, 0, 0.40, 0, false);
            if (r.isFlag()) {
                flagged = true;
                assertEquals("Fly", r.checkName());
            }
        }
        assertTrue(flagged, "a genuine mid-air double jump must still be caught");
    }

    @Test
    void flyDetectsSustainedHoverRegardlessOfHowItStarted() {
        FlyCheck check = new FlyCheck();
        UserData data = new UserData(UUID.randomUUID(), "Hoverer");
        data.setEnvironment(TestSnapshots.builder().jumpStrength(0.42).build());

        // Left the ground normally, then simply refuses to let gravity apply - classic Fly engine.
        check.check(data, 0, 0.42, 0, false);
        boolean flagged = false;
        for (int i = 0; i < 15 && !flagged; i++) {
            CheckResult r = check.check(data, 0, 0.0, 0, false);
            if (r.isFlag()) flagged = true;
        }
        assertTrue(flagged, "gravity never applying must still be caught");
    }

    @Test
    void flyDetectsInstantHugeImpulseBeyondJumpStrength() {
        FlyCheck check = new FlyCheck();
        UserData data = new UserData(UUID.randomUUID(), "InstantFly");
        data.setEnvironment(TestSnapshots.builder().jumpStrength(0.42).build());

        // First airborne tick, vastly beyond anything a 0.42 jump-strength attribute could
        // legitimately produce (and below the pipeline's own >15.0 teleport-exclusion cutoff).
        // The jump-impulse sanity pattern sees this on tick 1; if the hack holds anywhere near
        // that value on tick 2 the gravity-consistency pattern catches it too - between the two,
        // this must not survive more than a couple of airborne ticks.
        boolean flagged = false;
        for (int i = 0; i < 5 && !flagged; i++) {
            CheckResult r = check.check(data, 0, 3.0, 0, false);
            if (r.isFlag()) {
                flagged = true;
                assertEquals("Fly", r.checkName());
            }
        }
        assertTrue(flagged, "an instant impulse far beyond the jump-strength attribute must be caught");
    }

    @Test
    void flyAllowsALegitimatelyHugeFirstTickImpulse() {
        // An extreme but genuinely-granted attribute value (some servers do run wild custom items)
        // must not be second-guessed just because it is unusual - the ceiling is the player's own
        // gear, not a vanilla assumption.
        FlyCheck check = new FlyCheck();
        UserData data = new UserData(UUID.randomUUID(), "ExtremeItem");
        data.setEnvironment(TestSnapshots.builder().jumpStrength(3.0).build());

        feedJump(check, data, jumpSequence(3.0, 20));
    }

    @Test
    void flySkipsWithoutSnapshot() {
        FlyCheck check = new FlyCheck();
        UserData data = new UserData(UUID.randomUUID(), "Unknown");
        for (int i = 0; i < 20; i++) {
            assertFalse(check.check(data, 0, 0.42, 0, false).isFlag());
        }
    }

    // ------------------------------------------------------------------
    // AutoCriticals: micro-hop band scaled by jump strength
    // ------------------------------------------------------------------

    @Test
    void autoCriticalsIgnoresARealVanillaJump() {
        AutoCriticalsCheck check = new AutoCriticalsCheck();
        UserData data = new UserData(UUID.randomUUID(), "RealJumper");
        data.setEnvironment(TestSnapshots.builder().jumpStrength(0.42).build());
        data.setLastPosition(0, 64.0, 0, true);
        data.setLastPosition(0, 64.42, 0, false);

        assertFalse(check.check(data).isFlag());
    }

    @Test
    void autoCriticalsDetectsVanillaMicroHopExploit() {
        AutoCriticalsCheck check = new AutoCriticalsCheck();
        UserData data = new UserData(UUID.randomUUID(), "Cheater");
        data.setEnvironment(TestSnapshots.builder().jumpStrength(0.42).build());
        data.setLastPosition(0, 64.0, 0, true);
        data.setLastPosition(0, 64.03, 0, true);

        CheckResult result = check.check(data);
        assertTrue(result.isFlag());
        assertEquals("AutoCriticals", result.checkName());
    }

    @Test
    void autoCriticalsAllowsARealShortJumpFromReducedJumpStrength() {
        // A gear-reduced jump strength (e.g. heavy custom armor) can legitimately land the very
        // scenario that would look like a micro-hop under the vanilla-tuned flat 0.09 ceiling.
        // Scaling the band by the player's own attribute is what keeps this a real jump instead of
        // an exploit.
        AutoCriticalsCheck check = new AutoCriticalsCheck();
        UserData data = new UserData(UUID.randomUUID(), "HeavyArmor");
        data.setEnvironment(TestSnapshots.builder().jumpStrength(0.12).build());
        data.setLastPosition(0, 64.0, 0, true);
        data.setLastPosition(0, 64.12, 0, true);

        assertFalse(check.check(data).isFlag(),
                "a real jump for this player's own (reduced) jump strength must not be flagged");
    }

    @Test
    void autoCriticalsStillCatchesAMicroHopWithReducedJumpStrength() {
        // The exploit is still proportionally tiny even for a reduced-jump-strength player.
        AutoCriticalsCheck check = new AutoCriticalsCheck();
        UserData data = new UserData(UUID.randomUUID(), "CheatingHeavyArmor");
        data.setEnvironment(TestSnapshots.builder().jumpStrength(0.12).build());
        data.setLastPosition(0, 64.0, 0, true);
        data.setLastPosition(0, 64.01, 0, true);

        assertTrue(check.check(data).isFlag());
    }

    @Test
    void autoCriticalsSkipsWithoutSnapshot() {
        AutoCriticalsCheck check = new AutoCriticalsCheck();
        UserData data = new UserData(UUID.randomUUID(), "Unknown");
        data.setLastPosition(0, 64.0, 0, true);
        data.setLastPosition(0, 64.03, 0, true);

        assertFalse(check.check(data).isFlag());
    }
}
