package net.lovelace.vesuvio;

import net.lovelace.vesuvio.engine.EnvironmentSnapshot;
import org.bukkit.Material;

/**
 * Builders for {@link EnvironmentSnapshot} values used across the check tests.
 *
 * <p>The snapshot is a wide record by design - it is the single hand-off between the main thread
 * and the async checks - but that makes its positional constructor miserable to spell out in every
 * test, and a silent hazard: inserting a field shifts every argument after it, and adjacent
 * booleans mean the compiler will not always catch the mistake. Everything the tests need goes
 * through here instead, so adding a field to the snapshot is a one-line change in this file rather
 * than a scavenger hunt through the suite.
 */
final class TestSnapshots {

    private TestSnapshots() {}

    /** A plain survival player, sprinting on solid ground, with nothing exempting them. */
    static EnvironmentSnapshot ground(Material below) {
        return builder().blockBelow(below).build();
    }

    static Builder builder() {
        return new Builder();
    }

    static final class Builder {
        private Material blockBelow = Material.GRASS_BLOCK;
        private boolean solidBelow = true;
        private boolean nearClimbable = false;
        private boolean insideSolidBlock = false;
        private boolean sprinting = true;
        private boolean jumpBoost = false;
        private double jumpStrength = 0.42; // vanilla Attribute.JUMP_STRENGTH default

        Builder blockBelow(Material m) { this.blockBelow = m; return this; }
        Builder solidBelow(boolean v) { this.solidBelow = v; return this; }
        Builder nearClimbable(boolean v) { this.nearClimbable = v; return this; }
        Builder insideSolidBlock(boolean v) { this.insideSolidBlock = v; return this; }
        Builder sprinting(boolean v) { this.sprinting = v; return this; }
        Builder jumpBoost(boolean v) { this.jumpBoost = v; return this; }
        Builder jumpStrength(double v) { this.jumpStrength = v; return this; }

        EnvironmentSnapshot build() {
            return new EnvironmentSnapshot(
                    System.currentTimeMillis(), true,
                    0, 64, 0,
                    // exemptGameMode, allowFlight, flying, gliding, insideVehicle
                    false, false, false, false, false,
                    sprinting,
                    // sneaking, swimming, dead, fallDistance
                    false, false, false, 0f,
                    // inWater, inLava, climbing, inCobweb
                    false, false, false, false,
                    nearClimbable, solidBelow, insideSolidBlock, blockBelow,
                    // levitation, slowFalling, jumpBoost, blindness, dolphinsGrace, speedAmplifier
                    false, false, jumpBoost, false, false, -1,
                    // depthStrider, soulSpeed
                    0, 0,
                    // jumpStrength
                    jumpStrength,
                    // containerOpen, openInventoryType
                    false, "CRAFTING");
        }
    }
}
