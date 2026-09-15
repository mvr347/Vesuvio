package net.lovelace.vesuvio.check.statistical;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.config.ConfigManager;
import net.lovelace.vesuvio.data.AimTrackingBuffer;
import net.lovelace.vesuvio.data.UserData;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Sensorimotor-latency check: how fast does the attacker's camera answer a target that suddenly
 * reverses direction?
 *
 * <h2>Why this survives "humanization" when the other aim checks don't</h2>
 * Everything else in {@link KillauraAngleCheck} measures <em>how well</em> someone aims - angle
 * error, jitter, streaks of precision. A paid humanized module defeats all of it by adding noise,
 * curves and randomized delay on top of an already-known correct angle. This check measures
 * something different: <em>what information the decision could have been based on</em>.
 *
 * <p>A human tracks a target from a mental picture roughly 150-250ms old. When the target reverses
 * its strafe, the human's camera is committed to the old direction and <em>must</em> keep going
 * that way for that long before the correction lands - that is physiology, not skill, and it is a
 * systematic error correlated with a specific event at the target, not random noise that can be
 * imitated by adding randomness. A module that recomputes the angle answers in the same tick or
 * the next one. To pass this check, a cheat has to genuinely miss right at the moment the target
 * jukes - which is exactly the moment the aura is bought for.
 *
 * <h2>Accumulator rather than a streak</h2>
 * Deliberately not a "N consecutive" streak like the older sub-checks: a randomized module breaks
 * any consecutive requirement by behaving humanly one attack in five. Each reversal event nudges a
 * decaying accumulator instead, so the verdict reflects the balance of evidence across a fight.
 *
 * Author: Lovelace
 */
public final class StrafeReversalCheck {

    private final ConfigManager config;

    public StrafeReversalCheck(ConfigManager config) {
        this.config = config;
    }

    private static final ThreadLocal<float[]> REQ_YAW = ThreadLocal.withInitial(() -> new float[AimTrackingBuffer.SIZE]);
    private static final ThreadLocal<float[]> ACT_YAW = ThreadLocal.withInitial(() -> new float[AimTrackingBuffer.SIZE]);
    private static final ThreadLocal<float[]> REQ_PITCH = ThreadLocal.withInitial(() -> new float[AimTrackingBuffer.SIZE]);
    private static final ThreadLocal<float[]> ACT_PITCH = ThreadLocal.withInitial(() -> new float[AimTrackingBuffer.SIZE]);
    private static final ThreadLocal<long[]> STAMPS = ThreadLocal.withInitial(() -> new long[AimTrackingBuffer.SIZE]);

    public CheckResult check(UserData data) {
        if (data == null || config == null || !config.isStrafeReversalEnabled()) {
            return CheckResult.pass("StrafeReversal");
        }

        AimTrackingBuffer buffer = data.getAimTrackingBuffer();
        // Two samples per velocity, plus room on each side of an event to measure a response.
        int minSamples = 12;
        if (buffer.getCount() < minSamples) return CheckResult.pass("StrafeReversal");

        float[] reqYaw = REQ_YAW.get();
        float[] actYaw = ACT_YAW.get();
        float[] reqPitch = REQ_PITCH.get();
        float[] actPitch = ACT_PITCH.get();
        long[] stamps = STAMPS.get();
        int n = buffer.copy(reqYaw, actYaw, reqPitch, actPitch, stamps);
        if (n < minSamples) return CheckResult.pass("StrafeReversal");

        double minReversalSpeed = config.getStrafeReversalMinSpeedDegrees();
        int maxResponseTicks = config.getStrafeReversalMaxResponseTicks();
        int fastTicks = config.getStrafeReversalFastResponseTicks();
        int humanTicks = config.getStrafeReversalHumanResponseTicks();

        long lastAnalyzed = data.getLastStrafeReversalAnalyzedNanos();
        long newestAnalyzed = lastAnalyzed;

        int fastEvents = 0;
        int humanEvents = 0;
        double lastReactionTicks = -1;

        // i indexes velocities: vel[i] = yaw[i] - yaw[i-1], so the first usable index is 1.
        for (int i = 2; i < n - 1; i++) {
            if (stamps[i] <= lastAnalyzed) continue;

            float targetVelBefore = AimTrackingBuffer.wrapDegrees(reqYaw[i - 1], reqYaw[i - 2]);
            float targetVelAfter = AimTrackingBuffer.wrapDegrees(reqYaw[i], reqYaw[i - 1]);

            // A real reversal: the target was moving meaningfully one way and is now moving
            // meaningfully the other. The speed floor keeps sub-degree jitter and standing targets
            // from manufacturing events.
            boolean reversed = Math.signum(targetVelBefore) != Math.signum(targetVelAfter)
                    && Math.abs(targetVelBefore) >= minReversalSpeed
                    && Math.abs(targetVelAfter) >= minReversalSpeed;
            if (!reversed) continue;

            int response = findResponseTicks(actYaw, i, n, targetVelAfter, maxResponseTicks, minReversalSpeed * 0.5);
            if (response < 0) continue; // the camera never followed - nothing to judge either way

            newestAnalyzed = Math.max(newestAnalyzed, stamps[i]);
            lastReactionTicks = response;

            if (response <= fastTicks) {
                fastEvents++;
                data.addStrafeReversalScore(config.getStrafeReversalScorePerFastEvent());
            } else if (response >= humanTicks) {
                humanEvents++;
                data.addStrafeReversalScore(-config.getStrafeReversalDecayPerHumanEvent());
            }
            data.incrementStrafeReversalEvents();
        }

        if (newestAnalyzed > lastAnalyzed) {
            data.setLastStrafeReversalAnalyzedNanos(newestAnalyzed);
        }

        double score = data.getStrafeReversalScore();
        int totalEvents = data.getStrafeReversalEvents();
        if (totalEvents < config.getStrafeReversalMinEvents() || score < config.getStrafeReversalFlagScore()) {
            return CheckResult.pass("StrafeReversal");
        }

        Map<String, Object> details = new HashMap<>();
        details.put("score", score);
        details.put("eventsAnalyzed", totalEvents);
        details.put("fastEventsThisPass", fastEvents);
        details.put("humanEventsThisPass", humanEvents);
        details.put("lastReactionTicks", lastReactionTicks);
        details.put("lastReactionMs", lastReactionTicks * 50.0);
        details.put("humanFloorMs", humanTicks * 50.0);

        String explanation = String.format(Locale.US,
                "Camera answered target direction reversals in ~%.0fms across %d events (human sensorimotor floor ~%dms)",
                lastReactionTicks * 50.0, totalEvents, humanTicks * 50);

        data.resetStrafeReversal();
        return CheckResult.flag("StrafeReversal", 0.93, 3.0, explanation, details);
    }

    /**
     * Ticks until the camera's own yaw velocity picks up the target's new direction.
     *
     * @return tick count, or -1 if the camera never followed inside the window
     */
    private static int findResponseTicks(float[] actualYaw, int eventIndex, int n,
                                          float targetVelAfter, int maxResponseTicks, double minCameraSpeed) {
        float wantedSign = Math.signum(targetVelAfter);
        int limit = Math.min(n - 1, eventIndex + maxResponseTicks);
        for (int j = eventIndex; j <= limit; j++) {
            float cameraVel = AimTrackingBuffer.wrapDegrees(actualYaw[j], actualYaw[j - 1]);
            if (Math.abs(cameraVel) >= minCameraSpeed && Math.signum(cameraVel) == wantedSign) {
                return j - eventIndex;
            }
        }
        return -1;
    }
}
