package net.lovelace.vesuvio;

import net.lovelace.vesuvio.check.movement.InvMoveCheck;
import net.lovelace.vesuvio.check.movement.PhaseCheck;
import net.lovelace.vesuvio.check.movement.VelocityCheck;
import net.lovelace.vesuvio.data.UserData;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Phase "buried", InvMove (packet close, RTT grace, inertia) and Velocity drops, 2026-11-11. */
class BuriedAndInvMoveTest {

    private static final double MIN_UNUSED = 0;

    private static TestSnapshots.Builder at(double x, boolean inside) {
        return TestSnapshots.builder().position(x, 64, 0).insideSolidBlock(inside);
    }

    @Test
    void aBlockThatAppearsOnTheStandingPlayerIsNotPhasingEvenWhileTheyDigOut() {
        PhaseCheck check = new PhaseCheck();
        UserData data = new UserData(UUID.randomUUID(), "t");
        data.setEnvironment(at(0.5, false).build());
        assertFalse(check.check(data, 0, 0).isFlag());
        int flags = 0;
        for (int i = 0; i < 120; i++) {
            // Same block cell, now inside: a reset mine / WorldEdit refilled the tunnel around them.
            data.setEnvironment(at(0.5 + (i % 2) * 0.1, true).build());
            if (check.check(data, 0.2, 0).isFlag()) flags++;
        }
        assertEquals(0, flags);
    }

    @Test
    void walkingIntoAWallIsStillPhasing() {
        PhaseCheck check = new PhaseCheck();
        UserData data = new UserData(UUID.randomUUID(), "t");
        data.setEnvironment(at(0.5, false).build());
        check.check(data, 0.2, 0);
        int flags = 0;
        for (int i = 0; i < 60; i++) {
            data.setEnvironment(at(1.5 + i * 0.2, true).build()); // a new cell on the first inside tick and onwards
            if (check.check(data, 0.2, 0).isFlag()) flags++;
        }
        assertTrue(flags >= 1);
    }

    @Test
    void afterLeavingTheBlockAFreshEntryIsJudgedAgain() {
        PhaseCheck check = new PhaseCheck();
        UserData data = new UserData(UUID.randomUUID(), "t");
        data.setEnvironment(at(0.5, false).build());
        check.check(data, 0, 0);
        data.setEnvironment(at(0.5, true).build());
        check.check(data, 0.2, 0); // buried
        data.setEnvironment(at(5.5, false).build());
        check.check(data, 0.2, 0); // out
        int flags = 0;
        for (int i = 0; i < 60; i++) {
            data.setEnvironment(at(6.5 + i * 0.2, true).build());
            if (check.check(data, 0.2, 0).isFlag()) flags++;
        }
        assertTrue(flags >= 1);
    }

    private static int invMoveFlags(InvMoveCheck check, UserData data, int ticks, double startSpeed, double decay, double rtt) {
        int flags = 0;
        double speed = startSpeed;
        for (int i = 0; i < ticks; i++) {
            data.setEnvironment(TestSnapshots.builder().container("CHEST").build());
            if (check.check(data, speed, 0, 0, rtt).isFlag()) flags++;
            speed *= decay;
        }
        return flags;
    }

    @Test
    void decayingInertiaWithAnOpenMenuIsNotEvidence() throws Exception {
        InvMoveCheck check = new InvMoveCheck();
        UserData data = new UserData(UUID.randomUUID(), "t");
        data.setContainerOpenSinceMillis(System.currentTimeMillis() - 5000L);
        // A sprint-jump's leftover speed decays by ~0.91 per tick: never flagged.
        assertEquals(0, invMoveFlags(check, data, 30, 0.45, 0.91, 40.0));
    }

    @Test
    void steadyMovementWithAnOpenMenuStillFlags() {
        InvMoveCheck check = new InvMoveCheck();
        UserData data = new UserData(UUID.randomUUID(), "t");
        data.setContainerOpenSinceMillis(System.currentTimeMillis() - 5000L);
        assertTrue(invMoveFlags(check, data, 30, 0.28, 1.0, 40.0) >= 1);
    }

    @Test
    void closingTheContainerByPacketStopsEvidenceBeforeTheSnapshotCatchesUp() {
        InvMoveCheck check = new InvMoveCheck();
        UserData data = new UserData(UUID.randomUUID(), "t");
        data.setContainerOpenSinceMillis(System.currentTimeMillis() - 5000L);
        data.setEnvironment(TestSnapshots.builder().container("CHEST").build());
        data.markContainerClosed(); // CLOSE_WINDOW arrived after the snapshot was captured
        for (int i = 0; i < 30; i++) {
            assertFalse(check.check(data, 0.28, 0, 0, 40.0).isFlag());
        }
    }

    @Test
    void highRoundTripTimeLengthensTheOpenGrace() {
        InvMoveCheck check = new InvMoveCheck();
        UserData data = new UserData(UUID.randomUUID(), "t");
        data.setContainerOpenSinceMillis(System.currentTimeMillis() - 700L);
        // 700 ms after opening: past the 400 ms floor, still inside 2 * 400 + 150 = 950 ms.
        assertEquals(0, invMoveFlags(check, data, 3, 0.28, 1.0, 400.0));
    }

    @Test
    void creativeNeverCountsAsAnOpenContainer() {
        // The service reports containerOpen=false for CREATIVE; here the equivalent snapshot has no container.
        InvMoveCheck check = new InvMoveCheck();
        UserData data = new UserData(UUID.randomUUID(), "t");
        data.setEnvironment(TestSnapshots.builder().build());
        for (int i = 0; i < 30; i++) assertFalse(check.check(data, 0.28, 0, 0, 40.0).isFlag());
    }
}
