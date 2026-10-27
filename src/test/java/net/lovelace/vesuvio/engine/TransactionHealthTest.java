package net.lovelace.vesuvio.engine;

import static org.junit.jupiter.api.Assertions.*;

import net.lovelace.vesuvio.TestSnapshots;
import net.lovelace.vesuvio.check.movement.BlinkCheck;
import net.lovelace.vesuvio.data.UserData;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

class TransactionHealthTest {
    private static final long MS = 1_000_000;
    private final AtomicLong now = new AtomicLong(10_000 * MS);
    private final TransactionManager transactions = new TransactionManager(now::get);
    private final UUID id = UUID.randomUUID();

    void answer() {
        int probe = transactions.begin(id);
        assertTrue(transactions.onResponse(id, probe));
    }

    @Test
    void zeroLocalRttIsValidWithContinuousProbes() {
        long start = now.get();
        for (int i = 0; i < 40; i++) {
            now.addAndGet(50 * MS);
            answer();
        }
        assertEquals(0, transactions.getTransactionPing(id));
        assertTrue(transactions.hasHealthyResponsesDuring(id, start, now.get(), 1000));
    }

    @Test
    void oldLowPingAndRecoveryFlushDoNotProveHealthyConnection() {
        answer();
        long start = now.get();
        now.addAndGet(2000 * MS); // server paused: no probes were sent in the gap
        for (int i = 0; i < 40; i++) answer(); // zero RTT on recovery is still insufficient
        assertEquals(0, transactions.getTransactionPing(id));
        assertFalse(transactions.hasHealthyResponsesDuring(id, start, now.get(), 1000));
        var data = new UserData(id, "LocalPlayer");
        data.setEnvironment(TestSnapshots.builder().build());
        data.setLastAnyMovementNanos(start);
        data.setLastSwingNanos(start + 1000 * MS);
        assertFalse(new BlinkCheck().check(id, data, transactions, now.get(), true, true).isFlag());
        assertEquals(0, data.getPendingBlinkNanos());
    }

    @Test
    void realActivityWithFlowingProbesStillDetectsBlinkAtZeroPing() {
        long start = now.get();
        for (int i = 0; i < 40; i++) {
            now.addAndGet(50 * MS);
            answer();
        }
        var data = new UserData(id, "Blinking");
        data.setEnvironment(TestSnapshots.builder().build());
        data.setLastAnyMovementNanos(start);
        data.setLastSwingNanos(start + 1000 * MS);
        assertTrue(new BlinkCheck().check(id, data, transactions, now.get(), true, true).isFlag());
    }

    @Test
    void missingResponsesAndSlowRepliesAreNotHealthy() {
        long start = now.get();
        for (int i = 0; i < 20; i++) {
            int probe = transactions.begin(id);
            now.addAndGet(100 * MS);
            transactions.onResponse(id, probe);
        }
        assertFalse(transactions.hasHealthyResponsesDuring(id, start, now.get(), 50));
        now.addAndGet(1000 * MS);
        assertFalse(transactions.hasHealthyResponsesDuring(id, start, now.get(), 200));
    }

    @Test
    void historyIsBoundedAndRemovedOnDisconnect() {
        long start = now.get();
        for (int i = 0; i < 2000; i++) {
            now.addAndGet(50 * MS);
            answer();
        }
        assertFalse(transactions.hasHealthyResponsesDuring(id, start, now.get(), 1000));
        assertTrue(
                transactions.hasHealthyResponsesDuring(id, now.get() - 2000 * MS, now.get(), 1000));
        transactions.remove(id);
        assertFalse(
                transactions.hasHealthyResponsesDuring(id, now.get() - 2000 * MS, now.get(), 1000));
        assertEquals(-1, transactions.getTransactionPing(id));
    }
}
