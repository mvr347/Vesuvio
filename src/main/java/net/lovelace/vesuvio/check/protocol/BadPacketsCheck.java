package net.lovelace.vesuvio.check.protocol;

import net.lovelace.vesuvio.check.CheckResult;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Protocol & State Guard (inspired by Themis & GrimAC).
 * Performs zero-overhead packet validation checks:
 * 1. NoSwing (Attacking without swinging arm)
 * 2. PitchBounds (Impossible pitch exceeding [-90°, 90°])
 * 3. InventoryAttack (Attacking while container/inventory is open)
 *
 * Author: Lovelace
 */
public final class BadPacketsCheck {

    public CheckResult checkNoSwing(long lastSwingNanos) {
        long delta = System.nanoTime() - lastSwingNanos;
        // If arm was not swung within the last 150ms
        if (lastSwingNanos == 0 || delta > 150_000_000L) {
            Map<String, Object> details = new HashMap<>();
            details.put("lastSwingDeltaMs", delta / 1_000_000.0);
            return CheckResult.flag("BadPackets", 0.99, 3.0,
                    String.format("Attacked without arm animation (NoSwing, Δ: %.1fms)", delta / 1_000_000.0), details);
        }
        return CheckResult.pass("BadPackets");
    }

    public CheckResult checkPitch(float pitch) {
        if (Math.abs(pitch) > 90.05f) {
            Map<String, Object> details = Collections.singletonMap("pitch", pitch);
            return CheckResult.flag("BadPackets", 1.0, 4.0,
                    String.format("Impossible pitch angle exceeding limits (Pitch: %.2f°)", pitch), details);
        }
        return CheckResult.pass("BadPackets");
    }

    /**
     * Reads the open container from the main-thread environment snapshot rather than calling
     * {@code Player#getOpenInventory()} here: this runs on a virtual thread, where that call can
     * observe a view mid-swap or race the main thread's own inventory handling.
     */
    public CheckResult checkInventoryAttack(net.lovelace.vesuvio.data.UserData data) {
        if (data == null) return CheckResult.pass("BadPackets");

        net.lovelace.vesuvio.engine.EnvironmentSnapshot env = data.getEnvironment();
        if (!env.isFresh(System.currentTimeMillis(), 500L)) return CheckResult.pass("BadPackets");

        if (env.containerOpen()) {
            Map<String, Object> details = Collections.singletonMap("containerType", env.openInventoryType());
            return CheckResult.flag("BadPackets", 0.95, 2.5,
                    "Attacked entity while container/inventory was open", details);
        }

        return CheckResult.pass("BadPackets");
    }
}
