package net.lovelace.vesuvio;

import static org.junit.jupiter.api.Assertions.*;

import net.lovelace.vesuvio.check.movement.InvMoveCheck;
import net.lovelace.vesuvio.check.movement.PhaseCheck;
import net.lovelace.vesuvio.data.UserData;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.UUID;

/**
 * Regression coverage for the false Phase / InvMove flags seen on the live server: a burst of Phase flags for a player
 * working next to blocks that changed, and InvMove flags with an anvil open.
 */
class PhaseInvMoveFalsePositiveTest {

    private static UserData insideBlock(String name) {
        UserData data = new UserData(UUID.randomUUID(), name);
        data.setEnvironment(TestSnapshots.builder().blockBelow(Material.STONE).insideSolidBlock(true).build());
        return data;
    }

    private static int phaseFlags(PhaseCheck check, UserData data, int ticks, double speed) {
        int flags = 0;
        for (int i = 0; i < ticks; i++) {
            // keep the snapshot fresh like the capture loop does
            data.setEnvironment(TestSnapshots.builder().blockBelow(Material.STONE).insideSolidBlock(true).build());
            if (check.check(data, speed, 0).isFlag()) flags++;
        }
        return flags;
    }

    @Test
    void oneLongEpisodeIsOneFlagNotABurst() {
        PhaseCheck check = new PhaseCheck();
        UserData data = insideBlock("Stuck");
        assertEquals(1, phaseFlags(check, data, 200, 0.20),
                "200 ticks inside a block used to flag every six ticks, 4 VL each");
    }

    @Test
    void aBlockChangeNearThePlayerSilencesPhaseForAMoment() {
        PhaseCheck check = new PhaseCheck();
        UserData data = insideBlock("Digger");
        data.markNearbyBlockChange(System.currentTimeMillis());
        assertEquals(0, phaseFlags(check, data, 30, 0.20),
                "the inside reading may come from a scan taken before the block changed");
    }

    @Test
    void anOldBlockChangeNoLongerProtects() {
        PhaseCheck check = new PhaseCheck();
        UserData data = insideBlock("Phaser");
        data.markNearbyBlockChange(System.currentTimeMillis() - 10_000L);
        assertTrue(phaseFlags(check, data, 30, 0.20) >= 1, "real walking through a wall is still caught");
    }

    @Test
    void shortWalksInsideABlockSeparatedByLongPausesNeverAddUp() {
        PhaseCheck check = new PhaseCheck();
        UserData data = insideBlock("Ejected");
        // three ticks of drifting out, ten ticks standing: each pause refunds more than the walk added
        int flags = 0;
        for (int cycle = 0; cycle < 20; cycle++) {
            for (int i = 0; i < 13; i++) {
                data.setEnvironment(TestSnapshots.builder().blockBelow(Material.STONE).insideSolidBlock(true).build());
                if (check.check(data, i < 3 ? 0.20 : 0.0, 0).isFlag()) flags++;
            }
        }
        assertEquals(0, flags);
    }

    // ------------------------------------------------------------------

    private static UserData withContainer(String name, long openSinceMillis, Material below, boolean water) {
        UserData data = new UserData(UUID.randomUUID(), name);
        data.setContainerOpenSinceMillis(openSinceMillis);
        data.setEnvironment(TestSnapshots.builder().blockBelow(below).inWater(water).container("ANVIL").build());
        return data;
    }

    private static int invMoveFlags(InvMoveCheck check, UserData data, int ticks, double speed) {
        int flags = 0;
        for (int i = 0; i < ticks; i++) {
            if (check.check(data, speed, 0, 0).isFlag()) flags++;
        }
        return flags;
    }

    @Test
    void walkingAwayWithAnAnvilOpenIsStillCaught() {
        InvMoveCheck check = new InvMoveCheck();
        UserData data = withContainer("Cheater", System.currentTimeMillis() - 5_000L, Material.STONE, false);
        assertTrue(invMoveFlags(check, data, 10, 0.25) >= 1);
    }

    @Test
    void theSprintFlagAloneIsNotMovement() {
        InvMoveCheck check = new InvMoveCheck();
        UserData data = withContainer("Stopped", System.currentTimeMillis() - 5_000L, Material.STONE, false);
        // the default test snapshot is sprinting; the client stopped, so no distance is covered
        assertEquals(0, invMoveFlags(check, data, 100, 0.0));
    }

    @Test
    void momentumRightAfterOpeningIsNotMovingWithTheMenu() {
        InvMoveCheck check = new InvMoveCheck();
        UserData data = withContainer("Opened", 0L, Material.STONE, false);
        // the first call only notes when it was opened; the next ones are inside the grace window
        assertEquals(0, invMoveFlags(check, data, 5, 0.25));
        assertTrue(data.getContainerOpenSinceMillis() > 0L);
    }

    @Test
    void currentsAndIceAreNotThePlayerMoving() {
        InvMoveCheck check = new InvMoveCheck();
        long open = System.currentTimeMillis() - 5_000L;
        assertEquals(0, invMoveFlags(check, withContainer("Swimmer", open, Material.STONE, true), 20, 0.25));
        assertEquals(0, invMoveFlags(check, withContainer("Slider", open, Material.BLUE_ICE, false), 20, 0.25));
    }
}
