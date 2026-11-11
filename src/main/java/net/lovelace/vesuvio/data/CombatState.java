package net.lovelace.vesuvio.data;

import net.lovelace.vesuvio.integration.essential.MechanicsState;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

/** Click, aim and attack state of one player: ring buffers, rotation history, aim evidence and the killaura accumulators. Moved out of UserData unchanged; UserData delegates. */
final class CombatState {

    final ClickRingBuffer clickBuffer = new ClickRingBuffer();

    final AimRingBuffer aimBuffer = new AimRingBuffer();

    // Playstyle Fingerprinting: baseline signature established across trusted sessions
    volatile long[] baselineSignature = null;

    volatile int signatureMatches = 0;

    // Temporal Consistency tracking (early combat 0-3s vs late 8-15s)
    volatile long combatStartMillis = 0;

    volatile long lastCombatActionMillis = 0;

    volatile float earlyCombatVariance = -1f;

    volatile float earlyCombatMean = -1f;

    // Extended early-combat snapshot for TemporalConsistencyCheck's DupRatio/Entropy/Peak/signature
    // comparison against the late-combat window - see that class for what each anomaly catches.
    volatile float earlyCombatDupRatio = -1f;

    volatile float earlyCombatEntropy = -1f;

    volatile float earlyCombatPeakCps = -1f;

    volatile long[] earlyCombatSignature = null;

    // Live display metrics
    volatile double lastCalculatedCPS = 0.0;

    volatile double lastMLProbability = 0.0;

    volatile long lastMLProbabilityNanos = 0L;

    /**
     * Rolling history of this session's feature vectors, so a confirmed label (ban or trap) can be
     * recorded against several windows of the session rather than only the instant it fired.
     * See {@link FeatureSnapshotHistory} for why one window per label is not enough.
     */
    final FeatureSnapshotHistory featureHistory = new FeatureSnapshotHistory();

    public FeatureSnapshotHistory getFeatureHistory() { return featureHistory; }

    // ML inference throttle
    final AtomicLong lastMLCheckTime = new AtomicLong(0);

    public ClickRingBuffer getClickBuffer() {
        return clickBuffer;
    }

    public AimRingBuffer getAimBuffer() {
        return aimBuffer;
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

    volatile double lastSelfLearnProbability = 0.0;

    public double getLastSelfLearnProbability() { return lastSelfLearnProbability; }

    public void setLastSelfLearnProbability(double v) { this.lastSelfLearnProbability = v; }

    volatile double lastAimSelfLearnProbability = 0.0;

    public double getLastAimSelfLearnProbability() { return lastAimSelfLearnProbability; }

    public void setLastAimSelfLearnProbability(double v) { this.lastAimSelfLearnProbability = v; }

    // Ban-evasion: whether the one-time playstyle-signature-vs-banlist check has run this
    // session yet (see BanEvasionManager / CheckPipeline#processClick).
    volatile boolean altCheckDone = false;

    public boolean isAltCheckDone() { return altCheckDone; }

    public void setAltCheckDone(boolean v) { this.altCheckDone = v; }

    // Live debug metrics (/vesuvio debug) - populated by StatisticalClickCheck on every evaluation
    volatile double lastStdDevMs = 0.0;

    volatile double lastDupRatio = 0.0;

    volatile double lastEntropy = 0.0;

    public double getLastStdDevMs() { return lastStdDevMs; }

    public void setLastStdDevMs(double v) { this.lastStdDevMs = v; }

    public double getLastDupRatio() { return lastDupRatio; }

    public void setLastDupRatio(double v) { this.lastDupRatio = v; }

    public double getLastEntropy() { return lastEntropy; }

    public void setLastEntropy(double v) { this.lastEntropy = v; }

    // Swing and Rotation Tracking for BadPackets & GCD
    volatile long lastSwingNanos = 0L;

    volatile float lastYaw = 0f;

    volatile float lastPitch = 0f;

    // Vanilla clients can send the very first attack's Interact packet before its Animation
    // (swing) packet within the same client tick, so lastSwingNanos==0 on a player's first-ever
    // attack this session isn't evidence of a NoSwing cheat - just no baseline yet. Exempts
    // exactly that one attack from BadPacketsCheck.checkNoSwing(); every attack after it is
    // judged normally, so a client that genuinely never swings is still caught starting there.
    volatile boolean firstAttackSeen = false;

    public long getLastSwingNanos() {
        return lastSwingNanos;
    }

    final long[] recentSwings = new long[64];

    int swingCursor, swingCount;

    public synchronized void setLastSwingNanos(long lastSwingNanos) {
        this.lastSwingNanos = lastSwingNanos;
        recentSwings[swingCursor] = lastSwingNanos;
        swingCursor = (swingCursor + 1) % recentSwings.length;
        swingCount = Math.min(recentSwings.length, swingCount + 1);
    }

    /**
     * Client ticks that PROVABLY ran between two instants, from swing packets only the client's main loop produces: for
     * every pair of neighbouring swings inside the window, min(gap / 50 ms, {@code maxPerGap}) ticks. The cap is the
     * vanilla catch-up clamp (10 ticks per frame): a gap bigger than that can be a freeze that ran only its clamp, so it
     * proves no more than the clamp. Fewer than two swings prove nothing. A sum of 24 or more means the client was past the
     * mandatory 20-tick position reminder, so a silent movement stream was really withheld.
     */
    public synchronized double provenMainLoopTicks(long fromNanos, long toNanos, double maxPerGap) {
        long[] inWindow = new long[swingCount];
        int n = 0;
        for (int i = 0; i < swingCount; i++) {
            long t = recentSwings[i];
            if (t > fromNanos && t <= toNanos) inWindow[n++] = t;
        }
        java.util.Arrays.sort(inWindow, 0, n);
        double ticks = 0.0;
        for (int i = 1; i < n; i++) {
            ticks += Math.min((inWindow[i] - inWindow[i - 1]) / 50_000_000.0, maxPerGap);
        }
        return ticks;
    }

    public synchronized boolean hasSwingNear(long attackNanos) {
        for (int i = 0; i < swingCount; i++) {
            if (Math.abs(recentSwings[i] - attackNanos) <= 150_000_000L) return true;
        }
        return false;
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
    volatile float pendingAttackYaw;

    volatile float pendingAttackPitch;

    volatile boolean hasPendingAttackRotation;

    public synchronized void setPendingAttackRotation(float yaw, float pitch) {
        this.pendingAttackYaw = yaw;
        this.pendingAttackPitch = pitch;
        this.hasPendingAttackRotation = true;
    }

    // ---- Rotation ring (2026-11-11) ----------------------------------------------------------------------------------
    // The client sends an attack packet BEFORE the rotation packet of the same tick, so the angle captured with the attack is
    // one tick older than what the player actually aimed with: a sharp correction right after the click read as a miss.
    // Every rotation is recorded here with the time its packet arrived (netty thread), so the combat checks can take the better
    // of the attack angle and the first rotation within 150 ms after it.
    static final int ROTATION_RING = 32;

    final long[] rotNanos = new long[ROTATION_RING];

    final float[] rotYaw = new float[ROTATION_RING];

    final float[] rotPitch = new float[ROTATION_RING];

    final float[] rotDelta = new float[ROTATION_RING];

    int rotCursor, rotCount;

    public synchronized void recordRotationSample(float yaw, float pitch, long nanos, float combinedDelta) {
        rotNanos[rotCursor] = nanos;
        rotYaw[rotCursor] = yaw;
        rotPitch[rotCursor] = pitch;
        rotDelta[rotCursor] = combinedDelta;
        rotCursor = (rotCursor + 1) % ROTATION_RING;
        rotCount = Math.min(ROTATION_RING, rotCount + 1);
    }

    /** The first rotation that arrived after {@code attackNanos} and no later than {@code windowNanos} after it. */
    public synchronized UserData.PendingRotation firstRotationAfter(long attackNanos, long windowNanos) {
        long best = Long.MAX_VALUE;
        int bestIdx = -1;
        for (int i = 0; i < rotCount; i++) {
            long t = rotNanos[i];
            if (t > attackNanos && t <= attackNanos + windowNanos && t < best) {
                best = t;
                bestIdx = i;
            }
        }
        return bestIdx < 0 ? new UserData.PendingRotation(false, 0f, 0f) : new UserData.PendingRotation(true, rotYaw[bestIdx], rotPitch[bestIdx]);
    }

    /** Time of the latest rotation before {@code beforeNanos} whose own turn was at least {@code floorDegrees}; 0 if none. */
    public synchronized long lastSignificantRotationBefore(long beforeNanos, double floorDegrees) {
        long best = 0L;
        for (int i = 0; i < rotCount; i++) {
            long t = rotNanos[i];
            if (t <= beforeNanos && t > best && rotDelta[i] >= floorDegrees) best = t;
        }
        return best;
    }

    final DecayingEvidence silentAimEvidence = new DecayingEvidence();

    final DecayingEvidence fovEvidence = new DecayingEvidence();

    final DecayingEvidence wallHitEvidence = new DecayingEvidence();

    public DecayingEvidence getSilentAimEvidence() { return silentAimEvidence; }

    public DecayingEvidence getFovEvidence() { return fovEvidence; }

    public DecayingEvidence getWallHitEvidence() { return wallHitEvidence; }

    /** Reads and clears the pending attack rotation in one step, so it is used at most once. */
    public synchronized UserData.PendingRotation consumePendingAttackRotation() {
        UserData.PendingRotation result = new UserData.PendingRotation(hasPendingAttackRotation, pendingAttackYaw, pendingAttackPitch);
        hasPendingAttackRotation = false;
        return result;
    }

    // Previous rotation packet's SIGNED yaw delta (wrapped to (-180, 180]) and when it landed, used
    // by check.statistical.SnapAimCheck to recognise a mechanical "snap there, snap back": a real
    // hand cannot reverse a large turn with near-equal magnitude within a couple of ticks, because
    // physical mouse motion has an acceleration/deceleration phase spread across several of them.
    volatile float prevSignedDeltaYaw = 0f;

    volatile long prevSignedDeltaYawNanos = 0L;

    public float getPrevSignedDeltaYaw() { return prevSignedDeltaYaw; }

    public long getPrevSignedDeltaYawNanos() { return prevSignedDeltaYawNanos; }

    public void setPrevSignedDeltaYaw(float delta, long nowNanos) {
        this.prevSignedDeltaYaw = delta;
        this.prevSignedDeltaYawNanos = nowNanos;
    }

    final DecayingEvidence snapAimEvidence = new DecayingEvidence();

    public DecayingEvidence getSnapAimEvidence() { return snapAimEvidence; }

    // Rotation initialization and GCD Streak
    volatile boolean initialRotation = false;

    volatile int gcdSuspiciousStreak = 0;

    public boolean hasInitialRotation() {
        return initialRotation;
    }

    public void setInitialRotation(boolean initialRotation) {
        this.initialRotation = initialRotation;
    }

    final DecayingEvidence gcdEvidence = new DecayingEvidence();

    public DecayingEvidence getGcdEvidence() { return gcdEvidence; }

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
        gcdEvidence.reset();
        this.gcdSuspiciousStreak = 0;
    }

    public boolean isInCombat() {
        return (System.currentTimeMillis() - lastCombatActionMillis) < 4000L;
    }

    final DecayingEvidence autoCritEvidence = new DecayingEvidence();

    public DecayingEvidence getAutoCritEvidence() { return autoCritEvidence; }

    final DecayingEvidence backTrackEvidence = new DecayingEvidence();

    public DecayingEvidence getBackTrackEvidence() { return backTrackEvidence; }

    // Consecutive near-perfect (sub-degree) aim-lock hits during combat, tracked by KillauraAngleCheck.
    final DecayingEvidence perfectAimEvidence = new DecayingEvidence();

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
    volatile int backTrackStreak = 0;

    public int getBackTrackStreak() { return backTrackStreak; }

    public void incrementBackTrackStreak() { this.backTrackStreak++; }

    public void resetBackTrackStreak() { this.backTrackStreak = 0; }

    // Hits landing significantly off the attacker's own sprint/movement direction while sprint is
    // maintained, tracked by check.combat.MoveDirectionCheck. DecayingEvidence rather than a
    // consecutive streak - a killaura that occasionally corrects its own direction for one honest
    // hit out of several used to reset a "3 in a row" counter for free.
    final DecayingEvidence moveDirectionEvidence = new DecayingEvidence();

    public DecayingEvidence getMoveDirectionEvidence() { return moveDirectionEvidence; }

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

    final DecayingEvidence reachEvidence = new DecayingEvidence();

    final DecayingEvidence hitboxEvidence = new DecayingEvidence();

    public DecayingEvidence reachEvidence() { return reachEvidence; }

    public DecayingEvidence hitboxEvidence() { return hitboxEvidence; }

    // Hit-rotation consistency tracking (KillauraAngleCheck): snapshot of the attacker's own
    // look yaw/pitch and the yaw/pitch that would be REQUIRED to face the target dead-on, taken
    // at the moment of each attack. Catches a killaura variant that never needs to visibly move
    // the camera at all (target stationary relative to attacker), which none of the aim-delta
    // based checks (GCD/StatisticalAim) can see, since they only run when real rotation packets
    // arrive. Comparing consecutive attacks catches the case a target-tracking cheat auto-faces
    // a MOVING target without generating the mouse input a human tracking it would.
    volatile boolean hasLastAttackSnapshot = false;

    volatile float lastAttackYaw = 0f;

    volatile float lastAttackPitch = 0f;

    volatile float lastAttackRequiredYaw = 0f;

    volatile float lastAttackRequiredPitch = 0f;

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

    final DecayingEvidence staticTrackingEvidence = new DecayingEvidence();

    public DecayingEvidence getStaticTrackingEvidence() { return staticTrackingEvidence; }

    public int getStaticTrackingStreak() { return (int) Math.round(staticTrackingEvidence.peek()); }

    public void resetStaticTrackingStreak() { staticTrackingEvidence.reset(); }

    // -------------------------------------------------------------
    // KillauraAngleCheck: reaction-time tracking. lastRotationNanos updates on every aim packet;
    // lastSignificantRotationNanos only when the combined delta clears a configured "real turn"
    // threshold, so a burst of sub-pixel jitter right before an attack does not read as the
    // attacker having "just turned onto" the target.
    // -------------------------------------------------------------
    volatile long lastRotationNanos = 0L;

    volatile long lastSignificantRotationNanos = 0L;

    public long getLastRotationNanos() { return lastRotationNanos; }

    public void setLastRotationNanos(long v) { this.lastRotationNanos = v; }

    public long getLastSignificantRotationNanos() { return lastSignificantRotationNanos; }

    public void setLastSignificantRotationNanos(long v) { this.lastSignificantRotationNanos = v; }

    final DecayingEvidence reactionTimeEvidence = new DecayingEvidence();

    public DecayingEvidence getReactionTimeEvidence() { return reactionTimeEvidence; }

    public int getReactionTimeStreak() { return (int) Math.round(reactionTimeEvidence.peek()); }

    public void resetReactionTimeStreak() { reactionTimeEvidence.reset(); }

    // -------------------------------------------------------------
    // KillauraAngleCheck: multi-target switch tracking. Records which entity the last attack was
    // against and the aim state at that moment, so a switch to a DIFFERENT target can be compared
    // against how much the attacker's own look actually moved to make that switch.
    // -------------------------------------------------------------
    volatile int lastAttackTargetId = -1;

    volatile long lastAttackNanos = 0L;

    public int getLastAttackTargetId() { return lastAttackTargetId; }

    public long getLastAttackNanos() { return lastAttackNanos; }

    public void setLastAttackTarget(int entityId, long nowNanos) {
        this.lastAttackTargetId = entityId;
        this.lastAttackNanos = nowNanos;
    }

    final DecayingEvidence targetSwitchEvidence = new DecayingEvidence();

    public DecayingEvidence getTargetSwitchEvidence() { return targetSwitchEvidence; }

    public int getTargetSwitchStreak() { return (int) Math.round(targetSwitchEvidence.peek()); }

    public void resetTargetSwitchStreak() { targetSwitchEvidence.reset(); }

    // Aim-consistency layer: accumulated evidence that this player's required-aim error keeps
    // landing below the natural-jitter floor established by their own recent AimRingBuffer variance.
    final DecayingEvidence aimConsistencyEvidence = new DecayingEvidence();

    public DecayingEvidence getAimConsistencyEvidence() { return aimConsistencyEvidence; }

    public int getAimConsistencyStreak() { return (int) Math.round(aimConsistencyEvidence.peek()); }

    public void resetAimConsistencyStreak() { aimConsistencyEvidence.reset(); }

    // Tracking-lag evidence: does this player's aim error correlate with target speed and sit off
    // a consistent human bias, or hold tight to a recalculated angle regardless - see
    // KillauraAngleCheck#checkTrackingLag.
    final DecayingEvidence trackingLagEvidence = new DecayingEvidence();

    public DecayingEvidence getTrackingLagEvidence() { return trackingLagEvidence; }

    // -------------------------------------------------------------
    // Target-relative aim tracking (see engine.AimTrackingService and
    // check.statistical.StrafeReversalCheck). Filled once per tick on the main thread while the
    // player is fighting, read off-thread by the checks.
    // -------------------------------------------------------------
    final AimTrackingBuffer aimTrackingBuffer = new AimTrackingBuffer();

    public AimTrackingBuffer getAimTrackingBuffer() { return aimTrackingBuffer; }

    /**
     * Decaying evidence accumulator for StrafeReversalCheck, deliberately not a consecutive
     * streak: a randomized module breaks any "N in a row" requirement by behaving humanly one
     * attack in five, whereas an accumulator reflects the balance of evidence across a fight.
     */
    double strafeReversalScore = 0.0;

    int strafeReversalEvents = 0;

    volatile long lastStrafeReversalAnalyzedNanos = 0L;

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
    volatile long lastStrafeReversalRunMillis = 0L;

    public boolean shouldRunStrafeReversal(long minIntervalMs) {
        long now = System.currentTimeMillis();
        if (now - lastStrafeReversalRunMillis < minIntervalMs) return false;
        lastStrafeReversalRunMillis = now;
        return true;
    }

    /** What a teleport invalidates on the combat side: rotation baseline, buffers, swing history, pending attack rotation. */
    void resetAttackTracking() {
        setInitialRotation(false);
        getAimBuffer().reset();
        getClickBuffer().reset();
        hasPendingAttackRotation = false;
        swingCount = 0;
        swingCursor = 0;
        lastSwingNanos = 0;
        resetGcdStreak();
        resetStrafeReversal();
    }
}
