package net.lovelace.vesuvio.pipeline;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.check.onnx.MLManager;
import net.lovelace.vesuvio.check.onnx.MLResult;
import net.lovelace.vesuvio.check.selflearning.ActiveLearning;
import net.lovelace.vesuvio.check.selflearning.AnomalyMemory;
import net.lovelace.vesuvio.check.selflearning.OnlineClassifier;
import net.lovelace.vesuvio.check.selflearning.SelfLearningManager;
import net.lovelace.vesuvio.check.statistical.StatisticalAimCheck;
import net.lovelace.vesuvio.check.statistical.StatisticalClickCheck;
import net.lovelace.vesuvio.check.statistical.TemporalConsistencyCheck;
import net.lovelace.vesuvio.config.ConfigManager;
import net.lovelace.vesuvio.data.ClickSignature;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.feature.AimFeatureExtractor;
import net.lovelace.vesuvio.feature.ClickFeatureExtractor;
import net.lovelace.vesuvio.staff.SmartAlertService;
import net.lovelace.vesuvio.storage.DatabaseManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 5.1 Check Pipeline with Prioritized Multi-Layer Execution.
 * Layer 1 (Statistical) -> Layer 2 (ONNX Inference) -> Layer 3 (Self-Learning & Anomaly Memory).
 * Dispatches heavy computations to virtual threads with 0% TPS impact.
 *
 * Author: Lovelace
 */
public final class CheckPipeline {

    private static final Logger LOGGER = Logger.getLogger("Vesuvio-Pipeline");

    private final Plugin plugin;
    private final ConfigManager config;
    private final MLManager mlManager;
    private volatile net.lovelace.vesuvio.check.onnx.ShadowEvaluator shadowEvaluator;
    private final SelfLearningManager selfLearning;
    private final SmartAlertService alertService;
    private final DatabaseManager databaseManager;
    private final net.lovelace.vesuvio.engine.LagCompensator lagCompensator;
    private final net.lovelace.vesuvio.punishment.PunishmentWaveManager waveManager;
    private final net.lovelace.vesuvio.staff.DiscordWebhookService discordService;
    private final net.lovelace.vesuvio.engine.HitboxHistoryTracker hitboxTracker;
    private final net.lovelace.vesuvio.evasion.BanEvasionManager banEvasionManager;
    private final net.lovelace.vesuvio.engine.TransactionManager transactionManager;
    private final net.lovelace.vesuvio.engine.NpcTrapManager npcTrapManager;
    private final MiniMessage mm = MiniMessage.miniMessage();

    private final StatisticalClickCheck clickCheck = new StatisticalClickCheck();
    private final StatisticalAimCheck aimCheck = new StatisticalAimCheck();
    private final TemporalConsistencyCheck temporalCheck = new TemporalConsistencyCheck();
    private final net.lovelace.vesuvio.check.statistical.GCDAimCheck gcdAimCheck = new net.lovelace.vesuvio.check.statistical.GCDAimCheck();
    private final net.lovelace.vesuvio.check.protocol.BadPacketsCheck badPacketsCheck = new net.lovelace.vesuvio.check.protocol.BadPacketsCheck();
    private final net.lovelace.vesuvio.check.statistical.StatisticalReachCheck reachCheck;
    private final net.lovelace.vesuvio.check.movement.FlyCheck flyCheck = new net.lovelace.vesuvio.check.movement.FlyCheck();
    private final net.lovelace.vesuvio.check.movement.SpeedCheck speedCheck = new net.lovelace.vesuvio.check.movement.SpeedCheck();
    private final net.lovelace.vesuvio.check.movement.NoFallCheck noFallCheck = new net.lovelace.vesuvio.check.movement.NoFallCheck();
    private final net.lovelace.vesuvio.check.movement.TimerCheck timerCheck = new net.lovelace.vesuvio.check.movement.TimerCheck();
    private final net.lovelace.vesuvio.check.movement.VelocityCheck velocityCheck = new net.lovelace.vesuvio.check.movement.VelocityCheck();
    private final net.lovelace.vesuvio.check.movement.PhaseCheck phaseCheck = new net.lovelace.vesuvio.check.movement.PhaseCheck();
    private final net.lovelace.vesuvio.check.movement.BlinkCheck blinkCheck;
    private final net.lovelace.vesuvio.check.movement.ElytraCheck elytraCheck = new net.lovelace.vesuvio.check.movement.ElytraCheck();
    private final net.lovelace.vesuvio.check.statistical.KillauraAngleCheck angleCheck;
    private final net.lovelace.vesuvio.check.statistical.StrafeReversalCheck strafeReversalCheck;
    private net.lovelace.vesuvio.engine.AimTrackingService aimTrackingService;
    private final net.lovelace.vesuvio.check.movement.StepUpCheck stepUpCheck = new net.lovelace.vesuvio.check.movement.StepUpCheck();
    private final net.lovelace.vesuvio.check.movement.InvMoveCheck invMoveCheck = new net.lovelace.vesuvio.check.movement.InvMoveCheck();
    private final net.lovelace.vesuvio.check.combat.AutoCriticalsCheck autoCriticalsCheck = new net.lovelace.vesuvio.check.combat.AutoCriticalsCheck();
    private final net.lovelace.vesuvio.check.combat.BackTrackCheck backTrackCheck = new net.lovelace.vesuvio.check.combat.BackTrackCheck();
    private final net.lovelace.vesuvio.check.combat.MoveDirectionCheck moveDirectionCheck = new net.lovelace.vesuvio.check.combat.MoveDirectionCheck();
    private final net.lovelace.vesuvio.check.statistical.BaritoneCheck baritoneCheck = new net.lovelace.vesuvio.check.statistical.BaritoneCheck();

    public CheckPipeline(Plugin plugin,
                         ConfigManager config,
                         MLManager mlManager,
                         SelfLearningManager selfLearning,
                         SmartAlertService alertService,
                         DatabaseManager databaseManager,
                         net.lovelace.vesuvio.engine.LagCompensator lagCompensator,
                         net.lovelace.vesuvio.punishment.PunishmentWaveManager waveManager,
                         net.lovelace.vesuvio.staff.DiscordWebhookService discordService,
                         net.lovelace.vesuvio.engine.HitboxHistoryTracker hitboxTracker,
                         net.lovelace.vesuvio.evasion.BanEvasionManager banEvasionManager,
                         net.lovelace.vesuvio.engine.TransactionManager transactionManager,
                         net.lovelace.vesuvio.engine.NpcTrapManager npcTrapManager) {
        this.plugin = plugin;
        this.config = config;
        this.mlManager = mlManager;
        this.selfLearning = selfLearning;
        this.alertService = alertService;
        this.databaseManager = databaseManager;
        this.lagCompensator = lagCompensator;
        this.waveManager = waveManager;
        this.discordService = discordService;
        this.hitboxTracker = hitboxTracker;
        this.banEvasionManager = banEvasionManager;
        this.transactionManager = transactionManager;
        this.npcTrapManager = npcTrapManager;
        this.reachCheck = new net.lovelace.vesuvio.check.statistical.StatisticalReachCheck(config.getMaxReach());
        this.blinkCheck = net.lovelace.vesuvio.check.movement.BlinkCheck.fromConfig(config);
        this.angleCheck = new net.lovelace.vesuvio.check.statistical.KillauraAngleCheck(config, hitboxTracker, transactionManager);
        this.strafeReversalCheck = new net.lovelace.vesuvio.check.statistical.StrafeReversalCheck(config);
    }

    /**
     * Wired after construction because the tracking service needs the plugin's tick scheduler,
     * which is set up later in {@code Vesuvio#onEnable} than the pipeline itself.
     */
    public void setAimTrackingService(net.lovelace.vesuvio.engine.AimTrackingService service) {
        this.aimTrackingService = service;
    }

    public net.lovelace.vesuvio.engine.AimTrackingService getAimTrackingService() {
        return aimTrackingService;
    }

    /**
     * Wired after construction (same reason as the tracking service above). Null unless shadow
     * mode is in use, in which case every real inference is mirrored to the candidate model.
     */
    public void setShadowEvaluator(net.lovelace.vesuvio.check.onnx.ShadowEvaluator evaluator) {
        this.shadowEvaluator = evaluator;
    }

    /**
     * Mirrors one inference to the shadow candidate, if one is loaded for this model.
     *
     * <p>Runs on the ONNX completion callback, never on the main thread, and its result is only
     * ever counted - it cannot touch VL, Risk, Trust or alerts. That is the entire contract of
     * shadow mode: an unproven model gets to be measured against live traffic without being
     * allowed to punish anyone on the strength of an offline score.
     */
    private void recordShadowVerdict(String modelName, float[] features, double liveProbability) {
        var evaluator = shadowEvaluator;
        if (evaluator == null) return;

        String shadowName = modelName + net.lovelace.vesuvio.check.onnx.ShadowEvaluator.SHADOW_SUFFIX;
        if (!mlManager.isModelLoaded(shadowName)) return;

        double liveThreshold = mlManager.getThreshold(modelName);
        double shadowThreshold = mlManager.getThreshold(shadowName);
        mlManager.evaluateAsync(shadowName, features).thenAccept(shadowResult ->
                evaluator.record(modelName, liveProbability, shadowResult.probability(),
                        liveThreshold, shadowThreshold));
    }

    public net.lovelace.vesuvio.evasion.BanEvasionManager getBanEvasionManager() {
        return banEvasionManager;
    }

    public net.lovelace.vesuvio.engine.NpcTrapManager getNpcTrapManager() {
        return npcTrapManager;
    }

    /**
     * Processes click events from a player. Runs asynchronously.
     */
    public void processClick(Player player, UserData data) {
        // -------------------------------------------------------------
        // Layer 1: Statistical Engine (Fast, Heuristic)
        // -------------------------------------------------------------
        CheckResult statResult = CheckResult.pass("ClickStatistical");
        if (config.isStatisticalEnabled()) {
            double lagTolerance = (lagCompensator != null) ? lagCompensator.getLagToleranceMultiplier(player) : 1.0;
            statResult = clickCheck.check(data);
            if (statResult.isFlag()) {
                double addedVl = (statResult.vl() * config.getStatisticalWeight()) / lagTolerance;
                data.addVl(addedVl);
                data.adjustRisk((statResult.confidence() * 8.0) / lagTolerance);
                data.setLastTriggeredCheck(statResult.checkName());

                handleFlag(player, data, statResult);

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
                data.addVl(tempResult.vl());
                data.adjustRisk(15.0);
                handleFlag(player, data, tempResult);
            }
        }

        // -------------------------------------------------------------
        // Layer 2: ONNX Machine Learning Inference Layer
        // -------------------------------------------------------------
        float[] features = ClickFeatureExtractor.extract(data.getClickBuffer(), data.getEarlyCombatMean());
        boolean isSuspicious = statResult.isFlag() || statResult.confidence() > 0.40 || data.getLastCalculatedCPS() > 11.5;

        // Held so the ensemble below can be scored against THIS click's probability rather than
        // whatever the previous inference happened to leave behind - see the ensemble block.
        java.util.concurrent.CompletableFuture<MLResult> clickMlFuture = null;

        if (config.isOnnxEnabled() && isSuspicious && data.shouldRunML()) {
            data.markMLRun();
            float[] featuresCopy = features.clone();

            clickMlFuture = mlManager.evaluateAsync("click_model", featuresCopy);
            clickMlFuture.thenAccept(mlResult -> {
                data.setLastMLProbability(mlResult.probability());
                recordShadowVerdict("click_model", featuresCopy, mlResult.probability());

                if (mlResult.isFlag()) {
                    data.addVl(mlResult.vl() * config.getOnnxWeight());
                    data.adjustRisk(mlResult.probability() * 12.0);
                    data.adjustTrust(-5.0);
                    data.setLastTriggeredCheck("ClickML");

                    CheckResult res = CheckResult.flag("ClickML", mlResult.probability(), mlResult.vl(),
                            String.format(Locale.US, "Neural click model confidence: %.1f%%", mlResult.probability() * 100),
                            mlResult.details());
                    handleFlag(player, data, res);

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
                    data.addVl(1.6 * config.getSelfLearningWeight());
                    data.adjustRisk(selfLearnProb * 9.0);
                    data.setLastTriggeredCheck("ClickSelfLearn");

                    Map<String, Object> slDetails = new HashMap<>();
                    slDetails.put("probability", selfLearnProb);
                    slDetails.put("trainedSamples", classifier.getTrainedSamplesCount());

                    CheckResult slResult = CheckResult.flag("ClickSelfLearn", selfLearnProb, 1.6 * config.getSelfLearningWeight(),
                            String.format(Locale.US, "Online self-learning classifier confidence: %.1f%% (trained on %d samples)",
                                    selfLearnProb * 100, classifier.getTrainedSamplesCount()),
                            slDetails);
                    handleFlag(player, data, slResult);
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
                    LOGGER.info(String.format("[Vesuvio] Playstyle fingerprint divergence for %s: sim=%.2f",
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
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                var match = banEvasionManager.findSignatureMatch(
                        signatureSnapshot, config.getBanEvasionSignatureThreshold(), config.getBanEvasionSignatureScanLimit());
                match.ifPresent(m -> {
                    data.adjustRisk(config.getBanEvasionSignatureMatchRisk());
                    Map<String, Object> details = new HashMap<>();
                    details.put("bannedUsername", m.bannedUsername());
                    details.put("similarity", m.similarity());
                    CheckResult result = CheckResult.flag("BanEvasion", 0.75, 1.5,
                            String.format(Locale.US, "Playstyle matches banned player '%s' (similarity %.0f%%)",
                                    m.bannedUsername(), m.similarity() * 100),
                            details);
                    handleFlag(player, data, result);
                });
            }).exceptionally(ex -> {
                // altCheckDone is already set above (this check only ever needs to run once per
                // session - the signature snapshot it compares only gets more stable over time,
                // not less), but a failure here would otherwise vanish silently since nothing
                // else observes this future. Log it so an operator can see the check misfired.
                LOGGER.log(java.util.logging.Level.WARNING,
                        "[Vesuvio] Ban-evasion signature check failed for " + player.getName(), ex);
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
                        .thenAccept(mlResult -> applyEnsemble(data, statConfidence, mlResult.probability(),
                                selfLearn, repeat, temporal))
                        .exceptionally(ex -> {
                            // Inference failed; still score the rest of the layers rather than
                            // silently dropping the whole ensemble contribution for this click.
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
        EnsembleScorer.Inputs inputs = new EnsembleScorer.Inputs(
                statConfidence, onnxProbability, selfLearnProbability, repeatScore, temporalConfidence);
        double ensembleScore = EnsembleScorer.score(inputs, config);
        if (ensembleScore < config.getEnsembleRiskThreshold()) return;

        double contribution = (ensembleScore - config.getEnsembleRiskThreshold())
                / Math.max(1e-6, 1.0 - config.getEnsembleRiskThreshold())
                * config.getEnsembleMaxRiskContribution();
        data.adjustRisk(contribution);
    }

    /**
     * Processes aim updates with delta rotations.
     */
    public void processAim(Player player, UserData data, float deltaYaw, float deltaPitch) {
        // KillauraAngleCheck's ReactionTime sub-check needs to know when the attacker's camera
        // last made a "real" turn, not merely received a rotation packet - tracked here since this
        // is the one place every aim update (combat or not) passes through.
        long nowNanos = System.nanoTime();
        data.setLastRotationNanos(nowNanos);
        double combinedRotation = Math.abs(deltaYaw) + Math.abs(deltaPitch);
        if (combinedRotation >= config.getKillauraReactionSignificantRotationDegrees()) {
            data.setLastSignificantRotationNanos(nowNanos);
        }

        // BadPackets Pitch Bounds Check
        if (config.isBadPacketsEnabled() && config.isBadPacketsPitchBounds()) {
            CheckResult pitchResult = badPacketsCheck.checkPitch(data.getLastPitch());
            if (pitchResult.isFlag()) {
                data.addVl(pitchResult.vl());
                data.adjustRisk(20.0);
                data.setLastTriggeredCheck(pitchResult.checkName());
                handleFlag(player, data, pitchResult);
            }
        }

        // Mathematical GCD Mouse Quantization Check (GrimAC/Polar)
        if (config.isGcdAimEnabled() && (deltaYaw > 0 || deltaPitch > 0)) {
            CheckResult gcdResult = gcdAimCheck.check(data, deltaYaw, deltaPitch, (float) config.getGcdAimMinRotation());
            if (gcdResult.isFlag()) {
                data.addVl(gcdResult.vl() * config.getStatisticalWeight());
                data.adjustRisk(gcdResult.confidence() * 7.0);
                data.setLastTriggeredCheck(gcdResult.checkName());
                handleFlag(player, data, gcdResult);
            }
        }

        if (!config.isStatisticalEnabled()) return;

        if (data.getAimBuffer().isFull()) {
            CheckResult result = aimCheck.check(data);
            if (result.isFlag()) {
                data.addVl(result.vl() * config.getStatisticalWeight());
                data.adjustRisk(result.confidence() * 6.0);
                data.setLastTriggeredCheck(result.checkName());
                handleFlag(player, data, result);
            }
        }

        // Layer 2: ONNX Aim Machine Learning Inference Layer
        if (config.isOnnxEnabled() && data.isInCombat() && data.getAimBuffer().getCount() >= 16) {
            float[] aimFeatures = AimFeatureExtractor.extract(data.getAimBuffer());
            boolean isAimSuspicious = (aimFeatures[4] > 0.15f || aimFeatures[6] > 12.0f || aimFeatures[5] > 0.35f || aimFeatures[7] < 0.20f);
            if (isAimSuspicious && data.shouldRunML()) {
                data.markMLRun();
                float[] featuresCopy = aimFeatures.clone();
                mlManager.evaluateAsync("aim_model", featuresCopy).thenAccept(mlResult -> {
                    recordShadowVerdict("aim_model", featuresCopy, mlResult.probability());
                    if (mlResult.isFlag()) {
                        data.addVl(mlResult.vl() * config.getOnnxWeight());
                        data.adjustRisk(mlResult.probability() * 10.0);
                        data.adjustTrust(-4.0);
                        data.setLastTriggeredCheck("AimML");

                        CheckResult res = CheckResult.flag("AimML", mlResult.probability(), mlResult.vl(),
                                String.format(Locale.US, "Neural aim model confidence: %.1f%%", mlResult.probability() * 100),
                                mlResult.details());
                        handleFlag(player, data, res);
                    }
                });
            }
        }

        // -------------------------------------------------------------
        // Layer 3a: Online Self-Learning Classifier for aim (separate weights/priors from the
        // click classifier - see SelfLearningManager.getAimClassifier / OnlineClassifier.AIM_PRIORS).
        // Same minimum-trained-sample gate as the click classifier, and additionally requires
        // combat context like the other aim checks to avoid scoring idle look-around.
        // -------------------------------------------------------------
        if (config.isSelfLearningEnabled() && data.isInCombat() && data.getAimBuffer().getCount() >= 16) {
            OnlineClassifier aimClassifier = selfLearning.getAimClassifier();
            if (aimClassifier.getTrainedSamplesCount() >= config.getOnlineClassifierMinTrainedSamples()) {
                float[] aimFeatures = AimFeatureExtractor.extract(data.getAimBuffer());
                double aimSelfLearnProb = aimClassifier.predict(aimFeatures);
                data.setLastAimSelfLearnProbability(aimSelfLearnProb);

                if (aimSelfLearnProb >= config.getOnlineClassifierFlagThreshold()) {
                    data.addVl(1.6 * config.getSelfLearningWeight());
                    data.adjustRisk(aimSelfLearnProb * 9.0);
                    data.setLastTriggeredCheck("AimSelfLearn");

                    Map<String, Object> slDetails = new HashMap<>();
                    slDetails.put("probability", aimSelfLearnProb);
                    slDetails.put("trainedSamples", aimClassifier.getTrainedSamplesCount());

                    CheckResult slResult = CheckResult.flag("AimSelfLearn", aimSelfLearnProb, 1.6 * config.getSelfLearningWeight(),
                            String.format(Locale.US, "Online aim self-learning classifier confidence: %.1f%% (trained on %d samples)",
                                    aimSelfLearnProb * 100, aimClassifier.getTrainedSamplesCount()),
                            slDetails);
                    handleFlag(player, data, slResult);
                } else if (aimSelfLearnProb >= config.getOnlineClassifierSilentRiskThreshold()) {
                    data.adjustRisk((aimSelfLearnProb - config.getOnlineClassifierSilentRiskThreshold()) * 12.0);
                }
            }
        }
    }

    public void processAim(Player player, UserData data) {
        processAim(player, data, 0f, 0f);
    }

    /**
     * Evaluates attack interactions: NoSwing, InventoryAttack, and Latency-Compensated Reach.
     */
    public void processAttack(Player player, int targetEntityId, UserData data) {
        // A downed/invulnerable attacker or target means a third-party plugin (revive/downed-
        // state mechanics, admin god-mode, spawn protection, etc.) is actively controlling that
        // entity's position/hitbox/state outside of normal survival rules - our combat math
        // (reach, angle, crit timing) isn't meaningful there and shouldn't punish it. Concretely
        // reported case: finishing off a player mid-revive (temporarily invulnerable, position
        // still settling) false-flagged the finisher for Reach/Angle.
        if (player.isInvulnerable()) {
            return;
        }

        // 1. BadPackets: NoSwing check
        if (config.isBadPacketsEnabled() && config.isBadPacketsNoSwing()) {
            if (!data.isFirstAttackSeen()) {
                // First attack of the session has no swing baseline yet - vanilla clients can
                // deliver this Interact packet before their Animation packet, so lastSwingNanos
                // being 0 here isn't a violation. Skip once, then judge normally from here on.
                data.setFirstAttackSeen(true);
            } else {
                CheckResult swingResult = badPacketsCheck.checkNoSwing(data.getLastSwingNanos());
                if (swingResult.isFlag()) {
                    data.addVl(swingResult.vl());
                    data.adjustRisk(18.0);
                    data.setLastTriggeredCheck(swingResult.checkName());
                    handleFlag(player, data, swingResult);
                }
            }
        }

        // 2. BadPackets: InventoryAttack check
        if (config.isBadPacketsEnabled() && config.isBadPacketsInventoryAttack()) {
            CheckResult invResult = badPacketsCheck.checkInventoryAttack(data);
            if (invResult.isFlag()) {
                data.addVl(invResult.vl());
                data.adjustRisk(15.0);
                data.setLastTriggeredCheck(invResult.checkName());
                handleFlag(player, data, invResult);
            }
        }

        // 2.5 Auto Criticals micro-hop check
        if (config.isAutoCriticalsEnabled()) {
            CheckResult critResult = autoCriticalsCheck.check(data);
            if (critResult.isFlag()) {
                data.addVl(critResult.vl());
                data.adjustRisk(critResult.confidence() * 10.0);
                data.setLastTriggeredCheck(critResult.checkName());
                handleFlag(player, data, critResult);
            }
        }

        // 2.6 Strafe-reversal sensorimotor latency. Runs off the aim-tracking buffer rather than
        // this attack's geometry, so it is throttled instead of running per attack - the buffer
        // only gains one sample per tick, and re-scanning it on every swing would just re-walk the
        // same events.
        if (config.isStrafeReversalEnabled() && data.shouldRunStrafeReversal(config.getStrafeReversalIntervalMs())) {
            CheckResult reversalResult = strafeReversalCheck.check(data);
            if (reversalResult.isFlag()) {
                data.addVl(reversalResult.vl());
                data.adjustRisk(reversalResult.confidence() * 10.0);
                data.setLastTriggeredCheck(reversalResult.checkName());
                handleFlag(player, data, reversalResult);
            }
        }

        // 3. Statistical Latency-Compensated Reach & Combat Angle check.
        //
        // These two are the only checks that need live world and entity data: resolving the target
        // entity id walks the surrounding chunks' entity lists, and the killaura line-of-sight test
        // raytraces blocks. Neither is safe from the virtual thread the rest of this method runs
        // on - both read structures the main thread mutates every tick, so off-thread they can
        // observe a half-updated entity list or force a chunk access from the wrong thread. The
        // work itself is small and attacks are rare compared to movement packets, so it is hopped
        // onto the main thread rather than being approximated.
        if (!config.isReachEnabled() && !config.isKillauraAngleEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> runAttackWorldChecks(player, targetEntityId, data));
    }

    /** Main-thread half of {@link #processAttack}: everything that needs live world state. */
    private void runAttackWorldChecks(Player player, int targetEntityId, UserData data) {
        if (!player.isOnline()) return;

        org.bukkit.entity.Entity target = null;
        for (org.bukkit.entity.Entity e : player.getNearbyEntities(7.0, 7.0, 7.0)) {
            if (e.getEntityId() == targetEntityId) {
                target = e;
                break;
            }
        }

        // A downed/invulnerable target means a third-party plugin (revive mechanics, admin
        // god-mode, spawn protection) is actively controlling its position/hitbox outside normal
        // survival rules - reach and angle math isn't meaningful against state we don't own.
        // Concretely reported case: finishing off a player mid-revive (temporarily invulnerable,
        // position still settling) false-flagged the finisher for Reach/Angle.
        if (target == null || target.equals(player) || target.isInvulnerable()) {
            // Reach and the whole killaura family live below this line, so when an operator reports
            // that an attack "was not caught", the first thing that has to be answered is whether
            // the attack reached them at all. Silence here and silence from a check that ran look
            // identical in the log otherwise.
            if (config.isDebug()) {
                LOGGER.info(String.format(Locale.US,
                        "[ATTACK] %s -> entityId=%d SKIPPED (%s) - no combat check ran for this attack",
                        player.getName(), targetEntityId,
                        target == null ? "target not among nearby entities"
                                : target.equals(player) ? "target resolved to the attacker"
                                : "target is invulnerable"));
            }
            return;
        }

        if (config.isReachEnabled()) {
            CheckResult reachResult = reachCheck.check(player, target, data, hitboxTracker, lagCompensator);
            if (reachResult.isFlag()) {
                data.addVl(reachResult.vl() * config.getStatisticalWeight());
                data.adjustRisk(reachResult.confidence() * 8.0);
                data.setLastTriggeredCheck(reachResult.checkName());
                handleFlag(player, data, reachResult);
            }
        }

        // Tell the per-tick aim tracker what this player is actually fighting, so it can record
        // target-relative aim from here on (see AimTrackingService / StrafeReversalCheck).
        if (aimTrackingService != null) {
            aimTrackingService.noteTarget(player, target);
        }

        if (config.isKillauraAngleEnabled()) {
            CheckResult angleResult = angleCheck.check(player, target, data);
            if (angleResult.isFlag()) {
                data.addVl(angleResult.vl());
                data.adjustRisk(angleResult.confidence() * 9.0);
                data.setLastTriggeredCheck(angleResult.checkName());
                handleFlag(player, data, angleResult);
            }
        }

        if (config.isBackTrackEnabled() && hitboxTracker != null && transactionManager != null) {
            CheckResult backTrackResult = backTrackCheck.check(player, target, data, hitboxTracker, transactionManager, config.getMaxReach());
            if (backTrackResult.isFlag()) {
                data.addVl(backTrackResult.vl());
                data.adjustRisk(backTrackResult.confidence() * 10.0);
                data.setLastTriggeredCheck(backTrackResult.checkName());
                handleFlag(player, data, backTrackResult);
            }
        }

        if (config.isMoveDirectionEnabled()) {
            CheckResult moveDirResult = moveDirectionCheck.check(player, target, data);
            if (moveDirResult.isFlag()) {
                data.addVl(moveDirResult.vl());
                data.adjustRisk(moveDirResult.confidence() * 8.0);
                data.setLastTriggeredCheck(moveDirResult.checkName());
                handleFlag(player, data, moveDirResult);
            }
        }

        // Fake-NPC trap: converting accumulating combat suspicion into a conclusive answer, not
        // surveilling every player - only offered once a player is already flagged high-risk by
        // the checks above (or anything else feeding into risk/VL).
        if (npcTrapManager != null && data.isSuspect(config.getHighRiskThreshold())) {
            npcTrapManager.maybeSpawnTrap(player);
        }
    }

    /**
     * Definitive KillAura/MobAura proof: the attack packet named an entity ID that only exists as
     * a per-player packet-level trap (see {@link net.lovelace.vesuvio.engine.NpcTrapManager}). No
     * further validation is meaningful here - a legitimate client cannot see or aim at this entity
     * at all, so a single hit is conclusive rather than needing the streak/confidence machinery
     * every other combat check relies on.
     */
    public void handleNpcTrapHit(Player player, UserData data) {
        if (npcTrapManager != null) npcTrapManager.despawnTrap(player.getUniqueId());

        // The one label in the system that is not self-confirming: a legitimate client cannot see
        // this entity at all, so the verdict owes nothing to the statistics the models would
        // otherwise just be learning to reproduce.
        selfLearning.getAutoDatasetCollector().collectTrapSample(player, data);

        Map<String, Object> details = new HashMap<>();
        details.put("target", "npc-trap");

        CheckResult result = CheckResult.flag("NpcTrap", 1.0, 25.0,
                "Attacked a per-player packet trap entity invisible to legitimate play", details);
        data.addVl(result.vl());
        data.adjustRisk(60.0);
        data.setLastTriggeredCheck(result.checkName());
        handleFlag(player, data, result);
    }

    /**
     * Processes player movement packets (Fly, Speed, NoFall, Timer).
     */
    public void processMovement(Player player, UserData data, double x, double y, double z, boolean onGround, boolean hasPos, long packetReceiptNanos) {
        processMovement(player, data, x, y, z, onGround, hasPos, packetReceiptNanos, true);
    }

    /**
     * @param clientHadSomethingToReport whether this packet reports state the client had not
     *                                   already reported - computed in packet order on the netty
     *                                   thread, see {@link UserData#noteReportedClientState}.
     */
    public void processMovement(Player player, UserData data, double x, double y, double z, boolean onGround, boolean hasPos, long packetReceiptNanos, boolean clientHadSomethingToReport) {
        // A third-party plugin controlling this player's state (revive/downed mechanics,
        // god-mode, spawn protection) can legitimately move/teleport/ragdoll them outside normal
        // survival physics - e.g. a "downed" player briefly falling before their temporary
        // post-revive invulnerability kicks in. Don't run movement heuristics against state we
        // don't own.
        //
        // Every per-check momentum/streak state that carries meaning across ticks is cleared here,
        // the same way the teleport-exclusion branch below clears it - invulnerability is exactly
        // the same "this delta cannot be trusted, nothing should carry forward from it" situation.
        // lastAnyMovementNanos is refreshed rather than left stale so BlinkCheck does not read the
        // whole invulnerability window as withheld-movement-on-a-healthy-connection the instant it
        // ends: transactions keep flowing throughout regardless (TransactionManager ticks every
        // online player unconditionally), so a stale timestamp here really would look exactly like
        // a textbook blink.
        if (player.isInvulnerable()) {
            data.resetAirTicks();
            data.resetFlyStreak();
            data.resetSpeedStreak();
            data.setPrevHorizontalSpeed(0.0);
            data.setSpeedPredictionDebt(0.0);
            data.clearPendingVelocity();
            data.resetPhaseTicks();
            data.setLastAnyMovementNanos(packetReceiptNanos);
            if (hasPos) {
                data.setLastPosition(x, y, z, onGround);
            }
            return;
        }

        // A game mode change (staff entering /vesuvio spectate, an admin switching to creative and
        // back) is applied instantly, but the game mode every movement check reads comes from the
        // once-per-tick EnvironmentSnapshot. For up to one tick after the change the checks are
        // therefore judging a spectator-speed flyer against the survival movement model, and
        // SpeedCheck's debt accumulator needs only a single such packet to cross its flag
        // threshold - which is exactly how staff opening a spectate session flagged themselves for
        // Speed. Handled the same way as the invulnerability branch above: nothing carries across
        // the transition, and the silence up to it is not Blink's to read either.
        if (data.hasRecentGameModeChange()) {
            data.resetAirTicks();
            data.resetFlyStreak();
            data.resetSpeedStreak();
            data.setPrevHorizontalSpeed(0.0);
            data.setSpeedPredictionDebt(0.0);
            data.clearPendingVelocity();
            data.resetPhaseTicks();
            data.setLastAnyMovementNanos(packetReceiptNanos);
            if (hasPos) {
                data.setLastPosition(x, y, z, onGround);
            }
            return;
        }

        // Lag tolerance is computed once per movement packet and shared by every check below, so a
        // single spike cannot be counted differently by each of them.
        double lagTolerance = (lagCompensator != null) ? lagCompensator.getLagToleranceMultiplier(player) : 1.0;

        // 1. Timer check runs on every movement packet
        if (config.isTimerEnabled()) {
            CheckResult timerResult = timerCheck.check(data, lagTolerance);
            if (timerResult.isFlag()) {
                data.addVl(timerResult.vl());
                data.adjustRisk(timerResult.confidence() * 10.0);
                data.setLastTriggeredCheck(timerResult.checkName());
                handleFlag(player, data, timerResult);
            }
        }

        // 1a. Blink runs on every movement packet, position-carrying or not - an idle vanilla
        // client sends position-less flying packets each tick, and it is the silence of that whole
        // stream (not of positions alone) that distinguishes a lag switch from standing still.
        if (config.isBlinkEnabled() && transactionManager != null) {
            CheckResult blinkResult = blinkCheck.check(player.getUniqueId(), data, transactionManager,
                    packetReceiptNanos, hasPos, clientHadSomethingToReport);
            if (blinkResult.isFlag()) {
                data.addVl(blinkResult.vl());
                data.adjustRisk(blinkResult.confidence() * 12.0);
                data.setLastTriggeredCheck(blinkResult.checkName());
                handleFlag(player, data, blinkResult);
            }
        }

        if (!hasPos) {
            return;
        }

        long nowNanos = System.nanoTime();
        long prevNanos = data.getLastPositionNanos();
        data.setLastPositionNanos(nowNanos);

        if (!data.hasLastPosition()) {
            data.setLastPosition(x, y, z, onGround);
            return;
        }

        double deltaX = x - data.getLastX();
        double deltaY = y - data.getLastY();
        double deltaZ = z - data.getLastZ();

        data.setLastPosition(x, y, z, onGround);
        data.setLastMoveDelta(deltaX, deltaZ);

        // Real time this delta covers. The movement models are per-tick, so a delta spanning more
        // than a tick (idle player resuming, post-lag burst) has to be handled differently rather
        // than being read as one very fast tick.
        double elapsedMs = prevNanos == 0L ? 50.0 : (nowNanos - prevNanos) / 1_000_000.0;

        // 1a-1. Blink release-burst confirmation. Must run BEFORE the teleport-size exclusion below
        // discards this delta: a real blink's release is frequently exactly that size (queued
        // movement flushed in one packet), which is precisely why it used to be silently absorbed
        // there with no flag at all instead of being recognised as the signature it is.
        if (config.isBlinkEnabled() && transactionManager != null) {
            CheckResult burstResult = blinkCheck.checkReleaseBurst(data, deltaX, deltaY, deltaZ, elapsedMs, nowNanos);
            if (burstResult.isFlag()) {
                data.addVl(burstResult.vl());
                data.adjustRisk(burstResult.confidence() * 12.0);
                data.setLastTriggeredCheck(burstResult.checkName());
                handleFlag(player, data, burstResult);
            }
        }

        // Exclude teleports / huge jumps
        if (Math.abs(deltaX) > 10.0 || Math.abs(deltaY) > 15.0 || Math.abs(deltaZ) > 10.0) {
            data.resetAirTicks();
            data.resetFlyStreak();
            data.resetSpeedStreak();
            data.setPrevHorizontalSpeed(0.0);
            data.setSpeedPredictionDebt(0.0);
            data.clearPendingVelocity();
            data.resetPhaseTicks();
            return;
        }

        // 1a-2. Elytra: the one movement state every other check in this pipeline exempts
        // outright (EnvironmentSnapshot#isMovementExempt()) because none of them model glide
        // physics. Runs unconditionally here; the check itself gates on env.gliding() and is a
        // no-op otherwise.
        if (config.isElytraEnabled()) {
            CheckResult elytraResult = elytraCheck.check(data, deltaX, deltaY, deltaZ);
            if (elytraResult.isFlag()) {
                data.addVl(elytraResult.vl());
                data.adjustRisk(elytraResult.confidence() * 10.0);
                data.setLastTriggeredCheck(elytraResult.checkName());
                handleFlag(player, data, elytraResult);
            }
        }

        if (config.isBaritoneEnabled()) {
            CheckResult baritoneResult = baritoneCheck.check(player, data, deltaX, deltaZ);
            if (baritoneResult.isFlag()) {
                data.addVl(baritoneResult.vl());
                data.adjustRisk(baritoneResult.confidence() * 7.0);
                data.setLastTriggeredCheck(baritoneResult.checkName());
                handleFlag(player, data, baritoneResult);
            }
        }

        // 1b. Velocity / anti-knockback. Runs before the exemption-heavy checks because it is the
        // check that validates the "recent velocity" exemption the others rely on.
        if (config.isVelocityEnabled() && transactionManager != null) {
            CheckResult velocityResult = velocityCheck.check(player.getUniqueId(), data, transactionManager, deltaX, deltaZ);
            if (velocityResult.isFlag()) {
                data.addVl(velocityResult.vl());
                data.adjustRisk(velocityResult.confidence() * 11.0);
                data.setLastTriggeredCheck(velocityResult.checkName());
                handleFlag(player, data, velocityResult);
            }
        }

        // 1c. Phase / Clip
        if (config.isPhaseEnabled()) {
            CheckResult phaseResult = phaseCheck.check(data, deltaX, deltaZ);
            if (phaseResult.isFlag()) {
                data.addVl(phaseResult.vl());
                data.adjustRisk(phaseResult.confidence() * 12.0);
                data.setLastTriggeredCheck(phaseResult.checkName());
                handleFlag(player, data, phaseResult);
            }
        }

        // 2. Fly & AirJump check
        if (config.isFlyEnabled()) {
            CheckResult flyResult = flyCheck.check(data, deltaX, deltaY, deltaZ, onGround);
            if (flyResult.isFlag()) {
                data.addVl(flyResult.vl());
                data.adjustRisk(flyResult.confidence() * 12.0);
                data.setLastTriggeredCheck(flyResult.checkName());
                handleFlag(player, data, flyResult);

                // Fly is one of the fastest and hardest checks - on a high-confidence flag there is
                // little value in also running Speed/NoFall/StepUp/InvMove this same tick.
                if (flyResult.confidence() >= config.getStatisticalCutoff()) {
                    return;
                }
            }
        }

        // 3. Horizontal Speed check
        if (config.isSpeedEnabled()) {
            CheckResult speedResult = speedCheck.check(data, deltaX, deltaZ, elapsedMs, lagTolerance);
            if (speedResult.isFlag()) {
                data.addVl(speedResult.vl());
                data.adjustRisk(speedResult.confidence() * 8.0);
                data.setLastTriggeredCheck(speedResult.checkName());
                handleFlag(player, data, speedResult);
            }
        }

        // 4. NoFall check
        if (config.isNoFallEnabled()) {
            CheckResult noFallResult = noFallCheck.check(data, deltaY, onGround);
            if (noFallResult.isFlag()) {
                data.addVl(noFallResult.vl());
                data.adjustRisk(noFallResult.confidence() * 10.0);
                data.setLastTriggeredCheck(noFallResult.checkName());
                handleFlag(player, data, noFallResult);
            }
        }

        // 5. StepUp check
        if (config.isStepEnabled()) {
            CheckResult stepResult = stepUpCheck.check(data, deltaY, onGround);
            if (stepResult.isFlag()) {
                data.addVl(stepResult.vl());
                data.adjustRisk(stepResult.confidence() * 10.0);
                data.setLastTriggeredCheck(stepResult.checkName());
                handleFlag(player, data, stepResult);
            }
        }

        // 6. InvMove check
        if (config.isInvMoveEnabled()) {
            CheckResult invMoveResult = invMoveCheck.check(data, deltaX, deltaZ, deltaY);
            if (invMoveResult.isFlag()) {
                data.addVl(invMoveResult.vl());
                data.adjustRisk(invMoveResult.confidence() * 8.0);
                data.setLastTriggeredCheck(invMoveResult.checkName());
                handleFlag(player, data, invMoveResult);
            }
        }
    }

    public void handleFlag(Player player, UserData data, CheckResult result) {
        // 0. Verbose console diagnostics (settings.debug: true) - dumps the exact feature values
        // that triggered the flag, so server owners can tune thresholds without guessing.
        if (config.isDebug()) {
            LOGGER.info(String.format(Locale.US,
                    "[FLAG] %s | check=%s confidence=%.2f vl+=%.2f -> VL=%.1f Risk=%.1f Trust=%.1f | %s | details=%s",
                    player.getName(), result.checkName(), result.confidence(), result.vl(),
                    data.getVl(), data.getRiskIndex(), data.getTrustScore(),
                    result.explanation(), result.details()));
        }

        // 1. Broadcast smart MiniMessage alert to staff
        alertService.broadcastAlert(player, data, result);

        // 2. Dispatch Discord Webhook rich embed asynchronously
        if (discordService != null) {
            discordService.dispatchAlertAsync(player, data, result);
        }

        // 3. Queue asynchronous database violation record
        databaseManager.logViolationAsync(new net.lovelace.vesuvio.storage.ViolationRecord(
                player.getUniqueId(),
                player.getName(),
                result.checkName(),
                data.getVl(),
                result.confidence(),
                result.explanation(),
                result.details().toString(),
                System.currentTimeMillis()
        ));

        // 4. Evaluate punishment thresholds
        evaluatePunishments(player, data);
    }

    private void evaluatePunishments(Player player, UserData data) {
        double currentVl = data.getVl();

        for (ConfigManager.PunishmentRule rule : config.getPunishmentRules()) {
            if (currentVl >= rule.vlThreshold()) {
                String cmd = rule.command();
                if (cmd != null && !cmd.isBlank()) {
                    String formatted = cmd
                            .replace("%player%", player.getName())
                            .replace("%vl%", String.format(Locale.US, "%.0f", currentVl))
                            .replace("%risk%", String.format(Locale.US, "%.0f", data.getRiskIndex()))
                            .replace("%ml%", String.format(Locale.US, "%.1f", data.getLastMLProbability() * 100));

                    // Auto-collect a confirmed-cheat training sample the moment a ban fires -
                    // by now the pipeline is confident enough that this is safe ground truth.
                    // Also snapshot a ban-evasion fingerprint (IP + playstyle signature) so a
                    // fresh account rejoining later can be matched against it.
                    if ("ban".equalsIgnoreCase(rule.action())) {
                        selfLearning.getAutoDatasetCollector().collectCheatSample(player, data);

                        if (banEvasionManager != null && config.isBanEvasionEnabled()) {
                            String ip = null;
                            try {
                                if (player.getAddress() != null && player.getAddress().getAddress() != null) {
                                    ip = player.getAddress().getAddress().getHostAddress();
                                }
                            } catch (Throwable ignored) {}
                            banEvasionManager.recordBanFingerprint(
                                    player.getUniqueId(), player.getName(), ip, data.getClientBrand(),
                                    data.getClickBuffer().getSignature(), formatted);
                        }
                    }

                    // Check if rule is a ban and Lava Wave mode is enabled
                    if ("ban".equalsIgnoreCase(rule.action()) && config.isWavePunishmentEnabled() && waveManager != null) {
                        if (!waveManager.isQueued(player.getUniqueId())) {
                            waveManager.queuePunishment(player.getUniqueId(), player.getName(), formatted, "Lava Wave: Unfair Advantage");
                        }
                        break;
                    }

                    // Execute immediate punishment (kick or instant command) on main server thread
                    if ("kick".equalsIgnoreCase(rule.action())) {
                        String kickReason = formatted;
                        String prefix1 = "kick " + player.getName() + " ";
                        String prefix2 = "minecraft:kick " + player.getName() + " ";
                        if (kickReason.regionMatches(true, 0, prefix1, 0, prefix1.length())) {
                            kickReason = kickReason.substring(prefix1.length());
                        } else if (kickReason.regionMatches(true, 0, prefix2, 0, prefix2.length())) {
                            kickReason = kickReason.substring(prefix2.length());
                        }

                        final String finalKickReason = kickReason;
                        final String finalFormatted = formatted;
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (player.isOnline()) {
                                try {
                                    player.kick(mm.deserialize(finalKickReason));
                                } catch (Throwable t) {
                                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), toLegacyCommand(finalFormatted));
                                }
                            } else {
                                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), toLegacyCommand(finalFormatted));
                            }
                        });
                    } else {
                        final String consoleCmd = toLegacyCommand(formatted);
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), consoleCmd);
                        });
                    }

                    databaseManager.logPunishmentAsync(new net.lovelace.vesuvio.storage.PunishmentRecord(
                            player.getUniqueId(),
                            player.getName(),
                            rule.action(),
                            formatted,
                            System.currentTimeMillis()
                    ));
                    break;
                }
            }
        }
    }

    private String toLegacyCommand(String input) {
        if (input == null || input.isBlank()) return "";
        try {
            if (input.contains("<") && input.contains(">")) {
                return LegacyComponentSerializer.legacySection().serialize(mm.deserialize(input));
            }
        } catch (Throwable ignored) {}
        return input;
    }
}
