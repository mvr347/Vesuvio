package net.lovelace.vesuvio.feature;

import net.lovelace.vesuvio.data.ClickRingBuffer;

import java.util.Arrays;

/**
 * High-performance, zero-allocation feature extractor for click pattern analysis.
 * Extracts 16 normalized biometric and statistical features using ThreadLocal buffers.
 *
 * Author: Lovelace
 */
public final class ClickFeatureExtractor {

    public static final int FEATURE_COUNT = 16;

    private static final ThreadLocal<long[]> TEMP_BUFFER = ThreadLocal.withInitial(() -> new long[ClickRingBuffer.SIZE]);
    private static final ThreadLocal<float[]> FEATURE_BUFFER = ThreadLocal.withInitial(() -> new float[FEATURE_COUNT]);

    private ClickFeatureExtractor() {}

    /**
     * Extracts 16 statistical & biometric features from the player's ClickRingBuffer.
     * Returns a thread-local float[16]. If caller needs to retain it across threads,
     * they should clone it.
     */
    public static float[] extract(ClickRingBuffer buffer) {
        return extract(buffer, 0f);
    }

    public static float[] extract(ClickRingBuffer buffer, float earlyCombatMeanMs) {
        long[] data = TEMP_BUFFER.get();
        buffer.copyIntervals(data);
        int n = buffer.getCount();

        float[] features = FEATURE_BUFFER.get();
        Arrays.fill(features, 0f);

        if (n < 8) {
            return features; // Not enough samples
        }

        // 1. Mean Delay (ms)
        double sumNanos = 0;
        for (int i = 0; i < n; i++) {
            sumNanos += data[i];
        }
        double meanNanos = sumNanos / n;
        double meanMs = meanNanos / 1_000_000.0;
        features[0] = (float) meanMs;

        // 2. Variance & Standard Deviation (ms)
        double varianceSum = 0;
        for (int i = 0; i < n; i++) {
            double d = (data[i] - meanNanos) / 1_000_000.0;
            varianceSum += d * d;
        }
        double variance = varianceSum / n;
        double std = Math.sqrt(Math.max(1e-6, variance));
        features[1] = (float) std;

        // 3. Skewness & 4. Excess Kurtosis
        double m3 = 0, m4 = 0;
        double stdNanos = std * 1_000_000.0;
        for (int i = 0; i < n; i++) {
            double d = (data[i] - meanNanos) / stdNanos;
            double d2 = d * d;
            m3 += d2 * d;
            m4 += d2 * d2;
        }
        features[2] = (float) (m3 / n);                  // Skewness
        features[3] = (float) Math.max(-3.0, (m4 / n - 3.0)); // Excess Kurtosis

        // 5. Duplicate Ratio (intervals within ±2% of preceding interval)
        int duplicates = 0;
        for (int i = 1; i < n; i++) {
            long a = data[i - 1];
            long b = data[i];
            long diff = Math.abs(a - b);
            if (diff <= a * 0.02) {
                duplicates++;
            }
        }
        features[4] = (float) duplicates / (n - 1);

        // 6. Shannon Entropy (quantized into 20ms bins from 0 to 400ms)
        int[] bins = new int[20];
        for (int i = 0; i < n; i++) {
            int bin = (int) ((data[i] / 1_000_000L) / 20);
            if (bin < 0) bin = 0;
            if (bin >= 20) bin = 19;
            bins[bin]++;
        }
        double entropy = 0.0;
        for (int count : bins) {
            if (count > 0) {
                double p = (double) count / n;
                entropy -= p * (Math.log(p) / Math.log(2));
            }
        }
        features[5] = (float) entropy;

        // 7. Peak CPS in this window
        long minIntervalNanos = Long.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            if (data[i] < minIntervalNanos && data[i] > 0) {
                minIntervalNanos = data[i];
            }
        }
        features[6] = (minIntervalNanos > 0) ? (float) (1_000_000_000.0 / minIntervalNanos) : 0f;

        // 8. Consecutive Identical/Near-Identical Delays (±1ms)
        int consecutiveCount = 0;
        int maxConsecutive = 0;
        for (int i = 1; i < n; i++) {
            if (Math.abs(data[i] - data[i - 1]) < 1_000_000L) { // within 1ms
                consecutiveCount++;
                if (consecutiveCount > maxConsecutive) maxConsecutive = consecutiveCount;
            } else {
                consecutiveCount = 0;
            }
        }
        features[7] = (float) maxConsecutive / n;

        // 9. Low-delay ratio (< 50ms = > 20 CPS bursts)
        int lowCount = 0;
        for (int i = 0; i < n; i++) {
            if (data[i] < 50_000_000L) lowCount++;
        }
        features[8] = (float) lowCount / n;

        // 10. High-delay outlier ratio (> 150ms)
        int highCount = 0;
        for (int i = 0; i < n; i++) {
            if (data[i] > 150_000_000L) highCount++;
        }
        features[9] = (float) highCount / n;

        // 11. Mean Acceleration (second order delta)
        double accelSum = 0;
        for (int i = 2; i < n; i++) {
            double delta1 = (data[i - 1] - data[i - 2]) / 1_000_000.0;
            double delta2 = (data[i] - data[i - 1]) / 1_000_000.0;
            accelSum += Math.abs(delta2 - delta1);
        }
        features[10] = (n >= 3) ? (float) (accelSum / (n - 2)) : 0f;

        // 12. Micro-pause frequency (80ms to 160ms)
        int microPauseCount = 0;
        for (int i = 0; i < n; i++) {
            if (data[i] >= 80_000_000L && data[i] <= 160_000_000L) {
                microPauseCount++;
            }
        }
        features[11] = (float) microPauseCount / n;

        // 13. Lag-1 Autocorrelation
        double autoSum = 0;
        for (int i = 1; i < n; i++) {
            autoSum += (data[i] - meanNanos) * (data[i - 1] - meanNanos);
        }
        double denom = (n * stdNanos * stdNanos);
        features[12] = (denom > 1e-9) ? (float) (autoSum / denom) : 0f;

        // 14. Outlier ratio (> 2.5 std devs from mean)
        int outliers = 0;
        for (int i = 0; i < n; i++) {
            if (Math.abs(data[i] - meanNanos) > 2.5 * stdNanos) {
                outliers++;
            }
        }
        features[13] = (float) outliers / n;

        // 15. Average CPS over current window
        features[14] = (meanMs > 0) ? (float) (1000.0 / meanMs) : 0f;

        // 16. Temporal delta (early combat mean vs current combat mean)
        if (earlyCombatMeanMs > 0) {
            features[15] = (float) (meanMs - earlyCombatMeanMs);
        } else {
            features[15] = 0f;
        }

        return features;
    }

    /**
     * Calculates the Interquartile Range (IQR = Q3 - Q1) of click intervals in ms.
     * Boxplot outlier-resistant metric inspired by Kauri.
     * Paid ghost clients inject artificial long pauses to fool Standard Deviation,
     * but their central IQR remains extremely tight.
     */
    public static float calculateIQR(ClickRingBuffer buffer) {
        int n = buffer.getCount();
        if (n < 8) return 999f;

        long[] copy = new long[n];
        long[] data = TEMP_BUFFER.get();
        buffer.copyIntervals(data);
        System.arraycopy(data, 0, copy, 0, n);
        Arrays.sort(copy);

        int q1Idx = n / 4;
        int q3Idx = (3 * n) / 4;

        double q1Ms = copy[q1Idx] / 1_000_000.0;
        double q3Ms = copy[q3Idx] / 1_000_000.0;

        return (float) Math.max(0.0, q3Ms - q1Ms);
    }
}
