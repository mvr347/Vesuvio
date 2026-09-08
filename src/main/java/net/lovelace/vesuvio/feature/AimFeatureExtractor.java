package net.lovelace.vesuvio.feature;

import net.lovelace.vesuvio.data.AimRingBuffer;

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
