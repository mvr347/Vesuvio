package net.lovelace.vesuvio;

import net.lovelace.vesuvio.data.AimRingBuffer;
import net.lovelace.vesuvio.data.AimTrackingBuffer;
import net.lovelace.vesuvio.feature.AimFeatureExtractor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for the target-relative features (11: speed/error correlation, 13: tight-tracking
 * fraction, 14: signed bias) that KillauraAngleCheck#checkTrackingLag reads directly - the one
 * signal in this codebase aimed specifically at a well-humanized CONTINUOUS killaura, which passes
 * GCD, PerfectAimLock and FOV/SilentAim because its jerk looks human and its angle at the exact
 * attack tick is genuinely correct.
 */
class AimFeatureExtractorTrackingTest {

    private static final long MS = 1_000_000L;

    @Test
    void humanLikeTrackingShowsPositiveCorrelationAndABias() {
        AimRingBuffer ring = new AimRingBuffer();
        AimTrackingBuffer tracking = new AimTrackingBuffer();

        long now = 0L;
        float requiredYaw = 0f;
        float actualYaw = 0f;
        // A target juking side to side at varying speed. The "human" always aims at where the
        // target WAS one tick ago (a one-tick reaction lag) plus a small, constant rightward bias
        // from favoring that side of their crosshair - both are real, physically-grounded traits.
        float[] targetSteps = {2f, 8f, 3f, 15f, 4f, 20f, 2f, 25f, 5f, 10f, 3f, 18f, 6f, 22f, 2f, 12f,
                4f, 16f, 3f, 9f, 5f, 14f, 2f, 11f, 3f, 17f, 4f, 8f, 6f, 13f};
        final float humanBias = 1.6f;

        for (float step : targetSteps) {
            ring.addRotation(actualYaw, 0f, now);
            tracking.record(requiredYaw, 0f, actualYaw, 0f, now);

            // The actor catches up to where the target required to be BEFORE this step, plus bias -
            // so this tick's error is proportional to how far the target just moved (the lag).
            actualYaw = requiredYaw + humanBias;
            requiredYaw += step;
            now += 50 * MS;
        }

        float[] features = AimFeatureExtractor.extract(ring, tracking);
        double correlation = features[11];
        double signedBias = features[14];

        assertTrue(correlation > 0.5, "a human's error should clearly grow with target speed, got " + correlation);
        assertTrue(Math.abs(signedBias) > 1.0, "a human holds a real bias, not a centred error, got " + signedBias);
    }

    @Test
    void recalculatedAimStaysFlatAndCentredRegardlessOfTargetSpeed() {
        AimRingBuffer ring = new AimRingBuffer();
        AimTrackingBuffer tracking = new AimTrackingBuffer();

        long now = 0L;
        float requiredYaw = 0f;
        // Same target manoeuvre as the human case above.
        float[] targetSteps = {2f, 8f, 3f, 15f, 4f, 20f, 2f, 25f, 5f, 10f, 3f, 18f, 6f, 22f, 2f, 12f,
                4f, 16f, 3f, 9f, 5f, 14f, 2f, 11f, 3f, 17f, 4f, 8f, 6f, 13f};

        for (float step : targetSteps) {
            // Recalculated every tick: the actor is exactly on target (tiny, speed-independent
            // jitter only), no matter how far the target just jumped.
            float jitter = (requiredYaw % 2 == 0) ? 0.15f : -0.15f;
            float actualYaw = requiredYaw + jitter;

            ring.addRotation(actualYaw, 0f, now);
            tracking.record(requiredYaw, 0f, actualYaw, 0f, now);

            requiredYaw += step;
            now += 50 * MS;
        }

        float[] features = AimFeatureExtractor.extract(ring, tracking);
        double correlation = features[11];
        double signedBias = features[14];
        double tightFraction = features[13];

        assertTrue(correlation < 0.15, "error must not track target speed when the angle is recalculated every tick, got " + correlation);
        assertTrue(Math.abs(signedBias) < 1.0, "a recalculated angle centres on zero, got " + signedBias);
        assertTrue(tightFraction > 0.55, "a recalculated angle stays under 1 degree almost every tick, got " + tightFraction);
    }

    @Test
    void aStationaryTargetProducesNoUsableSpeedSignalEitherWay() {
        AimRingBuffer ring = new AimRingBuffer();
        AimTrackingBuffer tracking = new AimTrackingBuffer();

        long now = 0L;
        // Target never moves - required angle is constant. Both a human and an assisted aim
        // legitimately look identical here (nothing to lag behind, nothing to bias against), which
        // is exactly why the real check gates on mean target speed before trusting this signal.
        for (int i = 0; i < 30; i++) {
            ring.addRotation(0.1f * i, 0f, now);
            tracking.record(10f, 0f, 10f, 0f, now);
            now += 50 * MS;
        }

        float[] features = AimFeatureExtractor.extract(ring, tracking);
        double meanTargetSpeed = features[12];
        assertTrue(meanTargetSpeed < 3.0, "a stationary target must not look like a fast-moving one, got " + meanTargetSpeed);
    }
}
