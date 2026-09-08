package net.lovelace.vesuvio;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.check.movement.FlyCheck;
import net.lovelace.vesuvio.check.movement.NoFallCheck;
import net.lovelace.vesuvio.check.movement.SpeedCheck;
import net.lovelace.vesuvio.check.movement.TimerCheck;
import net.lovelace.vesuvio.data.UserData;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MovementCheckTest {

    @Test
    void testTimerCheckUnderNormalRate() {
        TimerCheck timerCheck = new TimerCheck();
        UserData data = new UserData(UUID.randomUUID(), "TestPlayer");

        // 20 packets/sec is vanilla rate
        CheckResult result = CheckResult.pass("Timer");
        for (int i = 0; i < 20; i++) {
            result = timerCheck.check(null, data);
        }
        assertFalse(result.isFlag());
    }

    @Test
    void testTimerCheckAccelerated() {
        TimerCheck timerCheck = new TimerCheck();
        UserData data = new UserData(UUID.randomUUID(), "TestPlayer");

        // Simulate 1 second passed
        data.setTimerWindowStartNanos(System.nanoTime() - 1_100_000_000L);
        data.setTimerPacketCount(35); // 35 packets in 1 second (Cheat Timer)

        CheckResult result = timerCheck.check(null, data);
        assertTrue(result.isFlag());
        assertEquals("Timer", result.checkName());
    }

    @Test
    void testFlyStreakTracking() {
        UserData data = new UserData(UUID.randomUUID(), "TestPlayer");
        assertEquals(0, data.getFlyStreak());
        assertEquals(0, data.getAirTicks());

        data.incrementAirTicks();
        data.incrementAirTicks();
        assertEquals(2, data.getAirTicks());

        data.incrementFlyStreak();
        assertEquals(1, data.getFlyStreak());

        data.resetAirTicks();
        assertEquals(0, data.getAirTicks());
    }
}
