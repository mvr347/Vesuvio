package net.lovelace.vesuvio.pipeline;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.check.onnx.MLManager;
import net.lovelace.vesuvio.check.selflearning.SelfLearningManager;
import net.lovelace.vesuvio.config.ConfigManager;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.staff.SmartAlertService;
import net.lovelace.vesuvio.storage.DatabaseManager;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.function.Function;
import net.lovelace.vesuvio.integration.essential.MechanicsState;
import java.util.UUID;
import java.util.concurrent.Executor;
import net.lovelace.vesuvio.check.onnx.ShadowEvaluator;
import net.lovelace.vesuvio.diagnostics.DiagnosticService;
import net.lovelace.vesuvio.engine.AimTrackingService;
import net.lovelace.vesuvio.engine.AttackSnapshot;
import net.lovelace.vesuvio.engine.HitboxHistoryTracker;
import net.lovelace.vesuvio.engine.LagCompensator;
import net.lovelace.vesuvio.engine.NpcTrapManager;
import net.lovelace.vesuvio.engine.TransactionManager;
import net.lovelace.vesuvio.evasion.BanEvasionManager;
import net.lovelace.vesuvio.punishment.PunishmentService;
import net.lovelace.vesuvio.punishment.PunishmentWaveManager;
import net.lovelace.vesuvio.staff.DiscordWebhookService;

/**
 * 5.1 Check Pipeline with Prioritized Multi-Layer Execution.
 * Layer 1 (Statistical) -> Layer 2 (ONNX Inference) -> Layer 3 (Self-Learning & Anomaly Memory).
 * Dispatches heavy computations to virtual threads with 0% TPS impact.
 *
 * Author: Lovelace
 */
public final class CheckPipeline {

    private final ConfigManager config;
    private final PipelineContext ctx;
    private final FlagReporter reporter;
    private final ClickPipeline clickPipeline;
    private final AimPipeline aimPipeline;
    private final AttackPipeline attackPipeline;
    private final MovementPipeline movementPipeline;

    public CheckPipeline(Plugin plugin,
                         ConfigManager config,
                         MLManager mlManager,
                         SelfLearningManager selfLearning,
                         SmartAlertService alertService,
                         DatabaseManager databaseManager,
                         LagCompensator lagCompensator,
                         PunishmentWaveManager waveManager,
                         DiscordWebhookService discordService,
                         HitboxHistoryTracker hitboxTracker,
                         BanEvasionManager banEvasionManager,
                         TransactionManager transactionManager,
                         NpcTrapManager npcTrapManager,
                         Executor backgroundExecutor,
                         DiagnosticService diagnostics) {
        this.config = config;
        PunishmentService punishmentService = new PunishmentService(plugin, config, databaseManager, waveManager,
                selfLearning, banEvasionManager);
        this.ctx = new PipelineContext(plugin, config, mlManager, selfLearning, alertService, databaseManager, lagCompensator,
                waveManager, discordService, hitboxTracker, banEvasionManager, transactionManager, npcTrapManager,
                backgroundExecutor, diagnostics, punishmentService);
        this.reporter = new FlagReporter(config, alertService, databaseManager, discordService, diagnostics, punishmentService);
        ctx.reporter(reporter);
        this.clickPipeline = new ClickPipeline(ctx);
        this.aimPipeline = new AimPipeline(ctx);
        this.attackPipeline = new AttackPipeline(ctx);
        this.movementPipeline = new MovementPipeline(ctx);
    }

    public void targetMechanics(Function<UUID, MechanicsState> resolver) {
        ctx.targetMechanics(resolver);
    }

    public void setAimTrackingService(AimTrackingService service) {
        ctx.aimTrackingService(service);
    }

    public AimTrackingService getAimTrackingService() {
        return ctx.aimTrackingService();
    }

    /**
     * Wired after construction (same reason as the tracking service above). Null unless shadow
     * mode is in use, in which case every real inference is mirrored to the candidate model.
     */
    public void setShadowEvaluator(ShadowEvaluator evaluator) {
        ctx.shadowEvaluator(evaluator);
    }


    /**
     * The global kill-switch (/vesuvio toggle) - see {@link ConfigManager#isAnticheatEnabled()}.
     * Exposed so packet listeners that only hold a reference to this pipeline (not to
     * {@link ConfigManager} directly) can gate before doing any per-packet work at all, rather
     * than dispatching to a virtual thread just to have this pipeline no-op there.
     */
    public void submitPacketCheck(UserData data, Executor executor, Runnable task) {
        long epoch = config.runtime().detectionEpoch();
        long reset = data.getMovementResetNanos();
        data.packetChecks().submit(executor, () -> {
            synchronized (data) {
                if (config.runtime().acceptsDetection(epoch) && data.getMovementResetNanos() == reset) task.run();
            }
        });
    }

    public boolean isAnticheatEnabled() {
        return config.isAnticheatEnabled();
    }
    public long getDetectionEpoch() { return config.runtime().detectionEpoch(); }

    public BanEvasionManager getBanEvasionManager() {
        return ctx.banEvasionManager();
    }

    public NpcTrapManager getNpcTrapManager() {
        return ctx.npcTrapManager();
    }

    public void processClick(Player player, UserData data) {
        clickPipeline.processClick(player, data);
    }

    public void processAim(Player player, UserData data, float deltaYaw, float deltaPitch) {
        aimPipeline.processAim(player, data, deltaYaw, deltaPitch);
    }

    public void processAim(Player player, UserData data, float deltaYaw, float deltaPitch, float signedDeltaYaw) {
        aimPipeline.processAim(player, data, deltaYaw, deltaPitch, signedDeltaYaw);
    }

    public void processAim(Player player, UserData data) {
        aimPipeline.processAim(player, data);
    }

    public void processAttack(Player player, int targetEntityId, UserData data) {
        attackPipeline.processAttack(player, targetEntityId, data);
    }

    public void processAttack(Player player, int targetEntityId, UserData data, AttackSnapshot attack) {
        attackPipeline.processAttack(player, targetEntityId, data, attack);
    }

    public void handleNpcTrapHit(Player player, UserData data) {
        attackPipeline.handleNpcTrapHit(player, data);
    }

    public void processMovement(Player player, UserData data, double x, double y, double z, boolean onGround, boolean hasPos, long packetReceiptNanos) {
        movementPipeline.processMovement(player, data, x, y, z, onGround, hasPos, packetReceiptNanos);
    }

    public void processMovement(Player player, UserData data, double x, double y, double z, boolean onGround, boolean hasPos, long packetReceiptNanos, boolean clientHadSomethingToReport) {
        movementPipeline.processMovement(player, data, x, y, z, onGround, hasPos, packetReceiptNanos, clientHadSomethingToReport);
    }

    public boolean report(Player player, UserData data, CheckResult result, double vl, double risk) {
        return reporter.report(player, data, result, vl, risk);
    }

    public boolean reportWindowed(Player player, UserData data, CheckResult result, double vl, double risk) {
        return reporter.reportWindowed(player, data, result, vl, risk);
    }

    public void handleFlag(Player player, UserData data, CheckResult result) {
        reporter.handleFlag(player, data, result);
    }

    /** Transactional round-trip time in milliseconds (never Player#getPing()), or -1 when unknown. */
    public double transactionRtt(UUID uuid) {
        return ctx.transactionRtt(uuid);
    }
}
