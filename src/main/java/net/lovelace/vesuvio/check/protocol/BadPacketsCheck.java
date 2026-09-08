package net.lovelace.vesuvio.check.protocol;

import net.lovelace.vesuvio.check.CheckResult;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;

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

    public CheckResult checkInventoryAttack(Player player) {
        if (player == null) return CheckResult.pass("BadPackets");

        try {
            var openInv = player.getOpenInventory();
            if (openInv != null && openInv.getType() != InventoryType.CRAFTING) {
                Map<String, Object> details = Collections.singletonMap("containerType", openInv.getType().name());
                return CheckResult.flag("BadPackets", 0.95, 2.5,
                        "Attacked entity while container/inventory was open", details);
            }
        } catch (Throwable ignored) {}

        return CheckResult.pass("BadPackets");
    }
}
