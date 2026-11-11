package net.lovelace.vesuvio.data;

import net.lovelace.vesuvio.integration.essential.MechanicsState;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

/** Movement, environment and block-interaction state of one player: positions, air/ground ticks, per-check accumulators, velocity, teleport and blink tracking. Moved out of UserData unchanged; UserData delegates. */
final class KinematicState {

    // -------------------------------------------------------------
    // Movement Tracking States
    // -------------------------------------------------------------
    volatile double lastX = 0;

    volatile double lastY = 0;

    volatile double lastZ = 0;

    volatile double lastDeltaY = 0;

    volatile double lastDeltaXZ = 0;

    volatile boolean lastOnGround = true;

    volatile boolean hasLastPosition = false;

    volatile int airTicks = 0;

    volatile int groundTicks = 0;

    volatile int flyStreak = 0;

    volatile int speedStreak = 0;

    volatile int noFallStreak = 0;

    volatile int stepStreak = 0;

    volatile int invMoveStreak = 0;

    // Timer tracking
    volatile int timerPacketCount = 0;

    volatile long timerWindowStartNanos = System.nanoTime();

    // Knockback / external velocity grace period
    volatile long lastVelocityMillis = 0;

    public double getLastX() { return lastX; }

    public double getLastY() { return lastY; }

    public double getLastZ() { return lastZ; }

    public double getLastDeltaY() { return lastDeltaY; }

    public double getLastDeltaXZ() { return lastDeltaXZ; }

    public boolean isLastOnGround() { return lastOnGround; }

    public boolean hasLastPosition() { return hasLastPosition; }

    public void setLastPosition(double x, double y, double z, boolean onGround) {
        if (hasLastPosition) {
            this.lastDeltaY = y - this.lastY;
            double dx = x - this.lastX;
            double dz = z - this.lastZ;
            this.lastDeltaXZ = Math.sqrt(dx * dx + dz * dz);
        }
        this.lastX = x;
        this.lastY = y;
        this.lastZ = z;
        this.lastOnGround = onGround;
        this.hasLastPosition = true;
    }

    public int getAirTicks() { return airTicks; }

    public void incrementAirTicks() { this.airTicks++; this.groundTicks = 0; }

    public void resetAirTicks() { this.airTicks = 0; this.groundTicks++; }

    public int getGroundTicks() { return groundTicks; }

    public int getFlyStreak() { return flyStreak; }

    public void incrementFlyStreak() { this.flyStreak++; }

    public void decrementFlyStreak() { this.flyStreak = Math.max(0, this.flyStreak - 1); }

    public void resetFlyStreak() { this.flyStreak = 0; flyEvidence.reset(); }

    public int getSpeedStreak() { return speedStreak; }

    public void incrementSpeedStreak() { this.speedStreak++; }

    public void decrementSpeedStreak() { this.speedStreak = Math.max(0, this.speedStreak - 1); }

    public void resetSpeedStreak() { this.speedStreak = 0; }

    public int getNoFallStreak() { return noFallStreak; }

    public void incrementNoFallStreak() { this.noFallStreak++; }

    public void resetNoFallStreak() { this.noFallStreak = 0; }

    public int getStepStreak() { return stepStreak; }

    public void incrementStepStreak() { this.stepStreak++; }

    public void resetStepStreak() { this.stepStreak = 0; }

    public int getInvMoveStreak() { return invMoveStreak; }

    public void incrementInvMoveStreak() { this.invMoveStreak++; }

    public void resetInvMoveStreak() { this.invMoveStreak = 0; }

    public int getTimerPacketCount() { return timerPacketCount; }

    public void setTimerPacketCount(int count) { this.timerPacketCount = count; }

    public void incrementTimerPacketCount() { this.timerPacketCount++; }

    public long getTimerWindowStartNanos() { return timerWindowStartNanos; }

    public void setTimerWindowStartNanos(long nanos) { this.timerWindowStartNanos = nanos; }

    public long getLastVelocityMillis() { return lastVelocityMillis; }

    // A container (chest, furnace, ...) was closed. The close arrives as a packet and as an event, both before the server
    // snapshot of the next tick shows it, so the checks that read the snapshot consult this timestamp too.
    volatile long lastContainerClosedMillis = 0L;

    public void markContainerClosed() { this.lastContainerClosedMillis = System.currentTimeMillis(); }

    public long getLastContainerClosedMillis() { return lastContainerClosedMillis; }

    /** True when a close happened after {@code sinceMillis} (typically the snapshot capture time). */
    public boolean containerClosedAfter(long sinceMillis) { return lastContainerClosedMillis >= sinceMillis && lastContainerClosedMillis != 0L; }

    final DecayingEvidence stepEvidence = new DecayingEvidence();

    public DecayingEvidence getStepEvidence() { return stepEvidence; }

    final DecayingEvidence flyEvidence = new DecayingEvidence();

    public DecayingEvidence getFlyEvidence() { return flyEvidence; }

    public void recordVelocity() { this.lastVelocityMillis = System.currentTimeMillis(); }

    public boolean hasRecentVelocity() { return (System.currentTimeMillis() - lastVelocityMillis) < 1200L; }

    // Vertical velocity of the previous air tick, used by FlyCheck to detect gravity that
    // fails to accelerate the player downward (sustained-flight / hover engines).
    volatile double prevAirDeltaY = 0.0;

    public double getPrevAirDeltaY() { return prevAirDeltaY; }

    public void setPrevAirDeltaY(double value) { this.prevAirDeltaY = value; }

    // Consecutive movement ticks with sub-degree alignment between look yaw and travel direction,
    // tracked by check.statistical.BaritoneCheck.
    volatile int baritoneStreak = 0;

    public int getBaritoneStreak() { return baritoneStreak; }

    public void incrementBaritoneStreak() { this.baritoneStreak++; }

    public void resetBaritoneStreak() { this.baritoneStreak = 0; }

    // -------------------------------------------------------------
    // Main-thread environment snapshot (see engine.EnvironmentSnapshotService)
    //
    // Movement checks run on virtual threads and must never touch the Bukkit world themselves.
    // The snapshot service publishes an immutable view here once per tick; the checks read it.
    // -------------------------------------------------------------
    volatile net.lovelace.vesuvio.engine.EnvironmentSnapshot environment =
            net.lovelace.vesuvio.engine.EnvironmentSnapshot.EMPTY;

    public net.lovelace.vesuvio.engine.EnvironmentSnapshot getEnvironment() { return environment; }

    volatile boolean physicsObserved;

    final net.lovelace.vesuvio.engine.physics.ClientWorld clientWorld =
            new net.lovelace.vesuvio.engine.physics.ClientWorld();

    public net.lovelace.vesuvio.engine.physics.ClientWorld clientWorld() { return clientWorld; }

    volatile long physicsObservationEpoch;

    volatile net.lovelace.vesuvio.engine.physics.PhysicsFrame physicsFrame =
            net.lovelace.vesuvio.engine.physics.PhysicsFrame.unknown("OFF");

    final net.lovelace.vesuvio.engine.physics.PhysicsSession physicsSession =
            new net.lovelace.vesuvio.engine.physics.PhysicsSession();

    public boolean isPhysicsObserved() { return physicsObserved; }

    public long getPhysicsObservationEpoch() { return physicsObservationEpoch; }

    public synchronized void setPhysicsObserved(boolean enabled) {
        if (physicsObserved == enabled) return;
        physicsObserved = enabled;
        physicsObservationEpoch++;
        clientWorld.reset(enabled ? "WAIT_CHUNKS" : "OFF");
        physicsFrame = net.lovelace.vesuvio.engine.physics.PhysicsFrame.unknown(enabled ? "WARMUP" : "OFF");
        physicsSession.reset(enabled ? "WARMUP" : "OFF");
    }

    public net.lovelace.vesuvio.engine.physics.PhysicsSession physicsSession() { return physicsSession; }

    public net.lovelace.vesuvio.engine.physics.PhysicsFrame getPhysicsFrame() { return physicsFrame; }

    public void setPhysicsFrame(net.lovelace.vesuvio.engine.physics.PhysicsFrame frame) { physicsFrame = frame; }

    public void setEnvironment(net.lovelace.vesuvio.engine.EnvironmentSnapshot environment) {
        this.environment = environment;
    }

    // -------------------------------------------------------------
    // Timer balance (see check.movement.TimerCheck)
    //
    // Credit accumulated by movement packets against real elapsed time. Positive means the client
    // is sending its tick loop faster than wall-clock allows.
    // -------------------------------------------------------------
    volatile double timerBalanceMs = 0.0;

    volatile long timerLastPacketNanos = 0L;

    volatile int timerViolationStreak = 0;

    public double getTimerBalanceMs() { return timerBalanceMs; }

    public void setTimerBalanceMs(double v) { this.timerBalanceMs = v; }

    public long getTimerLastPacketNanos() { return timerLastPacketNanos; }

    public void setTimerLastPacketNanos(long v) { this.timerLastPacketNanos = v; }

    /** Smoothed real interval between movement packets, in ms. ~50 for a vanilla client. */
    volatile double timerIntervalEmaMs = -1.0;

    public double getTimerIntervalEmaMs() { return timerIntervalEmaMs; }

    public void setTimerIntervalEmaMs(double v) { this.timerIntervalEmaMs = v; }

    public int getTimerViolationStreak() { return timerViolationStreak; }

    public void incrementTimerViolationStreak() { this.timerViolationStreak++; }

    public void decrementTimerViolationStreak() { this.timerViolationStreak = Math.max(0, this.timerViolationStreak - 1); }

    public void resetTimerBalance() {
        this.timerBalanceMs = 0.0;
        this.timerLastPacketNanos = 0L;
        this.timerViolationStreak = 0;
        this.timerIntervalEmaMs = -1.0;
    }

    // -------------------------------------------------------------
    // Knockback / velocity tracking (see check.movement.VelocityCheck)
    //
    // The exact vector the server pushed onto the player, captured from the outgoing
    // EntityVelocity packet, plus the transaction sequence that proves the client received it.
    // Movement is only compared against the knockback once that sequence is acknowledged, so a
    // high-latency player is never judged on a push they had not yet been told about.
    // -------------------------------------------------------------
    volatile double pendingVelX = 0.0;

    volatile double pendingVelY = 0.0;

    volatile double pendingVelZ = 0.0;

    volatile long pendingVelSequence = -1L;

    volatile boolean velocityPending = false;

    volatile int velocityTicksTracked = 0;

    volatile double velocityObservedHorizontal = 0.0;

    // DecayingEvidence rather than a "3 in a row" streak - an anti-knockback that occasionally lets
    // one hit through honestly (randomized, or simply the player forgetting to hold the counter
    // key) used to reset a bare streak for free. See CLAUDE.md's standing rule on this.
    final DecayingEvidence velocityEvidence = new DecayingEvidence();

    public DecayingEvidence getVelocityEvidence() { return velocityEvidence; }

    /**
     * Whether the client has acknowledged the pending knockback yet. The tick counter is reset at
     * that moment so the measurement window is always the same length regardless of latency -
     * otherwise a high-ping player's window would be eaten by the time spent waiting for the ack,
     * and they would be scored on one or two ticks of movement instead of four.
     */
    volatile boolean velocityAckSeen = false;

    public boolean isVelocityAckSeen() { return velocityAckSeen; }

    public void markVelocityAcknowledged() {
        this.velocityAckSeen = true;
        this.velocityTicksTracked = 0;
        this.velocityObservedHorizontal = 0.0;
    }

    public synchronized void recordPendingVelocity(double x, double y, double z, long sequence) {
        this.pendingVelX = x;
        this.pendingVelY = y;
        this.pendingVelZ = z;
        this.pendingVelSequence = sequence;
        this.velocityPending = true;
        this.velocityAckSeen = false;
        this.velocityTicksTracked = 0;
        this.velocityObservedHorizontal = 0.0;
        this.lastVelocityMillis = System.currentTimeMillis();
    }

    public double getPendingVelX() { return pendingVelX; }

    public double getPendingVelY() { return pendingVelY; }

    public double getPendingVelZ() { return pendingVelZ; }

    public long getPendingVelSequence() { return pendingVelSequence; }

    public boolean isVelocityPending() { return velocityPending; }

    public void clearPendingVelocity() {
        this.velocityPending = false;
        this.velocityAckSeen = false;
        this.velocityTicksTracked = 0;
        this.velocityObservedHorizontal = 0.0;
    }

    public int getVelocityTicksTracked() { return velocityTicksTracked; }

    public void incrementVelocityTicksTracked() { this.velocityTicksTracked++; }

    public double getVelocityObservedHorizontal() { return velocityObservedHorizontal; }

    public void addVelocityObservedHorizontal(double d) { this.velocityObservedHorizontal += d; }

    // -------------------------------------------------------------
    // Most recent tick's horizontal movement vector, used by check.combat.MoveDirectionCheck to
    // compare where the player is actually travelling against where an attack claims to be aimed -
    // independent of, and a different signal from, KillauraAngleCheck's crosshair/FOV analysis.
    // -------------------------------------------------------------
    volatile double lastMoveDeltaX = 0.0;

    volatile double lastMoveDeltaZ = 0.0;

    public double getLastMoveDeltaX() { return lastMoveDeltaX; }

    public double getLastMoveDeltaZ() { return lastMoveDeltaZ; }

    public void setLastMoveDelta(double dx, double dz) { this.lastMoveDeltaX = dx; this.lastMoveDeltaZ = dz; }

    // -------------------------------------------------------------
    // Horizontal momentum, used by the prediction-based SpeedCheck to model friction instead of
    // comparing against a single flat speed cap.
    // -------------------------------------------------------------
    volatile double prevHorizontalSpeed = 0.0;

    volatile double speedPredictionDebt = 0.0;

    /**
     * Nanotime of the previous position packet, so the movement checks can tell a normal 50ms tick
     * from a multi-tick gap (idle player resuming, post-lag flush) whose delta must not be treated
     * as one tick of movement.
     */
    volatile long lastPositionNanos = 0L;

    public long getLastPositionNanos() { return lastPositionNanos; }

    public void setLastPositionNanos(long v) { this.lastPositionNanos = v; }

    // -------------------------------------------------------------
    // Phase / Clip (see check.movement.PhaseCheck)
    // -------------------------------------------------------------
    /**
     * Phase evidence: a time-decaying accumulator instead of a "N ticks in a row" counter (see DecayingEvidence), one flag per
     * episode (a cooldown after each flag), and a grace window after any block changed near the player.
     */
    final DecayingEvidence phaseEvidence = new DecayingEvidence();

    public DecayingEvidence getPhaseEvidence() { return phaseEvidence; }

    volatile long phaseCooldownUntilMillis = 0L;

    public long getPhaseCooldownUntilMillis() { return phaseCooldownUntilMillis; }

    public void setPhaseCooldownUntilMillis(long millis) { this.phaseCooldownUntilMillis = millis; }

    volatile long lastNearbyBlockChangeMillis = 0L;

    public long getLastNearbyBlockChangeMillis() { return lastNearbyBlockChangeMillis; }

    /** A block changed near this player (any cause, including a cancelled break): block data read before is stale. */
    public void markNearbyBlockChange(long nowMillis) { this.lastNearbyBlockChangeMillis = nowMillis; }

    /** InvMove evidence and the moment the open container was first seen (momentum right after opening is not a cheat). */
    final DecayingEvidence invMoveEvidence = new DecayingEvidence();

    public DecayingEvidence getInvMoveEvidence() { return invMoveEvidence; }

    volatile long containerOpenSinceMillis = 0L;

    public long getContainerOpenSinceMillis() { return containerOpenSinceMillis; }

    public void setContainerOpenSinceMillis(long millis) { this.containerOpenSinceMillis = millis; }

    volatile int phaseTicks = 0;

    // Phase "buried" state: the player found themselves inside a block without having walked into it (a block appeared on
    // them, a teleport, a reset of a mine), which is not phasing until they have left the block again.
    boolean phaseWasInside;

    boolean phaseBuried;

    boolean phaseHasPrevBlock;

    int phasePrevX, phasePrevY, phasePrevZ;

    /**
     * Records this tick's block cell and inside state and returns whether the player is "buried": entered a block without
     * changing block coordinates, right after a teleport, or inside the window of a nearby block change.
     */
    public synchronized boolean updatePhaseEntry(boolean inside, int bx, int by, int bz, boolean suspiciousEntry) {
        boolean sameCell = phaseHasPrevBlock && phasePrevX == bx && phasePrevY == by && phasePrevZ == bz;
        phaseHasPrevBlock = true;
        phasePrevX = bx;
        phasePrevY = by;
        phasePrevZ = bz;
        if (!inside) {
            phaseWasInside = false;
            phaseBuried = false;
            return false;
        }
        if (!phaseWasInside) {
            phaseWasInside = true;
            phaseBuried = sameCell || suspiciousEntry;
        }
        return phaseBuried;
    }

    public synchronized void resetPhaseEntry() {
        phaseWasInside = false;
        phaseBuried = false;
        phaseHasPrevBlock = false;
    }

    volatile double invMovePrevHorizontal = 0.0;

    public double getInvMovePrevHorizontal() { return invMovePrevHorizontal; }

    public void setInvMovePrevHorizontal(double v) { this.invMovePrevHorizontal = v; }

    public int getPhaseTicks() { return phaseTicks; }

    public void incrementPhaseTicks() { this.phaseTicks++; }

    public void decrementPhaseTicks() { this.phaseTicks = Math.max(0, this.phaseTicks - 1); }

    public void resetPhaseTicks() { this.phaseTicks = 0; }

    // -------------------------------------------------------------
    // Vehicle Clip / BoatClip (see listener.WorldInteractionListener#onVehicleMove) - the
    // vehicle-riding equivalent of Phase/Clip above.
    // -------------------------------------------------------------
    volatile int vehicleClipTicks = 0;

    // Block-state desync (2026-11-11): after a place or break the player's OWN client had cancelled by the server, the client still
    // holds a ghost block for a round trip. Whatever it does against that block in this window is not a cheat.
    volatile long lastOwnBlockDesyncMillis = 0L;

    public void markOwnBlockDesync() { this.lastOwnBlockDesyncMillis = System.currentTimeMillis(); }

    public long getLastOwnBlockDesyncMillis() { return lastOwnBlockDesyncMillis; }

    /** True within {@code rttMs + 500 ms} of the last own cancelled place/break (RTT unknown counts as 0). */
    public boolean inOwnBlockDesyncWindow(double rttMs) {
        long last = lastOwnBlockDesyncMillis;
        return last != 0L && System.currentTimeMillis() - last < Math.max(0.0, rttMs) + 500.0;
    }

    final DecayingEvidence airPlaceEvidence = new DecayingEvidence();

    final DecayingEvidence scaffoldEvidence = new DecayingEvidence();

    final DecayingEvidence ghostHandEvidence = new DecayingEvidence();

    final DecayingEvidence blockReachEvidence = new DecayingEvidence();

    final DecayingEvidence vehicleFlyEvidence = new DecayingEvidence();

    public DecayingEvidence getAirPlaceEvidence() { return airPlaceEvidence; }

    public DecayingEvidence getScaffoldEvidence() { return scaffoldEvidence; }

    public DecayingEvidence getGhostHandEvidence() { return ghostHandEvidence; }

    public DecayingEvidence getBlockReachEvidence() { return blockReachEvidence; }

    public DecayingEvidence getVehicleFlyEvidence() { return vehicleFlyEvidence; }

    double vehiclePrevDeltaY = Double.NaN;

    long vehiclePrevDeltaYMillis = 0L;

    /** Stores this vehicle tick's deltaY and returns the previous tick's (NaN when there is no recent one). */
    public synchronized double swapVehicleDeltaY(double deltaY, long nowMillis) {
        double previous = (nowMillis - vehiclePrevDeltaYMillis) <= 250L ? vehiclePrevDeltaY : Double.NaN;
        vehiclePrevDeltaY = deltaY;
        vehiclePrevDeltaYMillis = nowMillis;
        return previous;
    }

    public int getVehicleClipTicks() { return vehicleClipTicks; }

    public void incrementVehicleClipTicks() { this.vehicleClipTicks++; }

    public void decrementVehicleClipTicks() { this.vehicleClipTicks = Math.max(0, this.vehicleClipTicks - 1); }

    public void resetVehicleClipTicks() { this.vehicleClipTicks = 0; }

    // -------------------------------------------------------------
    // Elytra glide (see check.movement.ElytraCheck)
    //
    // A firework rocket's boost is not delivered as a server EntityVelocity packet the way
    // knockback is - it is simulated by both client and server applying the same per-tick impulse
    // over the rocket's flight duration - so it needs its own "recently boosted" signal rather than
    // reusing UserData#hasRecentVelocity(). The altitude window accumulates net Y change over a
    // few seconds: unboosted vanilla glide cannot sustain a net climb, so a sustained climb with no
    // recent boost is the check's primary signal. Speed debt is a secondary, generously-tolerant
    // catch-all for sustained excess 3D speed, mirroring SpeedCheck's debt model rather than a flat
    // cap, since a legitimate steep dive can reach a genuinely high, unbounded-looking speed.
    // -------------------------------------------------------------
    volatile long lastElytraBoostMillis = 0L;

    volatile long elytraWindowStartMillis = 0L;

    volatile double elytraWindowDeltaY = 0.0;

    volatile double elytraSpeedDebt = 0.0;

    volatile int elytraViolationStreak = 0;

    public void recordElytraBoost() { this.lastElytraBoostMillis = System.currentTimeMillis(); }

    public boolean hasRecentElytraBoost() { return (System.currentTimeMillis() - lastElytraBoostMillis) < 2000L; }

    public long getElytraWindowStartMillis() { return elytraWindowStartMillis; }

    public double getElytraWindowDeltaY() { return elytraWindowDeltaY; }

    public void resetElytraWindow(long nowMillis) {
        this.elytraWindowStartMillis = nowMillis;
        this.elytraWindowDeltaY = 0.0;
    }

    public void addElytraWindowDeltaY(double v) { this.elytraWindowDeltaY += v; }

    public double getElytraSpeedDebt() { return elytraSpeedDebt; }

    public void addElytraSpeedDebt(double v) { this.elytraSpeedDebt = Math.max(0.0, this.elytraSpeedDebt + v); }

    public void setElytraSpeedDebt(double v) { this.elytraSpeedDebt = v; }

    public int getElytraViolationStreak() { return elytraViolationStreak; }

    public void incrementElytraViolationStreak() { this.elytraViolationStreak++; }

    public void resetElytraViolationStreak() { this.elytraViolationStreak = 0; }

    // -------------------------------------------------------------
    // Blink / lag-switch (see check.movement.BlinkCheck)
    //
    // Tracks EVERY movement packet, position-carrying or not: a standing vanilla client sends the
    // position-less flying packet each tick and a full position packet only about once a second,
    // so measuring silence on positions alone would make every idle player look like a blink.
    // -------------------------------------------------------------
    volatile long lastAnyMovementNanos = 0L;

    volatile int blinkStreak = 0;

    public long getLastAnyMovementNanos() { return lastAnyMovementNanos; }

    public void setLastAnyMovementNanos(long v) { this.lastAnyMovementNanos = v; }

    /**
     * Timestamp of the last movement packet that actually carried a position, tracked separately
     * from {@link #lastAnyMovementNanos} purely for diagnostics (see BlinkCheck's "positionSilenceMs"
     * detail). Gating the silence detector itself on this alone was considered and rejected: a
     * player who simply stands still while panning the camera (looting, watching chat, aiming)
     * legitimately sends rotation-only packets for seconds at a time with zero position packets,
     * which would make ordinary play look identical to a blink candidate. Kept as a secondary,
     * informational signal instead.
     */
    volatile long lastPositionCarryingNanos = 0L;

    public long getLastPositionCarryingNanos() { return lastPositionCarryingNanos; }

    public void setLastPositionCarryingNanos(long v) { this.lastPositionCarryingNanos = v; }

    /**
     * Fixed-size ring of recent blink-candidate timestamps, used to compute a true sliding-window
     * occurrence count rather than a gap-chained streak. A gap-chained streak (increment while
     * consecutive gaps stay under the window, reset only on a gap that exceeds it) can be fooled by
     * occurrences spaced just under the window apart forever - the span between the first and last
     * would grow unbounded while never triggering the reset. Counting how many recorded timestamps
     * actually fall inside [now - window, now] is the correct semantics for "N times in M minutes".
     */
    final long[] blinkOccurrenceNanos = new long[16];

    int blinkOccurrenceHead = 0;

    int blinkOccurrenceFill = 0;

    public int getBlinkStreak() { return blinkStreak; }

    /**
     * Records a suspicious silence and returns the number counted inside the rolling window.
     */
    public synchronized int recordBlinkOccurrence(long nowNanos, long windowNanos) {
        blinkOccurrenceNanos[blinkOccurrenceHead] = nowNanos;
        blinkOccurrenceHead = (blinkOccurrenceHead + 1) % blinkOccurrenceNanos.length;
        if (blinkOccurrenceFill < blinkOccurrenceNanos.length) blinkOccurrenceFill++;

        int inWindow = 0;
        for (int i = 0; i < blinkOccurrenceFill; i++) {
            if (nowNanos - blinkOccurrenceNanos[i] <= windowNanos) inWindow++;
        }
        this.blinkStreak = inWindow;
        return inWindow;
    }

    // -------------------------------------------------------------
    // Blink "release burst" pending-confirmation bridge.
    //
    // BlinkCheck's main silence detector runs at packet-receipt time, before the position for that
    // same tick (if any) has been parsed - so it cannot itself look at the displacement that
    // resolves the silence. Instead it stashes a candidate here, and CheckPipeline.processMovement
    // calls BlinkCheck#checkReleaseBurst once the tick's real deltaX/Y/Z are known, which can
    // upgrade a not-yet-streak-confirmed candidate straight to a flag if the position that broke
    // the silence is an unexplained catch-up jump - exactly the signature a lag-switch produces
    // when the client flushes its queued movement in one packet.
    // -------------------------------------------------------------
    volatile long pendingBlinkNanos = 0L;

    volatile double pendingBlinkSilenceMs = 0.0;

    volatile double pendingBlinkRttMs = 0.0;

    volatile boolean pendingBlinkShort = false;

    volatile int pendingBlinkOccurrences = 0;

    public boolean hasPendingBlink() { return pendingBlinkNanos != 0L; }

    public long getPendingBlinkNanos() { return pendingBlinkNanos; }

    public double getPendingBlinkSilenceMs() { return pendingBlinkSilenceMs; }

    public double getPendingBlinkRttMs() { return pendingBlinkRttMs; }

    public boolean isPendingBlinkShort() { return pendingBlinkShort; }

    public int getPendingBlinkOccurrences() { return pendingBlinkOccurrences; }

    public void setPendingBlink(long nowNanos, double silenceMs, double rttMs, boolean isShort, int occurrences) {
        this.pendingBlinkNanos = nowNanos;
        this.pendingBlinkSilenceMs = silenceMs;
        this.pendingBlinkRttMs = rttMs;
        this.pendingBlinkShort = isShort;
        this.pendingBlinkOccurrences = occurrences;
    }

    public void clearPendingBlink() { this.pendingBlinkNanos = 0L; }

    // -------------------------------------------------------------
    // Blink "flush burst" counter.
    //
    // A vanilla client that was frozen does NOT replay the movement it missed: it resumes from
    // its current position, and its catch-up is bounded by the client's own tick-per-frame clamp
    // (10 ticks), so at most a handful of packets arrive back-to-back. A blink module that
    // buffers packets flushes the whole queue at once on release - tens of movement packets in a
    // single netty read. Counting packets in a short window right after a silence separates those
    // two directly, without any reference to displacement.
    // -------------------------------------------------------------
    volatile long blinkFlushWindowStartNanos = 0L;

    volatile int blinkFlushPackets = 0;

    public boolean hasBlinkFlushWindow() { return blinkFlushWindowStartNanos != 0L; }

    public long getBlinkFlushWindowStartNanos() { return blinkFlushWindowStartNanos; }

    public synchronized void startBlinkFlushWindow(long nowNanos) {
        this.blinkFlushWindowStartNanos = nowNanos;
        this.blinkFlushPackets = 0;
    }

    public synchronized int incrementBlinkFlushPackets() { return ++this.blinkFlushPackets; }

    /**
     * Packet-accounting state for BlinkCheck: how many movement packets have arrived since the
     * client last answered a transaction, and which answer that was. See BlinkCheck#checkBurstBudget.
     */
    long blinkAckSequence = -1L;

    int blinkPacketsSinceAck = 0;

    /**
     * Records a movement packet against the current transaction-ack bucket and returns how many
     * have now arrived in it. A new ack opens a new bucket.
     */
    public synchronized int recordMovementAgainstAck(long ackSequence) {
        if (ackSequence != blinkAckSequence) {
            blinkAckSequence = ackSequence;
            blinkPacketsSinceAck = 1;
        } else {
            blinkPacketsSinceAck++;
        }
        return blinkPacketsSinceAck;
    }

    /** Drops the current bucket so one release cannot be reported twice. */
    public synchronized void resetMovementAckBucket() {
        blinkAckSequence = -1L;
        blinkPacketsSinceAck = 0;
    }

    public synchronized void clearBlinkFlushWindow() {
        this.blinkFlushWindowStartNanos = 0L;
        this.blinkFlushPackets = 0;
    }

    // Wall-clock time of the last movement packet exempted for being a huge (teleport-sized)
    // displacement, so BlinkCheck can tell "just teleported" apart from "silence just broke with a
    // huge catch-up jump" - both look identical at the position-delta level.
    volatile long lastTeleportMillis = 0L;

    /**
     * Forgets everything the movement checks carry from one packet to the next (air time, Fly/Speed accumulators, the predicted
     * motion vector, a pending knockback, Phase ticks). Used where the next delta cannot be trusted: invulnerability, a game
     * mode change, a teleport-sized jump. One method instead of the same eight lines copied into each branch, where one copy
     * always missed whatever state was added last.
     */
    public void resetKinematicState() {
        resetAirTicks();
        resetFlyStreak();
        resetSpeedStreak();
        setPrevHorizontalSpeed(0.0);
        setSpeedPredictionDebt(0.0);
        clearPredictedVector();
        clearPendingVelocity();
        resetPhaseTicks();
        resetPhaseEntry();
        stepEvidence.reset();
    }

    volatile long movementResetNanos;

    public long getMovementResetNanos() { return movementResetNanos; }

    volatile boolean awaitingTeleport;

    volatile int expectedTeleportId;

    volatile long teleportSentNanos;

    public synchronized void expectTeleport(int id) {
        teleportHook.run();
        expectedTeleportId = id;
        teleportSentNanos = System.nanoTime();
        awaitingTeleport = true;
    }

    public synchronized boolean acknowledgeTeleport(int id) {
        if (!awaitingTeleport || id != expectedTeleportId) return false;
        awaitingTeleport = false;
        teleportHook.run();
        return true;
    }

    public boolean hasRecentTeleport() {
        return (awaitingTeleport && System.nanoTime() - teleportSentNanos < 10_000_000_000L)
                || (System.currentTimeMillis() - lastTeleportMillis) < 3000L;
    }

    // Wall-clock time of the last game-mode change. A game mode is read from the once-per-tick
    // EnvironmentSnapshot, so for up to one tick after the change the movement checks are still
    // judging a creative/spectator flyer against the survival movement model - which is exactly
    // how staff entering /vesuvio spectate flagged themselves for Speed. The change itself is an
    // event, so it is recorded here the instant it happens and the movement checks skip the
    // handful of packets that fall inside the gap. Unlike the teleport grace this cannot be
    // triggered on demand (an ender pearl is not a game-mode change), so it opens no window a
    // cheat could stand in.
    volatile long lastGameModeChangeMillis = 0L;

    public void recordGameModeChange() { this.lastGameModeChangeMillis = System.currentTimeMillis(); }

    public boolean hasRecentGameModeChange() {
        return (System.currentTimeMillis() - lastGameModeChangeMillis) < 1200L;
    }

    // ---------------------------------------------------------------------------------------
    // Last state the client actually reported, used by BlinkCheck to tell "the vanilla client
    // had nothing to send" apart from "the client withheld what it had".
    //
    // A vanilla client does not send a movement packet every tick. LocalPlayer#sendPosition only
    // sends when the position moved past its own epsilon, the rotation changed, or the onGround
    // flag flipped - otherwise it sends nothing at all, except one forced position packet every
    // 20 ticks (the "position reminder"). A player standing perfectly still therefore produces
    // ONE movement packet per ~1000ms as its normal, correct output, which lands squarely inside
    // the silence bands Blink judges. That is not a detectable event, it is the protocol.
    // ---------------------------------------------------------------------------------------
    double reportedX, reportedY, reportedZ;

    float reportedYaw, reportedPitch;

    boolean reportedOnGround;

    boolean hasReportedState;

    boolean hasReportedPosition;

    /** Client's own send epsilon is 2.0E-4 per axis; a little headroom for float round-tripping. */
    static final double REPORTED_POSITION_EPSILON = 0.002;

    /**
     * Records what this movement packet reported and answers whether anything in it differs from
     * what the client last reported - i.e. whether the client had something to send at all.
     * Only the fields the packet actually carries are compared and stored, so a look-only or
     * status-only packet does not clobber the known position.
     *
     * @return true if this packet carries a state the client had not already reported
     */
    public synchronized boolean noteReportedClientState(boolean hasPosition, double x, double y, double z,
                                                        boolean hasRotation, float yaw, float pitch,
                                                        boolean onGround) {
        boolean changed = false;

        if (!hasReportedState) {
            hasReportedState = true;
            changed = true;
        }

        if (hasPosition) {
            hasReportedPosition = true;
            if (Math.abs(x - reportedX) > REPORTED_POSITION_EPSILON
                    || Math.abs(y - reportedY) > REPORTED_POSITION_EPSILON
                    || Math.abs(z - reportedZ) > REPORTED_POSITION_EPSILON) {
                changed = true;
            }
            reportedX = x;
            reportedY = y;
            reportedZ = z;
        }

        if (hasRotation) {
            if (yaw != reportedYaw || pitch != reportedPitch) {
                changed = true;
            }
            reportedYaw = yaw;
            reportedPitch = pitch;
        }

        if (onGround != reportedOnGround) {
            changed = true;
            reportedOnGround = onGround;
        }

        return changed;
    }

    volatile double predictedPreviousX, predictedPreviousZ;

    volatile boolean hasPredictedVector;

    public double getPredictedPreviousX() { return predictedPreviousX; }

    public double getPredictedPreviousZ() { return predictedPreviousZ; }

    public boolean hasPredictedVector() { return hasPredictedVector; }

    public void setPredictedVector(double x, double z) {
        predictedPreviousX = x; predictedPreviousZ = z; hasPredictedVector = true;
    }

    public void clearPredictedVector() { hasPredictedVector = false; }

    public double getPrevHorizontalSpeed() { return prevHorizontalSpeed; }

    public void setPrevHorizontalSpeed(double v) { this.prevHorizontalSpeed = v; }

    /**
     * Ticks since the player left the ground, as seen by the speed check specifically. The
     * sprint-jump impulse is a one-off at the jump, not a per-air-tick bonus, so the model needs
     * to know which airborne tick this is - granting the impulse every tick would raise the
     * airborne prediction ceiling to several blocks per tick and make the check blind in the air.
     */
    volatile int speedAirborneTicks = 0;

    volatile boolean speedPrevOnGround = true;

    public int getSpeedAirborneTicks() { return speedAirborneTicks; }

    public boolean isSpeedPrevOnGround() { return speedPrevOnGround; }

    public void updateSpeedGroundState(boolean onGround) {
        if (onGround) {
            this.speedAirborneTicks = 0;
        } else {
            this.speedAirborneTicks++;
        }
        this.speedPrevOnGround = onGround;
    }

    // Ground flags of the two previous movement packets, as seen by SpeedCheck. Vanilla applies this tick's acceleration by the
    // state at the START of the tick (= flag of packet t-1) but friction to the previous tick's displacement by the state at
    // the start of THAT tick (= flag of packet t-2). Using the current packet's flag for both mis-judges the jump and landing ticks.
    volatile boolean speedGround1 = true;

    volatile boolean speedGround2 = true;

    public boolean isSpeedGround1() { return speedGround1; }

    public boolean isSpeedGround2() { return speedGround2; }

    public synchronized void shiftSpeedGround(boolean onGroundNow) {
        speedGround2 = speedGround1;
        speedGround1 = onGroundNow;
    }

    // Slipperiness memory: friction uses the highest value seen in the last second (ice just left still carries momentum),
    // acceleration the lowest seen in the last 150 ms (a bigger acceleration is the more permissive bound).
    double slipMax = 0.0;

    long slipMaxMillis = 0L;

    double slipMin = 1.0;

    long slipMinMillis = 0L;

    public synchronized double slipperyMax(double current, long nowMillis) {
        if (nowMillis - slipMaxMillis > 1000L || current >= slipMax) {
            slipMax = current;
            slipMaxMillis = nowMillis;
        }
        return slipMax;
    }

    public synchronized double slipperyMin(double current, long nowMillis) {
        if (nowMillis - slipMinMillis > 150L || current <= slipMin) {
            slipMin = current;
            slipMinMillis = nowMillis;
        }
        return slipMin;
    }

    public double getSpeedPredictionDebt() { return speedPredictionDebt; }

    public void setSpeedPredictionDebt(double v) { this.speedPredictionDebt = v; }

    public void addSpeedPredictionDebt(double v) { this.speedPredictionDebt = Math.max(0.0, this.speedPredictionDebt + v); }

    /** Reset kinematic history, never the player's accumulated violations. */
    synchronized void resetMovementTracking() {
        movementResetNanos = System.nanoTime();
        physicsFrame = net.lovelace.vesuvio.engine.physics.PhysicsFrame.unknown("MOVEMENT_RESET");
        physicsSession.reset("MOVEMENT_RESET");
        hasLastPosition = false;
        hasReportedState = false;
        hasReportedPosition = false;
        lastDeltaY = 0;
        lastDeltaXZ = 0;
        airTicks = 0;
        groundTicks = 0;
        lastPositionNanos = 0;
        lastAnyMovementNanos = 0;
        lastPositionCarryingNanos = 0;
        resetFlyStreak();
        resetSpeedStreak();
        resetNoFallStreak();
        resetStepStreak(); stepEvidence.reset();
        resetInvMoveStreak();
        resetPhaseTicks();
        resetPhaseEntry();
        phaseEvidence.reset();
        invMoveEvidence.reset();
        resetTimerBalance();
        resetMovementAckBucket();
        clearBlinkFlushWindow();
        clearPendingBlink();
        clearPendingVelocity();
        setPrevAirDeltaY(0);
        setPrevHorizontalSpeed(0);
        clearPredictedVector();
        speedAirborneTicks = 0;
        speedPrevOnGround = true;
        setSpeedPredictionDebt(0);
    }
    /** Set by UserData: a teleport also invalidates combat state, which this class does not own. */
    volatile Runnable teleportHook = () -> { };

    void markTeleport() {
        this.lastTeleportMillis = System.currentTimeMillis();
    }
}
