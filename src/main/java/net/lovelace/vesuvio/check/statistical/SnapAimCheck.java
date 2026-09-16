package net.lovelace.vesuvio.check.statistical;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.config.ConfigManager;
import net.lovelace.vesuvio.data.DecayingEvidence;
import net.lovelace.vesuvio.data.UserData;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Angular-jerk ("snap-back") detector: a large rotation immediately undone by a comparably large
 * rotation in the opposite direction, within a couple of ticks.
 *
 * <h2>Why this check exists</h2>
 * A silent-aim module that snaps a player's reported yaw/pitch onto a target for exactly the tick
 * of the attack - then restores it, so the victim's client (which smooths entity rotation over
 * several render frames) never visibly shows the turn - produces a rotation signature no human
 * hand can: a jump of tens of degrees followed, within one or two ticks, by another jump of
 * similar magnitude back the other way. Real mouse motion has an acceleration and deceleration
 * phase spread across several ticks; there is no physical input that reverses a fast turn with
 * near-equal magnitude that quickly. This is the "physically the camera cannot do this" signal,
 * not a measurement of how well the player aims - so unlike {@link KillauraAngleCheck}'s FOV and
 * silent-aim sub-checks, it is not defeated by a module that computes a perfectly correct angle
 * for the attack, because it never looks at whether the angle was correct.
 *
 * <p>Deliberately independent of the attack packet itself: the two rotation packets that make up a
 * snap-back do not both need to land on the exact attack tick (the snap can arrive with the attack
 * and the revert one tick later, or the reverse), so this is wired into every rotation update via
 * {@code CheckPipeline#processAim}, not into the combat path.
 *
 * Author: Lovelace
 */
public final class SnapAimCheck {

    private final ConfigManager config;

    public SnapAimCheck(ConfigManager config) {
        this.config = config;
    }

    /**
     * @param signedDeltaYaw this rotation packet's yaw change, signed and wrapped to (-180, 180] -
     *                       see {@link net.lovelace.vesuvio.data.AimTrackingBuffer#wrapDegrees}
     */
    public CheckResult check(UserData data, float signedDeltaYaw, long nowNanos) {
        if (data == null) return CheckResult.pass("SnapAim");

        float prevDelta = data.getPrevSignedDeltaYaw();
        long prevNanos = data.getPrevSignedDeltaYawNanos();
        data.setPrevSignedDeltaYaw(signedDeltaYaw, nowNanos);

        double minDegrees = config != null ? config.getSnapAimMinDegrees() : 45.0;
        double maxGapMs = config != null ? config.getSnapAimMaxGapMs() : 150.0;
        double minRatio = config != null ? config.getSnapAimMinMagnitudeRatio() : 0.5;

        if (prevNanos == 0L) return CheckResult.pass("SnapAim");

        double gapMs = (nowNanos - prevNanos) / 1_000_000.0;
        if (gapMs < 0 || gapMs > maxGapMs) return CheckResult.pass("SnapAim");

        double magA = Math.abs(prevDelta);
        double magB = Math.abs(signedDeltaYaw);
        boolean bothLarge = magA >= minDegrees && magB >= minDegrees;
        boolean opposite = Math.signum(prevDelta) != 0
                && Math.signum(signedDeltaYaw) != 0
                && Math.signum(prevDelta) != Math.signum(signedDeltaYaw);
        double ratio = Math.min(magA, magB) / Math.max(magA, magB);
        boolean comparable = ratio >= minRatio;

        DecayingEvidence evidence = data.getSnapAimEvidence();
        double halfLifeMs = config != null ? config.getSnapAimEvidenceHalfLifeMs() : 20_000.0;

        if (bothLarge && opposite && comparable) {
            double threshold = config != null ? config.getSnapAimEvidenceThreshold() : 3.0;
            int minEvents = config != null ? config.getSnapAimEvidenceMinEvents() : 3;
            double score = evidence.reward(1.0, nowNanos, halfLifeMs);
            // A one-off is consumed here regardless of whether it crosses the bar, so the pair
            // that just fired cannot also seed a THIRD comparison against whichever rotation
            // follows it.
            data.setPrevSignedDeltaYaw(0f, 0L);

            if (score >= threshold && evidence.events() >= minEvents) {
                Map<String, Object> details = new HashMap<>();
                details.put("firstDeltaYaw", prevDelta);
                details.put("secondDeltaYaw", signedDeltaYaw);
                details.put("gapMs", gapMs);
                details.put("score", score);
                details.put("threshold", threshold);
                details.put("eventsEvaluated", evidence.events());

                String explanation = String.format(Locale.US,
                        "Mechanical rotation reversal: %.1f° then %.1f° back within %.0fms (evidence %.1f/%.1f)",
                        prevDelta, signedDeltaYaw, gapMs, score, threshold);

                evidence.reset();
                return CheckResult.flag("SnapAim", confidenceFor(0.90, score, threshold), 2.2, explanation, details);
            }
        } else if (magA > 1.0 || magB > 1.0) {
            // Only relieve on genuine rotation, not on ticks with no mouse movement at all - those
            // are silent for everyone and should neither help nor hurt the accumulator.
            evidence.relieve(0.4, nowNanos, halfLifeMs);
        }

        return CheckResult.pass("SnapAim");
    }

    private static double confidenceFor(double base, double score, double threshold) {
        double over = Math.max(0.0, score - threshold);
        return Math.min(0.97, base + Math.min(0.07, over * 0.03));
    }
}
