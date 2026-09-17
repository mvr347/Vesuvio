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
    // Extended early-combat snapshot for TemporalConsistencyCheck's DupRatio/Entropy/Peak/signature
    // comparison against the late-combat window - see that class for what each anomaly catches.
    private volatile float earlyCombatDupRatio = -1f;
    private volatile float earlyCombatEntropy = -1f;
    private volatile float earlyCombatPeakCps = -1f;
    private volatile long[] earlyCombatSignature = null;

    // Live display metrics
    private volatile double lastCalculatedCPS = 0.0;
    private volatile double lastMLProbability = 0.0;
    private volatile long lastMLProbabilityNanos = 0L;

    /**
     * Rolling history of this session's feature vectors, so a confirmed label (ban or trap) can be
     * recorded against several windows of the session rather than only the instant it fired.
     * See {@link FeatureSnapshotHistory} for why one window per label is not enough.
     */
    private final FeatureSnapshotHistory featureHistory = new FeatureSnapshotHistory();
    public FeatureSnapshotHistory getFeatureHistory() { return featureHistory; }
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
            earlyCombatDupRatio = -1f;
            earlyCombatEntropy = -1f;
            earlyCombatPeakCps = -1f;
            earlyCombatSignature = null;
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

    public float getEarlyCombatDupRatio() { return earlyCombatDupRatio; }
    public void setEarlyCombatDupRatio(float v) { this.earlyCombatDupRatio = v; }
    public float getEarlyCombatEntropy() { return earlyCombatEntropy; }
    public void setEarlyCombatEntropy(float v) { this.earlyCombatEntropy = v; }
    public float getEarlyCombatPeakCps() { return earlyCombatPeakCps; }
    public void setEarlyCombatPeakCps(float v) { this.earlyCombatPeakCps = v; }
    public long[] getEarlyCombatSignature() { return earlyCombatSignature; }
    public void setEarlyCombatSignature(long[] v) { this.earlyCombatSignature = v; }

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

    /**
     * When {@link #setLastMLProbability(double)} last ran. The ensemble needs this to tell a
     * probability produced for the click it is scoring from one left over from a fight minutes ago;
     * a stale value is dropped from the fusion entirely rather than counted as evidence of calm.
     */
    public long getLastMLProbabilityNanos() {
        return lastMLProbabilityNanos;
    }

    public void setLastMLProbability(double lastMLProbability) {
        this.lastMLProbabilityNanos = System.nanoTime();
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

    // ---------------------------------------------------------------------------------------
    // Rotation the attack packet was sent alongside, captured on the netty thread in true packet
    // arrival order (see packet.ClickPacketListener). KillauraAngleCheck's world-check half runs a
    // full tick later on the main thread (target resolution needs live entity lists); reading
    // attacker.getLocation() there instead reads whatever rotation packets arrived DURING that
    // extra tick - which is exactly the gap a rotation that is sent for one packet and reverted
    // the next is built to hide in. This is a one-shot value: KillauraAngleCheck consumes and
    // clears it for the attack it belongs to, so a skipped or exempted attack cannot leave it to
    // be read by an unrelated later one.
    //
    // Known limitation: this is a single slot, not a per-attack queue. A client that sends more
    // than one ATTACK packet inside the same tick (a "multi-target-per-tick" killaura mode, hitting
    // several entities at once) only gets this treatment for whichever of those attacks a later
    // one has not yet overwritten by the time its own world-check runs - the rest fall back to the
    // live-location read this was written to improve on, not to something worse. Correctly
    // attributing this per target would need keying by entity id; not done here for now.
    // ---------------------------------------------------------------------------------------
    private volatile float pendingAttackYaw;
    private volatile float pendingAttackPitch;
    private volatile boolean hasPendingAttackRotation;

    public synchronized void setPendingAttackRotation(float yaw, float pitch) {
        this.pendingAttackYaw = yaw;
        this.pendingAttackPitch = pitch;
        this.hasPendingAttackRotation = true;
    }

    /** Result of {@link #consumePendingAttackRotation()}: whether a value was present, and its yaw/pitch. */
    public record PendingRotation(boolean present, float yaw, float pitch) {}

    /** Reads and clears the pending attack rotation in one step, so it is used at most once. */
    public synchronized PendingRotation consumePendingAttackRotation() {
        PendingRotation result = new PendingRotation(hasPendingAttackRotation, pendingAttackYaw, pendingAttackPitch);
        hasPendingAttackRotation = false;
        return result;
    }

    // Previous rotation packet's SIGNED yaw delta (wrapped to (-180, 180]) and when it landed, used
    // by check.statistical.SnapAimCheck to recognise a mechanical "snap there, snap back": a real
    // hand cannot reverse a large turn with near-equal magnitude within a couple of ticks, because
    // physical mouse motion has an acceleration/deceleration phase spread across several of them.
    private volatile float prevSignedDeltaYaw = 0f;
    private volatile long prevSignedDeltaYawNanos = 0L;

    public float getPrevSignedDeltaYaw() { return prevSignedDeltaYaw; }
    public long getPrevSignedDeltaYawNanos() { return prevSignedDeltaYawNanos; }

    public void setPrevSignedDeltaYaw(float delta, long nowNanos) {
        this.prevSignedDeltaYaw = delta;
        this.prevSignedDeltaYawNanos = nowNanos;
    }

    private final DecayingEvidence snapAimEvidence = new DecayingEvidence();
    public DecayingEvidence getSnapAimEvidence() { return snapAimEvidence; }

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
    private final DecayingEvidence perfectAimEvidence = new DecayingEvidence();
    public DecayingEvidence getPerfectAimEvidence() { return perfectAimEvidence; }
    /**
     * Kept for the API/web-panel snapshot, which has always exposed this as an int. It now reports
     * the accumulated evidence weight rather than a consecutive count - same scale (one suspicious
     * hit is worth 1.0), but it no longer collapses to zero the instant one hit looks human.
     */
    public int getPerfectAimStreak() { return (int) Math.round(perfectAimEvidence.peek()); }
    public void resetPerfectAimStreak() { perfectAimEvidence.reset(); }

    // Consecutive hits whose required hitbox rewind exceeded what the attacker's real transaction
    // RTT could explain, tracked by check.combat.BackTrackCheck.
    private volatile int backTrackStreak = 0;
    public int getBackTrackStreak() { return backTrackStreak; }
    public void incrementBackTrackStreak() { this.backTrackStreak++; }
    public void resetBackTrackStreak() { this.backTrackStreak = 0; }

    // Hits landing significantly off the attacker's own sprint/movement direction while sprint is
    // maintained, tracked by check.combat.MoveDirectionCheck. DecayingEvidence rather than a
    // consecutive streak - a killaura that occasionally corrects its own direction for one honest
    // hit out of several used to reset a "3 in a row" counter for free.
    private final DecayingEvidence moveDirectionEvidence = new DecayingEvidence();
    public DecayingEvidence getMoveDirectionEvidence() { return moveDirectionEvidence; }

    // Consecutive movement ticks with sub-degree alignment between look yaw and travel direction,
    // tracked by check.statistical.BaritoneCheck.
    private volatile int baritoneStreak = 0;
    public int getBaritoneStreak() { return baritoneStreak; }
    public void incrementBaritoneStreak() { this.baritoneStreak++; }
    public void resetBaritoneStreak() { this.baritoneStreak = 0; }

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

    /**
     * Timestamp of the last movement packet that actually carried a position, tracked separately
     * from {@link #lastAnyMovementNanos} purely for diagnostics (see BlinkCheck's "positionSilenceMs"
     * detail). Gating the silence detector itself on this alone was considered and rejected: a
     * player who simply stands still while panning the camera (looting, watching chat, aiming)
     * legitimately sends rotation-only packets for seconds at a time with zero position packets,
     * which would make ordinary play look identical to a blink candidate. Kept as a secondary,
     * informational signal instead.
     */
    private volatile long lastPositionCarryingNanos = 0L;
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
    private final long[] blinkOccurrenceNanos = new long[16];
    private int blinkOccurrenceHead = 0;
    private int blinkOccurrenceFill = 0;

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
    private volatile long pendingBlinkNanos = 0L;
    private volatile double pendingBlinkSilenceMs = 0.0;
    private volatile double pendingBlinkRttMs = 0.0;
    private volatile boolean pendingBlinkShort = false;
    private volatile int pendingBlinkOccurrences = 0;

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
    private volatile long blinkFlushWindowStartNanos = 0L;
    private volatile int blinkFlushPackets = 0;

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
    private long blinkAckSequence = -1L;
    private int blinkPacketsSinceAck = 0;

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

    /**
     * True if the client produced a packet that only its main game loop can generate (swing, or an
     * attack) inside the given window. A genuine client-side freeze stops that loop entirely, so
     * nothing at all arrives; a blink suppresses movement while the player keeps fighting. This is
     * the discriminator the original "silence + healthy RTT" test lacked - see BlinkCheck.
     */
    public boolean hadMainLoopActivityBetween(long fromNanos, long toNanos) {
        long swing = lastSwingNanos;
        if (swing > fromNanos && swing <= toNanos) return true;
        long click = clickBuffer.getLastClickNanos();
        return click > fromNanos && click <= toNanos;
    }

    // Wall-clock time of the last movement packet exempted for being a huge (teleport-sized)
    // displacement, so BlinkCheck can tell "just teleported" apart from "silence just broke with a
    // huge catch-up jump" - both look identical at the position-delta level.
    private volatile long lastTeleportMillis = 0L;
    public void recordTeleport() { this.lastTeleportMillis = System.currentTimeMillis(); }
    public boolean hasRecentTeleport() { return (System.currentTimeMillis() - lastTeleportMillis) < 1500L; }

    // Wall-clock time of the last game-mode change. A game mode is read from the once-per-tick
    // EnvironmentSnapshot, so for up to one tick after the change the movement checks are still
    // judging a creative/spectator flyer against the survival movement model - which is exactly
    // how staff entering /vesuvio spectate flagged themselves for Speed. The change itself is an
    // event, so it is recorded here the instant it happens and the movement checks skip the
    // handful of packets that fall inside the gap. Unlike the teleport grace this cannot be
    // triggered on demand (an ender pearl is not a game-mode change), so it opens no window a
    // cheat could stand in.
    private volatile long lastGameModeChangeMillis = 0L;
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
    private double reportedX, reportedY, reportedZ;
    private float reportedYaw, reportedPitch;
    private boolean reportedOnGround;
    private boolean hasReportedState;

    /** Client's own send epsilon is 2.0E-4 per axis; a little headroom for float round-tripping. */
    private static final double REPORTED_POSITION_EPSILON = 0.002;

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

    private final DecayingEvidence staticTrackingEvidence = new DecayingEvidence();
    public DecayingEvidence getStaticTrackingEvidence() { return staticTrackingEvidence; }
    public int getStaticTrackingStreak() { return (int) Math.round(staticTrackingEvidence.peek()); }
    public void resetStaticTrackingStreak() { staticTrackingEvidence.reset(); }

    // -------------------------------------------------------------
    // KillauraAngleCheck: reaction-time tracking. lastRotationNanos updates on every aim packet;
    // lastSignificantRotationNanos only when the combined delta clears a configured "real turn"
    // threshold, so a burst of sub-pixel jitter right before an attack does not read as the
    // attacker having "just turned onto" the target.
    // -------------------------------------------------------------
    private volatile long lastRotationNanos = 0L;
    private volatile long lastSignificantRotationNanos = 0L;
    public long getLastRotationNanos() { return lastRotationNanos; }
    public void setLastRotationNanos(long v) { this.lastRotationNanos = v; }
    public long getLastSignificantRotationNanos() { return lastSignificantRotationNanos; }
    public void setLastSignificantRotationNanos(long v) { this.lastSignificantRotationNanos = v; }

    private final DecayingEvidence reactionTimeEvidence = new DecayingEvidence();
    public DecayingEvidence getReactionTimeEvidence() { return reactionTimeEvidence; }
    public int getReactionTimeStreak() { return (int) Math.round(reactionTimeEvidence.peek()); }
    public void resetReactionTimeStreak() { reactionTimeEvidence.reset(); }

    // -------------------------------------------------------------
    // KillauraAngleCheck: multi-target switch tracking. Records which entity the last attack was
    // against and the aim state at that moment, so a switch to a DIFFERENT target can be compared
    // against how much the attacker's own look actually moved to make that switch.
    // -------------------------------------------------------------
    private volatile int lastAttackTargetId = -1;
    private volatile long lastAttackNanos = 0L;
    public int getLastAttackTargetId() { return lastAttackTargetId; }
    public long getLastAttackNanos() { return lastAttackNanos; }
    public void setLastAttackTarget(int entityId, long nowNanos) {
        this.lastAttackTargetId = entityId;
        this.lastAttackNanos = nowNanos;
    }

    private final DecayingEvidence targetSwitchEvidence = new DecayingEvidence();
    public DecayingEvidence getTargetSwitchEvidence() { return targetSwitchEvidence; }
    public int getTargetSwitchStreak() { return (int) Math.round(targetSwitchEvidence.peek()); }
    public void resetTargetSwitchStreak() { targetSwitchEvidence.reset(); }

    // Aim-consistency layer: accumulated evidence that this player's required-aim error keeps
    // landing below the natural-jitter floor established by their own recent AimRingBuffer variance.
    private final DecayingEvidence aimConsistencyEvidence = new DecayingEvidence();
    public DecayingEvidence getAimConsistencyEvidence() { return aimConsistencyEvidence; }
    public int getAimConsistencyStreak() { return (int) Math.round(aimConsistencyEvidence.peek()); }
    public void resetAimConsistencyStreak() { aimConsistencyEvidence.reset(); }

    // Tracking-lag evidence: does this player's aim error correlate with target speed and sit off
    // a consistent human bias, or hold tight to a recalculated angle regardless - see
    // KillauraAngleCheck#checkTrackingLag.
    private final DecayingEvidence trackingLagEvidence = new DecayingEvidence();
    public DecayingEvidence getTrackingLagEvidence() { return trackingLagEvidence; }

    // -------------------------------------------------------------
    // Target-relative aim tracking (see engine.AimTrackingService and
    // check.statistical.StrafeReversalCheck). Filled once per tick on the main thread while the
    // player is fighting, read off-thread by the checks.
    // -------------------------------------------------------------
    private final AimTrackingBuffer aimTrackingBuffer = new AimTrackingBuffer();
    public AimTrackingBuffer getAimTrackingBuffer() { return aimTrackingBuffer; }

    /**
     * Decaying evidence accumulator for StrafeReversalCheck, deliberately not a consecutive
     * streak: a randomized module breaks any "N in a row" requirement by behaving humanly one
     * attack in five, whereas an accumulator reflects the balance of evidence across a fight.
     */
    private double strafeReversalScore = 0.0;
    private int strafeReversalEvents = 0;
    private volatile long lastStrafeReversalAnalyzedNanos = 0L;

    public synchronized double getStrafeReversalScore() { return strafeReversalScore; }
    public synchronized void addStrafeReversalScore(double delta) {
        this.strafeReversalScore = Math.max(0.0, this.strafeReversalScore + delta);
    }
    public synchronized int getStrafeReversalEvents() { return strafeReversalEvents; }
    public synchronized void incrementStrafeReversalEvents() { this.strafeReversalEvents++; }
    public synchronized void resetStrafeReversal() {
        this.strafeReversalScore = 0.0;
        this.strafeReversalEvents = 0;
    }
    public long getLastStrafeReversalAnalyzedNanos() { return lastStrafeReversalAnalyzedNanos; }
    public void setLastStrafeReversalAnalyzedNanos(long v) { this.lastStrafeReversalAnalyzedNanos = v; }

    /** Throttle for StrafeReversalCheck - the buffer only gains one new sample per tick. */
    private volatile long lastStrafeReversalRunMillis = 0L;
    public boolean shouldRunStrafeReversal(long minIntervalMs) {
        long now = System.currentTimeMillis();
        if (now - lastStrafeReversalRunMillis < minIntervalMs) return false;
        lastStrafeReversalRunMillis = now;
        return true;
    }
}
