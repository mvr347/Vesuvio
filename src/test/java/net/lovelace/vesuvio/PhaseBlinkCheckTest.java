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
        StubTransactions transactions = new StubTransactions(2500.0, 0.0);
        UserData data = new UserData(uuid, "Lagging");
        data.setEnvironment(snapshot(false));

        long now = 0L;
        for (int i = 0; i < 10; i++) {
            now += 50 * MS;
            transactions.ack();
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
        StubTransactions transactions = new StubTransactions(45.0, 0.0);
        UserData data = new UserData(uuid, "Blinker");
        data.setEnvironment(snapshot(false));

        long now = 0L;
        for (int i = 0; i < 10; i++) {
            now += 50 * MS;
            transactions.ack();
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
        StubTransactions transactions = new StubTransactions(45.0, 0.0);
        UserData data = new UserData(uuid, "Stuttering");
        data.setEnvironment(snapshot(false));

        long now = 0L;
        for (int blink = 0; blink < 6; blink++) {
            for (int i = 0; i < 200; i++) {
                now += 50 * MS;
                transactions.ack();
                assertFalse(check.check(uuid, data, transactions, now).isFlag());
            }
            // netty answers right through the freeze, so the client's clock keeps advancing -
            // which is exactly why the silence alone can never separate this from a blink.
            now += 2500 * MS;
            for (int i = 0; i < 50; i++) transactions.ack();
            assertFalse(check.check(uuid, data, transactions, now).isFlag(),
                    "a client-side freeze must not be read as a lag switch, however often it repeats");
        }
    }

    @Test
    void blinkDetectsQueuedPacketFlush() {
        BlinkCheck check = new BlinkCheck();
        UUID uuid = UUID.randomUUID();
        StubTransactions transactions = new StubTransactions(45.0, 0.0);
        UserData data = new UserData(uuid, "Buffering");
        data.setEnvironment(snapshot(false));

        long now = 0L;
        for (int i = 0; i < 10; i++) {
            now += 50 * MS;
            transactions.ack();
            check.check(uuid, data, transactions, now);
        }

        // Silence with no swing at all - the activity discriminator stays silent here on purpose.
        // The client keeps answering transactions during it, as both a blink and a freeze do.
        now += 2500 * MS;
        for (int i = 0; i < 50; i++) transactions.ack();
        assertFalse(check.check(uuid, data, transactions, now).isFlag());

        // Release: the buffered queue arrives back to back. A recovering vanilla client cannot do
        // this - its catch-up is capped at 10 ticks in one frame and the rest of the backlog is
        // discarded, whereas the module replays what it withheld: 2500ms of silence is 50 packets.
        // Note what is NOT called here: transactions.ack(). The module never received those
        // transactions while it was buffering, so the client's clock cannot have moved between
        // the replayed packets - which is the entire signature.
        boolean flagged = false;
        for (int i = 0; i < 50 && !flagged; i++) {
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
        StubTransactions transactions = new StubTransactions(45.0, 0.0);
        UserData data = new UserData(uuid, "Blinker");
        data.setEnvironment(snapshot(false));

        long now = 0L;
        boolean flagged = false;

        for (int blink = 0; blink < 3 && !flagged; blink++) {
            for (int i = 0; i < 200; i++) {
                now += 50 * MS;
                transactions.ack();
                if (check.check(uuid, data, transactions, now).isFlag()) flagged = true;
            }
            now += 2500 * MS;
            for (int i = 0; i < 50; i++) transactions.ack();
            if (check.check(uuid, data, transactions, now).isFlag()) flagged = true;
        }

        assertTrue(flagged, "blinks separated by normal play must still accumulate in legacy mode");
    }

    @Test
    void blinkForgetsOccurrencesOutsideTheWindow() {
        BlinkCheck check = new BlinkCheck();
        UUID uuid = UUID.randomUUID();
        StubTransactions transactions = new StubTransactions(45.0, 0.0);
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
        StubTransactions transactions = new StubTransactions(45.0, 5000.0);
        UserData data = new UserData(uuid, "Stalled");
        data.setEnvironment(snapshot(false));

        long now = 0L;
        for (int i = 0; i < 10; i++) {
            now += 50 * MS;
            transactions.ack();
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

    @Test
    void vanillaFreezeRecoveryBurstIsNotAFlush() {
        // The regression this pins down: the flush threshold used to be a flat 14 packets in a
        // 300ms window, and a client recovering from a freeze produces exactly that on its own -
        // its catch-up clamp runs 10 ticks in the first frame, then it resumes its ordinary 20Hz
        // stream for the rest of the window. 10 + 6 = 16, comfortably past 14, with no cheat
        // involved. Every freeze recovery of a moving player was flagged.
        BlinkCheck check = new BlinkCheck();
        UUID uuid = UUID.randomUUID();
        StubTransactions transactions = new StubTransactions(45.0, 0.0);
        UserData data = new UserData(uuid, "Hitching");
        data.setEnvironment(snapshot(false));

        long now = 0L;
        for (int i = 0; i < 40; i++) {
            now += 50 * MS;
            transactions.ack();
            check.check(uuid, data, transactions, now);
        }

        // A 2s freeze: no movement packets and no swing - the main loop is stopped. netty is not,
        // so the client answers all 40 transactions the server sent during it.
        now += 2000 * MS;
        for (int i = 0; i < 40; i++) transactions.ack();
        assertFalse(check.check(uuid, data, transactions, now).isFlag());

        // Recovery: 10 catch-up ticks land in one frame (the rest of the backlog is discarded by
        // the client, not deferred), then the normal 20Hz stream resumes.
        for (int i = 0; i < 9; i++) {
            now += 1 * MS;
            assertFalse(check.check(uuid, data, transactions, now).isFlag(),
                    "the client's own catch-up clamp is not a flushed queue");
        }
        for (int i = 0; i < 6; i++) {
            now += 50 * MS;
            transactions.ack();
            assertFalse(check.check(uuid, data, transactions, now).isFlag(),
                    "resuming the ordinary packet rate after a freeze is not a flushed queue");
        }
    }

    @Test
    void queuedSwingArrivingWithTheRecoveryFlushIsNotActivityProof() {
        // The second false positive of the same kind. A client resuming from a freeze runs its
        // first tick as one unit: input handling emits the swing for a click held during the stall
        // BEFORE the movement send, so the swing lands a fraction of a millisecond before the
        // packet that ends the silence. That used to read as "the main loop was alive during the
        // silence" - proof of exactly the opposite of what happened.
        BlinkCheck check = new BlinkCheck();
        UUID uuid = UUID.randomUUID();
        StubTransactions transactions = new StubTransactions(45.0, 0.0);
        UserData data = new UserData(uuid, "FrozenMidFight");
        data.setEnvironment(snapshot(false));

        long now = 0L;
        for (int i = 0; i < 40; i++) {
            now += 50 * MS;
            transactions.ack();
            check.check(uuid, data, transactions, now);
        }

        now += 2000 * MS;
        for (int i = 0; i < 40; i++) transactions.ack();
        // The queued swing is flushed 0.2ms before the movement packet that ends the silence.
        data.setLastSwingNanos(now - (MS / 5));

        assertFalse(check.check(uuid, data, transactions, now).isFlag(),
                "a swing arriving in the recovery flush is not evidence the loop ran during the silence");
    }

    @Test
    void swingInTheMiddleOfTheSilenceStillProvesTheLoopWasAlive() {
        // The guard band must not cost the detection it exists to protect: a blink used in combat
        // swings throughout the silence, so activity lands far from either edge.
        BlinkCheck check = new BlinkCheck();
        UUID uuid = UUID.randomUUID();
        StubTransactions transactions = new StubTransactions(45.0, 0.0);
        UserData data = new UserData(uuid, "BlinkingInCombat");
        data.setEnvironment(snapshot(false));

        long now = 0L;
        for (int i = 0; i < 40; i++) {
            now += 50 * MS;
            transactions.ack();
            check.check(uuid, data, transactions, now);
        }

        long silenceStart = now;
        now += 2000 * MS;
        for (int i = 0; i < 40; i++) transactions.ack();
        data.setLastSwingNanos(silenceStart + 1000 * MS); // dead centre of the silence

        CheckResult result = check.check(uuid, data, transactions, now);
        assertTrue(result.isFlag(), "swinging through the silence is still conclusive");
        assertEquals("activity", result.details().get("evidence"));
    }

    @Test
    void shortBlinkIsSeparableByAccountingWhereAPacketCountWasNot() {
        // A 900ms silence backs up 18 packets against a vanilla recovery of 10 - too close for any
        // count over a wall-clock window to call, which is why the previous version had to stay
        // silent in this band entirely. The accounting separates them anyway, because what decides
        // it is not how many packets arrived but that the client's own clock never moved while
        // they did.
        BlinkCheck check = new BlinkCheck();
        UUID uuid = UUID.randomUUID();
        StubTransactions transactions = new StubTransactions(20.0, 0.0);
        UserData data = new UserData(uuid, "ShortBlinker");
        data.setEnvironment(snapshot(false));

        long now = 0L;
        for (int i = 0; i < 40; i++) {
            now += 50 * MS;
            transactions.ack();
            check.check(uuid, data, transactions, now);
        }

        now += 1000 * MS;
        for (int i = 0; i < 20; i++) transactions.ack();
        assertFalse(check.check(uuid, data, transactions, now).isFlag());

        boolean flagged = false;
        for (int i = 0; i < 18 && !flagged; i++) {
            now += 2 * MS;
            CheckResult result = check.check(uuid, data, transactions, now);
            if (result.isFlag()) {
                flagged = true;
                assertEquals("flush", result.details().get("evidence"));
            }
        }
        assertTrue(flagged, "a replayed queue is separable even in the short band");
    }

    @Test
    void serverLagSpikeDoesNotTurnLegitimateMovementIntoAFlush() {
        // This is what the derived budget buys over any flat packet count, and it is the scenario
        // that produces mass false positives in practice: the SERVER stalls. Transactions stop
        // going out, so the client's ack sequence stops advancing - while the client itself is
        // fine and keeps producing its 20 movement packets a second. All of them land in one ack
        // bucket through no fault of the player's.
        //
        // A flat threshold reads that pile as a replayed queue. The accounting does not, because
        // the budget carries the elapsed term: the server knows how long ago it sent the
        // transaction that was last answered, and that is exactly how many ticks the client is
        // entitled to have run since.
        BlinkCheck check = new BlinkCheck();
        UUID uuid = UUID.randomUUID();
        StubTransactions transactions = new StubTransactions(45.0, 0.0);
        UserData data = new UserData(uuid, "LaggedServer");
        data.setEnvironment(snapshot(false));

        long now = 0L;
        for (int i = 0; i < 40; i++) {
            now += 50 * MS;
            transactions.ack();
            check.check(uuid, data, transactions, now);
        }

        // The client hitches too (a server stall usually drags the client with it), so there is a
        // silence and therefore a pending candidate for a burst to be attributed to.
        now += 2000 * MS;
        for (int i = 0; i < 40; i++) transactions.ack();
        assertFalse(check.check(uuid, data, transactions, now).isFlag());

        // Now the server itself stalls: 700ms with no new transaction answered. The client's
        // catch-up clamp fires and it then keeps sending normally - 24 packets, one bucket.
        transactions.msSinceLastAckSent = 700.0;
        for (int i = 0; i < 9; i++) {
            now += 1 * MS;
            assertFalse(check.check(uuid, data, transactions, now).isFlag(),
                    "the catch-up clamp is inside the budget");
        }
        for (int i = 0; i < 14; i++) {
            now += 50 * MS;
            assertFalse(check.check(uuid, data, transactions, now).isFlag(),
                    "movement the client was entitled to produce is not a replayed queue, "
                            + "however much of it piles into one ack bucket");
        }
    }

    @Test
    void freezeRecoveryInTheShortBandStaysWithinItsBudget() {
        // The other half of the same claim: same silence, but the client merely froze, so it emits
        // its catch-up clamp and nothing more.
        BlinkCheck check = new BlinkCheck();
        UUID uuid = UUID.randomUUID();
        StubTransactions transactions = new StubTransactions(20.0, 0.0);
        UserData data = new UserData(uuid, "ShortHitch");
        data.setEnvironment(snapshot(false));

        long now = 0L;
        for (int i = 0; i < 40; i++) {
            now += 50 * MS;
            transactions.ack();
            check.check(uuid, data, transactions, now);
        }

        now += 1000 * MS;
        for (int i = 0; i < 20; i++) transactions.ack();
        assertFalse(check.check(uuid, data, transactions, now).isFlag());

        for (int i = 0; i < 9; i++) {
            now += 1 * MS;
            assertFalse(check.check(uuid, data, transactions, now).isFlag(),
                    "the client's own catch-up clamp is within its budget");
        }
    }

    // ------------------------------------------------------------------

    /** Stands in for a live transaction stream with a fixed RTT and pending-age. */
    private static final class StubTransactions extends TransactionManager {
        private final double rtt;
        private final double pendingAge;
        /** Answered-transaction counter - the clock the client keeps for the server. */
        long ackSequence = 1L;
        /** Milliseconds since the server SENT the transaction most recently answered. */
        double msSinceLastAckSent = 45.0;

        StubTransactions(double rtt, double pendingAge) {
            this.rtt = rtt;
            this.pendingAge = pendingAge;
        }

        /** The client answers a transaction: its clock advances. Real clients do this every tick. */
        void ack() {
            ackSequence++;
            msSinceLastAckSent = 45.0;
        }

        @Override
        public long getAckSequence(UUID uuid) {
            return ackSequence;
        }

        @Override
        public double getMsSinceLastAckSent(UUID uuid) {
            return msSinceLastAckSent;
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
