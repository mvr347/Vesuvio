package net.lovelace.vesuvio;

import net.lovelace.vesuvio.check.statistical.StatisticalReachCheck;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins StatisticalReachCheck's latency-source fix: {@code Player#getPing()} is client-reported and
 * directly spoofable, and both numbers this check derives from "ping" (how far the target's
 * hitbox is rewound, and the reach buffer past a threshold) work in the cheater's favour if
 * inflated. TransactionManager's RTT, from the client's own Ping/Pong answers on an ordered
 * connection, cannot be misreported the same way and must win whenever a sample exists.
 */
class StatisticalReachPingSourceTest {

    @Test
    void transactionRttWinsOverASpoofedHighReportedPing() {
        // A client claiming 999ms of latency while its real transaction RTT is 20ms - exactly the
        // shape of a spoof aimed at inflating the hitbox rewind and the reach buffer.
        int resolved = StatisticalReachCheck.resolveEffectivePing(20.0, 999);
        assertEquals(20, resolved, "a real transaction sample must not be overridden by a spoofed reported ping");
    }

    @Test
    void reportedPingIsUsedOnlyBeforeTheFirstTransactionSample() {
        // No transaction sample yet (just joined) - negative RTT is the documented "none yet" value.
        int resolved = StatisticalReachCheck.resolveEffectivePing(-1.0, 85);
        assertEquals(85, resolved, "reported ping is the only latency available before the first round-trip completes");
    }

    @Test
    void negativeReportedPingNeverProducesANegativeResult() {
        int resolved = StatisticalReachCheck.resolveEffectivePing(-1.0, -5);
        assertEquals(0, resolved, "a nonsensical negative reported ping must clamp to zero, not go negative");
    }

    @Test
    void aLowSpoofedPingCannotShrinkTheAllowanceBelowTheRealLatencyEither() {
        // The reverse spoof direction - claiming near-zero ping while real RTT is high - must also
        // be ignored once a transaction sample exists, since it would unfairly tighten the check
        // against a player who is genuinely laggy.
        int resolved = StatisticalReachCheck.resolveEffectivePing(180.0, 1);
        assertEquals(180, resolved, "transaction RTT must win in both spoof directions, not just the favourable one");
    }
}
