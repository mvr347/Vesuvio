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
 * Deliberately driven by <em>all</em> movement packets, not just those carrying a position.
 * Vanilla's own {@code LocalPlayer.sendPosition()} only emits a packet when position/rotation
 * moved past a tiny epsilon <em>or</em> a 20-tick ("positionReminder") counter rolls over - a
 * player who is perfectly stationary (no position delta, no camera movement, no on-ground change)
 * sends <strong>nothing at all</strong> for up to 20 ticks (~1000ms at 20 TPS), then a single
 * reminder packet. That is normal, healthy-connection silence, not a withheld stream - the
 * silence threshold below has to sit comfortably above it or every AFK-but-present player reads
 * as a blink. A blink suppresses packets far longer than one vanilla reminder interval, which is
 * what distinguishes the two.
 *
 * Author: Lovelace
 */
public final class BlinkCheck {

    /**
     * Vanilla's positionReminder rolls over every 20 ticks (~1000ms at 20 TPS) when a player is
     * completely stationary, producing a real packet gap of up to that long with a perfectly
     * healthy connection. The threshold sits well above that ceiling - with margin for server tick
     * jitter and network delivery delay - so normal idle play never reads as a blink, while still
     * being far shorter than a blink worth using.
     */
    private static final double MIN_SILENCE_MS = 1500.0;

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
