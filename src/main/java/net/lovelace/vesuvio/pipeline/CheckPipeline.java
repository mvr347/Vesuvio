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
    private final SelfLearningManager selfLearning;
    private final SmartAlertService alertService;
    private final DatabaseManager databaseManager;
    private final net.lovelace.vesuvio.engine.LagCompensator lagCompensator;
    private final net.lovelace.vesuvio.punishment.PunishmentWaveManager waveManager;
    private final net.lovelace.vesuvio.staff.DiscordWebhookService discordService;
    private final net.lovelace.vesuvio.engine.HitboxHistoryTracker hitboxTracker;
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
    private final net.lovelace.vesuvio.check.statistical.KillauraAngleCheck angleCheck = new net.lovelace.vesuvio.check.statistical.KillauraAngleCheck();
    private final net.lovelace.vesuvio.check.movement.StepUpCheck stepUpCheck = new net.lovelace.vesuvio.check.movement.StepUpCheck();
    private final net.lovelace.vesuvio.check.movement.InvMoveCheck invMoveCheck = new net.lovelace.vesuvio.check.movement.InvMoveCheck();
    private final net.lovelace.vesuvio.check.combat.AutoCriticalsCheck autoCriticalsCheck = new net.lovelace.vesuvio.check.combat.AutoCriticalsCheck();

    public CheckPipeline(Plugin plugin,
                         ConfigManager config,
                         MLManager mlManager,
                         SelfLearningManager selfLearning,
                         SmartAlertService alertService,
                         DatabaseManager databaseManager,
                         net.lovelace.vesuvio.engine.LagCompensator lagCompensator,
                         net.lovelace.vesuvio.punishment.PunishmentWaveManager waveManager,
                         net.lovelace.vesuvio.staff.DiscordWebhookService discordService,
                         net.lovelace.vesuvio.engine.HitboxHistoryTracker hitboxTracker) {
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
        this.reachCheck = new net.lovelace.vesuvio.check.statistical.StatisticalReachCheck(config.getMaxReach());
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
        if (config.isTemporalConsistencyEnabled()) {
            CheckResult tempResult = temporalCheck.check(data);
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

        if (config.isOnnxEnabled() && isSuspicious && data.shouldRunML()) {
            data.markMLRun();
            float[] featuresCopy = features.clone();

            mlManager.evaluateAsync("click_model", featuresCopy).thenAccept(mlResult -> {
                data.setLastMLProbability(mlResult.probability());

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
        if (config.isSelfLearningEnabled()) {
            OnlineClassifier classifier = selfLearning.getOnlineClassifier();
            if (classifier.getTrainedSamplesCount() >= config.getOnlineClassifierMinTrainedSamples()) {
                double selfLearnProb = classifier.predict(features);
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
    }

    /**
     * Processes aim updates with delta rotations.
     */
    public void processAim(Player player, UserData data, float deltaYaw, float deltaPitch) {
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
            CheckResult gcdResult = gcdAimCheck.check(data, deltaYaw, deltaPitch);
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
    }

    public void processAim(Player player, UserData data) {
        processAim(player, data, 0f, 0f);
    }

    /**
     * Evaluates attack interactions: NoSwing, InventoryAttack, and Latency-Compensated Reach.
     */
    public void processAttack(Player player, int targetEntityId, UserData data) {
        // 1. BadPackets: NoSwing check
        if (config.isBadPacketsEnabled() && config.isBadPacketsNoSwing()) {
            CheckResult swingResult = badPacketsCheck.checkNoSwing(data.getLastSwingNanos());
            if (swingResult.isFlag()) {
                data.addVl(swingResult.vl());
                data.adjustRisk(18.0);
                data.setLastTriggeredCheck(swingResult.checkName());
                handleFlag(player, data, swingResult);
            }
        }

        // 2. BadPackets: InventoryAttack check
        if (config.isBadPacketsEnabled() && config.isBadPacketsInventoryAttack()) {
            CheckResult invResult = badPacketsCheck.checkInventoryAttack(player);
            if (invResult.isFlag()) {
                data.addVl(invResult.vl());
                data.adjustRisk(15.0);
                data.setLastTriggeredCheck(invResult.checkName());
                handleFlag(player, data, invResult);
            }
        }

        // 2.5 Auto Criticals micro-hop check
        if (config.isAutoCriticalsEnabled()) {
            CheckResult critResult = autoCriticalsCheck.check(player, data);
            if (critResult.isFlag()) {
                data.addVl(critResult.vl());
                data.adjustRisk(critResult.confidence() * 10.0);
                data.setLastTriggeredCheck(critResult.checkName());
                handleFlag(player, data, critResult);
            }
        }

        // 3. Statistical Latency-Compensated Reach & Combat Angle check
        org.bukkit.entity.Entity target = null;
        for (org.bukkit.entity.Entity e : player.getNearbyEntities(7.0, 7.0, 7.0)) {
            if (e.getEntityId() == targetEntityId) {
                target = e;
                break;
            }
        }

        if (target != null && !target.equals(player)) {
            if (config.isReachEnabled()) {
                CheckResult reachResult = reachCheck.check(player, target, data, hitboxTracker, lagCompensator);
                if (reachResult.isFlag()) {
                    data.addVl(reachResult.vl() * config.getStatisticalWeight());
                    data.adjustRisk(reachResult.confidence() * 8.0);
                    data.setLastTriggeredCheck(reachResult.checkName());
                    handleFlag(player, data, reachResult);
                }
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
        }
    }

    /**
     * Processes player movement packets (Fly, Speed, NoFall, Timer).
     */
    public void processMovement(Player player, UserData data, double x, double y, double z, boolean onGround, boolean hasPos) {
        // 1. Timer check runs on every movement packet
        if (config.isTimerEnabled()) {
            CheckResult timerResult = timerCheck.check(player, data);
            if (timerResult.isFlag()) {
                data.addVl(timerResult.vl());
                data.adjustRisk(timerResult.confidence() * 10.0);
                data.setLastTriggeredCheck(timerResult.checkName());
                handleFlag(player, data, timerResult);
            }
        }

        if (!hasPos) {
            return;
        }

        if (!data.hasLastPosition()) {
            data.setLastPosition(x, y, z, onGround);
            return;
        }

        double deltaX = x - data.getLastX();
        double deltaY = y - data.getLastY();
        double deltaZ = z - data.getLastZ();

        data.setLastPosition(x, y, z, onGround);

        // Exclude teleports / huge jumps
        if (Math.abs(deltaX) > 10.0 || Math.abs(deltaY) > 15.0 || Math.abs(deltaZ) > 10.0) {
            data.resetAirTicks();
            data.resetFlyStreak();
            data.resetSpeedStreak();
            return;
        }

        // 2. Fly & AirJump check
        if (config.isFlyEnabled()) {
            CheckResult flyResult = flyCheck.check(player, data, deltaX, deltaY, deltaZ, onGround);
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
            CheckResult speedResult = speedCheck.check(player, data, deltaX, deltaZ);
            if (speedResult.isFlag()) {
                data.addVl(speedResult.vl());
                data.adjustRisk(speedResult.confidence() * 8.0);
                data.setLastTriggeredCheck(speedResult.checkName());
                handleFlag(player, data, speedResult);
            }
        }

        // 4. NoFall check
        if (config.isNoFallEnabled()) {
            CheckResult noFallResult = noFallCheck.check(player, data, deltaY, onGround);
            if (noFallResult.isFlag()) {
                data.addVl(noFallResult.vl());
                data.adjustRisk(noFallResult.confidence() * 10.0);
                data.setLastTriggeredCheck(noFallResult.checkName());
                handleFlag(player, data, noFallResult);
            }
        }

        // 5. StepUp check
        if (config.isStepEnabled()) {
            CheckResult stepResult = stepUpCheck.check(player, data, deltaY, onGround);
            if (stepResult.isFlag()) {
                data.addVl(stepResult.vl());
                data.adjustRisk(stepResult.confidence() * 10.0);
                data.setLastTriggeredCheck(stepResult.checkName());
                handleFlag(player, data, stepResult);
            }
        }

        // 6. InvMove check
        if (config.isInvMoveEnabled()) {
            CheckResult invMoveResult = invMoveCheck.check(player, data, deltaX, deltaZ, deltaY);
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
                    if ("ban".equalsIgnoreCase(rule.action())) {
                        selfLearning.getAutoDatasetCollector().collectCheatSample(player, data);
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
