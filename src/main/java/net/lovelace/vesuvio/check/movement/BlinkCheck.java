package net.lovelace.vesuvio.check.movement;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.engine.EnvironmentSnapshot;
import net.lovelace.vesuvio.engine.TransactionManager;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Blink / lag-switch detection.
 *
 * <h2>What the cheat does</h2>
 * The client stops sending movement packets while continuing to play locally. To the server the
 * player is frozen in place - unhittable, and unable to be tracked - and when the module is
 * released the queued movement arrives at once and the player "teleports" to where they actually
 * went. It is the same shape as a network stall, which is exactly why it is hard to catch and why
 * it has historically been used openly.
 *
 * <h2>Why this is now cheap to detect</h2>
 * A real network stall stops <em>everything</em>: the transactions this plugin sends every tick go
 * unanswered too, and when the connection recovers the measured round-trip reflects the outage.
 * A blink is selective - the client suppresses its movement packets while its connection stays
 * healthy, because it still needs to receive the world. So the discriminator is simply: did the
 * movement stream stop while the transaction stream kept flowing at a normal round-trip?
 *
 * <p>That test is only possible because latency here is measured with transactions rather than
 * KeepAlive. It cannot be answered from {@code Player#getPing()}, which is both too coarse and
 * under the client's control.
 *
 * <h2>Measuring the right stream</h2>
 * Deliberately driven by <em>all</em> movement packets, not just those carrying a position. A
 * vanilla client that is standing still sends the position-less "flying" packet every tick and a
 * full position packet only about once a second, so keying off position packets alone would make
 * every idle player look like a blink. A blink suppresses the whole flying stream, which is what
 * this measures.
 *
 * Author: Lovelace
 */
public final class BlinkCheck {

    /**
     * Silence longer than this is not ordinary scheduling jitter. Six ticks is comfortably past
     * any single skipped tick while still being far shorter than a blink worth using.
     */
    private static final double MIN_SILENCE_MS = 300.0;

    /**
     * The connection counts as healthy through the silence only if the measured round-trip is well
     * under the silence itself. A genuine stall of length T pushes the transaction RTT toward T;
     * a blink leaves it at the player's normal ping.
     */
    private static final double HEALTHY_RTT_FRACTION = 0.5;

    /** Absolute ceiling: past this, treat it as a real outage regardless of the ratio. */
    private static final double MAX_JUDGED_SILENCE_MS = 10_000.0;

    /** Separate silences before a flag, so one odd scheduling gap is never enough. */
    private static final int REQUIRED_STREAK = 2;

    /**
     * How long a suspicious silence stays counted. Blinks are separated by ordinary play, so the
     * count has to be windowed rather than decayed per packet - decaying it on every normal
     * movement packet would clear it between every pair of blinks and the threshold could never be
     * reached.
     */
    private static final long WINDOW_NANOS = 120_000_000_000L; // 2 minutes

    /**
     * @param nowNanos arrival time of the movement packet that broke the silence
     */
    public CheckResult check(UUID uuid, UserData data, TransactionManager transactions, long nowNanos) {
        if (uuid == null || data == null || transactions == null) return CheckResult.pass("Blink");

        long last = data.getLastAnyMovementNanos();
        data.setLastAnyMovementNanos(nowNanos);
        if (last == 0L) return CheckResult.pass("Blink");

        double silenceMs = (nowNanos - last) / 1_000_000.0;
        if (silenceMs < MIN_SILENCE_MS || silenceMs > MAX_JUDGED_SILENCE_MS) {
            return CheckResult.pass("Blink");
        }

        EnvironmentSnapshot env = data.getEnvironment();
        // A player who was dead, in a vehicle, or mid-teleport legitimately stops reporting.
        if (!env.isFresh(System.currentTimeMillis(), 1000L) || env.isMovementExempt()) {
            return CheckResult.pass("Blink");
        }

        double rtt = transactions.getTransactionPing(uuid);
        if (rtt < 0) {
            // No latency sample yet (the player only just joined) - nothing to compare against.
            return CheckResult.pass("Blink");
        }

        // Unanswered transactions right now mean the connection genuinely is not flowing, which is
        // a stall, not a blink.
        if (transactions.getOldestPendingAgeMs(uuid) > MIN_SILENCE_MS) {
            return CheckResult.pass("Blink");
        }

        boolean connectionWasHealthy = rtt < silenceMs * HEALTHY_RTT_FRACTION;
        if (!connectionWasHealthy) {
            return CheckResult.pass("Blink");
        }

        int occurrences = data.recordBlinkOccurrence(nowNanos, WINDOW_NANOS);
        if (occurrences < REQUIRED_STREAK) {
            return CheckResult.pass("Blink");
        }

        double confidence = Math.min(0.97, 0.80 + (silenceMs - MIN_SILENCE_MS) / 4000.0);
        double vl = Math.min(6.0, 2.0 + silenceMs / 500.0);

        Map<String, Object> details = new HashMap<>();
        details.put("silenceMs", silenceMs);
        details.put("transactionRttMs", rtt);
        details.put("occurrencesInWindow", occurrences);

        return CheckResult.flag("Blink", confidence, vl,
                String.format(Locale.US,
                        "Movement stream withheld for %.0fms while the connection stayed healthy (RTT %.0fms)",
                        silenceMs, rtt),
                details);
    }
}
