package net.lovelace.vesuvio.check.combat;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.engine.EnvironmentSnapshot;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Detects CheatUtils Auto Criticals exploit.
 * Cheat clients send micro-hops (ΔY: 0.01 - 0.08) right before attack packets
 * to trick Minecraft's critical hit calculation without actually jumping.
 *
 * <p>The micro-hop band is scaled off the player's live jump-strength attribute rather than a
 * flat 0.09 ceiling: a genuine jump's first-tick ΔY sits very close to that attribute's value
 * (vanilla 0.42, or whatever an item/plugin has raised or lowered it to), while an auto-crit
 * micro-hop is always a small fraction of it. A flat ceiling tuned for the vanilla 0.42 default
 * would misjudge a player whose gear legitimately lowers their jump strength - a real, if
 * unusually short, jump for them could land inside the vanilla-tuned exploit band. Scaling the
 * band by their own attribute keeps the exploit window a fraction of what THIS player's jump
 * actually looks like, in either direction.
 *
 * <p>Player state comes from the main-thread {@link EnvironmentSnapshot}; this check runs on a
 * virtual thread and must not query Bukkit itself.
 *
 * Author: Lovelace
 */
public final class AutoCriticalsCheck {

    /**
     * Fraction of the player's jump strength below which a first-tick hop is "too small to be a
     * real jump". 0.09 / 0.42 ≈ 0.214, so this reproduces the plugin's original vanilla-tuned
     * behaviour exactly while adapting correctly to a raised or lowered attribute.
     */
    private static final double MICRO_HOP_RATIO = 0.214;

    // Evidence instead of a single event (2026-11-11): three micro-hop attacks within about half a minute.
    private static final double EVIDENCE_THRESHOLD = 2.7; // below 3.0: three events always decay a little
    private static final int EVIDENCE_MIN_EVENTS = 3;
    private static final double EVIDENCE_HALF_LIFE_MS = 30_000.0;

    public CheckResult check(UserData data) {
        if (data == null) return CheckResult.pass("AutoCriticals");

        EnvironmentSnapshot env = data.getEnvironment();
        if (!env.isFresh(System.currentTimeMillis(), 500L)) return CheckResult.pass("AutoCriticals");

        if (env.isMovementExempt()) {
            return CheckResult.pass("AutoCriticals");
        }
        if (env.inWater() || env.inLava() || env.swimming()) {
            return CheckResult.pass("AutoCriticals");
        }
        if (data.hasRecentVelocity() || env.blindness()) {
            return CheckResult.pass("AutoCriticals");
        }
        // Ladders, vines, scaffolding, honey and the like move the player by fractions of a block without a jump.
        if (env.nearClimbable() || env.climbing()) {
            return CheckResult.pass("AutoCriticals");
        }
        // A micro-hop that claims onGround=true is a step onto a carpet, a path block, farmland or a snow layer
        // (+0.0625 / +0.1), which the vanilla client reports as ground. The exploit sends onGround=false to get the crit.
        if (data.isLastOnGround()) {
            return CheckResult.pass("AutoCriticals");
        }

        double deltaY = data.getLastDeltaY();
        int airTicks = data.getAirTicks();
        float fallDist = env.fallDistance();
        double jumpStrength = env.jumpStrength() > 0 ? env.jumpStrength() : 0.42;
        double microHopCeiling = jumpStrength * MICRO_HOP_RATIO;

        // Normal jump has initial velocity ~= the player's jump-strength attribute.
        // Auto-crits send micro-hops: a tiny fraction of that, with 0 fall distance or <= 1 air tick
        if (deltaY > 0.005 && deltaY < microHopCeiling && airTicks <= 1 && fallDist < 0.1f) {
            // One micro-hop before an attack can still be an odd terrain step: three such attacks are the exploit.
            long now = System.nanoTime();
            double score = data.getAutoCritEvidence().reward(1.0, now, EVIDENCE_HALF_LIFE_MS);
            if (score < EVIDENCE_THRESHOLD || data.getAutoCritEvidence().events() < EVIDENCE_MIN_EVENTS) {
                return CheckResult.pass("AutoCriticals");
            }
            data.getAutoCritEvidence().reset();
            Map<String, Object> details = new HashMap<>();
            details.put("deltaY", deltaY);
            details.put("airTicks", airTicks);
            details.put("fallDistance", fallDist);
            details.put("microHopCeiling", microHopCeiling);

            return CheckResult.flag(
                    "AutoCriticals",
                    0.94,
                    5.0,
                    String.format(Locale.US, "Критический удар через пакетный микропрыжок (ΔY: %.4f блока, в воздухе: %d тактов)", deltaY, airTicks),
                    details
            );
        }

        return CheckResult.pass("AutoCriticals");
    }
}
