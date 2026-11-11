package net.lovelace.vesuvio.pipeline;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.check.selflearning.SelfLearningManager;
import net.lovelace.vesuvio.config.ConfigManager;
import net.lovelace.vesuvio.data.UserData;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import net.lovelace.vesuvio.check.combat.AutoCriticalsCheck;
import net.lovelace.vesuvio.check.combat.BackTrackCheck;
import net.lovelace.vesuvio.check.combat.MoveDirectionCheck;
import net.lovelace.vesuvio.check.protocol.BadPacketsCheck;
import net.lovelace.vesuvio.check.statistical.KillauraAngleCheck;
import net.lovelace.vesuvio.check.statistical.StatisticalReachCheck;
import net.lovelace.vesuvio.check.statistical.StrafeReversalCheck;
import net.lovelace.vesuvio.engine.AimTrackingService;
import net.lovelace.vesuvio.engine.AttackSnapshot;
import net.lovelace.vesuvio.engine.HitboxHistoryTracker;
import net.lovelace.vesuvio.engine.LagCompensator;
import net.lovelace.vesuvio.engine.NpcTrapManager;
import net.lovelace.vesuvio.engine.TransactionManager;
import org.bukkit.entity.Entity;

/** Everything that runs when a player attacks: packets order checks, reach, angle, BackTrack, MoveDirection, NPC trap. Extracted from CheckPipeline unchanged; the facade keeps the public API. */
final class AttackPipeline {

    private static final Logger LOGGER = Logger.getLogger("Vesuvio-Pipeline");
    private final Plugin plugin;
    private final ConfigManager config;
    private final SelfLearningManager selfLearning;
    private final LagCompensator lagCompensator;
    private final HitboxHistoryTracker hitboxTracker;
    private final TransactionManager transactionManager;
    private final NpcTrapManager npcTrapManager;
    private final BadPacketsCheck badPacketsCheck = new BadPacketsCheck();
    private final StatisticalReachCheck reachCheck;
    private final KillauraAngleCheck angleCheck;
    private final StrafeReversalCheck strafeReversalCheck;
    private final AutoCriticalsCheck autoCriticalsCheck = new AutoCriticalsCheck();
    private final BackTrackCheck backTrackCheck = new BackTrackCheck();
    private final MoveDirectionCheck moveDirectionCheck = new MoveDirectionCheck();
    private final PipelineContext ctx;
    private final FlagReporter reporter;

    AttackPipeline(PipelineContext ctx) {
        this.ctx = ctx;
        this.reporter = ctx.reporter();
        this.plugin = ctx.plugin();
        this.config = ctx.config();
        this.selfLearning = ctx.selfLearning();
        this.lagCompensator = ctx.lagCompensator();
        this.hitboxTracker = ctx.hitboxTracker();
        this.transactionManager = ctx.transactionManager();
        this.npcTrapManager = ctx.npcTrapManager();
        this.reachCheck = new StatisticalReachCheck(config.getMaxReach(), transactionManager);
        this.angleCheck = new KillauraAngleCheck(config, hitboxTracker, transactionManager);
        this.strafeReversalCheck = new StrafeReversalCheck(config);
    }

    /**
     * Evaluates attack interactions: NoSwing, InventoryAttack, and Latency-Compensated Reach.
     */
    public void processAttack(Player player, int targetEntityId, UserData data) {
        processAttack(player, targetEntityId, data, data.captureAttack(System.nanoTime()));
    }

    public void processAttack(Player player, int targetEntityId, UserData data,
                              AttackSnapshot attack) {
        if (!config.isAnticheatEnabled() || data.hasRecentTeleport()
                || data.mechanics().combat(System.nanoTime())) return;

        // A downed/invulnerable attacker or target means a third-party plugin (revive/downed-
        // state mechanics, admin god-mode, spawn protection, etc.) is actively controlling that
        // entity's position/hitbox/state outside of normal survival rules - our combat math
        // (reach, angle, crit timing) isn't meaningful there and shouldn't punish it. Concretely
        // reported case: finishing off a player mid-revive (temporarily invulnerable, position
        // still settling) false-flagged the finisher for Reach/Angle.
        if (player.isInvulnerable()) {
            return;
        }

        // Animation may legally follow ATTACK in the same client tick. Wait three server
        // ticks and match against the bounded swing history, not just the last animation.
        if (config.isBadPacketsEnabled() && config.isBadPacketsNoSwing()) {
            long epoch = config.runtime().detectionEpoch();
            long reset = data.getMovementResetNanos();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!config.runtime().acceptsDetection(epoch) || data.getMovementResetNanos() != reset
                        || !player.isOnline() || data.hasRecentTeleport() || data.hasSwingNear(attack.receivedNanos())) return;
                CheckResult result = CheckResult.flag("BadPackets", 0.95, 3.0,
                        "Атака без соответствующей анимации руки", Map.of("windowMs", 150));
                reporter.report(player, data, result, result.vl(), 18);
            }, 3L);
        }

        // 2. BadPackets: InventoryAttack check
        if (config.isBadPacketsEnabled() && config.isBadPacketsInventoryAttack()) {
            CheckResult invResult = badPacketsCheck.checkInventoryAttack(data,
                    transactionManager != null ? transactionManager.getTransactionPing(player.getUniqueId()) : -1.0);
            if (invResult.isFlag()) {
                reporter.report(player, data, invResult, invResult.vl(), 15.0);
            }
        }

        // 2.5 Auto Criticals micro-hop check
        if (config.isAutoCriticalsEnabled()) {
            CheckResult critResult = autoCriticalsCheck.check(data);
            if (critResult.isFlag()) {
                reporter.report(player, data, critResult, critResult.vl(), critResult.confidence() * 10.0);
            }
        }

        // 2.6 Strafe-reversal sensorimotor latency. Runs off the aim-tracking buffer rather than
        // this attack's geometry, so it is throttled instead of running per attack - the buffer
        // only gains one sample per tick, and re-scanning it on every swing would just re-walk the
        // same events.
        if (config.isStrafeReversalEnabled() && data.shouldRunStrafeReversal(config.getStrafeReversalIntervalMs())) {
            CheckResult reversalResult = strafeReversalCheck.check(data);
            if (reversalResult.isFlag()) {
                reporter.report(player, data, reversalResult, reversalResult.vl(), reversalResult.confidence() * 10.0);
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
        long epoch = config.runtime().detectionEpoch();
        long reset = data.getMovementResetNanos();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!config.runtime().acceptsDetection(epoch) || data.getMovementResetNanos() != reset
                    || data.hasRecentTeleport()) return;
            runAttackWorldChecks(player, targetEntityId, data, attack);
        });
    }

    /** Main-thread half of {@link #processAttack}: everything that needs live world state. */
    private void runAttackWorldChecks(Player player, int targetEntityId, UserData data, AttackSnapshot attack) {
        if (!player.isOnline()) return;

        Entity target = null;
        for (Entity e : player.getNearbyEntities(7.0, 7.0, 7.0)) {
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
        if (target == null || target.equals(player) || target.isInvulnerable()
                || ctx.controlledTarget(target)) {
            // Reach and the whole killaura family live below this line, so when an operator reports
            // that an attack "was not caught", the first thing that has to be answered is whether
            // the attack reached them at all. Silence here and silence from a check that ran look
            // identical in the log otherwise.
            if (config.isDebug()) {
                LOGGER.info(String.format(Locale.US,
                        "[Атака] %s -> сущность=%d: пропущена (%s), боевые проверки не выполнены",
                        player.getName(), targetEntityId,
                        target == null ? "цель отсутствует среди ближайших сущностей"
                                : target.equals(player) ? "цель совпадает с атакующим"
                                : ctx.controlledTarget(target) ? "поза цели управляется LoveEssential"
                                : "цель неуязвима"));
            }
            return;
        }

        if (config.isReachEnabled()) {
            CheckResult reachResult = reachCheck.check(player, target, data, hitboxTracker, lagCompensator, attack);
            if (reachResult.isFlag()) {
                reporter.report(player, data, reachResult, reachResult.vl() * config.getStatisticalWeight(), reachResult.confidence() * 8.0);
            }
        }

        // Tell the per-tick aim tracker what this player is actually fighting, so it can record
        // target-relative aim from here on (see AimTrackingService / StrafeReversalCheck).
        if (ctx.aimTrackingService() != null) {
            ctx.aimTrackingService().noteTarget(player, target);
        }

        if (config.isKillauraAngleEnabled()) {
            CheckResult angleResult = angleCheck.check(player, target, data, attack);
            if (angleResult.isFlag()) {
                reporter.report(player, data, angleResult, angleResult.vl(), angleResult.confidence() * 9.0);
            }
        }

        if (config.isBackTrackEnabled() && hitboxTracker != null && transactionManager != null) {
            CheckResult backTrackResult = backTrackCheck.check(player, target, data, hitboxTracker, transactionManager, config.getMaxReach(), attack);
            if (backTrackResult.isFlag()) {
                reporter.report(player, data, backTrackResult, backTrackResult.vl(), backTrackResult.confidence() * 10.0);
            }
        }

        if (config.isMoveDirectionEnabled()) {
            CheckResult moveDirResult = moveDirectionCheck.check(player, target, data);
            if (moveDirResult.isFlag()) {
                reporter.report(player, data, moveDirResult, moveDirResult.vl(), moveDirResult.confidence() * 8.0);
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
     * a per-player packet-level trap (see {@link NpcTrapManager}). No
     * further validation is meaningful here - a legitimate client cannot see or aim at this entity
     * at all, so a single hit is conclusive rather than needing the streak/confidence machinery
     * every other combat check relies on.
     */
    public void handleNpcTrapHit(Player player, UserData data) {
        if (!config.isAnticheatEnabled() || data.hasRecentTeleport()
                || data.mechanics().combat(System.nanoTime())) return;

        if (npcTrapManager != null) npcTrapManager.despawnTrap(player.getUniqueId());

        // The one label in the system that is not self-confirming: a legitimate client cannot see
        // this entity at all, so the verdict owes nothing to the statistics the models would
        // otherwise just be learning to reproduce.
        selfLearning.getAutoDatasetCollector().collectTrapSample(player, data);

        Map<String, Object> details = new HashMap<>();
        details.put("target", "npc-trap");

        CheckResult result = CheckResult.flag("NpcTrap", 1.0, 25.0,
                "Атака персональной пакетной сущности-ловушки, недоступной при обычной игре", details);
        reporter.report(player, data, result, result.vl(), 60.0);
    }
}
