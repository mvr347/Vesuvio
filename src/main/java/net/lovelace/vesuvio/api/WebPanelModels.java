package net.lovelace.vesuvio.api;

import java.util.UUID;

/**
 * Read-only data-transfer records exposed to external web panels (e.g. LoveWebAdmin) through
 * {@link VesuvioAPI}. Kept separate from internal engine types (UserData, records in
 * net.lovelace.vesuvio.storage) so the public API surface can evolve independently of the
 * internal detection pipeline representation.
 *
 * Author: Lovelace
 */
public final class WebPanelModels {

    private WebPanelModels() {}

    /** Basic-tier: shown to staff with only "who is suspected" visibility. */
    public record SuspectInfo(
            UUID uuid,
            String name,
            double vl,
            double risk,
            double trust,
            String brand,
            String status,
            String lastTriggeredCheck,
            boolean manualSuspect
    ) {}

    /** Basic-tier: recent punishment log entries. */
    public record PunishmentInfo(
            UUID uuid,
            String name,
            String action,
            String reason,
            long timestamp
    ) {}

    /** Advanced-tier: raw violation (flag) log, one row per triggered check. */
    public record ViolationInfo(
            UUID uuid,
            String name,
            String checkName,
            double vl,
            double confidence,
            String explanation,
            long timestamp
    ) {}

    /** Advanced-tier: full per-player biometric/detection snapshot for deep inspection. */
    public record PlayerDetail(
            UUID uuid,
            String name,
            double vl,
            double risk,
            double trust,
            double sensitivity,
            String brand,
            String lastTriggeredCheck,
            boolean manualSuspect,
            double cps,
            double stdDevMs,
            double dupRatio,
            double entropy,
            double mlProbability,
            double selfLearnProbability,
            int airTicks,
            double lastDeltaY,
            boolean onGround,
            int clickBufferCount,
            int aimBufferCount,
            int gcdSuspiciousStreak,
            int perfectAimStreak,
            boolean inCombat
    ) {}

    /** Advanced-tier: engine-wide status (layers, models, dataset, web server). */
    public record EngineStatus(
            boolean statisticalEnabled,
            boolean onnxEnabled,
            boolean selfLearningEnabled,
            boolean clickModelLoaded,
            boolean aimModelLoaded,
            long onnxTotalInferences,
            int onlineTrackedPlayers,
            int suspectsCount,
            int datasetSize,
            long classifierTrainedSamples,
            boolean autoCollectionEnabled,
            boolean webEnabled,
            int totalFlagsLogged,
            int punishmentRulesCount
    ) {}
}
