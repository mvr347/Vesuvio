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
 * went. It is the same shape as a network stall, which is exactly why it is hard to catch.
 *
 * <h2>Why "silence + healthy RTT" is NOT enough on its own</h2>
 * The first version of this check flagged on: movement stream silent, transaction round-trip
 * normal, repeated a few times. That signature is produced just as faithfully by an ordinary
 * client-side freeze - chunk meshing, a GC pause, shader compilation, alt-tab (Sodium/Lunar
 * throttle to a handful of FPS on focus loss) - because movement packets come from the client's
 * main game loop while transactions are answered on its netty thread almost independently of it.
 * No threshold on time or RTT separates those two, which is why the streak path produced false
 * positives on weaker hardware. It is kept only as a confidence multiplier now, and can be
 * restored as a standalone trigger with {@code activity-proof-required: false}.
 *
 * <h2>What actually separates a freeze from a blink</h2>
 * Two independent discriminators, either of which is close to conclusive on its own:
 * <ul>
 *   <li><b>Main-loop activity during the silence.</b> A real freeze stops the whole main loop:
 *       no swing, no attacks, nothing. A blink suppresses <em>movement only</em> while the player
 *       keeps fighting, so swing/attack packets keep arriving through the silence. See
 *       {@link UserData#hadMainLoopActivityBetween}. Measured with a guard band at both edges of
 *       the silence, because the client's own recovery tick emits its queued swing immediately
 *       before the movement packet that ends the silence - see the call site.</li>
 *   <li><b>Flush burst, accounted against the transaction stream.</b> Transaction answers and
 *       movement packets share one ordered TCP connection, so no amount of network jitter can
 *       reorder them - which makes the client's own answers a clock it keeps for us. Movement
 *       packets that arrive without that clock advancing are packets the client cannot have
 *       generated in real time. See {@link #checkBurstBudget} - this replaced a packet count over
 *       a wall-clock window, which is precisely the measurement a bad connection distorts.</li>
 * </ul>
 * A third, weaker signal - an unexplained catch-up jump ({@link #checkReleaseBurst}) - stays as
 * it was, and still covers modules that drop packets rather than buffering them.
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
    private final boolean activityProofRequired;
    private final int catchUpTickClamp;
    private final int burstSlop;
    private final double activityGuardMs;

    /** Defaults for a config-less caller (e.g. unit tests exercising the check in isolation). */
    public BlinkCheck() {
        this(1550.0, 10_000.0, 0.5, 3, 120_000L, 900.0, 2, 1.6, 400.0, true, 10, 2, 250.0);
    }

    /**
     * Legacy constructor from when the flush burst was counted over a wall-clock window. Those two
     * parameters no longer exist - the burst is judged against the client's own transaction
     * answers instead (see {@link #checkBurstBudget}) - and are accepted and ignored so existing
     * call sites keep compiling.
     */
    public BlinkCheck(double minSilenceMs, double maxJudgedSilenceMs, double healthyRttFraction,
                       int requiredStreak, long windowMs, double shortSilenceMinMs,
                       int shortBlinkStreakBonus, double releaseBurstMinBlocks, double releaseBurstWindowMs,
                       boolean activityProofRequired, double ignoredFlushWindowMs, int ignoredFlushMinPackets) {
        this(minSilenceMs, maxJudgedSilenceMs, healthyRttFraction, requiredStreak, windowMs,
                shortSilenceMinMs, shortBlinkStreakBonus, releaseBurstMinBlocks, releaseBurstWindowMs,
                activityProofRequired, 10, 2, 250.0);
    }

    public BlinkCheck(double minSilenceMs, double maxJudgedSilenceMs, double healthyRttFraction,
                       int requiredStreak, long windowMs, double shortSilenceMinMs,
                       int shortBlinkStreakBonus, double releaseBurstMinBlocks, double releaseBurstWindowMs,
                       boolean activityProofRequired,
                       int catchUpTickClamp, int burstSlop, double activityGuardMs) {
        this.minSilenceMs = minSilenceMs;
        this.maxJudgedSilenceMs = maxJudgedSilenceMs;
        this.healthyRttFraction = healthyRttFraction;
        this.requiredStreak = requiredStreak;
        this.windowNanos = windowMs * 1_000_000L;
        this.shortSilenceMinMs = shortSilenceMinMs;
        this.shortBlinkStreakBonus = shortBlinkStreakBonus;
        this.releaseBurstMinBlocks = releaseBurstMinBlocks;
        this.releaseBurstWindowMs = releaseBurstWindowMs;
        this.activityProofRequired = activityProofRequired;
        this.catchUpTickClamp = Math.max(1, catchUpTickClamp);
        this.burstSlop = Math.max(0, burstSlop);
        this.activityGuardMs = Math.max(0.0, activityGuardMs);
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
                config.getBlinkReleaseBurstWindowMs(),
                config.isBlinkActivityProofRequired(),
                config.getBlinkCatchUpTickClamp(),
                config.getBlinkBurstSlop(),
                config.getBlinkActivityGuardMs());
    }

    /** Prints the active thresholds this instance was built with, for debug logging. */
    public String describeThresholds() {
        return String.format(Locale.US,
                "minSilenceMs=%.1f maxJudgedSilenceMs=%.1f healthyRttFraction=%.2f requiredStreak=%d "
                        + "shortSilenceMinMs=%.1f shortStreakBonus=%d releaseBurstMinBlocks=%.2f "
                        + "activityProofRequired=%b catchUpTickClamp=%d burstSlop=%d activityGuardMs=%.0f",
                minSilenceMs, maxJudgedSilenceMs, healthyRttFraction, requiredStreak,
                shortSilenceMinMs, shortBlinkStreakBonus, releaseBurstMinBlocks,
                activityProofRequired, catchUpTickClamp, burstSlop, activityGuardMs);
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

        // A queued-packet flush lands as a run of movement packets immediately after the silence,
        // so it is judged here, on the packets that follow - before this packet is itself judged
        // as the start of a new silence.
        CheckResult flush = checkBurstBudget(uuid, data, transactions);
        if (flush != null) return flush;

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

        // The silence is now a candidate either way: stash the context a later confirmation will
        // report. The packets that follow are measured by checkBurstBudget against the client's
        // own transaction answers, which needs no window to be armed.
        data.setPendingBlink(nowNanos, silenceMs, rtt, shortBand, occurrences);

        // Discriminator 1: did anything that only the client's main loop can produce arrive while
        // the movement stream was silent? A freeze stops all of it; a blink does not.
        //
        // The window is narrowed by a guard band at BOTH edges, and that guard is what makes this
        // test mean what it claims. A client resuming from a freeze runs its first tick as one
        // unit: input handling (which emits the swing/attack for a click that was held or queued
        // during the stall) runs before the movement send, so the swing lands a fraction of a
        // millisecond BEFORE the packet that ends the silence - inside the raw window, and
        // indistinguishable from a blink at that resolution. The mirror case exists at the start
        // edge, where a swing emitted in the same tick as the last movement packet can arrive just
        // after it and then the client freezes. Neither is evidence that the main loop ran DURING
        // the silence; both used to read as proof that it did.
        //
        // A blink used in combat is unaffected: it swings throughout a silence of at least
        // minSilenceMs, so activity lands in the middle, far from either edge.
        long guardNanos = (long) (activityGuardMs * 1_000_000.0);
        long activityFrom = last + guardNanos;
        long activityTo = nowNanos - guardNanos;
        boolean clientWasAlive = activityTo > activityFrom
                && data.hadMainLoopActivityBetween(activityFrom, activityTo);
        if (clientWasAlive) {
            data.clearPendingBlink();
            return buildFlag(data, silenceMs, rtt, occurrences, shortBand, Evidence.ACTIVITY, 0.0, 0, 0);
        }

        if (!activityProofRequired && occurrences >= requiredForBand) {
            // Legacy behaviour, opt-in only: streak alone. Retained so an operator who accepts the
            // false-positive rate can still catch a blink used while completely idle.
            data.clearPendingBlink();
            return buildFlag(data, silenceMs, rtt, occurrences, shortBand, Evidence.STREAK, 0.0, 0, 0);
        }

        return CheckResult.pass("Blink");
    }

    /**
     * Packet accounting against the transaction stream - the check that replaced a tuned packet
     * count with an identity the client cannot argue with.
     *
     * <h3>Why the old wall-clock version could not work</h3>
     * It counted movement packets inside a 300ms window measured on the server's receive clock.
     * That clock is exactly what a bad connection distorts: packets held up by the network and
     * then delivered together look identical to packets a module held back and flushed. Every
     * threshold over that window is therefore a guess about someone's ISP, and the two previous
     * attempts at picking one both landed under what an ordinary client produces by itself.
     *
     * <h3>What is measured instead</h3>
     * Transaction answers and movement packets travel on the same ordered TCP stream, so their
     * relative order survives any amount of jitter: delay moves both together. The server sends a
     * transaction every tick and the client's netty thread answers on receipt, which gives a clock
     * the client keeps for us. Between two consecutive answers, a client can only have run the
     * ticks that fit in the real time separating them - plus, once, the catch-up burst its own
     * clamp allows after a stall, the rest of that backlog being discarded rather than deferred.
     *
     * <p>So for movement packets arriving without the ack sequence advancing:
     * <pre>packets &lt;= elapsedSinceThatAckWasSent / 50ms + catchUpTickClamp</pre>
     * A client resuming from a freeze produces exactly its clamp and stops. A module replaying a
     * queue produces one packet per withheld tick - 40 of them for a two-second blink - without
     * the sequence moving at all, because it never received the transactions that would have
     * advanced it. Nothing here is tuned to a playerbase: the only constant is the client's own
     * clamp, and the elapsed term is measured from a timestamp the server wrote down before the
     * packet was ever sent.
     *
     * <h3>What this does not do</h3>
     * A module that withholds the transaction answers too is not caught here, and should not be:
     * from the server's side that player genuinely is not acknowledging anything, which is the
     * ordinary lag case handled by the pending-age test above.
     *
     * @return a flag once the burst exceeds what the client could have generated, otherwise {@code null}
     */
    private CheckResult checkBurstBudget(UUID uuid, UserData data, TransactionManager transactions) {
        long ackSequence = transactions.getAckSequence(uuid);
        int packetsInBucket = data.recordMovementAgainstAck(ackSequence);

        double sinceAckMs = transactions.getMsSinceLastAckSent(uuid);
        if (sinceAckMs < 0) return null; // client has not answered a transaction yet

        int budget = catchUpTickClamp + burstSlop + (int) (sinceAckMs / 50.0);
        if (packetsInBucket <= budget) return null;

        if (!data.hasPendingBlink()) {
            // Nothing was withheld that this could be the release of. A burst on its own is worth
            // knowing about but is not this check's verdict to give.
            return null;
        }

        double silenceMs = data.getPendingBlinkSilenceMs();
        double rtt = data.getPendingBlinkRttMs();
        boolean wasShort = data.isPendingBlinkShort();
        int occurrences = data.getPendingBlinkOccurrences();
        data.clearPendingBlink();
        data.resetMovementAckBucket();

        return buildFlag(data, silenceMs, rtt, occurrences, wasShort, Evidence.FLUSH, 0.0,
                packetsInBucket, budget);
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
        data.resetMovementAckBucket();

        return buildFlag(data, silenceMs, rtt, occurrences, wasShort, Evidence.DISPLACEMENT, displacement, 0, 0);
    }

    /** Which discriminator produced the verdict - reported in the alert details. */
    private enum Evidence { ACTIVITY, FLUSH, DISPLACEMENT, STREAK }

    private CheckResult buildFlag(UserData data, double silenceMs, double rtt, int occurrences,
                                   boolean shortBand, Evidence evidence, double displacementAfter,
                                   int flushPackets, int flushBudget) {
        // Repetition is no longer a trigger on its own, but a candidate that has recurred inside
        // the window is still meaningfully more convincing than a first sighting.
        double streakBoost = Math.min(0.05, Math.max(0, occurrences - 1) * 0.02);

        double confidence;
        double vl;
        String explanation;
        switch (evidence) {
            case ACTIVITY -> {
                confidence = Math.min(0.98, 0.93 + streakBoost);
                vl = Math.min(6.0, 3.0 + silenceMs / 500.0);
                explanation = String.format(Locale.US,
                        "Movement withheld for %.0fms (healthy RTT %.0fms) while the client kept swinging/attacking - its main loop was running",
                        silenceMs, rtt);
            }
            case FLUSH -> {
                confidence = Math.min(0.98, 0.92 + streakBoost);
                vl = Math.min(6.0, 3.0 + silenceMs / 500.0);
                explanation = String.format(Locale.US,
                        "Movement withheld for %.0fms then %d queued packets replayed against a budget of %d "
                                + "- the client answered no transaction between them, so it cannot have generated them (healthy RTT %.0fms)",
                        silenceMs, flushPackets, flushBudget, rtt);
            }
            case DISPLACEMENT -> {
                confidence = Math.min(0.97, 0.88 + Math.min(0.06, displacementAfter / 40.0) + streakBoost);
                vl = Math.min(6.0, 2.5 + silenceMs / 500.0);
                explanation = String.format(Locale.US,
                        "Movement withheld for %.0fms (healthy RTT %.0fms) then flushed an unexplained %.2f-block catch-up jump",
                        silenceMs, rtt, displacementAfter);
            }
            default -> {
                confidence = Math.min(0.90, 0.75 + (silenceMs - minSilenceMs) / 6000.0);
                vl = Math.min(5.0, 2.0 + silenceMs / 500.0);
                explanation = String.format(Locale.US,
                        "Movement stream withheld for %.0fms %d times while the connection stayed healthy (RTT %.0fms)",
                        silenceMs, occurrences, rtt);
            }
        }

        Map<String, Object> details = new HashMap<>();
        details.put("evidence", evidence.name().toLowerCase(Locale.ROOT));
        details.put("silenceMs", silenceMs);
        details.put("positionSilenceMs", data == null ? -1.0
                : (System.nanoTime() - data.getLastPositionCarryingNanos()) / 1_000_000.0);
        details.put("transactionRttMs", rtt);
        details.put("occurrencesInWindow", occurrences);
        details.put("shortBlink", shortBand);
        if (evidence == Evidence.DISPLACEMENT) {
            details.put("displacementAfter", displacementAfter);
        }
        if (evidence == Evidence.FLUSH) {
            details.put("flushPackets", flushPackets);
            details.put("flushBudget", flushBudget);
        }
        if (data != null) {
            details.put("playerState", describePlayerState(data.getEnvironment()));
        }

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
