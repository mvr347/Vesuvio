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

    public long getLastSwingNanos() {
        return lastSwingNanos;
    }

    public void setLastSwingNanos(long lastSwingNanos) {
        this.lastSwingNanos = lastSwingNanos;
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
}
