package net.lovelace.vesuvio.data;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Encapsulates all real-time anti-cheat metrics, ring buffers, and behavioral states
 * for a single player. Completely thread-safe.
 *
 * Author: Lovelace
 */
public final class UserData {

    private final UUID uuid;
    private volatile String username;

    private final ClickRingBuffer clickBuffer = new ClickRingBuffer();
    private final AimRingBuffer aimBuffer = new AimRingBuffer();

    // Violation Level (VL)
    private double vl = 0.0;
    private final AtomicLong lastViolationTime = new AtomicLong(System.currentTimeMillis());

    // Trust (0 - 100) & Risk (0 - 100). Not volatile: all reads/writes go through synchronized
    // accessors below so concurrent adjustTrust/adjustRisk calls (many can fire per tick across
    // click/aim/movement checks running on different virtual threads) never lose an update.
    private double trustScore = 50.0;
    private double riskIndex = 10.0;

    // Client Brand (e.g. vanilla, fabric, lunar, or cheat signature)
    private volatile String clientBrand = "unknown";

    // Playstyle Fingerprinting: baseline signature established across trusted sessions
    private volatile long[] baselineSignature = null;
    private volatile int signatureMatches = 0;

    // Temporal Consistency tracking (early combat 0-3s vs late 8-15s)
    private volatile long combatStartMillis = 0;
    private volatile long lastCombatActionMillis = 0;
    private volatile float earlyCombatVariance = -1f;
    private volatile float earlyCombatMean = -1f;

    // Live display metrics
    private volatile double lastCalculatedCPS = 0.0;
    private volatile double lastMLProbability = 0.0;
    private volatile String lastTriggeredCheck = "None";

    // ML inference throttle
    private final AtomicLong lastMLCheckTime = new AtomicLong(0);

    public UserData(UUID uuid, String username) {
        this.uuid = uuid;
        this.username = username;
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public ClickRingBuffer getClickBuffer() {
        return clickBuffer;
    }

    public AimRingBuffer getAimBuffer() {
        return aimBuffer;
    }

    public synchronized double getVl() {
        return vl;
    }

    public synchronized void addVl(double amount) {
        this.vl = Math.max(0.0, this.vl + amount);
        this.lastViolationTime.set(System.currentTimeMillis());
    }

    public synchronized void resetVl() {
        this.vl = 0.0;
    }

    public synchronized void decayVl(double amount) {
        this.vl = Math.max(0.0, this.vl - amount);
    }

    public long getLastViolationTime() {
        return lastViolationTime.get();
    }

    public synchronized double getTrustScore() {
        return trustScore;
    }

    public synchronized void setTrustScore(double trustScore) {
        this.trustScore = Math.max(0.0, Math.min(100.0, trustScore));
    }

    public synchronized void adjustTrust(double delta) {
        this.trustScore = Math.max(0.0, Math.min(100.0, this.trustScore + delta));
    }

    public synchronized double getRiskIndex() {
        return riskIndex;
    }

    public synchronized void setRiskIndex(double riskIndex) {
        this.riskIndex = Math.max(0.0, Math.min(100.0, riskIndex));
    }

    public synchronized void adjustRisk(double delta) {
        this.riskIndex = Math.max(0.0, Math.min(100.0, this.riskIndex + delta));
    }

    // Manual Suspect flag (persists across reboots and reconnects)
    private volatile boolean manualSuspect = false;

    public boolean isManualSuspect() {
        return manualSuspect;
    }

    public void setManualSuspect(boolean manualSuspect) {
        this.manualSuspect = manualSuspect;
    }

    public boolean isSuspect(double highRiskThreshold) {
        return manualSuspect || getRiskIndex() >= highRiskThreshold || getVl() >= 15.0;
    }

    /**
     * Dynamic sensitivity multiplier.
     * Baseline (fresh account: trust=50, risk=10) resolves to ~1.18 - moderately harsh, never the
     * lenient end of the range - so that out-of-the-box detection works from a player's very
     * first session instead of giving brand-new (and therefore unproven) accounts the benefit
     * of the doubt.
     * Low trust / high risk push the multiplier above 1.0 (harsher thresholds).
     * High trust / low risk (earned over time) push it below 1.0 (more lenient, fewer false positives).
     *
     * Callers must apply this consistently: for "flag if metric < threshold" checks, multiply the
     * threshold by this value (higher sensitivity -> larger allowed threshold -> easier to flag).
     * For "flag if metric > threshold" checks, divide the threshold by this value (higher sensitivity
     * -> smaller required threshold -> easier to flag).
     */
    public double getSensitivityMultiplier() {
        double trust = getTrustScore();
        double risk = getRiskIndex();
        double trustFactor = (60.0 - trust) / 60.0; // trust=50 -> +0.17 (slightly harsh), trust=100 -> -0.67 (lenient), trust=0 -> +1.0
        double riskFactor = risk / 100.0;            // risk=10 -> +0.10, risk=100 -> +1.0, risk=0 -> 0.0
        double sensitivity = 1.0 + (trustFactor * 0.6) + (riskFactor * 0.8);
        return Math.max(0.6, Math.min(2.5, sensitivity));
    }

    public String getClientBrand() {
        return clientBrand;
    }

    public void setClientBrand(String clientBrand) {
        this.clientBrand = clientBrand != null ? clientBrand : "vanilla";
    }

    public long[] getBaselineSignature() {
        return baselineSignature;
    }

    public void setBaselineSignature(long[] signature) {
        this.baselineSignature = signature;
    }

    public int getSignatureMatches() {
        return signatureMatches;
    }

    public void incrementSignatureMatches() {
        this.signatureMatches++;
    }

    // Combat lifecycle
    public void recordCombatAction() {
        long now = System.currentTimeMillis();
        if (now - lastCombatActionMillis > 10_000) {
            // New combat session started
            combatStartMillis = now;
            earlyCombatVariance = -1f;
            earlyCombatMean = -1f;
        }
        lastCombatActionMillis = now;
    }

    public long getCombatDurationMillis() {
        if (combatStartMillis == 0) return 0;
        return System.currentTimeMillis() - combatStartMillis;
    }

    public float getEarlyCombatVariance() {
        return earlyCombatVariance;
    }

    public void setEarlyCombatVariance(float earlyCombatVariance) {
        this.earlyCombatVariance = earlyCombatVariance;
    }

    public float getEarlyCombatMean() {
        return earlyCombatMean;
    }

    public void setEarlyCombatMean(float earlyCombatMean) {
        this.earlyCombatMean = earlyCombatMean;
    }

    public boolean shouldRunML() {
        long now = System.currentTimeMillis();
        // Throttle ML evaluations to at most 1 every 250ms per player to conserve CPU
        return (now - lastMLCheckTime.get()) > 250;
    }

    public void markMLRun() {
        lastMLCheckTime.set(System.currentTimeMillis());
    }

    public double getLastCalculatedCPS() {
        return lastCalculatedCPS;
    }

    public void setLastCalculatedCPS(double lastCalculatedCPS) {
        this.lastCalculatedCPS = lastCalculatedCPS;
    }

    public double getLastMLProbability() {
        return lastMLProbability;
    }

    public void setLastMLProbability(double lastMLProbability) {
        this.lastMLProbability = lastMLProbability;
    }

    private volatile double lastSelfLearnProbability = 0.0;
    public double getLastSelfLearnProbability() { return lastSelfLearnProbability; }
    public void setLastSelfLearnProbability(double v) { this.lastSelfLearnProbability = v; }

    private volatile double lastAimSelfLearnProbability = 0.0;
    public double getLastAimSelfLearnProbability() { return lastAimSelfLearnProbability; }
    public void setLastAimSelfLearnProbability(double v) { this.lastAimSelfLearnProbability = v; }

    // Ban-evasion: whether the one-time playstyle-signature-vs-banlist check has run this
    // session yet (see BanEvasionManager / CheckPipeline#processClick).
    private volatile boolean altCheckDone = false;
    public boolean isAltCheckDone() { return altCheckDone; }
    public void setAltCheckDone(boolean v) { this.altCheckDone = v; }

    public String getLastTriggeredCheck() {
        return lastTriggeredCheck;
    }

    public void setLastTriggeredCheck(String lastTriggeredCheck) {
        this.lastTriggeredCheck = lastTriggeredCheck;
    }

    // Live debug metrics (/vesuvio debug) - populated by StatisticalClickCheck on every evaluation
    private volatile double lastStdDevMs = 0.0;
    private volatile double lastDupRatio = 0.0;
    private volatile double lastEntropy = 0.0;

    public double getLastStdDevMs() { return lastStdDevMs; }
    public void setLastStdDevMs(double v) { this.lastStdDevMs = v; }
    public double getLastDupRatio() { return lastDupRatio; }
    public void setLastDupRatio(double v) { this.lastDupRatio = v; }
    public double getLastEntropy() { return lastEntropy; }
    public void setLastEntropy(double v) { this.lastEntropy = v; }

    // Swing and Rotation Tracking for BadPackets & GCD
    private volatile long lastSwingNanos = 0L;
    private volatile float lastYaw = 0f;
    private volatile float lastPitch = 0f;
    // Vanilla clients can send the very first attack's Interact packet before its Animation
    // (swing) packet within the same client tick, so lastSwingNanos==0 on a player's first-ever
    // attack this session isn't evidence of a NoSwing cheat - just no baseline yet. Exempts
    // exactly that one attack from BadPacketsCheck.checkNoSwing(); every attack after it is
    // judged normally, so a client that genuinely never swings is still caught starting there.
    private volatile boolean firstAttackSeen = false;

    public long getLastSwingNanos() {
        return lastSwingNanos;
    }

    public void setLastSwingNanos(long lastSwingNanos) {
        this.lastSwingNanos = lastSwingNanos;
    }

    public boolean isFirstAttackSeen() {
        return firstAttackSeen;
    }

    public void setFirstAttackSeen(boolean firstAttackSeen) {
        this.firstAttackSeen = firstAttackSeen;
    }

    public float getLastYaw() {
        return lastYaw;
    }

    public void setLastYaw(float lastYaw) {
        this.lastYaw = lastYaw;
    }

    public float getLastPitch() {
        return lastPitch;
    }

    public void setLastPitch(float lastPitch) {
        this.lastPitch = lastPitch;
    }

    // Rotation initialization and GCD Streak
    private volatile boolean initialRotation = false;
    private volatile int gcdSuspiciousStreak = 0;

    public boolean hasInitialRotation() {
        return initialRotation;
    }

    public void setInitialRotation(boolean initialRotation) {
        this.initialRotation = initialRotation;
    }

    public int getGcdSuspiciousStreak() {
        return gcdSuspiciousStreak;
    }

    public void incrementGcdStreak() {
        this.gcdSuspiciousStreak++;
    }

    public void decrementGcdStreak() {
        this.gcdSuspiciousStreak = Math.max(0, this.gcdSuspiciousStreak - 2);
    }

    public void resetGcdStreak() {
        this.gcdSuspiciousStreak = 0;
    }

    public boolean isInCombat() {
        return (System.currentTimeMillis() - lastCombatActionMillis) < 4000L;
    }

    // -------------------------------------------------------------
    // Movement Tracking States
    // -------------------------------------------------------------
    private volatile double lastX = 0;
    private volatile double lastY = 0;
    private volatile double lastZ = 0;
    private volatile double lastDeltaY = 0;
    private volatile double lastDeltaXZ = 0;
    private volatile boolean lastOnGround = true;
    private volatile boolean hasLastPosition = false;

    private volatile int airTicks = 0;
    private volatile int groundTicks = 0;

    private volatile int flyStreak = 0;
    private volatile int speedStreak = 0;
    private volatile int noFallStreak = 0;
    private volatile int stepStreak = 0;
    private volatile int invMoveStreak = 0;

    // Timer tracking
    private volatile int timerPacketCount = 0;
    private volatile long timerWindowStartNanos = System.nanoTime();

    // Knockback / external velocity grace period
    private volatile long lastVelocityMillis = 0;

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
    public void resetFlyStreak() { this.flyStreak = 0; }

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
    public void recordVelocity() { this.lastVelocityMillis = System.currentTimeMillis(); }
    public boolean hasRecentVelocity() { return (System.currentTimeMillis() - lastVelocityMillis) < 1200L; }

    // Vertical velocity of the previous air tick, used by FlyCheck to detect gravity that
    // fails to accelerate the player downward (sustained-flight / hover engines).
    private volatile double prevAirDeltaY = 0.0;
    public double getPrevAirDeltaY() { return prevAirDeltaY; }
    public void setPrevAirDeltaY(double value) { this.prevAirDeltaY = value; }

    // Consecutive near-perfect (sub-degree) aim-lock hits during combat, tracked by KillauraAngleCheck.
    private volatile int perfectAimStreak = 0;
    public int getPerfectAimStreak() { return perfectAimStreak; }
    public void incrementPerfectAimStreak() { this.perfectAimStreak++; }
    public void resetPerfectAimStreak() { this.perfectAimStreak = 0; }

    // Consecutive hits whose required hitbox rewind exceeded what the attacker's real transaction
    // RTT could explain, tracked by check.combat.BackTrackCheck.
    private volatile int backTrackStreak = 0;
    public int getBackTrackStreak() { return backTrackStreak; }
    public void incrementBackTrackStreak() { this.backTrackStreak++; }
    public void resetBackTrackStreak() { this.backTrackStreak = 0; }

    // Consecutive hits landing significantly off the attacker's own sprint/movement direction
    // while sprint is maintained, tracked by check.combat.MoveDirectionCheck.
    private volatile int moveDirectionStreak = 0;
    public int getMoveDirectionStreak() { return moveDirectionStreak; }
    public void incrementMoveDirectionStreak() { this.moveDirectionStreak++; }
    public void resetMoveDirectionStreak() { this.moveDirectionStreak = 0; }

    // -------------------------------------------------------------
    // Main-thread environment snapshot (see engine.EnvironmentSnapshotService)
    //
    // Movement checks run on virtual threads and must never touch the Bukkit world themselves.
    // The snapshot service publishes an immutable view here once per tick; the checks read it.
    // -------------------------------------------------------------
    private volatile net.lovelace.vesuvio.engine.EnvironmentSnapshot environment =
            net.lovelace.vesuvio.engine.EnvironmentSnapshot.EMPTY;

    public net.lovelace.vesuvio.engine.EnvironmentSnapshot getEnvironment() { return environment; }

    public void setEnvironment(net.lovelace.vesuvio.engine.EnvironmentSnapshot environment) {
        this.environment = environment;
    }

    // -------------------------------------------------------------
    // Timer balance (see check.movement.TimerCheck)
    //
    // Credit accumulated by movement packets against real elapsed time. Positive means the client
    // is sending its tick loop faster than wall-clock allows.
    // -------------------------------------------------------------
    private volatile double timerBalanceMs = 0.0;
    private volatile long timerLastPacketNanos = 0L;
    private volatile int timerViolationStreak = 0;

    public double getTimerBalanceMs() { return timerBalanceMs; }
    public void setTimerBalanceMs(double v) { this.timerBalanceMs = v; }
    public long getTimerLastPacketNanos() { return timerLastPacketNanos; }
    public void setTimerLastPacketNanos(long v) { this.timerLastPacketNanos = v; }
    /** Smoothed real interval between movement packets, in ms. ~50 for a vanilla client. */
    private volatile double timerIntervalEmaMs = -1.0;
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
    private volatile double pendingVelX = 0.0;
    private volatile double pendingVelY = 0.0;
    private volatile double pendingVelZ = 0.0;
    private volatile long pendingVelSequence = -1L;
    private volatile boolean velocityPending = false;
    private volatile int velocityTicksTracked = 0;
    private volatile double velocityObservedHorizontal = 0.0;
    private volatile int velocityViolationStreak = 0;
    /**
     * Whether the client has acknowledged the pending knockback yet. The tick counter is reset at
     * that moment so the measurement window is always the same length regardless of latency -
     * otherwise a high-ping player's window would be eaten by the time spent waiting for the ack,
     * and they would be scored on one or two ticks of movement instead of four.
     */
    private volatile boolean velocityAckSeen = false;

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
    public int getVelocityViolationStreak() { return velocityViolationStreak; }
    public void incrementVelocityViolationStreak() { this.velocityViolationStreak++; }
    public void decrementVelocityViolationStreak() { this.velocityViolationStreak = Math.max(0, this.velocityViolationStreak - 1); }

    // -------------------------------------------------------------
    // Most recent tick's horizontal movement vector, used by check.combat.MoveDirectionCheck to
    // compare where the player is actually travelling against where an attack claims to be aimed -
    // independent of, and a different signal from, KillauraAngleCheck's crosshair/FOV analysis.
    // -------------------------------------------------------------
    private volatile double lastMoveDeltaX = 0.0;
    private volatile double lastMoveDeltaZ = 0.0;
    public double getLastMoveDeltaX() { return lastMoveDeltaX; }
    public double getLastMoveDeltaZ() { return lastMoveDeltaZ; }
    public void setLastMoveDelta(double dx, double dz) { this.lastMoveDeltaX = dx; this.lastMoveDeltaZ = dz; }

    // -------------------------------------------------------------
    // Horizontal momentum, used by the prediction-based SpeedCheck to model friction instead of
    // comparing against a single flat speed cap.
    // -------------------------------------------------------------
    private volatile double prevHorizontalSpeed = 0.0;
    private volatile double speedPredictionDebt = 0.0;

    /**
     * Nanotime of the previous position packet, so the movement checks can tell a normal 50ms tick
     * from a multi-tick gap (idle player resuming, post-lag flush) whose delta must not be treated
     * as one tick of movement.
     */
    private volatile long lastPositionNanos = 0L;
    public long getLastPositionNanos() { return lastPositionNanos; }
    public void setLastPositionNanos(long v) { this.lastPositionNanos = v; }

    // -------------------------------------------------------------
    // Phase / Clip (see check.movement.PhaseCheck)
    // -------------------------------------------------------------
    private volatile int phaseTicks = 0;
    public int getPhaseTicks() { return phaseTicks; }
    public void incrementPhaseTicks() { this.phaseTicks++; }
    public void decrementPhaseTicks() { this.phaseTicks = Math.max(0, this.phaseTicks - 1); }
    public void resetPhaseTicks() { this.phaseTicks = 0; }

    // -------------------------------------------------------------
    // Vehicle Clip / BoatClip (see listener.WorldInteractionListener#onVehicleMove) - the
    // vehicle-riding equivalent of Phase/Clip above.
    // -------------------------------------------------------------
    private volatile int vehicleClipTicks = 0;
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
    private volatile long lastElytraBoostMillis = 0L;
    private volatile long elytraWindowStartMillis = 0L;
    private volatile double elytraWindowDeltaY = 0.0;
    private volatile double elytraSpeedDebt = 0.0;
    private volatile int elytraViolationStreak = 0;

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
    private volatile long lastAnyMovementNanos = 0L;
    private volatile int blinkStreak = 0;

    public long getLastAnyMovementNanos() { return lastAnyMovementNanos; }
    public void setLastAnyMovementNanos(long v) { this.lastAnyMovementNanos = v; }
    private volatile long lastBlinkNanos = 0L;

    public int getBlinkStreak() { return blinkStreak; }

    /**
     * Records a suspicious silence and returns the number counted inside the rolling window.
     *
     * <p>A window, not a per-packet streak: blinks are separated by ordinary play, so decaying the
     * count on every normal packet - as the first cut of this did - would reset it between every
     * pair of blinks and the threshold could never be reached at all. Occurrences that fall
     * outside the window start the count over instead.
     */
    public synchronized int recordBlinkOccurrence(long nowNanos, long windowNanos) {
        if (lastBlinkNanos != 0L && (nowNanos - lastBlinkNanos) > windowNanos) {
            this.blinkStreak = 0;
        }
        this.lastBlinkNanos = nowNanos;
        return ++this.blinkStreak;
    }


    public double getPrevHorizontalSpeed() { return prevHorizontalSpeed; }
    public void setPrevHorizontalSpeed(double v) { this.prevHorizontalSpeed = v; }
    /**
     * Ticks since the player left the ground, as seen by the speed check specifically. The
     * sprint-jump impulse is a one-off at the jump, not a per-air-tick bonus, so the model needs
     * to know which airborne tick this is - granting the impulse every tick would raise the
     * airborne prediction ceiling to several blocks per tick and make the check blind in the air.
     */
    private volatile int speedAirborneTicks = 0;
    private volatile boolean speedPrevOnGround = true;

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

    public double getSpeedPredictionDebt() { return speedPredictionDebt; }
    public void setSpeedPredictionDebt(double v) { this.speedPredictionDebt = v; }
    public void addSpeedPredictionDebt(double v) { this.speedPredictionDebt = Math.max(0.0, this.speedPredictionDebt + v); }
    // Hit-rotation consistency tracking (KillauraAngleCheck): snapshot of the attacker's own
    // look yaw/pitch and the yaw/pitch that would be REQUIRED to face the target dead-on, taken
    // at the moment of each attack. Catches a killaura variant that never needs to visibly move
    // the camera at all (target stationary relative to attacker), which none of the aim-delta
    // based checks (GCD/StatisticalAim) can see, since they only run when real rotation packets
    // arrive. Comparing consecutive attacks catches the case a target-tracking cheat auto-faces
    // a MOVING target without generating the mouse input a human tracking it would.
    private volatile boolean hasLastAttackSnapshot = false;
    private volatile float lastAttackYaw = 0f;
    private volatile float lastAttackPitch = 0f;
    private volatile float lastAttackRequiredYaw = 0f;
    private volatile float lastAttackRequiredPitch = 0f;
    private volatile int staticTrackingStreak = 0;

    public boolean hasLastAttackSnapshot() { return hasLastAttackSnapshot; }
    public float getLastAttackYaw() { return lastAttackYaw; }
    public float getLastAttackPitch() { return lastAttackPitch; }
    public float getLastAttackRequiredYaw() { return lastAttackRequiredYaw; }
    public float getLastAttackRequiredPitch() { return lastAttackRequiredPitch; }

    public void setLastAttackSnapshot(float yaw, float pitch, float requiredYaw, float requiredPitch) {
        this.lastAttackYaw = yaw;
        this.lastAttackPitch = pitch;
        this.lastAttackRequiredYaw = requiredYaw;
        this.lastAttackRequiredPitch = requiredPitch;
        this.hasLastAttackSnapshot = true;
    }

    public int getStaticTrackingStreak() { return staticTrackingStreak; }
    public void incrementStaticTrackingStreak() { this.staticTrackingStreak++; }
    public void resetStaticTrackingStreak() { this.staticTrackingStreak = 0; }
}
