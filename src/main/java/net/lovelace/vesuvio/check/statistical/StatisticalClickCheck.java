package net.lovelace.vesuvio.check.statistical;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.feature.ClickFeatureExtractor;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Layer 1: Zero-allocation, high-speed statistical heuristic engine for click patterns.
 * Catches autoclickers, macros, jitter bots, and drag-click scripts out-of-the-box.
 *
 * Warm-up tiers:
 *  - Soft tier (12+ samples): coarse but fast checks, catch obvious autoclickers immediately.
 *  - Full tier (28+ samples): higher-confidence checks that need a wider statistical window
 *    (peak-burst detection, IQR outlier resistance).
 *
 * Sensitivity direction convention (see UserData#getSensitivityMultiplier):
 *  - "flag if metric < threshold"  -> threshold * sensitivity  (harsher players get a bigger,
 *    easier-to-satisfy threshold)
 *  - "flag if metric > threshold"  -> threshold / sensitivity  (harsher players get a smaller,
 *    easier-to-exceed threshold)
 *
 * Author: Lovelace
 */
public final class StatisticalClickCheck {

    private static final int SOFT_MIN_SAMPLES = 12;
    private static final int FULL_MIN_SAMPLES = 28;

    public CheckResult check(UserData data) {
        if (data.getClickBuffer().getCount() < SOFT_MIN_SAMPLES) {
            return CheckResult.pass("ClickStatistical");
        }

        float[] f = ClickFeatureExtractor.extract(data.getClickBuffer(), data.getEarlyCombatMean());
        float meanMs = f[0];
        float stdDev = f[1];
        float kurtosis = f[3];
        float dupRatio = f[4];
        float entropy = f[5];
        float peakCps = f[6];
        float maxConsecutive = f[7];
        float lowDelayRatio = f[8];
        float cps = f[14];

        data.setLastCalculatedCPS(cps);
        data.setLastStdDevMs(stdDev);
        data.setLastDupRatio(dupRatio);
        data.setLastEntropy(entropy);

        Map<String, Object> details = new HashMap<>();
        details.put("cps", cps);
        details.put("meanMs", meanMs);
        details.put("stdDev", stdDev);
        details.put("dupRatio", dupRatio);
        details.put("kurtosis", kurtosis);
        details.put("entropy", entropy);
        details.put("peakCps", peakCps);
        details.put("maxConsecutive", maxConsecutive);
        details.put("samples", data.getClickBuffer().getCount());

        // Dynamic multiplier based on user's Trust / Risk scores
        double sensitivity = data.getSensitivityMultiplier();

        // 1. High CPS with unnatural consistency (StdDev too low for a human at that speed)
        if (cps >= 12.0 && stdDev < (8.5 * sensitivity)) {
            double confidence = Math.min(0.99, 0.78 + (12.0 / cps) * 0.2);
            return CheckResult.flag("ClickStatistical", confidence, 2.0,
                    String.format(Locale.US, "Unnatural low variance (StdDev: %.1fms) at %.1f CPS", stdDev, cps), details);
        }

        // 2. Macro / Script Duplicate Interval Ratio (identically spaced clicks)
        if (dupRatio >= (0.30 / sensitivity) && cps >= 10.0) {
            double confidence = Math.min(0.97, 0.68 + dupRatio * 0.3);
            return CheckResult.flag("ClickStatistical", confidence, 1.8,
                    String.format(Locale.US, "Repetitive interval duplication (DupRatio: %.0f%%) at %.1f CPS", dupRatio * 100, cps), details);
        }

        // 3. Constant Delay Autoclicker (consecutive near-identical clicks within 1ms)
        if (maxConsecutive >= (0.28 / sensitivity) && cps >= 7.0) {
            return CheckResult.flag("ClickStatistical", 0.93, 2.4,
                    String.format(Locale.US, "Consecutive identical intervals (%.0f%% of window)", maxConsecutive * 100), details);
        }

        // 4. Low Shannon Entropy at sustained high clicking
        if (entropy < (1.6 * sensitivity) && cps >= 10.0) {
            return CheckResult.flag("ClickStatistical", 0.85, 1.5,
                    String.format(Locale.US, "Robotic click entropy (Entropy: %.2f) at %.1f CPS", entropy, cps), details);
        }

        if (data.getClickBuffer().getCount() < FULL_MIN_SAMPLES) {
            return CheckResult.pass("ClickStatistical");
        }

        // 5. Impossibly High Peak CPS bursts (> 24 CPS)
        if (peakCps > 24.0 && lowDelayRatio > (0.35 / sensitivity)) {
            return CheckResult.flag("ClickStatistical", 0.94, 3.0,
                    String.format(Locale.US, "Burst speed anomaly (Peak CPS: %.1f)", peakCps), details);
        }

        // 6. IQR Boxplot Outlier Resistance (Kauri-inspired: catches humanized cheats with artificial pauses)
        float iqr = ClickFeatureExtractor.calculateIQR(data.getClickBuffer());
        details.put("iqr", iqr);
        if (cps >= 11.0 && iqr < (6.5 * sensitivity)) {
            return CheckResult.flag("ClickStatistical", 0.93, 2.2,
                    String.format(Locale.US, "Synthetic IQR density (IQR: %.1fms) at %.1f CPS", iqr, cps), details);
        }

        return CheckResult.pass("ClickStatistical");
    }
}
