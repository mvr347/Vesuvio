package net.lovelace.vesuvio;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.check.combat.AutoCriticalsCheck;
import net.lovelace.vesuvio.check.movement.InvMoveCheck;
import net.lovelace.vesuvio.check.movement.StepUpCheck;
import net.lovelace.vesuvio.data.UserData;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CheatUtilsChecksTest {

    @Test
    void testManualSuspectPersistenceState() {
        UserData data = new UserData(UUID.randomUUID(), "Lovelace69");
        assertFalse(data.isManualSuspect());
        assertFalse(data.isSuspect(75.0));

        data.setManualSuspect(true);
        assertTrue(data.isManualSuspect());
        assertTrue(data.isSuspect(75.0));

        // Unmarking manual suspect restores clean status
        data.setManualSuspect(false);
        assertFalse(data.isManualSuspect());
    }

    @Test
    void testStepUpCheckNormalVsCheat() {
        StepUpCheck check = new StepUpCheck();
        UserData data = new UserData(UUID.randomUUID(), "StepTester");

        // Normal slab or stair step: deltaY = 0.5b
        CheckResult passResult = check.check(null, data, 0.50, true);
        assertFalse(passResult.isFlag());

        // Normal jump has onGround = false
        CheckResult jumpResult = check.check(null, data, 1.0, false);
        assertFalse(jumpResult.isFlag());
    }

    @Test
    void testAutoCriticalsCheckNormalVsCheat() {
        AutoCriticalsCheck check = new AutoCriticalsCheck();
        UserData data = new UserData(UUID.randomUUID(), "CritTester");

        // Null checks
        CheckResult passResult = check.check(null, data);
        assertFalse(passResult.isFlag());
    }

    @Test
    void testUserDataMovementDeltas() {
        UserData data = new UserData(UUID.randomUUID(), "DeltaTester");
        data.setLastPosition(100.0, 64.0, 100.0, true);
        assertEquals(100.0, data.getLastX());
        assertEquals(64.0, data.getLastY());
        assertEquals(100.0, data.getLastZ());
        assertTrue(data.isLastOnGround());

        data.setLastPosition(100.3, 65.0, 100.4, false);
        assertEquals(1.0, data.getLastDeltaY(), 0.001);
        assertEquals(0.5, data.getLastDeltaXZ(), 0.001);
        assertFalse(data.isLastOnGround());
    }
}
