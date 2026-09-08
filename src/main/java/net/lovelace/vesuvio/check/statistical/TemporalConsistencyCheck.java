package net.lovelace.vesuvio.check.statistical;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.feature.ClickFeatureExtractor;

import java.util.HashMap;
import java.util.Map;

/**
 * Advanced Mechanic 1.2: Temporal Consistency Check.
 * Compares player's clicking metrics in early combat (0-3 seconds)
 * vs sustained combat (8-15 seconds).
 *
 * Real human hands experience physiological fatigue and variance shifts.
 * Paid ghost cheats maintain robotic variance or abruptly switch modes mid-fight.
 *
 * Author: Lovelace
 */
public final class TemporalConsistencyCheck {

    public CheckResult check(UserData data) {
        long combatDuration = data.getCombatDurationMillis();
        if (combatDuration < 1000) {
            return CheckResult.pass("TemporalConsistency");
        }

        float[] f = ClickFeatureExtractor.extract(data.getClickBuffer());
        float currentMean = f[0];
        float currentStdDev = f[1];
        float currentCps = f[14];

        // 1. Record early combat snapshot (first 0 - 3 seconds)
        if (combatDuration <= 3500) {
            if (data.getEarlyCombatVariance() < 0 && data.getClickBuffer().getCount() >= 16) {
                data.setEarlyCombatMean(currentMean);
                data.setEarlyCombatVariance(currentStdDev);
            }
            return CheckResult.pass("TemporalConsistency");
        }

        // 2. Evaluate during sustained combat (8 - 15 seconds)
        if (combatDuration >= 8000 && combatDuration <= 16000) {
            float earlyMean = data.getEarlyCombatMean();
            float earlyStd = data.getEarlyCombatVariance();

            if (earlyMean > 0 && earlyStd > 0 && currentCps > 10.0) {
                Map<String, Object> details = new HashMap<>();
                details.put("earlyMean", earlyMean);
                details.put("currentMean", currentMean);
                details.put("earlyStd", earlyStd);
                details.put("currentStd", currentStdDev);
                details.put("combatDurationSec", combatDuration / 1000.0);

                // Anomaly A: Variance collapsed drastically mid-fight (cheat toggled on)
                if (earlyStd > 25.0f && currentStdDev < 6.0f) {
                    return CheckResult.flag("TemporalConsistency", 0.91, 2.5,
                            String.format("Mid-fight variance collapse (EarlyStd: %.1fms -> LateStd: %.1fms)", earlyStd, currentStdDev), details);
                }

                // Anomaly B: Robotic stamina - 0% fatigue across 12+ seconds of continuous 15+ CPS clicking
                double meanDiff = Math.abs(currentMean - earlyMean);
                double stdDiff = Math.abs(currentStdDev - earlyStd);
                if (currentCps > 14.0 && meanDiff < 1.0 && stdDiff < 0.8) {
                    return CheckResult.flag("TemporalConsistency", 0.88, 2.0,
                            String.format("Superhuman fatigue resistance at %.1f CPS (ΔMean: %.2fms)", currentCps, meanDiff), details);
                }
            }
        }

        return CheckResult.pass("TemporalConsistency");
    }
}
