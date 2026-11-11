package net.lovelace.vesuvio;

import net.lovelace.vesuvio.data.UserData;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The three state objects behind UserData must still behave as one: cross-state operations go through UserData. */
class UserDataStateSplitTest {

    @Test
    void teleportInvalidatesMovementCombatAndEnvironmentTogether() {
        UserData d = new UserData(UUID.randomUUID(), "t");
        d.setEnvironment(TestSnapshots.builder().build());
        d.setInitialRotation(true);
        d.setLastSwingNanos(123L);
        d.setLastPosition(1, 64, 1, true);
        d.addVl(5.0);

        d.expectTeleport(7); // goes through the kinematic state, which must reach UserData#recordTeleport

        assertFalse(d.hasInitialRotation(), "combat rotation baseline reset");
        assertEquals(0L, d.getLastSwingNanos(), "swing history cleared");
        assertFalse(d.hasLastPosition(), "movement tracking reset");
        assertFalse(d.getEnvironment().valid(), "environment snapshot dropped");
        assertEquals(5.0, d.getVl(), 1e-9, "violations are never cleared by a teleport");
        assertTrue(d.hasRecentTeleport());
    }

    @Test
    void scoreAndSensitivityStillDelegate() {
        UserData d = new UserData(UUID.randomUUID(), "t");
        d.adjustRisk(20.0);
        d.adjustTrust(-10.0);
        assertEquals(30.0, d.getRiskIndex(), 1e-9);
        assertEquals(40.0, d.getTrustScore(), 1e-9);
        assertTrue(d.getSensitivityMultiplier() > 1.0);
        assertTrue(d.tryLatchPunished());
        assertFalse(d.tryLatchPunished());
    }

    @Test
    void movementResetAlsoClearsTheReachAccumulators() {
        UserData d = new UserData(UUID.randomUUID(), "t");
        d.reachEvidence().reward(1.0, System.nanoTime(), 4000);
        d.hitboxEvidence().reward(1.0, System.nanoTime(), 3000);
        d.resetMovementTracking();
        assertEquals(0, d.reachEvidence().events());
        assertEquals(0, d.hitboxEvidence().events());
    }
}
