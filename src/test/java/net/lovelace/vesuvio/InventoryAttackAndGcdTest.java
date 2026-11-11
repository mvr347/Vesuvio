package net.lovelace.vesuvio;

import net.lovelace.vesuvio.check.protocol.BadPacketsCheck;
import net.lovelace.vesuvio.check.statistical.GCDAimCheck;
import net.lovelace.vesuvio.data.UserData;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class InventoryAttackAndGcdTest {

    private static UserData withContainer(long openMsAgo) {
        UserData data = new UserData(UUID.randomUUID(), "t");
        data.setEnvironment(TestSnapshots.builder().container("CHEST")
                .containerOpenSince(System.currentTimeMillis() - openMsAgo).build());
        return data;
    }

    @Test
    void attackRightAfterOpeningIsWithinTheGrace() {
        assertFalse(new BadPacketsCheck().checkInventoryAttack(withContainer(100), 40.0).isFlag());
    }

    @Test
    void attackWithAContainerOpenForSecondsFlags() {
        assertTrue(new BadPacketsCheck().checkInventoryAttack(withContainer(3000), 40.0).isFlag());
    }

    @Test
    void closeAfterTheSnapshotCancelsTheFlag() {
        UserData data = withContainer(3000);
        data.markContainerClosed();
        assertFalse(new BadPacketsCheck().checkInventoryAttack(data, 40.0).isFlag());
    }

    @Test
    void highRttExtendsTheGrace() {
        // 700 ms open, RTT 400 ms: grace is 2 * 400 + 150 = 950 ms.
        assertFalse(new BadPacketsCheck().checkInventoryAttack(withContainer(700), 400.0).isFlag());
    }

    @Test
    void noContainerNeverFlags() {
        UserData data = new UserData(UUID.randomUUID(), "t");
        data.setEnvironment(TestSnapshots.builder().build());
        assertFalse(new BadPacketsCheck().checkInventoryAttack(data, 40.0).isFlag());
    }

    @Test
    void gcdIsSkippedWhenYawHasLostFloatPrecision() {
        GCDAimCheck check = new GCDAimCheck();
        UserData data = new UserData(UUID.randomUUID(), "t");
        data.recordCombatAction();
        data.setLastYaw(40000f);
        for (int i = 0; i < 20; i++) assertFalse(check.check(data, 1.0f, 1.0f, 0.3f).isFlag());
    }

    @Test
    void gcdToleranceNeverDropsBelowHalfAThousandthOfADegree() {
        // Exactly divisible deltas are caught by the plain tolerance as before.
        assertEquals(0.5, GCDAimCheck.calculateGCD(1.5, 0.5, 0.0005), 1e-9);
        assertTrue(GCDAimCheck.calculateGCD(1.0, 0.3333, 0.0005) >= 0.0);
    }
}
