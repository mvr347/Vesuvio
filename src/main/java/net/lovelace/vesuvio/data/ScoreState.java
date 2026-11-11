package net.lovelace.vesuvio.data;

import net.lovelace.vesuvio.integration.essential.MechanicsState;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

/** Violation level, trust, risk, sensitivity and the rate limits that decide when a flag turns into an alert or a punishment. Moved out of UserData unchanged; UserData delegates. */
final class ScoreState {

    // Violation Level (VL)
    double vl = 0.0;

    final AtomicLong lastViolationTime = new AtomicLong(System.currentTimeMillis());

    // Trust (0 - 100) & Risk (0 - 100). Not volatile: all reads/writes go through synchronized
    // accessors below so concurrent adjustTrust/adjustRisk calls (many can fire per tick across
    // click/aim/movement checks running on different virtual threads) never lose an update.
    double trustScore = 50.0;

    double riskIndex = 10.0;

    volatile String lastTriggeredCheck = "None";

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
        if (delta > 0) lastRiskRaiseMillis = System.currentTimeMillis();
    }

    long lastRiskRaiseMillis = System.currentTimeMillis();

    long lastCleanDecayMillis = System.currentTimeMillis();

    /**
     * Passive recovery for a player who has not been flagged for {@code cleanMillis}: risk drops by
     * {@code riskPerMinute} down to {@code riskFloor}, trust grows by 1.0 per {@code trustHours} of clean time.
     * Without this the risk only ever grew and trust only ever fell, which tightened every threshold
     * through {@link #getSensitivityMultiplier()} and produced yet more flags.
     */
    public synchronized void applyCleanDecay(long nowMillis, long cleanMillis, double riskPerMinute,
                                             double riskFloor, double trustHours) {
        long elapsed = Math.max(0L, nowMillis - lastCleanDecayMillis);
        lastCleanDecayMillis = nowMillis;
        long lastBad = Math.max(lastViolationTime.get(), lastRiskRaiseMillis);
        if (nowMillis - lastBad < cleanMillis) return;
        // Only the time past the clean threshold counts, otherwise the first tick after it would back-pay it.
        long counted = Math.min(elapsed, nowMillis - lastBad - cleanMillis);
        if (counted <= 0) return;
        double minutes = counted / 60_000.0;
        if (riskIndex > riskFloor) {
            riskIndex = Math.max(riskFloor, riskIndex - riskPerMinute * minutes);
        }
        if (trustHours > 0) {
            trustScore = Math.min(100.0, trustScore + minutes / (trustHours * 60.0));
        }
    }

    final java.util.concurrent.ConcurrentHashMap<String, Long> lastAlertMillis = new java.util.concurrent.ConcurrentHashMap<>();

    /** Rate limit of staff chat and Discord alerts per check (the database record is never limited). */
    public boolean tryAlert(String check, long nowMillis, long intervalMillis) {
        if (intervalMillis <= 0) return true;
        final boolean[] allowed = {false};
        lastAlertMillis.compute(check, (k, last) -> {
            if (last == null || nowMillis - last >= intervalMillis || nowMillis < last) {
                allowed[0] = true;
                return nowMillis;
            }
            return last;
        });
        return allowed[0];
    }

    final java.util.concurrent.ConcurrentHashMap<String, Long> lastReflagMillis = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Rate limit for window-based checks: they re-evaluate the same 64-sample window on every packet, so
     * one real anomaly would otherwise flag 10-20 times a second. True when a flag for {@code check} may
     * be reported now; records the time when it does.
     */
    public boolean tryReflag(String check, long nowMillis, long intervalMillis) {
        if (intervalMillis <= 0) return true;
        final boolean[] allowed = {false};
        lastReflagMillis.compute(check, (k, last) -> {
            if (last == null || nowMillis - last >= intervalMillis || nowMillis < last) {
                allowed[0] = true;
                return nowMillis;
            }
            return last;
        });
        return allowed[0];
    }

    // Manual Suspect flag (persists across reboots and reconnects)
    volatile boolean manualSuspect = false;

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
        return Math.max(0.6, Math.min(sensitivityCap, sensitivity));
    }

    static volatile double sensitivityCap = 1.3;

    /** Upper bound of {@link #getSensitivityMultiplier()} (scoring.sensitivity.max-multiplier). */
    public static void setSensitivityCap(double cap) {
        sensitivityCap = Math.max(1.0, cap);
    }

    public String getLastTriggeredCheck() {
        return lastTriggeredCheck;
    }

    public void setLastTriggeredCheck(String lastTriggeredCheck) {
        this.lastTriggeredCheck = lastTriggeredCheck;
    }

    final java.util.concurrent.atomic.AtomicBoolean punished = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Per-session latch: true exactly once, so the (non-wave) punishment command is dispatched a
     * single time instead of on every flag once VL stays over the threshold. UserData is created
     * per join, so a new session starts unlatched.
     */
    public boolean tryLatchPunished() { return punished.compareAndSet(false, true); }

    public boolean isPunished() { return punished.get(); }

    public void resetPunishedLatch() { punished.set(false); }

    long lastPunishmentNanos;

    public synchronized boolean tryPunishment(long now) {
        if (lastPunishmentNanos != 0 && now - lastPunishmentNanos < 1_000_000_000L) return false;
        lastPunishmentNanos = now;
        return true;
    }
}
