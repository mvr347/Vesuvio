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
        return snapshot(false);
    }

    private static net.lovelace.vesuvio.engine.EnvironmentSnapshot nearLadder() {
        return snapshot(true);
    }

    private static net.lovelace.vesuvio.engine.EnvironmentSnapshot snapshot(boolean nearClimbable) {
        return new net.lovelace.vesuvio.engine.EnvironmentSnapshot(
                System.currentTimeMillis(), true,
                0, 64, 0,
                false, false, false, false, false, true, false, false, false, 0f,
                false, false, false, false, nearClimbable, true, org.bukkit.Material.GRASS_BLOCK,
                false, false, false, false, false, -1,
                0, 0,
                false, "CRAFTING");
    }

    @Test
    void testAutoCriticalsCheckNormalVsCheat() {
        AutoCriticalsCheck check = new AutoCriticalsCheck();
        UserData data = new UserData(UUID.randomUUID(), "CritTester");
        data.setEnvironment(plainGround());

        // A real jump: deltaY well above the micro-hop band.
        data.setLastPosition(0, 64.0, 0, true);
        data.setLastPosition(0, 64.42, 0, false);
        assertFalse(check.check(data).isFlag());

        // A packet micro-hop: a few hundredths of a block, no fall distance, still "on the ground".
        UserData cheater = new UserData(UUID.randomUUID(), "CritCheater");
        cheater.setEnvironment(plainGround());
        cheater.setLastPosition(0, 64.0, 0, true);
        cheater.setLastPosition(0, 64.03, 0, true);
        CheckResult cheatResult = check.check(cheater);
        assertTrue(cheatResult.isFlag());
        assertEquals("AutoCriticals", cheatResult.checkName());
    }

    @Test
    void testAutoCriticalsSkipsWithoutSnapshot() {
        AutoCriticalsCheck check = new AutoCriticalsCheck();
        UserData data = new UserData(UUID.randomUUID(), "CritTester");
        data.setLastPosition(0, 64.0, 0, true);
        data.setLastPosition(0, 64.03, 0, true);

        // No snapshot: the check cannot rule out a boat, water or an elytra, so it abstains.
        assertFalse(check.check(data).isFlag());
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
