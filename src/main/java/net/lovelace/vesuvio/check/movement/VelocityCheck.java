package net.lovelace.vesuvio.check.movement;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.data.DecayingEvidence;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.engine.EnvironmentSnapshot;
import net.lovelace.vesuvio.engine.TransactionManager;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Anti-knockback (Velocity) detection.
 *
 * <h2>The gap this closes</h2>
 * Threshold-based anticheats have a well-documented hole here: they know that <em>some</em>
 * velocity was applied, but never correlate it with an acknowledgement, so a client can drop or
 * delay the packet and take 0% knockback while the server's grace period quietly expires. Every
 * movement check in this plugin already treats "recent velocity" as a blanket exemption, which
 * means an undetected anti-knockback also hands the player a free pass on Speed, Fly and StepUp
 * for the duration - the hole is worth more than the knockback itself.
 *
 * <h2>How this closes it</h2>
 * The exact knockback vector is captured from the outgoing EntityVelocity packet, and a
 * transaction is stamped alongside it. Measurement does not begin until the client acknowledges
 * that transaction, which is what makes the check latency-proof: a player on a 300ms connection
 * simply starts being measured 300ms later instead of being judged on a push they had not
 * received. Once acknowledged, the horizontal displacement over the next few ticks is compared
 * against the horizontal impulse the server actually applied. Taking a large fraction of it is
 * normal - friction, walls, and the player's own input all eat into it - but taking almost none of
 * it, repeatedly, is not something a vanilla client can do.
 *
 * <p>Wall collisions are the main honest way to absorb knockback, so a single low reading is never
 * enough: the check requires the pattern to repeat across separate knockback events before it
 * flags.
 *
 * Author: Lovelace
 */
public final class VelocityCheck {

    /** Knockbacks smaller than this carry too little signal to judge. */
    private static final double MIN_TRACKABLE_IMPULSE = 0.08;

    /** Ticks of movement to accumulate after acknowledgement before scoring. */
    private static final int MEASURE_TICKS = 4;

    /**
     * Fraction of the applied horizontal impulse a legitimate player is expected to travel over
     * the measurement window. Deliberately low: friction, sneaking, collisions and counter-input
     * all legitimately eat knockback, and the cheat being caught takes essentially none.
     */
    private static final double MIN_EXPECTED_RATIO = 0.33;

    /**
     * Score an evidence accumulator needs to reach before flagging - see {@link DecayingEvidence}.
     * One suspicious knockback is still worth 1.0, so this reuses the old streak's meaning.
     */
    private static final double EVIDENCE_THRESHOLD = 3.0;
    private static final int EVIDENCE_MIN_EVENTS = 3;
    private static final double EVIDENCE_HALF_LIFE_MS = 20_000.0;
    private static final double EVIDENCE_CLEAN_RELIEF = 0.6;

    /**
     * Give up on a knockback the client never acknowledges within this many ticks. The stall
     * itself is not scored here - {@link TransactionManager#getOldestPendingAgeMs} is the right
     * signal for that - but a knockback we cannot time is a knockback we must not judge.
     */
    private static final int ACK_TIMEOUT_TICKS = 60;

    /**
     * Called once per movement packet.
     *
     * @param deltaX horizontal movement since the previous position packet
     * @param deltaZ horizontal movement since the previous position packet
     */
    public CheckResult check(UUID uuid, UserData data, TransactionManager transactions,
                             double deltaX, double deltaZ) {
        if (uuid == null || data == null || transactions == null) return CheckResult.pass("Velocity");
        if (!data.isVelocityPending()) return CheckResult.pass("Velocity");

        EnvironmentSnapshot env = data.getEnvironment();
        if (!env.isFresh(System.currentTimeMillis(), 500L) || env.isMovementExempt()) {
            data.clearPendingVelocity();
            return CheckResult.pass("Velocity");
        }

        double velX = data.getPendingVelX();
        double velZ = data.getPendingVelZ();
        double impulse = Math.hypot(velX, velZ);
        if (impulse < MIN_TRACKABLE_IMPULSE) {
            data.clearPendingVelocity();
            return CheckResult.pass("Velocity");
        }

        // Wait for proof the client received the knockback before measuring anything.
        if (!data.isVelocityAckSeen()) {
            if (!transactions.isAcknowledged(uuid, data.getPendingVelSequence())) {
                data.incrementVelocityTicksTracked();
                if (data.getVelocityTicksTracked() > ACK_TIMEOUT_TICKS) {
                    data.clearPendingVelocity();
                }
                return CheckResult.pass("Velocity");
            }
            // The ack just landed. Restart the window here so a laggy player gets the same number
            // of measured ticks as anyone else.
            data.markVelocityAcknowledged();
        }

        data.addVelocityObservedHorizontal(Math.hypot(deltaX, deltaZ));
        data.incrementVelocityTicksTracked();

        if (data.getVelocityTicksTracked() < MEASURE_TICKS) {
            return CheckResult.pass("Velocity");
        }

        double observed = data.getVelocityObservedHorizontal();
        double expected = expectedTravel(impulse);
        double ratio = expected <= 0 ? 1.0 : observed / expected;
        data.clearPendingVelocity();

        DecayingEvidence evidence = data.getVelocityEvidence();
        long nowNanos = System.nanoTime();

        if (ratio >= MIN_EXPECTED_RATIO) {
            evidence.relieve(EVIDENCE_CLEAN_RELIEF, nowNanos, EVIDENCE_HALF_LIFE_MS);
            return CheckResult.pass("Velocity");
        }

        // A wall standing directly in the knockback's path can legitimately zero out a client's
        // travel long before any anti-knockback client would need to - vanilla collision resolves
        // the X and Z components of that motion independently against the world every tick, same
        // as it does for ordinary walking. This reading proves nothing either way, so it must not
        // touch the accumulator at all (neither reward nor relieve): a player who gets hit into
        // corners often (very ordinary PvP behaviour - 1v1 arenas, cornering an opponent) would
        // otherwise accumulate false evidence purely from geometry, never from anything their
        // client actually did.
        if (env.isHorizontallyBlocked(velX, velZ)) {
            return CheckResult.pass("Velocity");
        }

        double score = evidence.reward(1.0, nowNanos, EVIDENCE_HALF_LIFE_MS);
        if (score < EVIDENCE_THRESHOLD || evidence.events() < EVIDENCE_MIN_EVENTS) {
            return CheckResult.pass("Velocity");
        }

        evidence.reset();

        double percent = ratio * 100.0;
        double confidence = Math.min(0.98, 0.80 + (MIN_EXPECTED_RATIO - ratio) * 0.5);
        double vl = Math.min(6.0, 2.0 + (MIN_EXPECTED_RATIO - ratio) * 6.0);

        Map<String, Object> details = new HashMap<>();
        details.put("impulse", impulse);
        details.put("expectedTravel", expected);
        details.put("observedTravel", observed);
        details.put("ratio", ratio);
        details.put("score", score);
        details.put("threshold", EVIDENCE_THRESHOLD);

        return CheckResult.flag("Velocity", confidence, vl,
                String.format(Locale.US,
                        "Absorbed server knockback (took %.0f%% of a %.2fb/t impulse over %d ticks, evidence %.1f/%.1f)",
                        percent, impulse, MEASURE_TICKS, score, EVIDENCE_THRESHOLD),
                details);
    }

    /**
     * Distance a knockback of the given magnitude carries a player over the measurement window,
     * with horizontal friction decaying it each tick. This is the same first-order decay the speed
     * model uses, summed over the window.
     */
    private static double expectedTravel(double impulse) {
        double friction = 0.6 * 0.91; // ground slipperiness * air drag
        double speed = impulse;
        double total = 0.0;
        for (int i = 0; i < MEASURE_TICKS; i++) {
            total += speed;
            speed *= friction;
        }
        return total;
    }
}
