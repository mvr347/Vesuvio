package net.lovelace.vesuvio.check.movement;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.data.UserData;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;

/**
 * Advanced Movement: Game Clock Acceleration (Timer) Detection.
 * Counts flying packets per second to detect speeded-up client simulation.
 *
 * Author: Lovelace
 */
public final class TimerCheck {

    private static final long WINDOW_NANOS = 1_000_000_000L; // 1 second

    public CheckResult check(Player player, UserData data) {
        if (data == null) return CheckResult.pass("Timer");

        long now = System.nanoTime();
        long windowStart = data.getTimerWindowStartNanos();

        data.incrementTimerPacketCount();

        if (now - windowStart >= WINDOW_NANOS) {
            int packets = data.getTimerPacketCount();
            data.setTimerPacketCount(0);
            data.setTimerWindowStartNanos(now);

            // Normal tick rate is 20 pps. Buffer to 24 pps to absorb network lag flushes.
            int maxAllowed = 24;

            if (packets > maxAllowed) {
                double confidence = Math.min(0.99, 0.70 + (packets - maxAllowed) * 0.05);
                double vl = Math.max(1.0, (packets - maxAllowed) * 1.5);

                Map<String, Object> details = new HashMap<>();
                details.put("packetsPerSec", packets);
                details.put("maxAllowed", maxAllowed);

                return CheckResult.flag("Timer", confidence, vl,
                        String.format("Accelerated game clock (Packets: %d/s, Max: %d/s)", packets, maxAllowed),
                        details);
            }
        }

        return CheckResult.pass("Timer");
    }
}
