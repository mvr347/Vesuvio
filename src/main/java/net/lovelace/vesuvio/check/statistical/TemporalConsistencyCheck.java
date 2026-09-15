package net.lovelace.vesuvio.check.statistical;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.data.ClickSignature;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.feature.ClickFeatureExtractor;

import java.util.HashMap;
import java.util.Locale;
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
        float currentDupRatio = f[4];
        float currentEntropy = f[5];
        float currentPeakCps = f[6];
        float currentCps = f[14];

        // 1. Record early combat snapshot (first 0 - 3 seconds)
        if (combatDuration <= 3500) {
            if (data.getEarlyCombatVariance() < 0 && data.getClickBuffer().getCount() >= 16) {
                data.setEarlyCombatMean(currentMean);
                data.setEarlyCombatVariance(currentStdDev);
                data.setEarlyCombatDupRatio(currentDupRatio);
                data.setEarlyCombatEntropy(currentEntropy);
                data.setEarlyCombatPeakCps(currentPeakCps);
                data.setEarlyCombatSignature(data.getClickBuffer().getSignature());
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
                details.put("earlyDupRatio", data.getEarlyCombatDupRatio());
                details.put("currentDupRatio", currentDupRatio);
                details.put("earlyEntropy", data.getEarlyCombatEntropy());
                details.put("currentEntropy", currentEntropy);
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

                // Anomaly C: Duplicate-interval ratio spikes mid-fight - a legit-looking, jittered
                // early window suddenly becoming heavily self-repeating is the signature of a
                // click-assist module being toggled on partway through a fight rather than used the
                // whole time (used-the-whole-time modules are already caught by StatisticalClickCheck).
                float earlyDup = data.getEarlyCombatDupRatio();
                if (earlyDup >= 0 && earlyDup < 0.15f && currentDupRatio > 0.55f) {
                    return CheckResult.flag("TemporalConsistency", 0.89, 2.2,
                            String.format(Locale.US, "Mid-fight duplicate-interval spike (Early: %.2f -> Late: %.2f)",
                                    earlyDup, currentDupRatio), details);
                }

                // Anomaly D: Entropy collapse - natural early-fight click timing spread narrows
                // into just a couple of dominant bins, the same "mode switched on mid-fight" shape
                // as Anomaly A but seen in the timing distribution rather than the raw std-dev.
                float earlyEntropy = data.getEarlyCombatEntropy();
                if (earlyEntropy >= 2.2f && currentEntropy < 1.0f) {
                    return CheckResult.flag("TemporalConsistency", 0.87, 2.0,
                            String.format(Locale.US, "Mid-fight click-timing entropy collapse (Early: %.2f -> Late: %.2f bits)",
                                    earlyEntropy, currentEntropy), details);
                }

                // Anomaly E: Signature similarity - the rolling click signature barely changed
                // between two windows that are seconds apart and built from entirely different
                // clicks. A human's signature drifts continuously (fatigue, grip adjustment,
                // micro-corrections); a near-frozen signature under sustained fast clicking means
                // the same mechanical pattern is being reproduced rather than played.
                long[] earlySig = data.getEarlyCombatSignature();
                if (earlySig != null && currentCps > 9.0) {
                    float similarity = ClickSignature.hammingSimilarity(earlySig, data.getClickBuffer().getSignature());
                    if (similarity > 0.985f) {
                        details.put("signatureSimilarity", similarity);
                        return CheckResult.flag("TemporalConsistency", 0.85, 1.8,
                                String.format(Locale.US, "Click signature frozen across combat (similarity %.1f%% over %.1fs)",
                                        similarity * 100, combatDuration / 1000.0), details);
                    }
                }
            }
        }

        return CheckResult.pass("TemporalConsistency");
    }
}
