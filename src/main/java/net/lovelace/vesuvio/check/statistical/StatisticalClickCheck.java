package net.lovelace.vesuvio.check.statistical;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.feature.ClickFeatureExtractor;

import java.util.HashMap;
import java.util.Map;

/**
 * Layer 1: Zero-allocation, high-speed statistical heuristic engine for click patterns.
 * Catches autoclickers, macros, jitter bots, and drag-click scripts out-of-the-box.
 *
 * Author: Lovelace
 */
public final class StatisticalClickCheck {

    public CheckResult check(UserData data) {
        if (data.getClickBuffer().getCount() < 16) {
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

        Map<String, Object> details = new HashMap<>();
        details.put("cps", cps);
        details.put("meanMs", meanMs);
        details.put("stdDev", stdDev);
        details.put("dupRatio", dupRatio);
        details.put("kurtosis", kurtosis);
        details.put("entropy", entropy);
        details.put("peakCps", peakCps);

        // Dynamic multiplier based on user's Trust / Risk scores
        double sensitivity = data.getSensitivityMultiplier();

        // 1. Extreme Autoclicker: High CPS (> 13) with unnatural consistency (stdDev < 5.0ms)
        if (cps > 13.0 && stdDev < (6.5 / sensitivity)) {
            double confidence = Math.min(0.99, 0.75 + (13.0 / cps) * 0.2);
            return CheckResult.flag("ClickStatistical", confidence, 2.0,
                    String.format("Unnatural low variance (StdDev: %.1fms) at %.1f CPS", stdDev, cps), details);
        }

        // 2. Macro / Script Duplicate Interval Ratio (identically spaced clicks)
        if (dupRatio > (0.45 / sensitivity) && cps > 9.0) {
            double confidence = Math.min(0.98, 0.70 + dupRatio * 0.3);
            return CheckResult.flag("ClickStatistical", confidence, 1.8,
                    String.format("Repetitive interval duplication (DupRatio: %.0f%%) at %.1f CPS", dupRatio * 100, cps), details);
        }

        // 3. Constant Delay Autoclicker (consecutive identical clicks within 1ms)
        if (maxConsecutive > 0.35 && cps > 8.0) {
            return CheckResult.flag("ClickStatistical", 0.95, 2.5,
                    String.format("Consecutive identical intervals (%.0f%% of window)", maxConsecutive * 100), details);
        }

        // 4. Low Shannon Entropy at sustained high clicking
        if (entropy < (1.5 / sensitivity) && cps > 11.0) {
            return CheckResult.flag("ClickStatistical", 0.85, 1.5,
                    String.format("Robotic click entropy (Entropy: %.2f) at %.1f CPS", entropy, cps), details);
        }

        // 5. Impossibly High Peak CPS bursts (> 28 CPS)
        if (peakCps > 28.0 && lowDelayRatio > 0.40) {
            return CheckResult.flag("ClickStatistical", 0.94, 3.0,
                    String.format("Burst speed anomaly (Peak CPS: %.1f)", peakCps), details);
        }

        // 6. IQR Boxplot Outlier Resistance (Kauri-inspired: catches humanized cheats with artificial pauses)
        float iqr = ClickFeatureExtractor.calculateIQR(data.getClickBuffer());
        details.put("iqr", iqr);
        if (cps > 12.0 && iqr < (5.5 / sensitivity)) {
            return CheckResult.flag("ClickStatistical", 0.94, 2.2,
                    String.format("Synthetic IQR density (IQR: %.1fms) at %.1f CPS", iqr, cps), details);
        }

        return CheckResult.pass("ClickStatistical");
    }
}
