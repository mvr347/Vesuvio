package net.lovelace.vesuvio.check.combat;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.engine.HitboxHistoryTracker;
import net.lovelace.vesuvio.engine.TransactionManager;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * BackTrack detection: a hit that only lands against a target's <em>much older</em> hitbox
 * position than the attacker's real connection could justify.
 *
 * <h2>The gap this closes</h2>
 * {@code StatisticalReachCheck} rewinds the target's hitbox using {@code Player#getPing()} to be
 * generous toward laggy legitimate players - but that ping is exactly the KeepAlive-derived value
 * {@link TransactionManager}'s own class docs warn is "under the client's control" (a cheat client
 * can report or exploit a larger number than its real round-trip to justify hitting where a target
 * used to be). A BackTrack cheat freezes the victim's hitbox on the attacker's screen and keeps
 * landing hits against that stale position long after the target has actually moved.
 *
 * <h2>How this catches it</h2>
 * Rather than trusting reported latency, this asks {@link HitboxHistoryTracker} the honest
 * question: how far back in time did the target's hitbox actually need to be for this hit to be
 * geometrically valid at all? That required age is then compared against the attacker's <em>real</em>,
 * transaction-measured round-trip - unspoofable, sampled every tick. A hit that only works against
 * a position from well beyond what the real connection could have delayed is not lag compensation,
 * it is exploiting stale state.
 *
 * Author: Lovelace
 */
public final class BackTrackCheck {

    /**
     * How many multiples of the attacker's real RTT the required rewind may exceed before it stops
     * being explainable as ordinary lag compensation. Generous: tick misalignment between the
     * server's snapshot cadence and the exact moment of the hit, plus jitter, both cost some slack.
     */
    private static final double RTT_TOLERANCE_MULTIPLIER = 2.0;

    /** Fixed floor added on top of the multiplier, so a very low-ping player still gets slack. */
    private static final double MARGIN_MS = 100.0;

    /** Separate hits before a flag, so one jittery reading is never enough. */
    private static final int REQUIRED_STREAK = 3;

    public CheckResult check(Player attacker, Entity target, UserData data,
                             HitboxHistoryTracker hitboxTracker, TransactionManager transactionManager,
                             double maxReach) {
        if (attacker == null || data == null || hitboxTracker == null || transactionManager == null) {
            return CheckResult.pass("BackTrack");
        }
        if (!(target instanceof Player targetPlayer)) {
            // Only players have tracked hitbox history; mobs/entities are not this check's concern.
            return CheckResult.pass("BackTrack");
        }

        double realRtt = transactionManager.getTransactionPing(attacker.getUniqueId());
        if (realRtt < 0) {
            // No latency sample yet - nothing honest to compare the rewind against.
            return CheckResult.pass("BackTrack");
        }

        Location eye = attacker.getEyeLocation();
        double requiredMs = hitboxTracker.findRequiredRewindMs(targetPlayer, eye.getX(), eye.getY(), eye.getZ(), maxReach);

        if (requiredMs < 0) {
            // The hit was not valid at any point in the tracked window - not a rewind problem,
            // some other check's business (or the hit already missed on the server's own terms).
            data.resetBackTrackStreak();
            return CheckResult.pass("BackTrack");
        }

        double allowedMs = realRtt * RTT_TOLERANCE_MULTIPLIER + MARGIN_MS;
        if (requiredMs <= allowedMs) {
            data.resetBackTrackStreak();
            return CheckResult.pass("BackTrack");
        }

        data.incrementBackTrackStreak();
        if (data.getBackTrackStreak() < REQUIRED_STREAK) {
            return CheckResult.pass("BackTrack");
        }

        data.resetBackTrackStreak();

        double excessMs = requiredMs - allowedMs;
        double confidence = Math.min(0.97, 0.82 + excessMs / 1000.0);
        double vl = Math.min(7.0, 2.0 + excessMs / 150.0);

        Map<String, Object> details = new HashMap<>();
        details.put("requiredRewindMs", requiredMs);
        details.put("realRttMs", realRtt);
        details.put("allowedMs", allowedMs);
        details.put("target", targetPlayer.getName());

        return CheckResult.flag("BackTrack", confidence, vl,
                String.format(Locale.US,
                        "Hit only valid against a %.0fms-old position (real RTT %.0fms, allowed %.0fms)",
                        requiredMs, realRtt, allowedMs),
                details);
    }
}
