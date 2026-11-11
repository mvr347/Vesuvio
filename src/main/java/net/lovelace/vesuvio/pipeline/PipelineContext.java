package net.lovelace.vesuvio.pipeline;

import net.lovelace.vesuvio.check.onnx.MLManager;
import net.lovelace.vesuvio.check.selflearning.SelfLearningManager;
import net.lovelace.vesuvio.config.ConfigManager;
import net.lovelace.vesuvio.staff.SmartAlertService;
import net.lovelace.vesuvio.storage.DatabaseManager;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import java.util.function.Function;
import net.lovelace.vesuvio.integration.essential.MechanicsState;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import net.lovelace.vesuvio.check.onnx.ShadowEvaluator;
import net.lovelace.vesuvio.diagnostics.DiagnosticService;
import net.lovelace.vesuvio.engine.AimTrackingService;
import net.lovelace.vesuvio.engine.HitboxHistoryTracker;
import net.lovelace.vesuvio.engine.LagCompensator;
import net.lovelace.vesuvio.engine.NpcTrapManager;
import net.lovelace.vesuvio.engine.TransactionManager;
import net.lovelace.vesuvio.evasion.BanEvasionManager;
import net.lovelace.vesuvio.punishment.PunishmentService;
import net.lovelace.vesuvio.punishment.PunishmentWaveManager;
import net.lovelace.vesuvio.staff.DiscordWebhookService;
import org.bukkit.entity.Entity;

/** State shared by the sub-pipelines: services, plus the few things wired after construction. */
final class PipelineContext {

    private final Plugin plugin;
    private final ConfigManager config;
    private final MLManager mlManager;
    private final SelfLearningManager selfLearning;
    private final SmartAlertService alertService;
    private final DatabaseManager databaseManager;
    private final LagCompensator lagCompensator;
    private final PunishmentWaveManager waveManager;
    private final DiscordWebhookService discordService;
    private final HitboxHistoryTracker hitboxTracker;
    private final BanEvasionManager banEvasionManager;
    private final TransactionManager transactionManager;
    private final NpcTrapManager npcTrapManager;
    private final Executor backgroundExecutor;
    private final DiagnosticService diagnostics;
    private final PunishmentService punishmentService;
    private volatile ShadowEvaluator shadowEvaluator;
    private volatile AimTrackingService aimTrackingService;
    private volatile Function<UUID, MechanicsState> targetMechanics = id -> MechanicsState.NONE;
    private FlagReporter reporter;

    PipelineContext(Plugin plugin, ConfigManager config, MLManager mlManager, SelfLearningManager selfLearning, SmartAlertService alertService, DatabaseManager databaseManager, LagCompensator lagCompensator, PunishmentWaveManager waveManager, DiscordWebhookService discordService, HitboxHistoryTracker hitboxTracker, BanEvasionManager banEvasionManager, TransactionManager transactionManager, NpcTrapManager npcTrapManager, Executor backgroundExecutor, DiagnosticService diagnostics, PunishmentService punishmentService) {
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
        this.backgroundExecutor = backgroundExecutor;
        this.diagnostics = diagnostics;
        this.punishmentService = punishmentService;
    }

    Plugin plugin() { return plugin; }
    ConfigManager config() { return config; }
    MLManager mlManager() { return mlManager; }
    SelfLearningManager selfLearning() { return selfLearning; }
    SmartAlertService alertService() { return alertService; }
    DatabaseManager databaseManager() { return databaseManager; }
    LagCompensator lagCompensator() { return lagCompensator; }
    PunishmentWaveManager waveManager() { return waveManager; }
    DiscordWebhookService discordService() { return discordService; }
    HitboxHistoryTracker hitboxTracker() { return hitboxTracker; }
    BanEvasionManager banEvasionManager() { return banEvasionManager; }
    TransactionManager transactionManager() { return transactionManager; }
    NpcTrapManager npcTrapManager() { return npcTrapManager; }
    Executor backgroundExecutor() { return backgroundExecutor; }
    DiagnosticService diagnostics() { return diagnostics; }
    PunishmentService punishmentService() { return punishmentService; }
    FlagReporter reporter() { return reporter; }
    void reporter(FlagReporter value) { this.reporter = value; }
    AimTrackingService aimTrackingService() { return aimTrackingService; }
    void aimTrackingService(AimTrackingService value) { this.aimTrackingService = value; }
    void shadowEvaluator(ShadowEvaluator value) { this.shadowEvaluator = value; }
    void targetMechanics(Function<UUID, MechanicsState> value) { this.targetMechanics = Objects.requireNonNull(value); }

    boolean controlledTarget(Entity target) {
        return target instanceof Player player
                && targetMechanics.apply(player.getUniqueId()).hitbox(System.nanoTime());
    }

    /**
     * Mirrors one inference to the shadow candidate, if one is loaded for this model.
     *
     * <p>Runs on the ONNX completion callback, never on the main thread, and its result is only
     * ever counted - it cannot touch VL, Risk, Trust or alerts. That is the entire contract of
     * shadow mode: an unproven model gets to be measured against live traffic without being
     * allowed to punish anyone on the strength of an offline score.
     */
    void recordShadowVerdict(String modelName, float[] features, double liveProbability) {
        var evaluator = shadowEvaluator;
        if (evaluator == null) return;

        String shadowName = modelName + ShadowEvaluator.SHADOW_SUFFIX;
        if (!mlManager.isModelLoaded(shadowName)) return;

        double liveThreshold = mlManager.getThreshold(modelName);
        double shadowThreshold = mlManager.getThreshold(shadowName);
        mlManager.evaluateAsync(shadowName, features).thenAccept(shadowResult ->
                evaluator.record(modelName, liveProbability, shadowResult.probability(),
                        liveThreshold, shadowThreshold));
    }

    /**
     * Single entry point for a flag: every place that used to call addVl + adjustRisk + handleFlag goes through here.
     * Order matters. Checks disabled or a recent teleport: diagnostics only, no VL (the flag is dropped, so its VL must not
     * stay behind). Windowed checks (see {@link #reportWindowed}) are re-reported at most once per
     * layers.statistical.reflag-interval-ms. Checks in punishments.excluded-checks are still logged and shown to staff,
     * but add neither VL nor risk. Only then the regular handleFlag (alert, Discord, database, punishments).
     *
     * @return whether the flag was accepted (reached handleFlag)
     */
    /** Transactional round-trip time in milliseconds (never Player#getPing()), or -1 when unknown. */
    public double transactionRtt(UUID uuid) {
        return transactionManager != null ? transactionManager.getTransactionPing(uuid) : -1.0;
    }
}
