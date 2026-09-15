package net.lovelace.vesuvio.check.movement;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.config.ConfigManager;
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
 * <h2>Two complementary detectors</h2>
 * <ul>
 *   <li><b>Streak</b> (the original design): count silences past {@code minSilenceMs} with a
 *       healthy connection inside a sliding window; flag once enough of them stack up. Robust
 *       against a module that never produces a visible catch-up jump (e.g. used while stationary),
 *       but slow - it needs the module to be used several times before it reaches a verdict.</li>
 *   <li><b>Release burst</b> ({@link #checkReleaseBurst}): the instant the silence breaks, check
 *       whether the position/velocity that arrives is an unexplained catch-up jump - queued
 *       movement flushed in one packet - which a single genuine hitch never produces. This can
     *       confirm a single occurrence immediately instead of waiting for a streak, closing the
     *       gap this plugin used to have where a large post-silence jump was silently absorbed by
     *       {@code CheckPipeline}'s teleport-exclusion branch with no flag at all.</li>
 * </ul>
 *
 * Author: Lovelace
 */
public final class BlinkCheck {

    private final double minSilenceMs;
    private final double maxJudgedSilenceMs;
    private final double healthyRttFraction;
    private final int requiredStreak;
    private final long windowNanos;
    private final double shortSilenceMinMs;
    private final int shortBlinkStreakBonus;
    private final double releaseBurstMinBlocks;
    private final double releaseBurstWindowMs;

    /** Defaults chosen for a config-less caller (e.g. unit tests exercising the check in isolation). */
    public BlinkCheck() {
        this(1550.0, 10_000.0, 0.5, 3, 120_000L, 900.0, 2, 1.6, 400.0);
    }

    public BlinkCheck(double minSilenceMs, double maxJudgedSilenceMs, double healthyRttFraction,
                       int requiredStreak, long windowMs, double shortSilenceMinMs,
                       int shortBlinkStreakBonus, double releaseBurstMinBlocks, double releaseBurstWindowMs) {
        this.minSilenceMs = minSilenceMs;
        this.maxJudgedSilenceMs = maxJudgedSilenceMs;
        this.healthyRttFraction = healthyRttFraction;
        this.requiredStreak = requiredStreak;
        this.windowNanos = windowMs * 1_000_000L;
        this.shortSilenceMinMs = shortSilenceMinMs;
        this.shortBlinkStreakBonus = shortBlinkStreakBonus;
        this.releaseBurstMinBlocks = releaseBurstMinBlocks;
        this.releaseBurstWindowMs = releaseBurstWindowMs;
    }

    public static BlinkCheck fromConfig(ConfigManager config) {
        return new BlinkCheck(
                config.getBlinkMinSilenceMs(),
                config.getBlinkMaxJudgedSilenceMs(),
                config.getBlinkHealthyRttFraction(),
                config.getBlinkRequiredStreak(),
                config.getBlinkWindowMs(),
                config.getBlinkShortSilenceMinMs(),
                config.getBlinkShortStreakBonus(),
                config.getBlinkReleaseBurstMinBlocks(),
                config.getBlinkReleaseBurstWindowMs());
    }

    /** Prints the active thresholds this instance was built with, for debug logging. */
    public String describeThresholds() {
        return String.format(Locale.US,
                "minSilenceMs=%.1f maxJudgedSilenceMs=%.1f healthyRttFraction=%.2f requiredStreak=%d "
                        + "shortSilenceMinMs=%.1f shortStreakBonus=%d releaseBurstMinBlocks=%.2f",
                minSilenceMs, maxJudgedSilenceMs, healthyRttFraction, requiredStreak,
                shortSilenceMinMs, shortBlinkStreakBonus, releaseBurstMinBlocks);
    }

    /**
     * @param nowNanos arrival time of the movement packet that broke the silence
     */
    public CheckResult check(UUID uuid, UserData data, TransactionManager transactions, long nowNanos) {
        return check(uuid, data, transactions, nowNanos, true);
    }

    /**
     * @param positionCarrying whether this packet carried a position update (as opposed to a pure
     *                         look/flying heartbeat). Currently used only to maintain the
     *                         diagnostic {@code lastPositionCarryingNanos} timestamp - see the
     *                         field's javadoc in {@link UserData} for why it is not used to gate
     *                         the silence detector itself.
     */
    public CheckResult check(UUID uuid, UserData data, TransactionManager transactions, long nowNanos,
                              boolean positionCarrying) {
        if (uuid == null || data == null || transactions == null) return CheckResult.pass("Blink");

        long last = data.getLastAnyMovementNanos();
        data.setLastAnyMovementNanos(nowNanos);
        if (positionCarrying) {
            data.setLastPositionCarryingNanos(nowNanos);
        }
        if (last == 0L) return CheckResult.pass("Blink");

        double silenceMs = (nowNanos - last) / 1_000_000.0;

        boolean shortBand = silenceMs >= shortSilenceMinMs && silenceMs < minSilenceMs;
        boolean longBand = silenceMs >= minSilenceMs && silenceMs <= maxJudgedSilenceMs;
        if (!shortBand && !longBand) {
            // Not in either judged band. A stale pending candidate this far removed from the
            // packet that created it is no longer relevant either.
            if (data.hasPendingBlink() && (nowNanos - data.getPendingBlinkNanos()) / 1_000_000.0 > releaseBurstWindowMs) {
                data.clearPendingBlink();
            }
            return CheckResult.pass("Blink");
        }

        EnvironmentSnapshot env = data.getEnvironment();
        // A player who was dead, in a vehicle, mid-teleport, gliding, swimming, or riding out a
        // recent server-applied velocity (knockback/Riptide/piston - see UserData#hasRecentVelocity)
        // legitimately produces an irregular or stalled packet stream that looks identical to a
        // blink at this level. None of these states are meaningful to judge movement silence in.
        if (!env.isFresh(System.currentTimeMillis(), 1000L) || env.isMovementExempt() || env.swimming()
                || data.hasRecentTeleport() || data.hasRecentVelocity()) {
            return CheckResult.pass("Blink");
        }

        double rtt = transactions.getTransactionPing(uuid);
        if (rtt < 0) {
            // No latency sample yet (the player only just joined) - nothing to compare against.
            return CheckResult.pass("Blink");
        }

        // Unanswered transactions right now mean the connection genuinely is not flowing, which is
        // a stall, not a blink.
        if (transactions.getOldestPendingAgeMs(uuid) > minSilenceMs) {
            return CheckResult.pass("Blink");
        }

        boolean connectionWasHealthy = rtt < silenceMs * healthyRttFraction;
        if (!connectionWasHealthy) {
            return CheckResult.pass("Blink");
        }

        int occurrences = data.recordBlinkOccurrence(nowNanos, windowNanos);
        int requiredForBand = shortBand ? (requiredStreak + shortBlinkStreakBonus) : requiredStreak;

        if (occurrences >= requiredForBand) {
            data.clearPendingBlink();
            return buildFlag(data, silenceMs, rtt, occurrences, shortBand, false, -1);
        }

        // Not enough occurrences yet on the streak path alone - stash this as a pending candidate
        // so checkReleaseBurst (called once this same tick's position delta, if any, is known) gets
        // a chance to confirm it immediately via the catch-up-jump signature instead.
        data.setPendingBlink(nowNanos, silenceMs, rtt, shortBand, occurrences);
        return CheckResult.pass("Blink");
    }

    /**
     * Evaluates the movement packet that follows a pending blink candidate for a "release burst":
     * an unexplained catch-up jump consistent with queued movement being flushed in one packet.
     * Must be called with the raw per-tick deltas <em>before</em> any teleport-size exclusion
     * discards them - a real blink's release is frequently exactly that size, which is precisely
     * why it used to be silently absorbed rather than flagged.
     *
     * @param elapsedMs real wall-clock time this position delta covers (not just one tick's worth)
     */
    public CheckResult checkReleaseBurst(UserData data, double deltaX, double deltaY, double deltaZ,
                                          double elapsedMs, long nowNanos) {
        if (data == null || !data.hasPendingBlink()) return CheckResult.pass("Blink");

        double ageMs = (nowNanos - data.getPendingBlinkNanos()) / 1_000_000.0;
        if (ageMs < 0 || ageMs > releaseBurstWindowMs) {
            data.clearPendingBlink();
            return CheckResult.pass("Blink");
        }

        double horizontal = Math.hypot(deltaX, deltaZ);
        double displacement = Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);

        // Plausible displacement for ordinary movement over elapsedMs, generously bounded (sprint +
        // jump + a realistic ceiling for enchant-boosted speed/soul-speed/ice - the aim is to only
        // catch what a single healthy tick truly cannot explain, not to re-implement SpeedCheck).
        double maxPlausibleBlocksPerMs = 0.0086; // ~5.6 blocks/s sustained ceiling with generous headroom
        double plausible = Math.max(0.65, elapsedMs * maxPlausibleBlocksPerMs);

        boolean burst = horizontal > plausible + releaseBurstMinBlocks;
        if (!burst) {
            // Not resolved by this packet (player may not have moved much on release, or the burst
            // will land on a later packet still inside the window) - leave the candidate pending.
            return CheckResult.pass("Blink");
        }

        double silenceMs = data.getPendingBlinkSilenceMs();
        double rtt = data.getPendingBlinkRttMs();
        boolean wasShort = data.isPendingBlinkShort();
        int occurrences = data.getPendingBlinkOccurrences();
        data.clearPendingBlink();

        return buildFlag(data, silenceMs, rtt, occurrences, wasShort, true, displacement);
    }

    private CheckResult buildFlag(UserData data, double silenceMs, double rtt, int occurrences,
                                   boolean shortBand, boolean burstConfirmed, double displacementAfter) {
        double confidence = burstConfirmed
                ? Math.min(0.98, 0.90 + Math.min(0.08, displacementAfter / 40.0))
                : Math.min(0.97, 0.80 + (silenceMs - minSilenceMs) / 4000.0);
        double vl = Math.min(6.0, 2.0 + silenceMs / 500.0 + (burstConfirmed ? 1.0 : 0.0));

        Map<String, Object> details = new HashMap<>();
        details.put("silenceMs", silenceMs);
        details.put("positionSilenceMs", data == null ? -1.0
                : (System.nanoTime() - data.getLastPositionCarryingNanos()) / 1_000_000.0);
        details.put("transactionRttMs", rtt);
        details.put("occurrencesInWindow", occurrences);
        details.put("shortBlink", shortBand);
        details.put("releaseBurstConfirmed", burstConfirmed);
        if (burstConfirmed) {
            details.put("displacementAfter", displacementAfter);
        }
        if (data != null) {
            EnvironmentSnapshot env = data.getEnvironment();
            details.put("playerState", describePlayerState(env));
        }

        String explanation = burstConfirmed
                ? String.format(Locale.US,
                        "Movement withheld for %.0fms (healthy RTT %.0fms) then flushed an unexplained %.2f-block catch-up jump",
                        silenceMs, rtt, displacementAfter)
                : String.format(Locale.US,
                        "Movement stream withheld for %.0fms %s times while the connection stayed healthy (RTT %.0fms)",
                        silenceMs, occurrences, rtt);

        return CheckResult.flag("Blink", confidence, vl, explanation, details);
    }

    private static String describePlayerState(EnvironmentSnapshot env) {
        if (env == null || !env.valid()) return "unknown";
        StringBuilder sb = new StringBuilder();
        if (env.gliding()) sb.append("gliding,");
        if (env.swimming()) sb.append("swimming,");
        if (env.insideVehicle()) sb.append("vehicle,");
        if (env.sprinting()) sb.append("sprinting,");
        if (env.sneaking()) sb.append("sneaking,");
        if (sb.length() == 0) sb.append("normal");
        else sb.setLength(sb.length() - 1);
        return sb.toString();
    }
}
