package net.lovelace.vesuvio;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.check.movement.FlyCheck;
import net.lovelace.vesuvio.data.UserData;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Fly false-positive regressions of 2026-11-11: honey/ladder/water neighbours, unloaded chunk, one-off ticks. */
class FlyEvidenceTest {

    private static UserData airborne(TestSnapshots.Builder env) {
        UserData data = new UserData(UUID.randomUUID(), "t");
        data.setEnvironment(env.build());
        return data;
    }

    @Test
    void constantMinus0098InAnUnloadedChunkIsNotEvidence() {
        FlyCheck check = new FlyCheck();
        UserData data = airborne(TestSnapshots.builder());
        for (int i = 0; i < 60; i++) {
            CheckResult r = check.check(data, 0, -0.098, 0, false);
            assertFalse(r.isFlag(), "tick " + i + ": " + r.explanation());
        }
    }

    @Test
    void nearbyClimbableLiquidOrStuckBlocksExemptTheCheck() {
        for (TestSnapshots.Builder env : new TestSnapshots.Builder[]{
                TestSnapshots.builder().nearClimbable(true),
                TestSnapshots.builder().nearLiquid(true),
                TestSnapshots.builder().nearStuck(true)}) {
            FlyCheck check = new FlyCheck();
            UserData data = airborne(env);
            // Holding altitude would be a textbook hover flag anywhere else.
            for (int i = 0; i < 40; i++) {
                assertFalse(check.check(data, 0, 0.0, 0, false).isFlag());
            }
        }
    }

    @Test
    void aSingleOddTickDoesNotFlag() {
        FlyCheck check = new FlyCheck();
        UserData data = airborne(TestSnapshots.builder());
        double v = 0.42;
        boolean flagged = false;
        for (int i = 0; i < 10; i++) {
            double dy = i == 5 ? v + 0.3 : v;
            flagged |= check.check(data, 0, dy, 0, false).isFlag();
            v = (v - 0.08) * 0.98;
        }
        assertFalse(flagged);
    }

    @Test
    void sustainedAscensionStillFlags() {
        FlyCheck check = new FlyCheck();
        UserData data = airborne(TestSnapshots.builder());
        boolean flagged = false;
        for (int i = 0; i < 40 && !flagged; i++) {
            flagged = check.check(data, 0, 0.5, 0, false).isFlag();
        }
        assertTrue(flagged);
    }
}
