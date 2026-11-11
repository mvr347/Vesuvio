package net.lovelace.vesuvio.pipeline;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.check.onnx.MLManager;
import net.lovelace.vesuvio.check.selflearning.OnlineClassifier;
import net.lovelace.vesuvio.check.selflearning.SelfLearningManager;
import net.lovelace.vesuvio.check.statistical.StatisticalAimCheck;
import net.lovelace.vesuvio.config.ConfigManager;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.feature.AimFeatureExtractor;
import org.bukkit.entity.Player;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.lovelace.vesuvio.check.protocol.BadPacketsCheck;
import net.lovelace.vesuvio.check.statistical.GCDAimCheck;
import net.lovelace.vesuvio.check.statistical.KillauraAngleCheck;
import net.lovelace.vesuvio.check.statistical.SnapAimCheck;
import net.lovelace.vesuvio.data.AimTrackingBuffer;
import net.lovelace.vesuvio.packet.AimPacketListener;

/** Rotation detection: snap-back, pitch bounds, GCD, statistical, ONNX and self-learning layers. Extracted from CheckPipeline unchanged; the facade keeps the public API. */
final class AimPipeline {

    private final ConfigManager config;
    private final MLManager mlManager;
    private final SelfLearningManager selfLearning;
    private final StatisticalAimCheck aimCheck = new StatisticalAimCheck();
    private final GCDAimCheck gcdAimCheck = new GCDAimCheck();
    private final BadPacketsCheck badPacketsCheck = new BadPacketsCheck();
    private final SnapAimCheck snapAimCheck;
    private final PipelineContext ctx;
    private final FlagReporter reporter;

    AimPipeline(PipelineContext ctx) {
        this.ctx = ctx;
        this.reporter = ctx.reporter();
        this.config = ctx.config();
        this.mlManager = ctx.mlManager();
        this.selfLearning = ctx.selfLearning();
        this.snapAimCheck = new SnapAimCheck(config);
    }

    /**
     * Processes aim updates with delta rotations.
     */
    public void processAim(Player player, UserData data, float deltaYaw, float deltaPitch) {
        processAim(player, data, deltaYaw, deltaPitch, deltaYaw);
    }

    /**
     * @param signedDeltaYaw this packet's yaw change, signed and wrapped to (-180, 180] - see
     *                       {@link AimTrackingBuffer#wrapDegrees}. The
     *                       3-arg overload above passes the unsigned magnitude instead, which only
     *                       matters to {@link SnapAimCheck}
     *                       and only means that overload's caller (tests, mainly) cannot exercise
     *                       the sign-reversal half of that check - production always goes through
     *                       {@link AimPacketListener}, which has the
     *                       real signed value.
     */
    public void processAim(Player player, UserData data, float deltaYaw, float deltaPitch, float signedDeltaYaw) {
        if (!config.isAnticheatEnabled() || data.hasRecentTeleport()
                || data.mechanics().combat(System.nanoTime())) return;

        // KillauraAngleCheck's ReactionTime sub-check needs to know when the attacker's camera
        // last made a "real" turn, not merely received a rotation packet - tracked here since this
        // is the one place every aim update (combat or not) passes through.
        long nowNanos = System.nanoTime();
        data.setLastRotationNanos(nowNanos);
        double combinedRotation = Math.abs(deltaYaw) + Math.abs(deltaPitch);
        if (combinedRotation >= config.getKillauraReactionSignificantRotationDegrees()) {
            data.setLastSignificantRotationNanos(nowNanos);
        }

        // Angular-jerk ("snap-back") detector - see SnapAimCheck's javadoc. Deliberately not
        // gated on combat: the revert half of a snap-back can land on the tick after the attack,
        // by which point isInCombat() is still true, but there is no reason to make the check
        // depend on that timing at all when the signature is meaningful on its own.
        if (config.isSnapAimEnabled()) {
            CheckResult snapResult = snapAimCheck.check(data, signedDeltaYaw, nowNanos);
            if (snapResult.isFlag()) {
                reporter.report(player, data, snapResult, snapResult.vl() * config.getStatisticalWeight(), snapResult.confidence() * 9.0);
            }
        }

        // BadPackets Pitch Bounds Check
        if (config.isBadPacketsEnabled() && config.isBadPacketsPitchBounds()) {
            CheckResult pitchResult = badPacketsCheck.checkPitch(data.getLastPitch());
            if (pitchResult.isFlag()) {
                reporter.report(player, data, pitchResult, pitchResult.vl(), 20.0);
            }
        }

        // Mathematical GCD Mouse Quantization Check (GrimAC/Polar)
        if (config.isGcdAimEnabled() && (deltaYaw > 0 || deltaPitch > 0)) {
            CheckResult gcdResult = gcdAimCheck.check(data, deltaYaw, deltaPitch, (float) config.getGcdAimMinRotation());
            if (gcdResult.isFlag()) {
                reporter.report(player, data, gcdResult, gcdResult.vl() * config.getStatisticalWeight(), gcdResult.confidence() * 7.0);
            }
        }

        if (!config.isStatisticalEnabled()) return;

        if (data.getAimBuffer().isFull()) {
            CheckResult result = aimCheck.check(data);
            if (result.isFlag()) {
                reporter.reportWindowed(player, data, result, result.vl() * config.getStatisticalWeight(), result.confidence() * 6.0);
            }
        }

        // Layer 2: ONNX Aim Machine Learning Inference Layer
        if (config.isOnnxEnabled() && data.isInCombat() && data.getAimBuffer().getCount() >= 16) {
            float[] aimFeatures = AimFeatureExtractor.extract(data.getAimBuffer());
            boolean isAimSuspicious = (aimFeatures[4] > 0.15f || aimFeatures[6] > 12.0f || aimFeatures[5] > 0.35f || aimFeatures[7] < 0.20f);
            if (isAimSuspicious && data.shouldRunML()) {
                data.markMLRun();
                float[] featuresCopy = aimFeatures.clone();
                long detectionEpoch = config.runtime().detectionEpoch();
                long resetEpoch = data.getMovementResetNanos();
                mlManager.evaluateAsync("aim_model", featuresCopy).thenAccept(mlResult -> {
                    if (!config.runtime().acceptsDetection(detectionEpoch) || data.getMovementResetNanos() != resetEpoch) return;
                    ctx.recordShadowVerdict("aim_model", featuresCopy, mlResult.probability());
                    if (mlResult.isFlag()) {
                        CheckResult res = CheckResult.flag("AimML", mlResult.probability(), mlResult.vl(),
                                String.format(Locale.US, "Уверенность модели прицеливания: %.1f%%", mlResult.probability() * 100),
                                mlResult.details());
                        reporter.reportWindowed(player, data, res, mlResult.vl() * config.getOnnxWeight(), mlResult.probability() * 10.0);
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
                    Map<String, Object> slDetails = new HashMap<>();
                    slDetails.put("probability", aimSelfLearnProb);
                    slDetails.put("trainedSamples", aimClassifier.getTrainedSamplesCount());

                    CheckResult slResult = CheckResult.flag("AimSelfLearn", aimSelfLearnProb, 1.6 * config.getSelfLearningWeight(),
                            String.format(Locale.US, "Уверенность самообучающегося классификатора прицеливания: %.1f%% (обучен на %d образцах)",
                                    aimSelfLearnProb * 100, aimClassifier.getTrainedSamplesCount()),
                            slDetails);
                    reporter.reportWindowed(player, data, slResult, 1.6 * config.getSelfLearningWeight(), aimSelfLearnProb * 9.0);
                } else if (aimSelfLearnProb >= config.getOnlineClassifierSilentRiskThreshold()) {
                    data.adjustRisk((aimSelfLearnProb - config.getOnlineClassifierSilentRiskThreshold()) * 12.0);
                }
            }
        }
    }

    public void processAim(Player player, UserData data) {
        processAim(player, data, 0f, 0f);
    }
}
