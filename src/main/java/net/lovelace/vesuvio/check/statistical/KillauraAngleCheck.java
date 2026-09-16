package net.lovelace.vesuvio.check.statistical;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.config.ConfigManager;
import net.lovelace.vesuvio.data.DecayingEvidence;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.engine.HitboxHistoryTracker;
import net.lovelace.vesuvio.engine.TransactionManager;
import net.lovelace.vesuvio.feature.AimFeatureExtractor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Advanced Combat: Field-of-View (FOV), Line-of-Sight, and Crosshair Ray Analyzer.
 * Inspired by GrimAC, Reflex, and Vulcan.
 *
 * Catches:
 * 1. WallHit: Attacking entities through solid blocks/obstacles.
 * 2. SilentAim: Attack packet sent while player crosshair ray completely misses entity hitbox,
 *    rewound to the moment the attacker's client actually saw it.
 * 3. KillauraAngle: Attacking entities outside human field of view (multi-point sampled).
 * 4. PerfectAimLock / StaticAimTracking / AimConsistency: accumulator-based aim-quality signatures.
 * 5. ReactionTime: attack landing implausibly soon after the attacker last turned meaningfully.
 * 6. TargetSwitch: switching attack target without a corresponding look-delta.
 *
 * Sub-checks 4-6 accumulate decaying evidence ({@link DecayingEvidence}) rather than counting
 * consecutive hits: a randomized aura defeats any "N in a row" counter for free by behaving humanly
 * one hit out of five, while an accumulator only cares about the ratio of suspicious to clean hits
 * inside a decay window. Each sub-check's configured {@code streak} value is reused as the score
 * threshold, so previously tuned numbers keep their meaning (one suspicious hit is still worth 1.0).
 *
 * All angle/streak/tolerance thresholds are configurable (see ConfigManager) and, where noted,
 * scaled by the player's dynamic Trust/Risk sensitivity multiplier so a proven-clean player is not
 * judged as harshly as a fresh or already-suspect one.
 *
 * Author: Lovelace
 */
public final class KillauraAngleCheck {

    private static final Logger LOGGER = Logger.getLogger("Vesuvio");

    private final ConfigManager config;
    private final HitboxHistoryTracker hitboxTracker;
    private final TransactionManager transactionManager;

    public KillauraAngleCheck(ConfigManager config, HitboxHistoryTracker hitboxTracker, TransactionManager transactionManager) {
        this.config = config;
        this.hitboxTracker = hitboxTracker;
        this.transactionManager = transactionManager;
    }

    public CheckResult check(Player attacker, Entity target, UserData data) {
        if (attacker == null || target == null || data == null) return CheckResult.pass("KillauraAngle");

        double sensitivity = (config != null && config.isKillauraDynamicSensitivityEnabled())
                ? data.getSensitivityMultiplier() : 1.0;

        Location eyeLoc = attacker.getEyeLocation();
        BoundingBox liveBox = target.getBoundingBox();

        Vector toCenter = liveBox.getCenter().subtract(eyeLoc.toVector());
        double distSq = toCenter.lengthSquared();
        if (distSq < 0.25) {
            // Right inside target body, angle is ambiguous
            return CheckResult.pass("KillauraAngle");
        }

        double distance = Math.sqrt(distSq);
        Vector eyeDir = eyeLoc.getDirection().normalize();

        // -------------------------------------------------------------
        // 1. Multi-point angle sampling: the naive "angle to hitbox center" flags a perfectly
        // legitimate hit on a target's edge (crouched, prone in a doorway, strafing at melee
        // range) as if it missed entirely. Sampling center/head/feet/nearest-box-point and taking
        // the smallest angle across them is what GrimAC-class anticheats do for the same reason -
        // a cheat that is actually aiming somewhere else entirely still fails every sample point.
        // -------------------------------------------------------------
        double effectiveAngle = minAngleToBox(eyeLoc, eyeDir, liveBox);
        double angleDegrees = angleTo(eyeDir, toCenter.clone().normalize());

        double fovLimit = scaleThresholdUp(config != null ? config.getKillauraFovLimitDegrees() : 75.0, sensitivity);

        // With settings.debug on, print the geometry of EVERY attack, not just the ones that flag.
        // A report of "the anticheat did not catch this" is otherwise impossible to act on: the
        // difference between a cheat the checks genuinely miss and one they correctly see as
        // aiming at its target (a module that snaps the rotation for the attack and restores it,
        // so the victim sees a back turned but the server never does) is a single number, and it
        // is a number only the server can read.
        if (config != null && config.isDebug()) {
            LOGGER.info(String.format(Locale.US,
                    "[ATTACK] %s -> %s | effectiveAngle=%.1f centerAngle=%.1f fovLimit=%.1f "
                            + "(base %.1f / sensitivity %.2f) distance=%.2f yaw=%.1f pitch=%.1f",
                    attacker.getName(),
                    target.getName() != null ? target.getName() : target.getType().name(),
                    effectiveAngle, angleDegrees, fovLimit,
                    config.getKillauraFovLimitDegrees(), sensitivity, distance,
                    eyeLoc.getYaw(), eyeLoc.getPitch()));
        }

        // -------------------------------------------------------------
        // 2. Line-of-Sight / WallHit Check (GrimAC / Vulcan technique)
        // -------------------------------------------------------------
        double wallhitTolerance = config != null ? config.getKillauraWallhitTolerance() : 0.35;
        if (distance > 0.8) {
            Vector toCenterNorm = toCenter.clone().normalize();
            RayTraceResult blockHit = attacker.getWorld().rayTraceBlocks(
                    eyeLoc, toCenterNorm, distance, FluidCollisionMode.NEVER, true
            );
            if (blockHit != null && blockHit.getHitBlock() != null) {
                Block b = blockHit.getHitBlock();
                // Dropped the isOccluding() requirement: a fence, wall, iron bars or glass pane is
                // not "occluding" (light still passes) but very much blocks a melee hit, and
                // rayTraceBlocks(ignorePassableBlocks=true) already resolves against the block's
                // real collision shape - so any non-null hit here already accounts for slabs,
                // stairs, and other partial blocks correctly. isOccluding() only excluded exactly
                // the solid-but-non-occluding case a real attacker also cannot swing through.
                if (!b.isPassable()) {
                    double distToBlock = blockHit.getHitPosition().distance(eyeLoc.toVector());
                    if (distToBlock < distance - wallhitTolerance) {
                        Map<String, Object> details = new HashMap<>();
                        details.put("block", b.getType().name());
                        details.put("distToBlock", distToBlock);
                        details.put("distToTarget", distance);

                        String explanation = String.format(Locale.US,
                                "Attack through solid obstacle %s (Block: %.2fm, Target: %.2fm)",
                                b.getType().name(), distToBlock, distance);

                        return CheckResult.flag("WallHit", 0.98, 3.5, explanation, details);
                    }
                }
            }
        }

        // -------------------------------------------------------------
        // 3. Crosshair Ray AABB Intersection (Silent Aim detection), rewound to the hitbox the
        // attacker's own client actually rendered (latency-compensated) rather than the live
        // server-side box - the same rewind logic Reach/BackTrack use, so a legitimate hit on a
        // moving target under real ping is never judged against where it already moved to.
        // -------------------------------------------------------------
        double transactionRtt = transactionManager != null ? transactionManager.getTransactionPing(attacker.getUniqueId()) : -1;
        int effectivePing = transactionRtt >= 0 ? (int) Math.round(transactionRtt) : Math.max(0, attacker.getPing());
        double pingThreshold = config != null ? config.getKillauraPingBufferThresholdMs() : 120.0;
        double pingBufferHigh = config != null ? config.getKillauraPingBufferHigh() : 0.20;
        double pingBufferLow = config != null ? config.getKillauraPingBufferLow() : 0.08;
        double pingBuffer = (effectivePing > pingThreshold) ? pingBufferHigh : pingBufferLow;
        double hitboxExpand = config != null ? config.getKillauraHitboxExpand() : 0.28;

        BoundingBox rewoundBox = liveBox;
        if (hitboxTracker != null && target instanceof Player targetPlayer) {
            HitboxHistoryTracker.BoxSnapshot snap = hitboxTracker.getRewoundBox(targetPlayer, effectivePing);
            rewoundBox = new BoundingBox(snap.minX(), snap.minY(), snap.minZ(), snap.maxX(), snap.maxY(), snap.maxZ());
        }
        BoundingBox targetBox = rewoundBox.clone().expand(hitboxExpand + pingBuffer);
        RayTraceResult crosshairHit = targetBox.rayTrace(eyeLoc.toVector(), eyeDir, 6.0);

        double silentAimAngle = scaleThresholdUp(config != null ? config.getKillauraSilentAimAngleDegrees() : 45.0, sensitivity);
        if (crosshairHit == null && effectiveAngle > silentAimAngle) {
            Map<String, Object> details = new HashMap<>();
            details.put("angle", effectiveAngle);
            details.put("target", target.getName() != null ? target.getName() : target.getType().name());
            details.put("ping", effectivePing);
            details.put("transactionRttMs", transactionRtt);

            String explanation = String.format(Locale.US,
                    "Crosshair completely off rewound target hitbox (Angle: %.1f°, Ping: %dms)",
                    effectiveAngle, effectivePing);

            return CheckResult.flag("SilentAim", 0.94, 2.5, explanation, details);
        }

        // -------------------------------------------------------------
        // 4. Absolute Peripheral FOV Limit (360 Killaura), on the multi-point effective angle so a
        // legitimate edge-of-hitbox hit is judged fairly.
        // -------------------------------------------------------------
        if (effectiveAngle > fovLimit) {
            Map<String, Object> details = new HashMap<>();
            details.put("angle", effectiveAngle);
            details.put("centerAngle", angleDegrees);
            details.put("target", target.getName() != null ? target.getName() : target.getType().name());
            details.put("fovLimit", fovLimit);

            String explanation = String.format(Locale.US,
                    "Attack outside field of view (Angle: %.1f°, Max: %.1f°)", effectiveAngle, fovLimit);

            data.resetPerfectAimStreak();
            data.resetAimConsistencyStreak();
            return CheckResult.flag("KillauraAngle", 0.96, 3.0, explanation, details);
        }

        long nowNanos = System.nanoTime();
        float currentYaw = attacker.getLocation().getYaw();
        float currentPitch = attacker.getLocation().getPitch();
        float requiredYaw = requiredYaw(toCenter.getX(), toCenter.getZ());
        float requiredPitch = requiredPitch(toCenter.getX(), toCenter.getY(), toCenter.getZ());

        // -------------------------------------------------------------
        // 5. Multi-target switch analysis: attacking a DIFFERENT entity than the previous attack,
        // shortly after it, with barely any change in the attacker's own look direction despite the
        // two targets requiring meaningfully different aim - the signature of an aura that snaps
        // its internal target reference without generating the mouse input a human retargeting
        // would need.
        // -------------------------------------------------------------
        CheckResult switchResult = checkTargetSwitch(data, target, currentYaw, currentPitch, requiredYaw, requiredPitch, nowNanos);
        if (switchResult != null) {
            data.setLastAttackTarget(target.getEntityId(), nowNanos);
            data.setLastAttackSnapshot(currentYaw, currentPitch, requiredYaw, requiredPitch);
            return switchResult;
        }

        // -------------------------------------------------------------
        // 6. Reaction-time check: the attack lands implausibly soon after the attacker's camera
        // last made a meaningful turn - either no visible acquisition turn at all, or one so close
        // to the click that no human decision time separates them. Gated on a real angular
        // requirement existing (the target isn't already dead-center) so a player who was already
        // looking at the target is never penalised for reacting "instantly".
        // -------------------------------------------------------------
        CheckResult reactionResult = checkReactionTime(data, effectiveAngle, nowNanos);
        if (reactionResult != null) {
            data.setLastAttackTarget(target.getEntityId(), nowNanos);
            data.setLastAttackSnapshot(currentYaw, currentPitch, requiredYaw, requiredPitch);
            return reactionResult;
        }

        // -------------------------------------------------------------
        // 7. Perfect-Aim Streak: many consecutive hits landing dead-center on the hitbox
        // (sub-degree crosshair error) is not humanly sustainable across a real fight -
        // catches basic/free KillAura variants that snap-lock exactly onto the target's
        // center every single tick instead of the natural jitter of manual tracking.
        // -------------------------------------------------------------
        double perfectAimAngle = config != null ? config.getKillauraPerfectAimAngleDegrees() : 0.6;
        double perfectAimThreshold = config != null ? config.getKillauraPerfectAimStreak() : 6;
        DecayingEvidence perfectAimEvidence = data.getPerfectAimEvidence();
        if (effectiveAngle < perfectAimAngle) {
            double score = perfectAimEvidence.reward(1.0, nowNanos, evidenceHalfLifeMs());
            if (score >= perfectAimThreshold && perfectAimEvidence.events() >= evidenceMinEvents()) {
                Map<String, Object> details = new HashMap<>();
                details.put("angle", effectiveAngle);
                details.put("score", score);
                details.put("threshold", perfectAimThreshold);
                details.put("hitsEvaluated", perfectAimEvidence.events());
                details.put("target", target.getName() != null ? target.getName() : target.getType().name());

                String explanation = String.format(Locale.US,
                        "Inhuman aim-lock precision: %.1f evidence over %d hits (threshold %.1f, Angle: %.3f°)",
                        score, perfectAimEvidence.events(), perfectAimThreshold, effectiveAngle);

                perfectAimEvidence.reset();
                data.setLastAttackTarget(target.getEntityId(), nowNanos);
                data.setLastAttackSnapshot(currentYaw, currentPitch, requiredYaw, requiredPitch);
                return CheckResult.flag("PerfectAimLock", confidenceFor(0.89, score, perfectAimThreshold), 2.0, explanation, details);
            }
        } else {
            perfectAimEvidence.relieve(evidenceCleanRelief(), nowNanos, evidenceHalfLifeMs());
        }

        // -------------------------------------------------------------
        // 8. Aim Consistency layer: compares the required-aim error against the natural jitter the
        // SAME player's own AimRingBuffer shows during ordinary look movement. A human's error
        // never sits meaningfully below their own demonstrated hand jitter for long - a killaura
        // that recalculates a slightly-humanized offset each tick still tends to average tighter
        // than the player's real mouse noise floor across many hits. Distinct from PerfectAimLock
        // (fixed absolute angle) in that the bar is calibrated per-player.
        // -------------------------------------------------------------
        CheckResult consistencyResult = checkAimConsistency(data, effectiveAngle, target, nowNanos);
        if (consistencyResult != null) {
            data.setLastAttackTarget(target.getEntityId(), nowNanos);
            data.setLastAttackSnapshot(currentYaw, currentPitch, requiredYaw, requiredPitch);
            return consistencyResult;
        }

        // -------------------------------------------------------------
        // 9. Hit-Rotation Consistency ("StaticAimTracking"): a real-world gap discovered while
        // testing a killaura that never had to turn at all, because both players stood still -
        // none of the rotation-delta checks above (or GCDAim/StatisticalAim) can see anything
        // wrong there, since a genuinely stationary target legitimately needs zero rotation.
        // This check instead asks: across two consecutive attacks, did the aim direction the
        // target ACTUALLY required change meaningfully (i.e. the target moved relative to the
        // attacker), while the attacker's OWN look yaw/pitch stayed essentially frozen, and they
        // still landed a clean hit? A human tracking a moving target with a truly unmoving
        // camera can't keep hitting it - that combination is the signature of aim/target
        // assistance that recalculates the correct angle without generating the mouse input a
        // real player tracking movement would produce. Mirrors the "hit-rotation check" technique
        // used by GrimAC/Vulcan-class anticheats.
        // -------------------------------------------------------------
        double staticTargetDelta = config != null ? config.getKillauraStaticTrackingTargetDeltaDegrees() : 4.0;
        double staticPlayerDelta = config != null ? config.getKillauraStaticTrackingPlayerDeltaDegrees() : 0.5;
        double staticMaxAngle = config != null ? config.getKillauraStaticTrackingMaxAngleDegrees() : 10.0;
        double staticThreshold = config != null ? config.getKillauraStaticTrackingStreak() : 3;

        if (data.hasLastAttackSnapshot()) {
            double targetAngularDelta = angularDiff(data.getLastAttackRequiredYaw(), requiredYaw)
                    + angularDiff(data.getLastAttackRequiredPitch(), requiredPitch);
            double playerAngularDelta = angularDiff(data.getLastAttackYaw(), currentYaw)
                    + angularDiff(data.getLastAttackPitch(), currentPitch);
            DecayingEvidence staticEvidence = data.getStaticTrackingEvidence();

            if (targetAngularDelta > staticTargetDelta && playerAngularDelta < staticPlayerDelta && effectiveAngle < staticMaxAngle) {
                double score = staticEvidence.reward(1.0, nowNanos, evidenceHalfLifeMs());
                if (score >= staticThreshold && staticEvidence.events() >= evidenceMinEvents()) {
                    Map<String, Object> details = new HashMap<>();
                    details.put("targetAngularDelta", targetAngularDelta);
                    details.put("playerAngularDelta", playerAngularDelta);
                    details.put("angle", effectiveAngle);
                    details.put("score", score);
                    details.put("threshold", staticThreshold);
                    details.put("hitsEvaluated", staticEvidence.events());

                    String explanation = String.format(Locale.US,
                            "Tracked a moving target (Δ%.1f°) with a frozen camera (Δ%.2f°) while still hitting (Angle: %.1f°, evidence %.1f/%.1f)",
                            targetAngularDelta, playerAngularDelta, effectiveAngle, score, staticThreshold);

                    staticEvidence.reset();
                    data.setLastAttackTarget(target.getEntityId(), nowNanos);
                    data.setLastAttackSnapshot(currentYaw, currentPitch, requiredYaw, requiredPitch);
                    return CheckResult.flag("StaticAimTracking", confidenceFor(0.90, score, staticThreshold), 2.3, explanation, details);
                }
            } else {
                staticEvidence.relieve(evidenceCleanRelief(), nowNanos, evidenceHalfLifeMs());
            }
        }
        data.setLastAttackTarget(target.getEntityId(), nowNanos);
        data.setLastAttackSnapshot(currentYaw, currentPitch, requiredYaw, requiredPitch);

        return CheckResult.pass("KillauraAngle");
    }

    /**
     * Compares the aim error the target switch produced against the attacker's own look-delta.
     * Returns {@code null} when there is nothing to judge (no previous attack, same target, or
     * outside the switch window) rather than a passing {@link CheckResult}, so the caller can tell
     * "not applicable" apart from "evaluated and clean".
     */
    private CheckResult checkTargetSwitch(UserData data, Entity target, float currentYaw, float currentPitch,
                                           float requiredYaw, float requiredPitch, long nowNanos) {
        if (config == null || !data.hasLastAttackSnapshot()) return null;
        int lastTargetId = data.getLastAttackTargetId();
        if (lastTargetId < 0 || lastTargetId == target.getEntityId()) return null;

        double windowMs = config.getKillauraTargetSwitchWindowMs();
        double sinceLastAttackMs = (nowNanos - data.getLastAttackNanos()) / 1_000_000.0;
        if (sinceLastAttackMs > windowMs) return null;

        double requiredDelta = angularDiff(data.getLastAttackRequiredYaw(), requiredYaw)
                + angularDiff(data.getLastAttackRequiredPitch(), requiredPitch);
        double lookDelta = angularDiff(data.getLastAttackYaw(), currentYaw)
                + angularDiff(data.getLastAttackPitch(), currentPitch);

        double minRequiredDelta = config.getKillauraTargetSwitchMinRequiredDeltaDegrees();
        double maxLookDelta = config.getKillauraTargetSwitchMaxLookDeltaDegrees();

        DecayingEvidence evidence = data.getTargetSwitchEvidence();
        if (requiredDelta > minRequiredDelta && lookDelta < maxLookDelta) {
            double threshold = config.getKillauraTargetSwitchStreak();
            double score = evidence.reward(1.0, nowNanos, evidenceHalfLifeMs());
            if (score >= threshold && evidence.events() >= evidenceMinEvents()) {
                Map<String, Object> details = new HashMap<>();
                details.put("requiredDelta", requiredDelta);
                details.put("lookDelta", lookDelta);
                details.put("score", score);
                details.put("threshold", threshold);
                details.put("switchesEvaluated", evidence.events());
                details.put("sinceLastAttackMs", sinceLastAttackMs);

                String explanation = String.format(Locale.US,
                        "Switched attack target (required Δ%.1f°) with almost no look movement (Δ%.2f°) within %.0fms, evidence %.1f/%.1f",
                        requiredDelta, lookDelta, sinceLastAttackMs, score, threshold);

                evidence.reset();
                return CheckResult.flag("TargetSwitch", confidenceFor(0.91, score, threshold), 2.4, explanation, details);
            }
        } else {
            evidence.relieve(evidenceCleanRelief(), nowNanos, evidenceHalfLifeMs());
        }
        return null;
    }

    /**
     * Compares the time since the attacker's camera last made a "real" turn against the attack
     * timestamp. Only evaluated when the target actually required a meaningful turn to acquire
     * (via {@code effectiveAngle} being past a small floor) so a player already looking at the
     * target is never penalised for a fast, honest click.
     */
    private CheckResult checkReactionTime(UserData data, double effectiveAngle, long nowNanos) {
        if (config == null) return null;
        double significantFloor = config.getKillauraReactionSignificantRotationDegrees();
        if (effectiveAngle < significantFloor || data.getLastSignificantRotationNanos() == 0L) return null;

        double reactionMs = (nowNanos - data.getLastSignificantRotationNanos()) / 1_000_000.0;
        double minReactionMs = config.getKillauraReactionMinMs();

        // Negative/absurd values mean the "last significant rotation" timestamp predates this
        // combat encounter entirely (stale from a much earlier fight) - not evidence of anything.
        if (reactionMs < 0 || reactionMs > 5000.0) return null;

        DecayingEvidence evidence = data.getReactionTimeEvidence();
        if (reactionMs < minReactionMs) {
            double threshold = config.getKillauraReactionStreak();
            double score = evidence.reward(1.0, nowNanos, evidenceHalfLifeMs());
            if (score >= threshold && evidence.events() >= evidenceMinEvents()) {
                Map<String, Object> details = new HashMap<>();
                details.put("reactionMs", reactionMs);
                details.put("angle", effectiveAngle);
                details.put("score", score);
                details.put("threshold", threshold);
                details.put("attacksEvaluated", evidence.events());

                String explanation = String.format(Locale.US,
                        "Attack landed %.0fms after last meaningful turn (min human ~%.0fms), evidence %.1f/%.1f over %d attacks",
                        reactionMs, minReactionMs, score, threshold, evidence.events());

                evidence.reset();
                return CheckResult.flag("ReactionTime", confidenceFor(0.87, score, threshold), 2.0, explanation, details);
            }
        } else {
            evidence.relieve(evidenceCleanRelief(), nowNanos, evidenceHalfLifeMs());
        }
        return null;
    }

    /**
     * Compares the required-aim error against this player's own recent mouse-jitter floor (from
     * {@link net.lovelace.vesuvio.data.AimRingBuffer} via {@link AimFeatureExtractor}), rather than
     * a single fixed angle - see the call site's comment for why that is a meaningfully different
     * signal from PerfectAimLock.
     */
    private CheckResult checkAimConsistency(UserData data, double effectiveAngle, Entity target, long nowNanos) {
        if (config == null) return null;
        if (!data.getAimBuffer().isFull()) return null;

        float[] aimFeatures = AimFeatureExtractor.extract(data.getAimBuffer());
        double yawStd = aimFeatures[2];
        double pitchStd = aimFeatures[3];
        double jitterFloor = Math.max(config.getKillauraAimConsistencyMinJitterDegrees(), Math.min(yawStd, pitchStd) * 0.5);
        double maxError = config.getKillauraAimConsistencyMaxErrorDegrees();

        DecayingEvidence evidence = data.getAimConsistencyEvidence();
        if (effectiveAngle < Math.min(maxError, jitterFloor)) {
            double threshold = config.getKillauraAimConsistencyStreak();
            double score = evidence.reward(1.0, nowNanos, evidenceHalfLifeMs());
            if (score >= threshold && evidence.events() >= evidenceMinEvents()) {
                Map<String, Object> details = new HashMap<>();
                details.put("angle", effectiveAngle);
                details.put("jitterFloor", jitterFloor);
                details.put("yawStd", yawStd);
                details.put("pitchStd", pitchStd);
                details.put("score", score);
                details.put("threshold", threshold);
                details.put("hitsEvaluated", evidence.events());
                details.put("target", target.getName() != null ? target.getName() : target.getType().name());

                String explanation = String.format(Locale.US,
                        "Aim error (%.2f°) keeps landing under this player's own mouse-jitter floor (%.2f°): evidence %.1f/%.1f over %d hits",
                        effectiveAngle, jitterFloor, score, threshold, evidence.events());

                evidence.reset();
                return CheckResult.flag("AimConsistency", confidenceFor(0.86, score, threshold), 1.8, explanation, details);
            }
        } else {
            evidence.relieve(evidenceCleanRelief(), nowNanos, evidenceHalfLifeMs());
        }
        return null;
    }

    /** Half-life of accumulated aim evidence - see {@link DecayingEvidence} and config.yml. */
    private double evidenceHalfLifeMs() {
        return config != null ? config.getKillauraEvidenceHalfLifeMs() : 4000.0;
    }

    /** How much weight one clean hit refunds. Deliberately a partial refund, never a reset. */
    private double evidenceCleanRelief() {
        return config != null ? config.getKillauraEvidenceCleanRelief() : 0.5;
    }

    /**
     * Minimum number of evaluated hits before any accumulator may produce a verdict. Without this
     * floor a player whose first few hits happen to look tight could reach the threshold on pure
     * chance, which is exactly the false positive the accumulator is meant to avoid.
     */
    private int evidenceMinEvents() {
        return config != null ? config.getKillauraEvidenceMinEvents() : 4;
    }

    /**
     * Nudges confidence up when the accumulator blew well past its threshold rather than creeping
     * over it, capped so an accumulator flag never reads as more certain than a hard geometric one.
     */
    private static double confidenceFor(double base, double score, double threshold) {
        if (threshold <= 0) return base;
        double overshoot = Math.max(0.0, (score - threshold) / threshold);
        return Math.min(base + 0.06, base + overshoot * 0.06);
    }

    /** Scales an "flag if metric > threshold"-style threshold down as sensitivity rises above 1.0. */
    private static double scaleThresholdUp(double baseThreshold, double sensitivity) {
        if (sensitivity <= 0) return baseThreshold;
        return baseThreshold / sensitivity;
    }

    /** Smallest angle (degrees) between {@code dir} and the ray from {@code eye} to any of several sample points on {@code box}. */
    private static double minAngleToBox(Location eye, Vector dir, BoundingBox box) {
        double eyeX = eye.getX(), eyeY = eye.getY(), eyeZ = eye.getZ();
        double cx = (box.getMinX() + box.getMaxX()) / 2.0;
        double cz = (box.getMinZ() + box.getMaxZ()) / 2.0;
        double centerY = (box.getMinY() + box.getMaxY()) / 2.0;
        double headY = box.getMinY() + (box.getMaxY() - box.getMinY()) * 0.85;
        double feetY = box.getMinY() + Math.min(0.1, (box.getMaxY() - box.getMinY()) * 0.1);
        double nearestX = clamp(eyeX, box.getMinX(), box.getMaxX());
        double nearestY = clamp(eyeY, box.getMinY(), box.getMaxY());
        double nearestZ = clamp(eyeZ, box.getMinZ(), box.getMaxZ());

        double minAngle = Double.MAX_VALUE;
        double[][] points = {
                {cx, centerY, cz},
                {cx, headY, cz},
                {cx, feetY, cz},
                {nearestX, nearestY, nearestZ}
        };
        for (double[] p : points) {
            Vector to = new Vector(p[0] - eyeX, p[1] - eyeY, p[2] - eyeZ);
            if (to.lengthSquared() < 1e-6) continue;
            double angle = angleTo(dir, to.normalize());
            if (angle < minAngle) minAngle = angle;
        }
        return minAngle == Double.MAX_VALUE ? 180.0 : minAngle;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double angleTo(Vector a, Vector bNormalized) {
        double dot = Math.max(-1.0, Math.min(1.0, a.dot(bNormalized)));
        return Math.toDegrees(Math.acos(dot));
    }

    /** Shortest angular distance between two degree values, wrapped to [0, 180]. */
    public static double angularDiff(float a, float b) {
        double raw = Math.abs(a - b) % 360.0;
        return raw > 180.0 ? 360.0 - raw : raw;
    }

    /**
     * Yaw (degrees) that would face a direction vector (dx, dy, dz) dead-on, matching the exact
     * convention CraftBukkit's own Location#setDirection uses (verified against its source):
     * yaw = atan2(-dx, dz).
     */
    public static float requiredYaw(double dx, double dz) {
        if (dx == 0 && dz == 0) return 0f;
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** Pitch (degrees) that would face a direction vector dead-on: pitch = atan2(-dy, hypot(dx,dz)). */
    public static float requiredPitch(double dx, double dy, double dz) {
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (dx == 0 && dz == 0) {
            return dy > 0 ? -90f : 90f;
        }
        return (float) Math.toDegrees(Math.atan2(-dy, horizontal));
    }
}
