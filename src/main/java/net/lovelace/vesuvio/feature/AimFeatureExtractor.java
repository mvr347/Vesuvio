package net.lovelace.vesuvio.feature;

import net.lovelace.vesuvio.data.AimRingBuffer;
import net.lovelace.vesuvio.data.AimTrackingBuffer;

import java.util.Arrays;

/**
 * Feature extractor for mouse aim dynamics.
 * Extracts angular speed, jerk/acceleration, GCD sensitivity consistency,
 * and snap rotation ratios.
 *
 * Author: Lovelace
 */
public final class AimFeatureExtractor {

    public static final int FEATURE_COUNT = 16;

    private static final ThreadLocal<float[]> YAW_BUFFER = ThreadLocal.withInitial(() -> new float[AimRingBuffer.SIZE]);
    private static final ThreadLocal<float[]> PITCH_BUFFER = ThreadLocal.withInitial(() -> new float[AimRingBuffer.SIZE]);
    private static final ThreadLocal<float[]> FEATURE_BUFFER = ThreadLocal.withInitial(() -> new float[FEATURE_COUNT]);
    private static final ThreadLocal<float[]> TRACK_REQ_YAW = ThreadLocal.withInitial(() -> new float[AimTrackingBuffer.SIZE]);
    private static final ThreadLocal<float[]> TRACK_ACT_YAW = ThreadLocal.withInitial(() -> new float[AimTrackingBuffer.SIZE]);
    private static final ThreadLocal<float[]> TRACK_REQ_PITCH = ThreadLocal.withInitial(() -> new float[AimTrackingBuffer.SIZE]);
    private static final ThreadLocal<float[]> TRACK_ACT_PITCH = ThreadLocal.withInitial(() -> new float[AimTrackingBuffer.SIZE]);
    private static final ThreadLocal<long[]> TRACK_STAMPS = ThreadLocal.withInitial(() -> new long[AimTrackingBuffer.SIZE]);

    private AimFeatureExtractor() {}

    public static float[] extract(AimRingBuffer buffer) {
        float[] dy = YAW_BUFFER.get();
        float[] dp = PITCH_BUFFER.get();
        buffer.copyDeltas(dy, dp);
        int n = buffer.getCount();

        float[] features = FEATURE_BUFFER.get();
        Arrays.fill(features, 0f);

        if (n < 8) return features;

        // 1. Mean Yaw Delta
        double sumYaw = 0;
        for (int i = 0; i < n; i++) sumYaw += dy[i];
        double meanYaw = sumYaw / n;
        features[0] = (float) meanYaw;

        // 2. Mean Pitch Delta
        double sumPitch = 0;
        for (int i = 0; i < n; i++) sumPitch += dp[i];
        double meanPitch = sumPitch / n;
        features[1] = (float) meanPitch;

        // 3. Yaw Variance & Std
        double varYaw = 0;
        for (int i = 0; i < n; i++) {
            double d = dy[i] - meanYaw;
            varYaw += d * d;
        }
        features[2] = (float) Math.sqrt(varYaw / n);

        // 4. Pitch Variance & Std
        double varPitch = 0;
        for (int i = 0; i < n; i++) {
            double d = dp[i] - meanPitch;
            varPitch += d * d;
        }
        features[3] = (float) Math.sqrt(varPitch / n);

        // 5. Snap Aim Ratio (instances with sudden massive rotation > 25 degrees)
        int snaps = 0;
        for (int i = 0; i < n; i++) {
            if (dy[i] > 25.0f || dp[i] > 20.0f) snaps++;
        }
        features[4] = (float) snaps / n;

        // 6. Zero Rotation Count (head staying completely static)
        int zeros = 0;
        for (int i = 0; i < n; i++) {
            if (dy[i] < 0.001f && dp[i] < 0.001f) zeros++;
        }
        features[5] = (float) zeros / n;

        // 7. Acceleration / Jerk in Yaw
        double accelSum = 0;
        for (int i = 1; i < n; i++) {
            accelSum += Math.abs(dy[i] - dy[i - 1]);
        }
        features[6] = (float) (accelSum / (n - 1));

        // 8. GCD Divisor consistency (Minecraft sensitivity divisor test)
        // Checks whether rotation deltas align with legitimate mouse sensitivity step
        features[7] = calculateGCDConsistency(dy, n);

        return features;
    }

    /**
     * Extracts the same 0-7 rotation-dynamics features and additionally fills 8-15 with
     * <em>target-relative</em> ones.
     *
     * <p>Features 0-7 describe the camera in isolation ("it turned 3.2° this tick"), which is
     * blind to the one thing that separates aim assistance from a human: the relationship between
     * the camera and the target. 8-15 describe that relationship - how large the aiming error is,
     * how steady it is, and (most importantly) how quickly it responds to the target manoeuvring.
     * A humanized module perturbs 0-7 easily by adding noise; it cannot make 8-15 look human
     * without actually missing when the target jukes.
     *
     * <p>Slots 8-15 are zero when no tracking data exists, which is exactly what older models
     * trained on the 8-wide vector already assume.
     */
    public static float[] extract(AimRingBuffer buffer, AimTrackingBuffer tracking) {
        float[] features = extract(buffer);
        if (tracking == null) return features;

        float[] reqYaw = TRACK_REQ_YAW.get();
        float[] actYaw = TRACK_ACT_YAW.get();
        float[] reqPitch = TRACK_REQ_PITCH.get();
        float[] actPitch = TRACK_ACT_PITCH.get();
        long[] stamps = TRACK_STAMPS.get();
        int t = tracking.copy(reqYaw, actYaw, reqPitch, actPitch, stamps);
        if (t < 8) return features;

        // 9. Mean absolute yaw error, 10. mean absolute pitch error.
        double sumYawErr = 0, sumPitchErr = 0;
        for (int i = 0; i < t; i++) {
            sumYawErr += Math.abs(AimTrackingBuffer.wrapDegrees(actYaw[i], reqYaw[i]));
            sumPitchErr += Math.abs(actPitch[i] - reqPitch[i]);
        }
        double meanYawErr = sumYawErr / t;
        features[8] = (float) meanYawErr;
        features[9] = (float) (sumPitchErr / t);

        // 11. Standard deviation of yaw error. A human's error breathes as the target moves;
        // a recalculated angle holds a near-constant offset.
        double varYawErr = 0;
        for (int i = 0; i < t; i++) {
            double d = Math.abs(AimTrackingBuffer.wrapDegrees(actYaw[i], reqYaw[i])) - meanYawErr;
            varYawErr += d * d;
        }
        features[10] = (float) Math.sqrt(varYawErr / t);

        // 12. Correlation between how fast the target moves and how large the error is. A human
        // lags behind a fast-moving target, so this is clearly positive; aim assistance keeps the
        // error flat regardless of target speed, driving it toward zero.
        double sumSpeed = 0, sumErr = 0;
        for (int i = 1; i < t; i++) {
            sumSpeed += Math.abs(AimTrackingBuffer.wrapDegrees(reqYaw[i], reqYaw[i - 1]));
            sumErr += Math.abs(AimTrackingBuffer.wrapDegrees(actYaw[i], reqYaw[i]));
        }
        double meanSpeed = sumSpeed / (t - 1);
        double meanErr = sumErr / (t - 1);
        double cov = 0, varSpeed = 0, varErr = 0;
        for (int i = 1; i < t; i++) {
            double ds = Math.abs(AimTrackingBuffer.wrapDegrees(reqYaw[i], reqYaw[i - 1])) - meanSpeed;
            double de = Math.abs(AimTrackingBuffer.wrapDegrees(actYaw[i], reqYaw[i])) - meanErr;
            cov += ds * de;
            varSpeed += ds * ds;
            varErr += de * de;
        }
        features[11] = (varSpeed > 1e-9 && varErr > 1e-9)
                ? (float) (cov / Math.sqrt(varSpeed * varErr)) : 0f;

        // 13. Mean camera speed while tracking, 14. fraction of ticks the error sat under 1°.
        int tightTicks = 0;
        for (int i = 0; i < t; i++) {
            if (Math.abs(AimTrackingBuffer.wrapDegrees(actYaw[i], reqYaw[i])) < 1.0f) tightTicks++;
        }
        features[12] = (float) meanSpeed;
        features[13] = (float) tightTicks / t;

        // 15. Signed mean yaw error - a persistent bias to one side is a human holding an offset,
        // whereas a recomputed angle centres on zero.
        double signedSum = 0;
        for (int i = 0; i < t; i++) {
            signedSum += AimTrackingBuffer.wrapDegrees(actYaw[i], reqYaw[i]);
        }
        features[14] = (float) (signedSum / t);

        // 16. Samples actually available, normalized - lets a model discount a thin window.
        features[15] = (float) t / AimTrackingBuffer.SIZE;

        return features;
    }

    private static float calculateGCDConsistency(float[] deltas, int n) {
        // Find smallest non-zero delta
        float minDelta = Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            if (deltas[i] > 0.01f && deltas[i] < minDelta) {
                minDelta = deltas[i];
            }
        }
        if (minDelta == Float.MAX_VALUE || minDelta < 0.005f) return 1.0f;

        int aligned = 0;
        for (int i = 0; i < n; i++) {
            if (deltas[i] > 0.01f) {
                float remainder = deltas[i] % minDelta;
                if (remainder < 0.01f || (minDelta - remainder) < 0.01f) {
                    aligned++;
                }
            }
        }
        return (float) aligned / n;
    }
}
