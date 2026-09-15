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
        TransactionManager transactions = new StubTransactions(2500.0, 0.0);
        UserData data = new UserData(uuid, "Lagging");
        data.setEnvironment(snapshot(false));

        long now = 0L;
        for (int i = 0; i < 10; i++) {
            now += 50 * MS;
            check.check(uuid, data, transactions, now);
        }
        // Freeze well past MIN_SILENCE_MS, with the connection genuinely down through it.
        for (int i = 0; i < 5; i++) {
            now += 2500 * MS;
            assertFalse(check.check(uuid, data, transactions, now).isFlag(),
                    "a real network stall must not be read as a lag switch");
        }
    }

    @Test
    void blinkDetectsMovementWithheldWhileClientKeptFighting() {
        BlinkCheck check = new BlinkCheck();
        UUID uuid = UUID.randomUUID();
        // The real signature: movement stops, transactions keep coming back at a normal ping, and
        // the client goes on producing packets only its main game loop can make (swings). A frozen
        // client produces none of those, which is what separates the two.
        TransactionManager transactions = new StubTransactions(45.0, 0.0);
        UserData data = new UserData(uuid, "Blinker");
        data.setEnvironment(snapshot(false));

        long now = 0L;
        for (int i = 0; i < 10; i++) {
            now += 50 * MS;
            check.check(uuid, data, transactions, now);
        }

        // Swing lands in the middle of the silence that follows.
        data.setLastSwingNanos(now + 1500 * MS);
        now += 2500 * MS;

        CheckResult result = check.check(uuid, data, transactions, now);
        assertTrue(result.isFlag(), "movement withheld while the client kept swinging must be caught");
        assertEquals("Blink", result.checkName());
        assertEquals("activity", result.details().get("evidence"));
    }

    @Test
    void blinkIgnoresClientFreezeWithNoMainLoopActivity() {
        BlinkCheck check = new BlinkCheck();
        UUID uuid = UUID.randomUUID();
        // Identical packet-level signature to the test above - silence plus a healthy transaction
        // RTT - but nothing proves the client was still running. This is an ordinary client-side
        // freeze (chunk meshing, GC, alt-tab), and repeating it must never accumulate into a flag.
        TransactionManager transactions = new StubTransactions(45.0, 0.0);
        UserData data = new UserData(uuid, "Stuttering");
        data.setEnvironment(snapshot(false));

        long now = 0L;
        for (int blink = 0; blink < 6; blink++) {
            for (int i = 0; i < 200; i++) {
                now += 50 * MS;
                assertFalse(check.check(uuid, data, transactions, now).isFlag());
            }
            now += 2500 * MS;
            assertFalse(check.check(uuid, data, transactions, now).isFlag(),
                    "a client-side freeze must not be read as a lag switch, however often it repeats");
        }
    }

    @Test
    void blinkDetectsQueuedPacketFlush() {
        BlinkCheck check = new BlinkCheck();
        UUID uuid = UUID.randomUUID();
        TransactionManager transactions = new StubTransactions(45.0, 0.0);
        UserData data = new UserData(uuid, "Buffering");
        data.setEnvironment(snapshot(false));

        long now = 0L;
        for (int i = 0; i < 10; i++) {
            now += 50 * MS;
            check.check(uuid, data, transactions, now);
        }

        // Silence with no swing at all - the activity discriminator stays silent here on purpose.
        now += 2500 * MS;
        assertFalse(check.check(uuid, data, transactions, now).isFlag());

        // Release: the buffered queue arrives back to back. A recovering vanilla client cannot do
        // this - it resumes from its current position, capped by its own 10-ticks-per-frame clamp.
        boolean flagged = false;
        for (int i = 0; i < 20 && !flagged; i++) {
            now += 2 * MS;
            CheckResult result = check.check(uuid, data, transactions, now);
            if (result.isFlag()) {
                flagged = true;
                assertEquals("flush", result.details().get("evidence"));
            }
        }
        assertTrue(flagged, "a flushed packet queue must be caught");
    }

    @Test
    void blinkStreakPathStillWorksWhenActivityProofIsDisabled() {
        // Legacy behaviour, opt-in via activity-proof-required: false. Kept covered so the escape
        // hatch an operator can fall back to does not rot.
        BlinkCheck check = new BlinkCheck(1550.0, 10_000.0, 0.5, 3, 120_000L, 900.0, 2, 1.6, 400.0,
                false, 300.0, 14);
        UUID uuid = UUID.randomUUID();
        TransactionManager transactions = new StubTransactions(45.0, 0.0);
        UserData data = new UserData(uuid, "Blinker");
        data.setEnvironment(snapshot(false));

        long now = 0L;
        boolean flagged = false;

        for (int blink = 0; blink < 3 && !flagged; blink++) {
            for (int i = 0; i < 200; i++) {
                now += 50 * MS;
                if (check.check(uuid, data, transactions, now).isFlag()) flagged = true;
            }
            now += 2500 * MS;
            if (check.check(uuid, data, transactions, now).isFlag()) flagged = true;
        }

        assertTrue(flagged, "blinks separated by normal play must still accumulate in legacy mode");
    }

    @Test
    void blinkForgetsOccurrencesOutsideTheWindow() {
        BlinkCheck check = new BlinkCheck();
        UUID uuid = UUID.randomUUID();
        TransactionManager transactions = new StubTransactions(45.0, 0.0);
        UserData data = new UserData(uuid, "Unlucky");
        data.setEnvironment(snapshot(false));

        long now = 0L;

        // Two occurrences close together, then a single one three minutes later. Un-forgotten,
        // 2 + 1 would reach REQUIRED_STREAK (3) and flag on the third; correctly windowed, the
        // three-minute gap (> WINDOW_NANOS) must forget the first two, leaving just 1.
        now += 50 * MS;
        check.check(uuid, data, transactions, now);
        now += 2500 * MS;
        check.check(uuid, data, transactions, now);

        now += 200_000 * MS;
        now += 2500 * MS;
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
            now += 2500 * MS;
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
