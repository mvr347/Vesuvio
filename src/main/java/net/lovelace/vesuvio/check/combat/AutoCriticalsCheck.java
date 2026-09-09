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
 * <p>Player state comes from the main-thread {@link EnvironmentSnapshot}; this check runs on a
 * virtual thread and must not query Bukkit itself.
 *
 * Author: Lovelace
 */
public final class AutoCriticalsCheck {

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

        double deltaY = data.getLastDeltaY();
        int airTicks = data.getAirTicks();
        float fallDist = env.fallDistance();

        // Normal jump has initial velocity ~0.42 b/t.
        // Auto-crits send micro-hops: 0.01 <= deltaY <= 0.0825 with 0 fall distance or <= 1 air tick
        if (deltaY > 0.005 && deltaY < 0.09 && airTicks <= 1 && fallDist < 0.1f) {
            Map<String, Object> details = new HashMap<>();
            details.put("deltaY", deltaY);
            details.put("airTicks", airTicks);
            details.put("fallDistance", fallDist);

            return CheckResult.flag(
                    "AutoCriticals",
                    0.94,
                    14.0,
                    String.format(Locale.US, "Packet micro-hop crit exploit (ΔY: %.4fb, airTicks: %d)", deltaY, airTicks),
                    details
            );
        }

        return CheckResult.pass("AutoCriticals");
    }
}
