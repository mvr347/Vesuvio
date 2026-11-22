package net.lovelace.vesuvio;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.check.movement.InvMoveCheck;
import net.lovelace.vesuvio.check.movement.TimerCheck;
import net.lovelace.vesuvio.data.UserData;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TimerAndInvMoveFalsePositiveTest {

    private static final long MS = 1_000_000L;

    @Test
    void postResetCatchUpBurstDoesNotFlagTimer() {
        TimerCheck check = new TimerCheck();
        UserData data = new UserData(UUID.randomUUID(), "Miha");
        data.resetTimerBalance();

        long now = 1_000_000L * MS;
        // First movement packet right after a teleport/reset, followed by 10 vanilla catch-up ticks (~4 ms apart)
        assertFalse(check.check(data, 1.0, now, 60.0).isFlag());
        for (int i = 0; i < 10; i++) {
            now += 4 * MS;
            CheckResult res = check.check(data, 1.0, now, 60.0);
            assertFalse(res.isFlag(), "Post-reset catch-up tick " + i + " must not flag: " + res.explanation());
        }
    }

    @Test
    void sustainedTimerStillFlagsAfterResetAndRelaxation() {
        TimerCheck check = new TimerCheck();
        UserData data = new UserData(UUID.randomUUID(), "Cheater");
        data.resetTimerBalance();

        long now = 1_000_000L * MS;
        assertFalse(check.check(data, 1.0, now, 60.0).isFlag());
        // Play normally for a bit so gentle relaxation moves balance toward 0
        for (int i = 0; i < 150; i++) {
            now += 50 * MS;
            assertFalse(check.check(data, 1.0, now, 60.0).isFlag());
        }
        assertEquals(0.0, data.getTimerBalanceMs(), 1e-6);

        boolean flagged = false;
        for (int i = 0; i < 40 && !flagged; i++) {
            now += 25 * MS; // 2x timer
            flagged = check.check(data, 1.0, now, 60.0).isFlag();
        }
        assertTrue(flagged, "Sustained 2x timer must still be caught");
    }

    @Test
    void containerClosedShortlyBeforeStaleSnapshotDoesNotFlagInvMove() throws Exception {
        InvMoveCheck check = new InvMoveCheck();
        UserData data = new UserData(UUID.randomUUID(), "0nRop_w");
        data.setContainerOpenSinceMillis(System.currentTimeMillis() - 3000L);
        data.markContainerClosed();

        // Snapshot captured a few ms after CLOSE_WINDOW arrived on Netty, before main thread processed it
        Thread.sleep(20);
        data.setEnvironment(TestSnapshots.builder().container("FURNACE").build());

        for (int i = 0; i < 10; i++) {
            assertFalse(check.check(data, 0.29, 0.0, 0.0, 40.0).isFlag(),
                    "Must not flag on stale snapshot right after CLOSE_WINDOW");
        }
    }

    @Test
    void openingNewContainerAfterClosingStillFlagsInvMove() {
        InvMoveCheck check = new InvMoveCheck();
        UserData data = new UserData(UUID.randomUUID(), "Cheater");
        data.markContainerClosed();
        data.markContainerOpened(0L);
        data.setContainerOpenSinceMillis(System.currentTimeMillis() - 3000L);
        data.setEnvironment(TestSnapshots.builder().container("CHEST").build());

        boolean flagged = false;
        for (int i = 0; i < 10 && !flagged; i++) {
            flagged = check.check(data, 0.29, 0.0, 0.0, 40.0).isFlag();
        }
        assertTrue(flagged, "Opening a new container after closing must still be checked");
    }
}
