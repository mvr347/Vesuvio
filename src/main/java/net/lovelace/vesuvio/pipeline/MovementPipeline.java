package net.lovelace.vesuvio.pipeline;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.config.ConfigManager;
import net.lovelace.vesuvio.data.UserData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import net.lovelace.vesuvio.check.movement.BlinkCheck;
import net.lovelace.vesuvio.check.movement.ElytraCheck;
import net.lovelace.vesuvio.check.movement.FlyCheck;
import net.lovelace.vesuvio.check.movement.InvMoveCheck;
import net.lovelace.vesuvio.check.movement.NoFallCheck;
import net.lovelace.vesuvio.check.movement.PhaseCheck;
import net.lovelace.vesuvio.check.movement.SpeedCheck;
import net.lovelace.vesuvio.check.movement.StepUpCheck;
import net.lovelace.vesuvio.check.movement.TimerCheck;
import net.lovelace.vesuvio.check.movement.VelocityCheck;
import net.lovelace.vesuvio.check.statistical.BaritoneCheck;
import net.lovelace.vesuvio.engine.LagCompensator;
import net.lovelace.vesuvio.engine.TransactionManager;

/** Per-movement-packet checks: Timer, Blink, Elytra, Velocity, Phase, Fly, Speed, NoFall, StepUp, InvMove. Extracted from CheckPipeline unchanged; the facade keeps the public API. */
final class MovementPipeline {

    private final Plugin plugin;
    private final ConfigManager config;
    private final LagCompensator lagCompensator;
    private final TransactionManager transactionManager;
    private final FlyCheck flyCheck = new FlyCheck();
    private final SpeedCheck speedCheck = new SpeedCheck();
    private final NoFallCheck noFallCheck = new NoFallCheck();
    private final TimerCheck timerCheck = new TimerCheck();
    private final VelocityCheck velocityCheck = new VelocityCheck();
    private final PhaseCheck phaseCheck = new PhaseCheck();
    private final BlinkCheck blinkCheck;
    private final ElytraCheck elytraCheck = new ElytraCheck();
    private final StepUpCheck stepUpCheck = new StepUpCheck();
    private final InvMoveCheck invMoveCheck = new InvMoveCheck();
    private final BaritoneCheck baritoneCheck = new BaritoneCheck();
    private final PipelineContext ctx;
    private final FlagReporter reporter;

    MovementPipeline(PipelineContext ctx) {
        this.ctx = ctx;
        this.reporter = ctx.reporter();
        this.plugin = ctx.plugin();
        this.config = ctx.config();
        this.lagCompensator = ctx.lagCompensator();
        this.transactionManager = ctx.transactionManager();
        this.blinkCheck = BlinkCheck.fromConfig(config);
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
        if (!config.isAnticheatEnabled() || packetReceiptNanos < data.getMovementResetNanos()) return;
        if (data.mechanics().movement(System.nanoTime())) {
            data.setLastAnyMovementNanos(packetReceiptNanos);
            data.resetTimerBalance();
            if (hasPos) {
                data.setLastPosition(x, y, z, onGround);
                data.setLastPositionNanos(packetReceiptNanos);
            }
            return;
        }

        if (data.hasRecentTeleport()) {
            data.resetTimerBalance();
            data.setLastAnyMovementNanos(packetReceiptNanos);
            if (hasPos) {
                data.setLastPosition(x, y, z, onGround);
                data.setLastPositionNanos(packetReceiptNanos);
            }
            return;
        }

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
            data.resetKinematicState();
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
            data.resetKinematicState();
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
            CheckResult timerResult = timerCheck.check(data, lagTolerance, packetReceiptNanos,
                    transactionManager != null ? transactionManager.getMsSinceLastAckSent(player.getUniqueId()) : -1.0);
            if (timerResult.isFlag()) {
                reporter.report(player, data, timerResult, timerResult.vl(), timerResult.confidence() * 10.0);
            }
        }

        // 1a. Blink runs on every movement packet, position-carrying or not - an idle vanilla
        // client sends position-less flying packets each tick, and it is the silence of that whole
        // stream (not of positions alone) that distinguishes a lag switch from standing still.
        if (config.isBlinkEnabled() && transactionManager != null) {
            CheckResult blinkResult = blinkCheck.check(player.getUniqueId(), data, transactionManager,
                    packetReceiptNanos, hasPos, clientHadSomethingToReport);
            if (blinkResult.isFlag()) {
                reporter.report(player, data, blinkResult, blinkResult.vl(), blinkResult.confidence() * 12.0);
            }
        }

        if (!hasPos) {
            return;
        }

        long nowNanos = packetReceiptNanos;
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
                reporter.report(player, data, burstResult, burstResult.vl(), burstResult.confidence() * 12.0);
            }
        }

        // Exclude teleports / huge jumps
        if (Math.abs(deltaX) > 10.0 || Math.abs(deltaY) > 15.0 || Math.abs(deltaZ) > 10.0) {
            data.resetKinematicState();
            return;
        }

        // 1a-2. Elytra: the one movement state every other check in this pipeline exempts
        // outright (EnvironmentSnapshot#isMovementExempt()) because none of them model glide
        // physics. Runs unconditionally here; the check itself gates on env.gliding() and is a
        // no-op otherwise.
        if (config.isElytraEnabled()) {
            CheckResult elytraResult = elytraCheck.check(data, deltaX, deltaY, deltaZ);
            if (elytraResult.isFlag()) {
                reporter.report(player, data, elytraResult, elytraResult.vl(), elytraResult.confidence() * 10.0);
            }
        }

        if (config.isBaritoneEnabled()) {
            CheckResult baritoneResult = baritoneCheck.check(player, data, deltaX, deltaZ);
            if (baritoneResult.isFlag()) {
                reporter.report(player, data, baritoneResult, baritoneResult.vl(), baritoneResult.confidence() * 7.0);
            }
        }

        // 1b. Velocity / anti-knockback. Runs before the exemption-heavy checks because it is the
        // check that validates the "recent velocity" exemption the others rely on.
        if (config.isVelocityEnabled() && transactionManager != null) {
            CheckResult velocityResult = velocityCheck.check(player.getUniqueId(), data, transactionManager, deltaX, deltaZ);
            if (velocityResult.isFlag()) {
                reporter.report(player, data, velocityResult, velocityResult.vl(), velocityResult.confidence() * 11.0);
            }
        }

        // 1c. Phase / Clip
        if (config.isPhaseEnabled()) {
            CheckResult phaseResult = phaseCheck.check(data, deltaX, deltaZ);
            if (phaseResult.isFlag()) {
                reporter.report(player, data, phaseResult, phaseResult.vl(), phaseResult.confidence() * 12.0);
            }
        }

        // 2. Fly & AirJump check
        if (config.isFlyEnabled()) {
            CheckResult flyResult = flyCheck.check(data, deltaX, deltaY, deltaZ, onGround);
            if (flyResult.isFlag()) {
                reporter.report(player, data, flyResult, flyResult.vl(), flyResult.confidence() * 12.0);

                // Fly is one of the fastest and hardest checks - on a high-confidence flag there is
                // little value in also running Speed/NoFall/StepUp/InvMove this same tick.
                if (flyResult.confidence() >= config.getStatisticalCutoff()) {
                    return;
                }
            }
        }

        // 3. Horizontal Speed check
        if (config.isSpeedEnabled()) {
            CheckResult speedResult = speedCheck.check(data, deltaX, deltaZ, deltaY, onGround, elapsedMs, lagTolerance);
            if (speedResult.isFlag()) {
                reporter.report(player, data, speedResult, speedResult.vl(), speedResult.confidence() * 8.0);
            }
        }

        // 4. NoFall check
        if (config.isNoFallEnabled()) {
            CheckResult noFallResult = noFallCheck.check(data, deltaY, onGround);
            if (noFallResult.isFlag()) {
                reporter.report(player, data, noFallResult, noFallResult.vl(), noFallResult.confidence() * 10.0);
            }
        }

        // 5. StepUp check
        if (config.isStepEnabled()) {
            CheckResult stepResult = stepUpCheck.check(data, deltaY, onGround);
            if (stepResult.isFlag()) {
                reporter.report(player, data, stepResult, stepResult.vl(), stepResult.confidence() * 10.0);
            }
        }

        // 6. InvMove check
        if (config.isInvMoveEnabled()) {
            CheckResult invMoveResult = invMoveCheck.check(data, deltaX, deltaZ, deltaY,
                    transactionManager != null ? transactionManager.getTransactionPing(player.getUniqueId()) : -1.0);
            if (invMoveResult.isFlag()) {
                reporter.report(player, data, invMoveResult, invMoveResult.vl(), invMoveResult.confidence() * 8.0);
            }
        }
    }
}
