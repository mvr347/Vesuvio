package net.lovelace.vesuvio;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.check.statistical.SnapAimCheck;
import net.lovelace.vesuvio.data.UserData;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for the mechanical "snap there, snap back" rotation-jerk detector.
 *
 * Reported from a live test: a silent-aim killaura's "Auto Rotate" sets the reported yaw/pitch to
 * face the target for exactly the attack, has no effect on the cheater's own client render (so
 * their screen never turns), and the victim's client smooths entity rotation over several frames
 * and never visibly shows the snap either - but the server's raw, un-smoothed rotation stream
 * still carries it, which is what this check reads.
 */
class SnapAimCheckTest {

    private static final long MS = 1_000_000L;

    @Test
    void snapThenEqualAndOppositeRevertIsFlagged() {
        SnapAimCheck check = new SnapAimCheck(null);
        UserData data = new UserData(UUID.randomUUID(), "Snapper");

        long now = 0L;
        CheckResult flagged = null;
        // Repeated a few times: the accumulator (not a bare streak) needs several occurrences
        // before it crosses its threshold, matching how every other sub-check in this codebase
        // avoids firing on a single ambiguous sample.
        for (int i = 0; i < 6 && flagged == null; i++) {
            // The snap: camera whips 90 degrees onto the target for the attack tick.
            CheckResult first = check.check(data, 90.0f, now);
            if (first.isFlag()) flagged = first;
            now += 50 * MS;
            // The revert: back the other way by almost the same amount, one tick later.
            CheckResult second = check.check(data, -88.0f, now);
            if (second.isFlag()) flagged = second;
            now += 500 * MS; // clear separation before the next pair
        }

        assertTrue(flagged != null, "a rotation reversed almost exactly within one tick must eventually be flagged");
        assertTrue(flagged.confidence() >= 0.85);
    }

    @Test
    void aSingleBigTurnWithNoRevertIsNotFlagged() {
        SnapAimCheck check = new SnapAimCheck(null);
        UserData data = new UserData(UUID.randomUUID(), "Flicker");

        long now = 0L;
        for (int i = 0; i < 20; i++) {
            // A real 180 flick: one big turn, then normal small tracking adjustments - never a
            // comparably large turn back the other way.
            float delta = (i == 0) ? 150.0f : 2.0f;
            CheckResult result = check.check(data, delta, now);
            assertFalse(result.isFlag(), "a turn that is never reversed is not a snap-back");
            now += 50 * MS;
        }
    }

    @Test
    void aSlowHumanTurnAndReturnSpreadOverManyTicksIsNotFlagged() {
        SnapAimCheck check = new SnapAimCheck(null);
        UserData data = new UserData(UUID.randomUUID(), "Human");

        long now = 0L;
        // A human checking their flank and turning back: the same total angle, but spread thin
        // enough per tick that no single pair of consecutive packets looks like a snap.
        float[] deltas = {20f, 20f, 20f, 20f, -18f, -18f, -18f, -18f};
        for (float d : deltas) {
            for (int rep = 0; rep < 5; rep++) {
                CheckResult result = check.check(data, d, now);
                assertFalse(result.isFlag(), "gradual human rotation must never be judged a snap-back");
                now += 50 * MS;
            }
        }
    }

    @Test
    void twoLargeTurnsInTheSameDirectionAreNotAReversal() {
        SnapAimCheck check = new SnapAimCheck(null);
        UserData data = new UserData(UUID.randomUUID(), "SameWay");

        long now = 0L;
        for (int i = 0; i < 10; i++) {
            // Two big turns, same sign - continuing to spin the same way is not a reversal.
            CheckResult result = check.check(data, 60.0f, now);
            assertFalse(result.isFlag());
            now += 50 * MS;
        }
    }

    @Test
    void reversalSeparatedByTooLongAGapIsNotASnap() {
        SnapAimCheck check = new SnapAimCheck(null);
        UserData data = new UserData(UUID.randomUUID(), "SlowRevert");

        long now = 0L;
        CheckResult flagged = null;
        for (int i = 0; i < 6 && flagged == null; i++) {
            CheckResult first = check.check(data, 90.0f, now);
            if (first.isFlag()) flagged = first;
            now += 2000 * MS; // 2 seconds - far outside the check's window
            CheckResult second = check.check(data, -88.0f, now);
            if (second.isFlag()) flagged = second;
            now += 2000 * MS;
        }

        assertFalse(flagged != null, "a reversal that arrives seconds later is not a mechanical snap-back");
    }
}
