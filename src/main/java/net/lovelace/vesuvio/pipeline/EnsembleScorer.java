package net.lovelace.vesuvio.pipeline;

import net.lovelace.vesuvio.config.ConfigManager;

/**
 * Combines the independent per-layer signals CheckPipeline already computes for a click
 * evaluation (statistical confidence, ONNX probability, self-learning classifier probability,
 * anomaly-repeat score, temporal-consistency confidence) into one weighted score, instead of each
 * layer's flag/no-flag decision acting in isolation.
 *
 * <p>Deliberately additive rather than a replacement for the existing per-layer VL/Risk additions:
 * those thresholds are individually tuned and battle-tested, and rebuilding the whole scoring
 * pipeline around a single fused number is a much larger, riskier change than what was asked for
 * here. This instead answers "how much do the layers agree with each other right now", contributing
 * a bounded Risk adjustment when several independently-tuned signals point the same way even though
 * none of them individually cleared its own flag threshold - the "silent-only contribution" case
 * explicitly called out in the design.
 *
 * Author: Lovelace
 */
public final class EnsembleScorer {

    private EnsembleScorer() {}

    public record Inputs(
            double statisticalConfidence,
            double onnxProbability,
            double selfLearnProbability,
            float anomalyRepeatScore,
            double temporalConfidence
    ) {}

    /** Weighted sum of the (already 0..1-ish) per-layer signals, clamped to [0, 1]. */
    public static double score(Inputs in, ConfigManager config) {
        if (in == null || config == null) return 0.0;
        double weighted =
                config.getEnsembleWeightStatistical() * in.statisticalConfidence()
                        + config.getEnsembleWeightOnnx() * in.onnxProbability()
                        + config.getEnsembleWeightSelfLearn() * in.selfLearnProbability()
                        + config.getEnsembleWeightAnomaly() * in.anomalyRepeatScore()
                        + config.getEnsembleWeightTemporal() * in.temporalConfidence();
        double totalWeight = config.getEnsembleWeightStatistical() + config.getEnsembleWeightOnnx()
                + config.getEnsembleWeightSelfLearn() + config.getEnsembleWeightAnomaly()
                + config.getEnsembleWeightTemporal();
        if (totalWeight <= 0) return 0.0;
        return Math.max(0.0, Math.min(1.0, weighted / totalWeight));
    }
}
