package net.lovelace.vesuvio;

import net.lovelace.vesuvio.check.CheckResult;
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
        data.setEnvironment(plainGround());

        // Normal slab or stair step: deltaY = 0.5b
        assertFalse(check.check(data, 0.50, true).isFlag());

        // Normal jump has onGround = false
        assertFalse(check.check(data, 1.0, false).isFlag());

        // A full block ascended in one packet while still claiming to be on the ground is past
        // vanilla's 0.6 step height and cannot be a jump.
        CheckResult cheatResult = check.check(data, 1.0, true);
        assertTrue(cheatResult.isFlag());
        assertEquals("StepUp", cheatResult.checkName());
    }

    @Test
    void testStepUpCheckSkipsWithoutSnapshot() {
        StepUpCheck check = new StepUpCheck();
        UserData data = new UserData(UUID.randomUUID(), "StepTester");

        // No main-thread snapshot: the check cannot see whether the player is on a ladder or in a
        // boat, so it must abstain rather than flag.
        assertFalse(check.check(data, 1.0, true).isFlag());
    }

    @Test
    void testStepUpCheckExemptsClimbables() {
        StepUpCheck check = new StepUpCheck();
        UserData data = new UserData(UUID.randomUUID(), "StepTester");
        data.setEnvironment(nearLadder());

        assertFalse(check.check(data, 1.0, true).isFlag(),
                "a scaffolding/ladder column legitimately lifts a player past the step height");
    }

    private static net.lovelace.vesuvio.engine.EnvironmentSnapshot plainGround() {
        return TestSnapshots.ground(org.bukkit.Material.GRASS_BLOCK);
    }

    private static net.lovelace.vesuvio.engine.EnvironmentSnapshot nearLadder() {
        return TestSnapshots.builder().nearClimbable(true).build();
    }
}
