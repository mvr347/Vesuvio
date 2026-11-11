package net.lovelace.vesuvio;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.check.movement.StepUpCheck;
import net.lovelace.vesuvio.check.movement.TimerCheck;
import net.lovelace.vesuvio.data.UserData;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TimerFreezeAndStepUpTest {

    private static final long MS = 1_000_000L;

    private static long steady(TimerCheck check, UserData data, long now, int packets) {
        for (int i = 0; i < packets; i++) {
            now += 50 * MS;
            assertFalse(check.check(data, 1.0, now, 60.0).isFlag());
        }
        return now;
    }

    @Test
    void clientFreezeFollowedByTenCatchUpTicksDoesNotFlag() {
        TimerCheck check = new TimerCheck();
        UserData data = new UserData(UUID.randomUUID(), "t");
        long now = steady(check, data, 0, 40);
        // Main loop frozen for 1.5 s (netty keeps answering transactions, so the last ack was sent a moment ago),
        // then vanilla runs up to 10 ticks in one frame.
        now += 1500 * MS;
        assertFalse(check.check(data, 1.0, now, 60.0).isFlag());
        for (int i = 0; i < 10; i++) {
            now += 1 * MS;
            CheckResult r = check.check(data, 1.0, now, 60.0);
            assertFalse(r.isFlag(), "catch-up tick " + i + ": " + r.explanation());
        }
        // ...and normal play afterwards.
        steady(check, data, now, 40);
    }

    @Test
    void networkStallWithBunchedPacketsDoesNotFlag() {
        TimerCheck check = new TimerCheck();
        UserData data = new UserData(UUID.randomUUID(), "t");
        long now = steady(check, data, 0, 40);
        // 700 ms in which nothing arrived: the last confirmed transaction was sent 800 ms ago. Then 14 packets at once.
        now += 700 * MS;
        for (int i = 0; i < 14; i++) {
            now += 1 * MS;
            double ackAge = i == 0 ? 800.0 : 800.0 + i;
            CheckResult r = check.check(data, 1.0, now, ackAge);
            assertFalse(r.isFlag(), "bunched packet " + i + ": " + r.explanation());
        }
    }

    @Test
    void realTimerStillFlagsAfterAFreeze() {
        TimerCheck check = new TimerCheck();
        UserData data = new UserData(UUID.randomUUID(), "t");
        long now = steady(check, data, 0, 40);
        now += 1500 * MS;
        check.check(data, 1.0, now, 60.0);
        boolean flagged = false;
        for (int i = 0; i < 80 && !flagged; i++) {
            now += 25 * MS; // 2x
            flagged = check.check(data, 1.0, now, 60.0).isFlag();
        }
        assertTrue(flagged);
    }

    @Test
    void stepUpNeedsTwoEventsAndHonoursTheStepHeightAttribute() {
        StepUpCheck check = new StepUpCheck();
        UserData data = new UserData(UUID.randomUUID(), "t");
        data.setEnvironment(TestSnapshots.builder().stepHeight(1.0).build());
        for (int i = 0; i < 5; i++) {
            assertFalse(check.check(data, 0.9, true).isFlag(), "within the raised step height");
        }
        data.setEnvironment(TestSnapshots.builder().build());
        assertFalse(check.check(data, 1.0, true).isFlag());
        assertFalse(check.check(data, 0.0, true).isFlag());
        assertTrue(check.check(data, 1.0, true).isFlag(), "second event flags");
    }
}
