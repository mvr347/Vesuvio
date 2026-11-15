package net.lovelace.vesuvio;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import net.lovelace.vesuvio.check.movement.SpeedCheck;
import net.lovelace.vesuvio.data.UserData;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class SpeedBurstEvidenceTest {
    private UserData data() {
        var data = new UserData(UUID.randomUUID(), "Vanilla");
        data.setEnvironment(TestSnapshots.builder().blockBelow(Material.GRASS_BLOCK).movementSpeed(0.13).build());
        return data;
    }
    private void tickGround(UserData data) { data.resetAirTicks(); }

    @Test void isolatedLargeDisplacementAndReturnToNormalDoNotFlag() {
        for (double burst : new double[]{1.079, 2.228, 3.536}) {
            var data = data();
            var check = new SpeedCheck();
            for (int i = 0; i < 40; i++) {
                tickGround(data);
                assertFalse(check.check(data, 0.28, 0, 50, 1).isFlag());
            }
            tickGround(data);
            assertFalse(check.check(data, burst, 0, 50, 1).isFlag(), "single displacement=" + burst);
            for (int i = 0; i < 40; i++) {
                tickGround(data);
                assertFalse(check.check(data, 0.28, 0, 50, 1).isFlag(), "recovery after=" + burst);
            }
        }
    }

    @Test void sustainedFastMovementStillFlagsAfterRepeatedObservations() {
        var data = data();
        var check = new SpeedCheck();
        boolean flagged = false;
        for (int i = 0; i < 100; i++) {
            tickGround(data);
            var result = check.check(data, 2.228, 0, 50, 1);
            if (i < 2) assertFalse(result.isFlag(), "one or two packets cannot establish persistent speed");
            if (result.isFlag()) { flagged = true; break; }
        }
        assertTrue(flagged);
    }
    @Test void discontinuitiesClearUnconfirmedEvidence() {
        for (boolean stale : new boolean[]{false, true}) {
            var data = data();
            var check = new SpeedCheck();
            check.check(data, 2.228, 0, 50, 1);
            assertTrue(data.getSpeedPredictionDebt() > 0);
            assertTrue(data.getSpeedEvidence().peek() > 0);
            if (stale) data.setEnvironment(net.lovelace.vesuvio.engine.EnvironmentSnapshot.EMPTY);
            assertFalse(check.check(data, 0.28, 0, stale ? 50 : 900, 1).isFlag());
            assertEquals(0, data.getSpeedPredictionDebt());
            assertEquals(0, data.getSpeedEvidence().peek());
        }
    }

    @Test void occasionalCleanPacketsDoNotHideSustainedSpeed() {
        var data = data();
        var check = new SpeedCheck();
        boolean flagged = false;
        for (int i = 0; i < 200; i++) {
            tickGround(data);
            if (check.check(data, i % 5 == 4 ? 0.28 : 0.57, 0, 50, 1).isFlag()) {
                flagged = true;
                break;
            }
        }
        assertTrue(flagged);
    }

}
