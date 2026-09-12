package net.lovelace.vesuvio;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.check.movement.BlinkCheck;
import net.lovelace.vesuvio.check.movement.PhaseCheck;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.engine.EnvironmentSnapshot;
import net.lovelace.vesuvio.engine.TransactionManager;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage for the two checks added after comparing our detection surface against the hack
 * taxonomy of a long-running commercial anticheat.
 */
class PhaseBlinkCheckTest {

    private static final long MS = 1_000_000L;

    // ------------------------------------------------------------------
    // Phase / Clip
    // ------------------------------------------------------------------

    @Test
    void phaseIgnoresPlayerInOpenAir() {
        PhaseCheck check = new PhaseCheck();
        UserData data = new UserData(UUID.randomUUID(), "Walker");
        data.setEnvironment(snapshot(false));

        for (int i = 0; i < 50; i++) {
            assertFalse(check.check(data, 0.28, 0).isFlag());
        }
    }

    @Test
    void phaseDetectsTravellingInsideSolidBlock() {
        PhaseCheck check = new PhaseCheck();
        UserData data = new UserData(UUID.randomUUID(), "Phaser");
        data.setEnvironment(snapshot(true));

        boolean flagged = false;
        for (int i = 0; i < 20 && !flagged; i++) {
            CheckResult result = check.check(data, 0.20, 0);
            if (result.isFlag()) {
                flagged = true;
                assertEquals("Phase", result.checkName());
            }
        }
        assertTrue(flagged, "walking through a wall must be caught");
    }

    @Test
    void phaseIgnoresPlayerStuckButNotMoving() {
        PhaseCheck check = new PhaseCheck();
        UserData data = new UserData(UUID.randomUUID(), "Trapped");
        data.setEnvironment(snapshot(true));

        // A block placed into someone's space, or a piston push: they are inside geometry but are
        // not travelling through it, and vanilla will eject them.
        for (int i = 0; i < 60; i++) {
            assertFalse(check.check(data, 0.0, 0).isFlag(),
                    "being stuck in a block is not phasing");
        }
    }

    @Test
    void phaseNeedsSustainedPresence() {
        PhaseCheck check = new PhaseCheck();
        UserData data = new UserData(UUID.randomUUID(), "Clipper");
        data.setEnvironment(snapshot(true));

        // A couple of ticks inside a block is what a chunk load or a teleport looks like.
        for (int i = 0; i < 3; i++) {
            assertFalse(check.check(data, 0.20, 0).isFlag());
        }
    }

    @Test
    void phaseSkipsWithoutSnapshot() {
        PhaseCheck check = new PhaseCheck();
        UserData data = new UserData(UUID.randomUUID(), "Unknown");
        for (int i = 0; i < 30; i++) {
            assertFalse(check.check(data, 0.20, 0).isFlag());
        }
    }

    // ------------------------------------------------------------------
    // Blink / lag switch
    // ------------------------------------------------------------------

    @Test
    void blinkIgnoresNormalPacketFlow() {
        BlinkCheck check = new BlinkCheck();
        TransactionManager transactions = new TransactionManager();
        UUID uuid = UUID.randomUUID();
        UserData data = new UserData(uuid, "Normal");
        data.setEnvironment(snapshot(false));

        long now = 0L;
        for (int i = 0; i < 200; i++) {
            now += 50 * MS;
            assertFalse(check.check(uuid, data, transactions, now).isFlag());
        }
    }

    @Test
    void blinkIgnoresIdlePlayerSendingFlyingPackets() {
        BlinkCheck check = new BlinkCheck();
        TransactionManager transactions = new TransactionManager();
        UUID uuid = UUID.randomUUID();
        UserData data = new UserData(uuid, "Idle");
        data.setEnvironment(snapshot(false));

        // A player who is perfectly stationary (no position delta, no camera movement, no
        // on-ground change) sends nothing at all under vanilla's positionReminder logic until the
        // 20-tick (~1000ms) counter rolls over. That real, healthy-connection gap must not read as
        // a blink.
        long now = 0L;
        for (int i = 0; i < 200; i++) {
            now += 1000 * MS;
            assertFalse(check.check(uuid, data, transactions, now).isFlag(),
                    "an idle player is not blinking");
        }
    }

    @Test
    void blinkIgnoresGenuineNetworkStall() {
        BlinkCheck check = new BlinkCheck();
        UUID uuid = UUID.randomUUID();
        // A stall shows up as a transaction round-trip on the order of the outage itself.
        TransactionManager transactions = new StubTransactions(2000.0, 0.0);
        UserData data = new UserData(uuid, "Lagging");
        data.setEnvironment(snapshot(false));

        long now = 0L;
        for (int i = 0; i < 10; i++) {
            now += 50 * MS;
            check.check(uuid, data, transactions, now);
        }
        // Two second freeze, with the connection genuinely down through it.
        for (int i = 0; i < 5; i++) {
            now += 2000 * MS;
            assertFalse(check.check(uuid, data, transactions, now).isFlag(),
                    "a real network stall must not be read as a lag switch");
        }
    }

    @Test
    void blinkDetectsWithheldMovementOnHealthyConnection() {
        BlinkCheck check = new BlinkCheck();
        UUID uuid = UUID.randomUUID();
        // The signature: movement stops, but transactions keep coming back at a normal ping.
        TransactionManager transactions = new StubTransactions(45.0, 0.0);
        UserData data = new UserData(uuid, "Blinker");
        data.setEnvironment(snapshot(false));

        long now = 0L;
        for (int i = 0; i < 10; i++) {
            now += 50 * MS;
            check.check(uuid, data, transactions, now);
        }

        boolean flagged = false;
        for (int i = 0; i < 5 && !flagged; i++) {
            now += 1800 * MS; // movement silence, well past vanilla's ~1000ms idle reminder gap
            CheckResult result = check.check(uuid, data, transactions, now);
            if (result.isFlag()) {
                flagged = true;
                assertEquals("Blink", result.checkName());
            }
        }
        assertTrue(flagged, "withheld movement on a healthy connection must be caught");
    }

    @Test
    void blinkCountsOccurrencesAcrossNormalPlay() {
        BlinkCheck check = new BlinkCheck();
        UUID uuid = UUID.randomUUID();
        TransactionManager transactions = new StubTransactions(45.0, 0.0);
        UserData data = new UserData(uuid, "Blinker");
        data.setEnvironment(snapshot(false));

        long now = 0L;
        boolean flagged = false;

        // Two blinks with a full ten seconds of ordinary play between them - which is how the
        // cheat is actually used. An earlier version decayed the count on every normal packet, so
        // the run of clean ticks below reset it and the threshold was unreachable in practice.
        for (int blink = 0; blink < 2 && !flagged; blink++) {
            for (int i = 0; i < 200; i++) {
                now += 50 * MS;
                if (check.check(uuid, data, transactions, now).isFlag()) flagged = true;
            }
            now += 1800 * MS;
            if (check.check(uuid, data, transactions, now).isFlag()) flagged = true;
        }

        assertTrue(flagged, "blinks separated by normal play must still accumulate");
    }

    @Test
    void blinkForgetsOccurrencesOutsideTheWindow() {
        BlinkCheck check = new BlinkCheck();
        UUID uuid = UUID.randomUUID();
        TransactionManager transactions = new StubTransactions(45.0, 0.0);
        UserData data = new UserData(uuid, "Unlucky");
        data.setEnvironment(snapshot(false));

        long now = 0L;
        now += 50 * MS;
        check.check(uuid, data, transactions, now);

        // One odd gap, then the same again over three minutes later. Two isolated hiccups that far
        // apart are not a pattern, and must not add up.
        now += 1800 * MS;
        assertFalse(check.check(uuid, data, transactions, now).isFlag());

        now += 200_000 * MS;
        check.check(uuid, data, transactions, now);
        now += 1800 * MS;
        assertFalse(check.check(uuid, data, transactions, now).isFlag(),
                "occurrences outside the window must not accumulate");
    }

    @Test
    void blinkIgnoresUnansweredTransactions() {
        BlinkCheck check = new BlinkCheck();
        UUID uuid = UUID.randomUUID();
        // Low recorded RTT but transactions currently outstanding: the connection is not flowing
        // right now, so the silence is not evidence of anything.
        TransactionManager transactions = new StubTransactions(45.0, 5000.0);
        UserData data = new UserData(uuid, "Stalled");
        data.setEnvironment(snapshot(false));

        long now = 0L;
        for (int i = 0; i < 10; i++) {
            now += 50 * MS;
            check.check(uuid, data, transactions, now);
        }
        for (int i = 0; i < 10; i++) {
            now += 1800 * MS;
            assertFalse(check.check(uuid, data, transactions, now).isFlag());
        }
    }

    @Test
    void blinkNeedsNoLatencySampleToStaySilent() {
        BlinkCheck check = new BlinkCheck();
        TransactionManager transactions = new TransactionManager();
        UUID uuid = UUID.randomUUID();
        UserData data = new UserData(uuid, "JustJoined");
        data.setEnvironment(snapshot(false));

        // No transaction has completed yet, so there is no baseline to judge the silence against.
        long now = 0L;
        for (int i = 0; i < 10; i++) {
            now += 900 * MS;
            assertFalse(check.check(uuid, data, transactions, now).isFlag());
        }
    }

    // ------------------------------------------------------------------

    /** Stands in for a live transaction stream with a fixed RTT and pending-age. */
    private static final class StubTransactions extends TransactionManager {
        private final double rtt;
        private final double pendingAge;

        StubTransactions(double rtt, double pendingAge) {
            this.rtt = rtt;
            this.pendingAge = pendingAge;
        }

        @Override
        public double getTransactionPing(UUID uuid) {
            return rtt;
        }

        @Override
        public double getOldestPendingAgeMs(UUID uuid) {
            return pendingAge;
        }
    }

    private static EnvironmentSnapshot snapshot(boolean insideSolidBlock) {
        return TestSnapshots.builder()
                .blockBelow(Material.STONE)
                .insideSolidBlock(insideSolidBlock)
                .build();
    }
}
