package net.lovelace.vesuvio.pipeline;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.check.onnx.MLManager;
import net.lovelace.vesuvio.check.onnx.MLResult;
import net.lovelace.vesuvio.check.selflearning.AnomalyMemory;
import net.lovelace.vesuvio.check.selflearning.OnlineClassifier;
import net.lovelace.vesuvio.check.selflearning.SelfLearningManager;
import net.lovelace.vesuvio.check.statistical.StatisticalClickCheck;
import net.lovelace.vesuvio.check.statistical.TemporalConsistencyCheck;
import net.lovelace.vesuvio.config.ConfigManager;
import net.lovelace.vesuvio.data.ClickSignature;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.feature.ClickFeatureExtractor;
import org.bukkit.entity.Player;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import net.lovelace.vesuvio.engine.AsyncTasks;
import net.lovelace.vesuvio.engine.LagCompensator;
import net.lovelace.vesuvio.evasion.BanEvasionManager;

/** Click-rhythm detection: statistical, temporal, ONNX and self-learning layers for one player. Extracted from CheckPipeline unchanged; the facade keeps the public API. */
final class ClickPipeline {

    private static final Logger LOGGER = Logger.getLogger("Vesuvio-Pipeline");
    private final ConfigManager config;
    private final MLManager mlManager;
    private final SelfLearningManager selfLearning;
    private final LagCompensator lagCompensator;
    private final BanEvasionManager banEvasionManager;
    private final Executor backgroundExecutor;
    private final StatisticalClickCheck clickCheck = new StatisticalClickCheck();
    private final TemporalConsistencyCheck temporalCheck = new TemporalConsistencyCheck();
    private final PipelineContext ctx;
    private final FlagReporter reporter;

    ClickPipeline(PipelineContext ctx) {
        this.ctx = ctx;
        this.reporter = ctx.reporter();
        this.config = ctx.config();
        this.mlManager = ctx.mlManager();
        this.selfLearning = ctx.selfLearning();
        this.lagCompensator = ctx.lagCompensator();
        this.banEvasionManager = ctx.banEvasionManager();
        this.backgroundExecutor = ctx.backgroundExecutor();
    }

    /**
     * Processes click events from a player. Runs asynchronously.
     */
    public void processClick(Player player, UserData data) {
        if (!config.isAnticheatEnabled() || data.hasRecentTeleport()
                || data.mechanics().combat(System.nanoTime())) return;

        // -------------------------------------------------------------
        // Layer 1: Statistical Engine (Fast, Heuristic)
        // -------------------------------------------------------------
        CheckResult statResult = CheckResult.pass("ClickStatistical");
        if (config.isStatisticalEnabled()) {
            double lagTolerance = (lagCompensator != null) ? lagCompensator.getLagToleranceMultiplier(player) : 1.0;
            statResult = clickCheck.check(data);
            if (statResult.isFlag()) {
                double addedVl = (statResult.vl() * config.getStatisticalWeight()) / lagTolerance;
                reporter.reportWindowed(player, data, statResult, addedVl, (statResult.confidence() * 8.0) / lagTolerance);

                // If certainty is overwhelmingly high (> 0.92), flag without wasting ML inference cycles
                if (statResult.confidence() >= config.getStatisticalCutoff() && lagTolerance <= 1.1) {
                    return;
                }
            }
        }

        // Temporal Consistency Check (Mechanic 1.2)
        double temporalConfidence = 0.0;
        if (config.isTemporalConsistencyEnabled()) {
            CheckResult tempResult = temporalCheck.check(data);
            temporalConfidence = tempResult.confidence();
            if (tempResult.isFlag()) {
                reporter.reportWindowed(player, data, tempResult, tempResult.vl(), 15.0);
            }
        }

        // -------------------------------------------------------------
        // Layer 2: ONNX Machine Learning Inference Layer
        // -------------------------------------------------------------
        float[] features = ClickFeatureExtractor.extract(data.getClickBuffer(), data.getEarlyCombatMean());
        boolean isSuspicious = statResult.isFlag() || statResult.confidence() > 0.40 || data.getLastCalculatedCPS() > 11.5;

        // Held so the ensemble below can be scored against THIS click's probability rather than
        // whatever the previous inference happened to leave behind - see the ensemble block.
        CompletableFuture<MLResult> clickMlFuture = null;

        final long detectionEpoch = config.runtime().detectionEpoch();
        final long resetEpoch = data.getMovementResetNanos();
        if (config.isOnnxEnabled() && isSuspicious && data.shouldRunML()) {
            data.markMLRun();
            float[] featuresCopy = features.clone();

            clickMlFuture = mlManager.evaluateAsync("click_model", featuresCopy);
            clickMlFuture.thenAccept(mlResult -> {
                if (!config.runtime().acceptsDetection(detectionEpoch) || data.getMovementResetNanos() != resetEpoch) return;
                data.setLastMLProbability(mlResult.probability());
                ctx.recordShadowVerdict("click_model", featuresCopy, mlResult.probability());

                if (mlResult.isFlag()) {
                    CheckResult res = CheckResult.flag("ClickML", mlResult.probability(), mlResult.vl(),
                            String.format(Locale.US, "Уверенность модели кликов: %.1f%%", mlResult.probability() * 100),
                            mlResult.details());
                    // ML only alerts: no trust penalty until the models have published metrics (see punishments.excluded-checks).
                    reporter.reportWindowed(player, data, res, mlResult.vl() * config.getOnnxWeight(), mlResult.probability() * 12.0);

                } else if (mlResult.probability() >= config.getActiveLearningMinProb()
                        && mlResult.probability() <= config.getActiveLearningMaxProb()) {
                    // ---------------------------------------------------------
                    // Layer 3: Active Learning Gray-Zone Review Prompt (Mechanic 2.1)
                    // ---------------------------------------------------------
                    selfLearning.getActiveLearning().requestReview(player, data, mlResult, featuresCopy);
                }
            });
        }

        // -------------------------------------------------------------
        // Layer 3a: Online Self-Learning Classifier (pure-Java SGD, trained continuously by
        // AutoDatasetCollector + staff Active Learning verdicts). Gated on a minimum trained
        // sample count so an undertrained model at server start can't produce noisy flags.
        // -------------------------------------------------------------
        double selfLearnProbability = 0.0;
        if (config.isSelfLearningEnabled()) {
            OnlineClassifier classifier = selfLearning.getOnlineClassifier();
            if (classifier.getTrainedSamplesCount() >= config.getOnlineClassifierMinTrainedSamples()) {
                double selfLearnProb = classifier.predict(features);
                selfLearnProbability = selfLearnProb;
                data.setLastSelfLearnProbability(selfLearnProb);

                if (selfLearnProb >= config.getOnlineClassifierFlagThreshold()) {
                    Map<String, Object> slDetails = new HashMap<>();
                    slDetails.put("probability", selfLearnProb);
                    slDetails.put("trainedSamples", classifier.getTrainedSamplesCount());

                    CheckResult slResult = CheckResult.flag("ClickSelfLearn", selfLearnProb, 1.6 * config.getSelfLearningWeight(),
                            String.format(Locale.US, "Уверенность самообучающегося классификатора: %.1f%% (обучен на %d образцах)",
                                    selfLearnProb * 100, classifier.getTrainedSamplesCount()),
                            slDetails);
                    reporter.reportWindowed(player, data, slResult, 1.6 * config.getSelfLearningWeight(), selfLearnProb * 9.0);
                } else if (selfLearnProb >= config.getOnlineClassifierSilentRiskThreshold()) {
                    // Below the confident-flag bar but still elevated - contribute to Risk only,
                    // silently, without a chat alert. This is the model corroborating other
                    // signals rather than acting alone.
                    data.adjustRisk((selfLearnProb - config.getOnlineClassifierSilentRiskThreshold()) * 12.0);
                }
            }
        }

        // -------------------------------------------------------------
        // Layer 3: Anomaly Memory & Click Fingerprinting
        // -------------------------------------------------------------
        long[] signature = data.getClickBuffer().getSignature();
        AnomalyMemory anomalyMem = selfLearning.getAnomalyMemory();

        // Observe near-flag patterns
        anomalyMem.observe(player.getUniqueId(), signature, statResult);

        // Check repetition
        float repeatScore = anomalyMem.getRepeatScore(player.getUniqueId(), signature);
        if (repeatScore > 0.35f) {
            data.adjustRisk(repeatScore * config.getAnomalyRiskBoost() * 10.0);
        }

        // Mechanic 1.1: Click Signature Fingerprinting
        if (config.isFingerprintingEnabled() && data.getTrustScore() >= 70.0) {
            long[] baseline = data.getBaselineSignature();
            if (baseline == null && data.getClickBuffer().getCount() >= 48) {
                data.setBaselineSignature(signature.clone());
            } else if (baseline != null && data.getClickBuffer().getCount() >= 48) {
                float similarity = ClickSignature.hammingSimilarity(baseline, signature);
                if (similarity < (1.0f - config.getFingerprintDeviationThreshold())) {
                    data.adjustRisk(config.getFingerprintRiskPenalty());
                    LOGGER.info(String.format("[Vesuvio] Изменение стиля игры %s: сходство=%.2f",
                            player.getName(), similarity));
                }
            }
        }

        // Ban-evasion: compare this (possibly brand-new) account's playstyle signature against
        // recently banned players once it has the same 48-sample baseline used for
        // fingerprinting above. Deliberately NOT gated on Trust (a ban-evading alt starts at
        // default trust) and runs at most once per session (altCheckDone).
        if (config.isBanEvasionEnabled() && config.isBanEvasionSignatureCheckEnabled() && banEvasionManager != null
                && !data.isAltCheckDone() && data.getClickBuffer().getCount() >= 48) {
            data.setAltCheckDone(true);
            long[] signatureSnapshot = signature.clone();
            AsyncTasks.run(() -> {
                var match = banEvasionManager.findSignatureMatch(
                        signatureSnapshot, config.getBanEvasionSignatureThreshold(), config.getBanEvasionSignatureScanLimit());
                match.ifPresent(m -> {
                    if (!config.runtime().acceptsDetection(detectionEpoch) || data.getMovementResetNanos() != resetEpoch) return;
                    Map<String, Object> details = new HashMap<>();
                    details.put("bannedUsername", m.bannedUsername());
                    details.put("similarity", m.similarity());
                    CheckResult result = CheckResult.flag("BanEvasion", 0.75, 1.5,
                            String.format(Locale.US, "Стиль игры совпадает с заблокированным игроком «%s» (сходство %.0f%%)",
                                    m.bannedUsername(), m.similarity() * 100),
                            details);
                    reporter.report(player, data, result, 0.0, config.getBanEvasionSignatureMatchRisk());
                });
            }, backgroundExecutor).exceptionally(ex -> {
                // altCheckDone is already set above (this check only ever needs to run once per
                // session - the signature snapshot it compares only gets more stable over time,
                // not less), but a failure here would otherwise vanish silently since nothing
                // else observes this future. Log it so an operator can see the check misfired.
                LOGGER.log(Level.WARNING,
                        "[Vesuvio] Ошибка проверки обхода блокировки по стилю игры: " + player.getName(), ex);
                return null;
            });
        }

        // -------------------------------------------------------------
        // Ensemble scoring (see EnsembleScorer): fuses this evaluation's statistical, self-learning,
        // anomaly-repeat and temporal signals with the ONNX probability.
        //
        // The ONNX layer is evaluated asynchronously above, so reading data.getLastMLProbability()
        // here would score this click against whatever the PREVIOUS inference left behind - which is
        // wrong in both directions: a player whose last inference was a 0.9 keeps getting Risk for a
        // click the model never saw, and a player whose inference is still in flight is fused with a
        // stale 0.0 that reads as the model vouching for them. So when an inference was dispatched
        // for this click, the ensemble is computed in its completion callback against the real
        // probability; when none was (ONNX off, not suspicious, or throttled) the last known value
        // is used only while it is still fresh, and otherwise the ONNX term is dropped from the
        // fusion entirely rather than defaulted.
        // -------------------------------------------------------------
        if (config.isEnsembleScoringEnabled()) {
            final double statConfidence = statResult.confidence();
            final double selfLearn = selfLearnProbability;
            final float repeat = repeatScore;
            final double temporal = temporalConfidence;

            if (clickMlFuture != null) {
                clickMlFuture
                        .thenAccept(mlResult -> {
                            if (config.runtime().acceptsDetection(detectionEpoch) && data.getMovementResetNanos() == resetEpoch)
                                applyEnsemble(data, statConfidence, mlResult.probability(), selfLearn, repeat, temporal);
                        })
                        .exceptionally(ex -> {
                            // Inference failed; still score the rest of the layers rather than
                            // silently dropping the whole ensemble contribution for this click.
                            if (config.runtime().acceptsDetection(detectionEpoch) && data.getMovementResetNanos() == resetEpoch)
                                applyEnsemble(data, statConfidence, null, selfLearn, repeat, temporal);
                            return null;
                        });
            } else {
                applyEnsemble(data, statConfidence, freshOnnxProbability(data), selfLearn, repeat, temporal);
            }
        }
    }

    /**
     * The last ONNX probability if it is recent enough to describe the player's current behaviour,
     * otherwise {@code null} so {@link EnsembleScorer} drops the term and its weight instead of
     * treating an absent signal as a clean one.
     */
    private Double freshOnnxProbability(UserData data) {
        long producedAt = data.getLastMLProbabilityNanos();
        if (producedAt == 0L) return null;
        double ageMs = (System.nanoTime() - producedAt) / 1_000_000.0;
        return ageMs <= config.getEnsembleMaxOnnxAgeMs() ? data.getLastMLProbability() : null;
    }

    /** Applies the bounded Risk contribution an above-threshold ensemble score earns. */
    private void applyEnsemble(UserData data, double statConfidence, Double onnxProbability,
                               double selfLearnProbability, float repeatScore, double temporalConfidence) {
        if (data.mechanics().combat(System.nanoTime())) return;
        EnsembleScorer.Inputs inputs = new EnsembleScorer.Inputs(
                statConfidence, onnxProbability, selfLearnProbability, repeatScore, temporalConfidence);
        double ensembleScore = EnsembleScorer.score(inputs, config);
        if (ensembleScore < config.getEnsembleRiskThreshold()) return;

        double contribution = (ensembleScore - config.getEnsembleRiskThreshold())
                / Math.max(1e-6, 1.0 - config.getEnsembleRiskThreshold())
                * config.getEnsembleMaxRiskContribution();
        data.adjustRisk(contribution);
    }
}
