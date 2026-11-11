package net.lovelace.vesuvio.data;

import net.lovelace.vesuvio.integration.essential.MechanicsState;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Encapsulates all real-time anti-cheat metrics for a single player. Completely thread-safe.
 *
 * <p>The state itself lives in three objects - {@link KinematicState} (movement, environment, block interaction),
 * {@link CombatState} (clicks, aim, attacks) and {@link ScoreState} (violation level, trust, risk, rate limits) - and this class
 * keeps the identity, the mechanics/packet-queue plumbing and delegates, so the hundreds of existing call sites are unchanged.
 * Methods that touch more than one of them (teleport, movement reset, attack capture) stay here.
 *
 * Author: Lovelace
 */
public final class UserData {

    private final KinematicState kinematic = new KinematicState();
    private final CombatState combat = new CombatState();
    private final ScoreState score = new ScoreState();

    private final UUID uuid;

    private volatile String username;

    // Client Brand (e.g. vanilla, fabric, lunar, or cheat signature)
    private volatile String clientBrand = "unknown";

    private final String learningSessionId = UUID.randomUUID().toString();

    private volatile String learningClientVersion = "unknown";

    public String getLearningSessionId() { return learningSessionId; }

    public String getLearningClientVersion() { return learningClientVersion; }

    public void setLearningClientVersion(String value) { learningClientVersion = value == null ? "unknown" : value; }

    public UUID getUuid() {
        return uuid;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getClientBrand() {
        return clientBrand;
    }

    public void setClientBrand(String clientBrand) {
        this.clientBrand = clientBrand != null ? clientBrand : "vanilla";
    }

    /** Result of {@link #consumePendingAttackRotation()}: whether a value was present, and its yaw/pitch. */
    public record PendingRotation(boolean present, float yaw, float pitch) {}

    private volatile MechanicsState mechanics =
            MechanicsState.NONE;

    public MechanicsState mechanics() {
        return mechanics;
    }

    public synchronized void updateMechanics(
            java.util.Set<String> states, long now, long grace, boolean pulse) {
        if (!pulse && mechanics.states().equals(states)) return;
        // Packet work is synchronized on this same object: old queued results cannot cross the
        // reset.
        resetMovementTracking();
        getClickBuffer().reset();
        getAimBuffer().reset();
        getAimTrackingBuffer().reset();
        setInitialRotation(false);
        resetGcdStreak();
        resetStrafeReversal();
        mechanics =
                new MechanicsState(
                        states, grace > 0 ? now + grace : 0);
    }

    private final net.lovelace.vesuvio.engine.OrderedTaskQueue packetChecks =
            new net.lovelace.vesuvio.engine.OrderedTaskQueue();

    public net.lovelace.vesuvio.engine.OrderedTaskQueue packetChecks() { return packetChecks; }

    public UserData(UUID uuid, String username) {
        this.uuid = uuid;
        this.username = username;
        this.kinematic.teleportHook = this::recordTeleport;
    }

    /** Reset kinematic history, never the player's accumulated violations. */
    public synchronized void resetMovementTracking() {
        kinematic.resetMovementTracking();
        combat.reachEvidence().reset();
        combat.hitboxEvidence().reset();
    }

    public synchronized void recordTeleport() {
        kinematic.markTeleport();
        resetMovementTracking();
        combat.resetAttackTracking();
        setEnvironment(net.lovelace.vesuvio.engine.EnvironmentSnapshot.EMPTY);
    }

    public synchronized net.lovelace.vesuvio.engine.AttackSnapshot captureAttack(long receivedNanos) {
        return new net.lovelace.vesuvio.engine.AttackSnapshot(kinematic.hasReportedPosition, kinematic.reportedX,
                kinematic.reportedY, kinematic.reportedZ, hasInitialRotation(), getLastYaw(), getLastPitch(), receivedNanos);
    }

    public double getLastX() {
        return kinematic.getLastX();
    }

    public double getLastY() {
        return kinematic.getLastY();
    }

    public double getLastZ() {
        return kinematic.getLastZ();
    }

    public double getLastDeltaY() {
        return kinematic.getLastDeltaY();
    }

    public double getLastDeltaXZ() {
        return kinematic.getLastDeltaXZ();
    }

    public boolean isLastOnGround() {
        return kinematic.isLastOnGround();
    }

    public boolean hasLastPosition() {
        return kinematic.hasLastPosition();
    }

    public void setLastPosition(double x, double y, double z, boolean onGround) {
        kinematic.setLastPosition(x, y, z, onGround);
    }

    public int getAirTicks() {
        return kinematic.getAirTicks();
    }

    public void incrementAirTicks() {
        kinematic.incrementAirTicks();
    }

    public void resetAirTicks() {
        kinematic.resetAirTicks();
    }

    public int getGroundTicks() {
        return kinematic.getGroundTicks();
    }

    public int getFlyStreak() {
        return kinematic.getFlyStreak();
    }

    public void incrementFlyStreak() {
        kinematic.incrementFlyStreak();
    }

    public void decrementFlyStreak() {
        kinematic.decrementFlyStreak();
    }

    public void resetFlyStreak() {
        kinematic.resetFlyStreak();
    }

    public int getSpeedStreak() {
        return kinematic.getSpeedStreak();
    }

    public void incrementSpeedStreak() {
        kinematic.incrementSpeedStreak();
    }

    public void decrementSpeedStreak() {
        kinematic.decrementSpeedStreak();
    }

    public void resetSpeedStreak() {
        kinematic.resetSpeedStreak();
    }

    public int getNoFallStreak() {
        return kinematic.getNoFallStreak();
    }

    public void incrementNoFallStreak() {
        kinematic.incrementNoFallStreak();
    }

    public void resetNoFallStreak() {
        kinematic.resetNoFallStreak();
    }

    public int getStepStreak() {
        return kinematic.getStepStreak();
    }

    public void incrementStepStreak() {
        kinematic.incrementStepStreak();
    }

    public void resetStepStreak() {
        kinematic.resetStepStreak();
    }

    public int getInvMoveStreak() {
        return kinematic.getInvMoveStreak();
    }

    public void incrementInvMoveStreak() {
        kinematic.incrementInvMoveStreak();
    }

    public void resetInvMoveStreak() {
        kinematic.resetInvMoveStreak();
    }

    public int getTimerPacketCount() {
        return kinematic.getTimerPacketCount();
    }

    public void setTimerPacketCount(int count) {
        kinematic.setTimerPacketCount(count);
    }

    public void incrementTimerPacketCount() {
        kinematic.incrementTimerPacketCount();
    }

    public long getTimerWindowStartNanos() {
        return kinematic.getTimerWindowStartNanos();
    }

    public void setTimerWindowStartNanos(long nanos) {
        kinematic.setTimerWindowStartNanos(nanos);
    }

    public long getLastVelocityMillis() {
        return kinematic.getLastVelocityMillis();
    }

    public void markContainerClosed() {
        kinematic.markContainerClosed();
    }

    public long getLastContainerClosedMillis() {
        return kinematic.getLastContainerClosedMillis();
    }

    public boolean containerClosedAfter(long sinceMillis) {
        return kinematic.containerClosedAfter(sinceMillis);
    }

    public DecayingEvidence getStepEvidence() {
        return kinematic.getStepEvidence();
    }

    public DecayingEvidence getFlyEvidence() {
        return kinematic.getFlyEvidence();
    }

    public void recordVelocity() {
        kinematic.recordVelocity();
    }

    public boolean hasRecentVelocity() {
        return kinematic.hasRecentVelocity();
    }

    public double getPrevAirDeltaY() {
        return kinematic.getPrevAirDeltaY();
    }

    public void setPrevAirDeltaY(double value) {
        kinematic.setPrevAirDeltaY(value);
    }

    public int getBaritoneStreak() {
        return kinematic.getBaritoneStreak();
    }

    public void incrementBaritoneStreak() {
        kinematic.incrementBaritoneStreak();
    }

    public void resetBaritoneStreak() {
        kinematic.resetBaritoneStreak();
    }

    public net.lovelace.vesuvio.engine.EnvironmentSnapshot getEnvironment() {
        return kinematic.getEnvironment();
    }

    public net.lovelace.vesuvio.engine.physics.ClientWorld clientWorld() {
        return kinematic.clientWorld();
    }

    public boolean isPhysicsObserved() {
        return kinematic.isPhysicsObserved();
    }

    public long getPhysicsObservationEpoch() {
        return kinematic.getPhysicsObservationEpoch();
    }

    public void setPhysicsObserved(boolean enabled) {
        kinematic.setPhysicsObserved(enabled);
    }

    public net.lovelace.vesuvio.engine.physics.PhysicsSession physicsSession() {
        return kinematic.physicsSession();
    }

    public net.lovelace.vesuvio.engine.physics.PhysicsFrame getPhysicsFrame() {
        return kinematic.getPhysicsFrame();
    }

    public void setPhysicsFrame(net.lovelace.vesuvio.engine.physics.PhysicsFrame frame) {
        kinematic.setPhysicsFrame(frame);
    }

    public void setEnvironment(net.lovelace.vesuvio.engine.EnvironmentSnapshot environment) {
        kinematic.setEnvironment(environment);
    }

    public double getTimerBalanceMs() {
        return kinematic.getTimerBalanceMs();
    }

    public void setTimerBalanceMs(double v) {
        kinematic.setTimerBalanceMs(v);
    }

    public long getTimerLastPacketNanos() {
        return kinematic.getTimerLastPacketNanos();
    }

    public void setTimerLastPacketNanos(long v) {
        kinematic.setTimerLastPacketNanos(v);
    }

    public double getTimerIntervalEmaMs() {
        return kinematic.getTimerIntervalEmaMs();
    }

    public void setTimerIntervalEmaMs(double v) {
        kinematic.setTimerIntervalEmaMs(v);
    }

    public int getTimerViolationStreak() {
        return kinematic.getTimerViolationStreak();
    }

    public void incrementTimerViolationStreak() {
        kinematic.incrementTimerViolationStreak();
    }

    public void decrementTimerViolationStreak() {
        kinematic.decrementTimerViolationStreak();
    }

    public void resetTimerBalance() {
        kinematic.resetTimerBalance();
    }

    public DecayingEvidence getVelocityEvidence() {
        return kinematic.getVelocityEvidence();
    }

    public boolean isVelocityAckSeen() {
        return kinematic.isVelocityAckSeen();
    }

    public void markVelocityAcknowledged() {
        kinematic.markVelocityAcknowledged();
    }

    public void recordPendingVelocity(double x, double y, double z, long sequence) {
        kinematic.recordPendingVelocity(x, y, z, sequence);
    }

    public double getPendingVelX() {
        return kinematic.getPendingVelX();
    }

    public double getPendingVelY() {
        return kinematic.getPendingVelY();
    }

    public double getPendingVelZ() {
        return kinematic.getPendingVelZ();
    }

    public long getPendingVelSequence() {
        return kinematic.getPendingVelSequence();
    }

    public boolean isVelocityPending() {
        return kinematic.isVelocityPending();
    }

    public void clearPendingVelocity() {
        kinematic.clearPendingVelocity();
    }

    public int getVelocityTicksTracked() {
        return kinematic.getVelocityTicksTracked();
    }

    public void incrementVelocityTicksTracked() {
        kinematic.incrementVelocityTicksTracked();
    }

    public double getVelocityObservedHorizontal() {
        return kinematic.getVelocityObservedHorizontal();
    }

    public void addVelocityObservedHorizontal(double d) {
        kinematic.addVelocityObservedHorizontal(d);
    }

    public double getLastMoveDeltaX() {
        return kinematic.getLastMoveDeltaX();
    }

    public double getLastMoveDeltaZ() {
        return kinematic.getLastMoveDeltaZ();
    }

    public void setLastMoveDelta(double dx, double dz) {
        kinematic.setLastMoveDelta(dx, dz);
    }

    public long getLastPositionNanos() {
        return kinematic.getLastPositionNanos();
    }

    public void setLastPositionNanos(long v) {
        kinematic.setLastPositionNanos(v);
    }

    public DecayingEvidence getPhaseEvidence() {
        return kinematic.getPhaseEvidence();
    }

    public long getPhaseCooldownUntilMillis() {
        return kinematic.getPhaseCooldownUntilMillis();
    }

    public void setPhaseCooldownUntilMillis(long millis) {
        kinematic.setPhaseCooldownUntilMillis(millis);
    }

    public long getLastNearbyBlockChangeMillis() {
        return kinematic.getLastNearbyBlockChangeMillis();
    }

    public void markNearbyBlockChange(long nowMillis) {
        kinematic.markNearbyBlockChange(nowMillis);
    }

    public DecayingEvidence getInvMoveEvidence() {
        return kinematic.getInvMoveEvidence();
    }

    public long getContainerOpenSinceMillis() {
        return kinematic.getContainerOpenSinceMillis();
    }

    public void setContainerOpenSinceMillis(long millis) {
        kinematic.setContainerOpenSinceMillis(millis);
    }

    public boolean updatePhaseEntry(boolean inside, int bx, int by, int bz, boolean suspiciousEntry) {
        return kinematic.updatePhaseEntry(inside, bx, by, bz, suspiciousEntry);
    }

    public void resetPhaseEntry() {
        kinematic.resetPhaseEntry();
    }

    public double getInvMovePrevHorizontal() {
        return kinematic.getInvMovePrevHorizontal();
    }

    public void setInvMovePrevHorizontal(double v) {
        kinematic.setInvMovePrevHorizontal(v);
    }

    public int getPhaseTicks() {
        return kinematic.getPhaseTicks();
    }

    public void incrementPhaseTicks() {
        kinematic.incrementPhaseTicks();
    }

    public void decrementPhaseTicks() {
        kinematic.decrementPhaseTicks();
    }

    public void resetPhaseTicks() {
        kinematic.resetPhaseTicks();
    }

    public void markOwnBlockDesync() {
        kinematic.markOwnBlockDesync();
    }

    public long getLastOwnBlockDesyncMillis() {
        return kinematic.getLastOwnBlockDesyncMillis();
    }

    public boolean inOwnBlockDesyncWindow(double rttMs) {
        return kinematic.inOwnBlockDesyncWindow(rttMs);
    }

    public DecayingEvidence getAirPlaceEvidence() {
        return kinematic.getAirPlaceEvidence();
    }

    public DecayingEvidence getScaffoldEvidence() {
        return kinematic.getScaffoldEvidence();
    }

    public DecayingEvidence getGhostHandEvidence() {
        return kinematic.getGhostHandEvidence();
    }

    public DecayingEvidence getBlockReachEvidence() {
        return kinematic.getBlockReachEvidence();
    }

    public DecayingEvidence getVehicleFlyEvidence() {
        return kinematic.getVehicleFlyEvidence();
    }

    public double swapVehicleDeltaY(double deltaY, long nowMillis) {
        return kinematic.swapVehicleDeltaY(deltaY, nowMillis);
    }

    public int getVehicleClipTicks() {
        return kinematic.getVehicleClipTicks();
    }

    public void incrementVehicleClipTicks() {
        kinematic.incrementVehicleClipTicks();
    }

    public void decrementVehicleClipTicks() {
        kinematic.decrementVehicleClipTicks();
    }

    public void resetVehicleClipTicks() {
        kinematic.resetVehicleClipTicks();
    }

    public void recordElytraBoost() {
        kinematic.recordElytraBoost();
    }

    public boolean hasRecentElytraBoost() {
        return kinematic.hasRecentElytraBoost();
    }

    public long getElytraWindowStartMillis() {
        return kinematic.getElytraWindowStartMillis();
    }

    public double getElytraWindowDeltaY() {
        return kinematic.getElytraWindowDeltaY();
    }

    public void resetElytraWindow(long nowMillis) {
        kinematic.resetElytraWindow(nowMillis);
    }

    public void addElytraWindowDeltaY(double v) {
        kinematic.addElytraWindowDeltaY(v);
    }

    public double getElytraSpeedDebt() {
        return kinematic.getElytraSpeedDebt();
    }

    public void addElytraSpeedDebt(double v) {
        kinematic.addElytraSpeedDebt(v);
    }

    public void setElytraSpeedDebt(double v) {
        kinematic.setElytraSpeedDebt(v);
    }

    public int getElytraViolationStreak() {
        return kinematic.getElytraViolationStreak();
    }

    public void incrementElytraViolationStreak() {
        kinematic.incrementElytraViolationStreak();
    }

    public void resetElytraViolationStreak() {
        kinematic.resetElytraViolationStreak();
    }

    public long getLastAnyMovementNanos() {
        return kinematic.getLastAnyMovementNanos();
    }

    public void setLastAnyMovementNanos(long v) {
        kinematic.setLastAnyMovementNanos(v);
    }

    public long getLastPositionCarryingNanos() {
        return kinematic.getLastPositionCarryingNanos();
    }

    public void setLastPositionCarryingNanos(long v) {
        kinematic.setLastPositionCarryingNanos(v);
    }

    public int getBlinkStreak() {
        return kinematic.getBlinkStreak();
    }

    public int recordBlinkOccurrence(long nowNanos, long windowNanos) {
        return kinematic.recordBlinkOccurrence(nowNanos, windowNanos);
    }

    public boolean hasPendingBlink() {
        return kinematic.hasPendingBlink();
    }

    public long getPendingBlinkNanos() {
        return kinematic.getPendingBlinkNanos();
    }

    public double getPendingBlinkSilenceMs() {
        return kinematic.getPendingBlinkSilenceMs();
    }

    public double getPendingBlinkRttMs() {
        return kinematic.getPendingBlinkRttMs();
    }

    public boolean isPendingBlinkShort() {
        return kinematic.isPendingBlinkShort();
    }

    public int getPendingBlinkOccurrences() {
        return kinematic.getPendingBlinkOccurrences();
    }

    public void setPendingBlink(long nowNanos, double silenceMs, double rttMs, boolean isShort, int occurrences) {
        kinematic.setPendingBlink(nowNanos, silenceMs, rttMs, isShort, occurrences);
    }

    public void clearPendingBlink() {
        kinematic.clearPendingBlink();
    }

    public boolean hasBlinkFlushWindow() {
        return kinematic.hasBlinkFlushWindow();
    }

    public long getBlinkFlushWindowStartNanos() {
        return kinematic.getBlinkFlushWindowStartNanos();
    }

    public void startBlinkFlushWindow(long nowNanos) {
        kinematic.startBlinkFlushWindow(nowNanos);
    }

    public int incrementBlinkFlushPackets() {
        return kinematic.incrementBlinkFlushPackets();
    }

    public int recordMovementAgainstAck(long ackSequence) {
        return kinematic.recordMovementAgainstAck(ackSequence);
    }

    public void resetMovementAckBucket() {
        kinematic.resetMovementAckBucket();
    }

    public void clearBlinkFlushWindow() {
        kinematic.clearBlinkFlushWindow();
    }

    public void resetKinematicState() {
        kinematic.resetKinematicState();
    }

    public long getMovementResetNanos() {
        return kinematic.getMovementResetNanos();
    }

    public void expectTeleport(int id) {
        kinematic.expectTeleport(id);
    }

    public boolean acknowledgeTeleport(int id) {
        return kinematic.acknowledgeTeleport(id);
    }

    public boolean hasRecentTeleport() {
        return kinematic.hasRecentTeleport();
    }

    public void recordGameModeChange() {
        kinematic.recordGameModeChange();
    }

    public boolean hasRecentGameModeChange() {
        return kinematic.hasRecentGameModeChange();
    }

    public boolean noteReportedClientState(boolean hasPosition, double x, double y, double z, boolean hasRotation, float yaw, float pitch, boolean onGround) {
        return kinematic.noteReportedClientState(hasPosition, x, y, z, hasRotation, yaw, pitch, onGround);
    }

    public double getPredictedPreviousX() {
        return kinematic.getPredictedPreviousX();
    }

    public double getPredictedPreviousZ() {
        return kinematic.getPredictedPreviousZ();
    }

    public boolean hasPredictedVector() {
        return kinematic.hasPredictedVector();
    }

    public void setPredictedVector(double x, double z) {
        kinematic.setPredictedVector(x, z);
    }

    public void clearPredictedVector() {
        kinematic.clearPredictedVector();
    }

    public double getPrevHorizontalSpeed() {
        return kinematic.getPrevHorizontalSpeed();
    }

    public void setPrevHorizontalSpeed(double v) {
        kinematic.setPrevHorizontalSpeed(v);
    }

    public int getSpeedAirborneTicks() {
        return kinematic.getSpeedAirborneTicks();
    }

    public boolean isSpeedPrevOnGround() {
        return kinematic.isSpeedPrevOnGround();
    }

    public void updateSpeedGroundState(boolean onGround) {
        kinematic.updateSpeedGroundState(onGround);
    }

    public boolean isSpeedGround1() {
        return kinematic.isSpeedGround1();
    }

    public boolean isSpeedGround2() {
        return kinematic.isSpeedGround2();
    }

    public void shiftSpeedGround(boolean onGroundNow) {
        kinematic.shiftSpeedGround(onGroundNow);
    }

    public double slipperyMax(double current, long nowMillis) {
        return kinematic.slipperyMax(current, nowMillis);
    }

    public double slipperyMin(double current, long nowMillis) {
        return kinematic.slipperyMin(current, nowMillis);
    }

    public double getSpeedPredictionDebt() {
        return kinematic.getSpeedPredictionDebt();
    }

    public void setSpeedPredictionDebt(double v) {
        kinematic.setSpeedPredictionDebt(v);
    }

    public void addSpeedPredictionDebt(double v) {
        kinematic.addSpeedPredictionDebt(v);
    }

    public FeatureSnapshotHistory getFeatureHistory() {
        return combat.getFeatureHistory();
    }

    public ClickRingBuffer getClickBuffer() {
        return combat.getClickBuffer();
    }

    public AimRingBuffer getAimBuffer() {
        return combat.getAimBuffer();
    }

    public long[] getBaselineSignature() {
        return combat.getBaselineSignature();
    }

    public void setBaselineSignature(long[] signature) {
        combat.setBaselineSignature(signature);
    }

    public int getSignatureMatches() {
        return combat.getSignatureMatches();
    }

    public void incrementSignatureMatches() {
        combat.incrementSignatureMatches();
    }

    public void recordCombatAction() {
        combat.recordCombatAction();
    }

    public long getCombatDurationMillis() {
        return combat.getCombatDurationMillis();
    }

    public float getEarlyCombatVariance() {
        return combat.getEarlyCombatVariance();
    }

    public void setEarlyCombatVariance(float earlyCombatVariance) {
        combat.setEarlyCombatVariance(earlyCombatVariance);
    }

    public float getEarlyCombatMean() {
        return combat.getEarlyCombatMean();
    }

    public void setEarlyCombatMean(float earlyCombatMean) {
        combat.setEarlyCombatMean(earlyCombatMean);
    }

    public float getEarlyCombatDupRatio() {
        return combat.getEarlyCombatDupRatio();
    }

    public void setEarlyCombatDupRatio(float v) {
        combat.setEarlyCombatDupRatio(v);
    }

    public float getEarlyCombatEntropy() {
        return combat.getEarlyCombatEntropy();
    }

    public void setEarlyCombatEntropy(float v) {
        combat.setEarlyCombatEntropy(v);
    }

    public float getEarlyCombatPeakCps() {
        return combat.getEarlyCombatPeakCps();
    }

    public void setEarlyCombatPeakCps(float v) {
        combat.setEarlyCombatPeakCps(v);
    }

    public long[] getEarlyCombatSignature() {
        return combat.getEarlyCombatSignature();
    }

    public void setEarlyCombatSignature(long[] v) {
        combat.setEarlyCombatSignature(v);
    }

    public boolean shouldRunML() {
        return combat.shouldRunML();
    }

    public void markMLRun() {
        combat.markMLRun();
    }

    public double getLastCalculatedCPS() {
        return combat.getLastCalculatedCPS();
    }

    public void setLastCalculatedCPS(double lastCalculatedCPS) {
        combat.setLastCalculatedCPS(lastCalculatedCPS);
    }

    public double getLastMLProbability() {
        return combat.getLastMLProbability();
    }

    public long getLastMLProbabilityNanos() {
        return combat.getLastMLProbabilityNanos();
    }

    public void setLastMLProbability(double lastMLProbability) {
        combat.setLastMLProbability(lastMLProbability);
    }

    public double getLastSelfLearnProbability() {
        return combat.getLastSelfLearnProbability();
    }

    public void setLastSelfLearnProbability(double v) {
        combat.setLastSelfLearnProbability(v);
    }

    public double getLastAimSelfLearnProbability() {
        return combat.getLastAimSelfLearnProbability();
    }

    public void setLastAimSelfLearnProbability(double v) {
        combat.setLastAimSelfLearnProbability(v);
    }

    public boolean isAltCheckDone() {
        return combat.isAltCheckDone();
    }

    public void setAltCheckDone(boolean v) {
        combat.setAltCheckDone(v);
    }

    public double getLastStdDevMs() {
        return combat.getLastStdDevMs();
    }

    public void setLastStdDevMs(double v) {
        combat.setLastStdDevMs(v);
    }

    public double getLastDupRatio() {
        return combat.getLastDupRatio();
    }

    public void setLastDupRatio(double v) {
        combat.setLastDupRatio(v);
    }

    public double getLastEntropy() {
        return combat.getLastEntropy();
    }

    public void setLastEntropy(double v) {
        combat.setLastEntropy(v);
    }

    public long getLastSwingNanos() {
        return combat.getLastSwingNanos();
    }

    public void setLastSwingNanos(long lastSwingNanos) {
        combat.setLastSwingNanos(lastSwingNanos);
    }

    public double provenMainLoopTicks(long fromNanos, long toNanos, double maxPerGap) {
        return combat.provenMainLoopTicks(fromNanos, toNanos, maxPerGap);
    }

    public boolean hasSwingNear(long attackNanos) {
        return combat.hasSwingNear(attackNanos);
    }

    public boolean isFirstAttackSeen() {
        return combat.isFirstAttackSeen();
    }

    public void setFirstAttackSeen(boolean firstAttackSeen) {
        combat.setFirstAttackSeen(firstAttackSeen);
    }

    public float getLastYaw() {
        return combat.getLastYaw();
    }

    public void setLastYaw(float lastYaw) {
        combat.setLastYaw(lastYaw);
    }

    public float getLastPitch() {
        return combat.getLastPitch();
    }

    public void setLastPitch(float lastPitch) {
        combat.setLastPitch(lastPitch);
    }

    public void setPendingAttackRotation(float yaw, float pitch) {
        combat.setPendingAttackRotation(yaw, pitch);
    }

    public void recordRotationSample(float yaw, float pitch, long nanos, float combinedDelta) {
        combat.recordRotationSample(yaw, pitch, nanos, combinedDelta);
    }

    public PendingRotation firstRotationAfter(long attackNanos, long windowNanos) {
        return combat.firstRotationAfter(attackNanos, windowNanos);
    }

    public long lastSignificantRotationBefore(long beforeNanos, double floorDegrees) {
        return combat.lastSignificantRotationBefore(beforeNanos, floorDegrees);
    }

    public DecayingEvidence getSilentAimEvidence() {
        return combat.getSilentAimEvidence();
    }

    public DecayingEvidence getFovEvidence() {
        return combat.getFovEvidence();
    }

    public DecayingEvidence getWallHitEvidence() {
        return combat.getWallHitEvidence();
    }

    public PendingRotation consumePendingAttackRotation() {
        return combat.consumePendingAttackRotation();
    }

    public float getPrevSignedDeltaYaw() {
        return combat.getPrevSignedDeltaYaw();
    }

    public long getPrevSignedDeltaYawNanos() {
        return combat.getPrevSignedDeltaYawNanos();
    }

    public void setPrevSignedDeltaYaw(float delta, long nowNanos) {
        combat.setPrevSignedDeltaYaw(delta, nowNanos);
    }

    public DecayingEvidence getSnapAimEvidence() {
        return combat.getSnapAimEvidence();
    }

    public boolean hasInitialRotation() {
        return combat.hasInitialRotation();
    }

    public void setInitialRotation(boolean initialRotation) {
        combat.setInitialRotation(initialRotation);
    }

    public DecayingEvidence getGcdEvidence() {
        return combat.getGcdEvidence();
    }

    public int getGcdSuspiciousStreak() {
        return combat.getGcdSuspiciousStreak();
    }

    public void incrementGcdStreak() {
        combat.incrementGcdStreak();
    }

    public void decrementGcdStreak() {
        combat.decrementGcdStreak();
    }

    public void resetGcdStreak() {
        combat.resetGcdStreak();
    }

    public boolean isInCombat() {
        return combat.isInCombat();
    }

    public DecayingEvidence getAutoCritEvidence() {
        return combat.getAutoCritEvidence();
    }

    public DecayingEvidence getBackTrackEvidence() {
        return combat.getBackTrackEvidence();
    }

    public DecayingEvidence getPerfectAimEvidence() {
        return combat.getPerfectAimEvidence();
    }

    public int getPerfectAimStreak() {
        return combat.getPerfectAimStreak();
    }

    public void resetPerfectAimStreak() {
        combat.resetPerfectAimStreak();
    }

    public int getBackTrackStreak() {
        return combat.getBackTrackStreak();
    }

    public void incrementBackTrackStreak() {
        combat.incrementBackTrackStreak();
    }

    public void resetBackTrackStreak() {
        combat.resetBackTrackStreak();
    }

    public DecayingEvidence getMoveDirectionEvidence() {
        return combat.getMoveDirectionEvidence();
    }

    public boolean hadMainLoopActivityBetween(long fromNanos, long toNanos) {
        return combat.hadMainLoopActivityBetween(fromNanos, toNanos);
    }

    public DecayingEvidence reachEvidence() {
        return combat.reachEvidence();
    }

    public DecayingEvidence hitboxEvidence() {
        return combat.hitboxEvidence();
    }

    public boolean hasLastAttackSnapshot() {
        return combat.hasLastAttackSnapshot();
    }

    public float getLastAttackYaw() {
        return combat.getLastAttackYaw();
    }

    public float getLastAttackPitch() {
        return combat.getLastAttackPitch();
    }

    public float getLastAttackRequiredYaw() {
        return combat.getLastAttackRequiredYaw();
    }

    public float getLastAttackRequiredPitch() {
        return combat.getLastAttackRequiredPitch();
    }

    public void setLastAttackSnapshot(float yaw, float pitch, float requiredYaw, float requiredPitch) {
        combat.setLastAttackSnapshot(yaw, pitch, requiredYaw, requiredPitch);
    }

    public DecayingEvidence getStaticTrackingEvidence() {
        return combat.getStaticTrackingEvidence();
    }

    public int getStaticTrackingStreak() {
        return combat.getStaticTrackingStreak();
    }

    public void resetStaticTrackingStreak() {
        combat.resetStaticTrackingStreak();
    }

    public long getLastRotationNanos() {
        return combat.getLastRotationNanos();
    }

    public void setLastRotationNanos(long v) {
        combat.setLastRotationNanos(v);
    }

    public long getLastSignificantRotationNanos() {
        return combat.getLastSignificantRotationNanos();
    }

    public void setLastSignificantRotationNanos(long v) {
        combat.setLastSignificantRotationNanos(v);
    }

    public DecayingEvidence getReactionTimeEvidence() {
        return combat.getReactionTimeEvidence();
    }

    public int getReactionTimeStreak() {
        return combat.getReactionTimeStreak();
    }

    public void resetReactionTimeStreak() {
        combat.resetReactionTimeStreak();
    }

    public int getLastAttackTargetId() {
        return combat.getLastAttackTargetId();
    }

    public long getLastAttackNanos() {
        return combat.getLastAttackNanos();
    }

    public void setLastAttackTarget(int entityId, long nowNanos) {
        combat.setLastAttackTarget(entityId, nowNanos);
    }

    public DecayingEvidence getTargetSwitchEvidence() {
        return combat.getTargetSwitchEvidence();
    }

    public int getTargetSwitchStreak() {
        return combat.getTargetSwitchStreak();
    }

    public void resetTargetSwitchStreak() {
        combat.resetTargetSwitchStreak();
    }

    public DecayingEvidence getAimConsistencyEvidence() {
        return combat.getAimConsistencyEvidence();
    }

    public int getAimConsistencyStreak() {
        return combat.getAimConsistencyStreak();
    }

    public void resetAimConsistencyStreak() {
        combat.resetAimConsistencyStreak();
    }

    public DecayingEvidence getTrackingLagEvidence() {
        return combat.getTrackingLagEvidence();
    }

    public AimTrackingBuffer getAimTrackingBuffer() {
        return combat.getAimTrackingBuffer();
    }

    public double getStrafeReversalScore() {
        return combat.getStrafeReversalScore();
    }

    public void addStrafeReversalScore(double delta) {
        combat.addStrafeReversalScore(delta);
    }

    public int getStrafeReversalEvents() {
        return combat.getStrafeReversalEvents();
    }

    public void incrementStrafeReversalEvents() {
        combat.incrementStrafeReversalEvents();
    }

    public void resetStrafeReversal() {
        combat.resetStrafeReversal();
    }

    public long getLastStrafeReversalAnalyzedNanos() {
        return combat.getLastStrafeReversalAnalyzedNanos();
    }

    public void setLastStrafeReversalAnalyzedNanos(long v) {
        combat.setLastStrafeReversalAnalyzedNanos(v);
    }

    public boolean shouldRunStrafeReversal(long minIntervalMs) {
        return combat.shouldRunStrafeReversal(minIntervalMs);
    }

    public double getVl() {
        return score.getVl();
    }

    public void addVl(double amount) {
        score.addVl(amount);
    }

    public void resetVl() {
        score.resetVl();
    }

    public void decayVl(double amount) {
        score.decayVl(amount);
    }

    public long getLastViolationTime() {
        return score.getLastViolationTime();
    }

    public double getTrustScore() {
        return score.getTrustScore();
    }

    public void setTrustScore(double trustScore) {
        score.setTrustScore(trustScore);
    }

    public void adjustTrust(double delta) {
        score.adjustTrust(delta);
    }

    public double getRiskIndex() {
        return score.getRiskIndex();
    }

    public void setRiskIndex(double riskIndex) {
        score.setRiskIndex(riskIndex);
    }

    public void adjustRisk(double delta) {
        score.adjustRisk(delta);
    }

    public void applyCleanDecay(long nowMillis, long cleanMillis, double riskPerMinute, double riskFloor, double trustHours) {
        score.applyCleanDecay(nowMillis, cleanMillis, riskPerMinute, riskFloor, trustHours);
    }

    public boolean tryAlert(String check, long nowMillis, long intervalMillis) {
        return score.tryAlert(check, nowMillis, intervalMillis);
    }

    public boolean tryReflag(String check, long nowMillis, long intervalMillis) {
        return score.tryReflag(check, nowMillis, intervalMillis);
    }

    public boolean isManualSuspect() {
        return score.isManualSuspect();
    }

    public void setManualSuspect(boolean manualSuspect) {
        score.setManualSuspect(manualSuspect);
    }

    public boolean isSuspect(double highRiskThreshold) {
        return score.isSuspect(highRiskThreshold);
    }

    public double getSensitivityMultiplier() {
        return score.getSensitivityMultiplier();
    }

    public static void setSensitivityCap(double cap) {
        ScoreState.setSensitivityCap(cap);
    }

    public String getLastTriggeredCheck() {
        return score.getLastTriggeredCheck();
    }

    public void setLastTriggeredCheck(String lastTriggeredCheck) {
        score.setLastTriggeredCheck(lastTriggeredCheck);
    }

    public boolean tryLatchPunished() {
        return score.tryLatchPunished();
    }

    public boolean isPunished() {
        return score.isPunished();
    }

    public void resetPunishedLatch() {
        score.resetPunishedLatch();
    }

    public boolean tryPunishment(long now) {
        return score.tryPunishment(now);
    }
}
