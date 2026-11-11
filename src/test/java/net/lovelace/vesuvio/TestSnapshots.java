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
public final class TestSnapshots {

    private TestSnapshots() {}

    /** A plain survival player, sprinting on solid ground, with nothing exempting them. */
    static EnvironmentSnapshot ground(Material below) {
        return builder().blockBelow(below).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Material blockBelow = Material.GRASS_BLOCK;
        private boolean solidBelow = true;
        private boolean nearClimbable = false;
        private boolean insideSolidBlock = false;
        private boolean sprinting = true;
        private boolean jumpBoost = false;
        private double jumpStrength = 0.42; // vanilla Attribute.JUMP_STRENGTH default
        private double movementSpeed = 0.1;
        Builder movementSpeed(double value) { movementSpeed = value; return this; }
        private boolean gliding = false;
        private boolean blockedPosX = false;
        private boolean blockedNegX = false;
        private boolean blockedPosZ = false;
        private boolean blockedNegZ = false;

        Builder blockBelow(Material m) { this.blockBelow = m; return this; }
        Builder solidBelow(boolean v) { this.solidBelow = v; return this; }
        Builder nearClimbable(boolean v) { this.nearClimbable = v; return this; }
        Builder insideSolidBlock(boolean v) { this.insideSolidBlock = v; return this; }
        Builder sprinting(boolean v) { this.sprinting = v; return this; }
        Builder jumpBoost(boolean v) { this.jumpBoost = v; return this; }
        Builder jumpStrength(double v) { this.jumpStrength = v; return this; }
        Builder gliding(boolean v) { this.gliding = v; return this; }
        Builder blockedPosX(boolean v) { this.blockedPosX = v; return this; }
        Builder blockedNegX(boolean v) { this.blockedNegX = v; return this; }
        Builder blockedPosZ(boolean v) { this.blockedPosZ = v; return this; }
        Builder blockedNegZ(boolean v) { this.blockedNegZ = v; return this; }
        private boolean inWater = false;
        private boolean containerOpen = false;
        private String openInventoryType = "CRAFTING";
        private double x = 0, y = 64, z = 0;
        Builder position(double x, double y, double z) { this.x = x; this.y = y; this.z = z; return this; }
        private double stepHeight = 0.6;
        private boolean nearLiquid = false;
        private boolean nearStuck = false;
        private long containerOpenSince = 0L;
        private boolean recentExemption = false;
        Builder stepHeight(double v) { this.stepHeight = v; return this; }
        Builder nearLiquid(boolean v) { this.nearLiquid = v; return this; }
        Builder nearStuck(boolean v) { this.nearStuck = v; return this; }
        Builder containerOpenSince(long v) { this.containerOpenSince = v; return this; }
        Builder recentExemption(boolean v) { this.recentExemption = v; return this; }
        Builder inWater(boolean v) { this.inWater = v; return this; }
        Builder container(String type) {
            this.containerOpen = true;
            this.openInventoryType = type;
            if (containerOpenSince == 0L) containerOpenSince = System.currentTimeMillis();
            return this;
        }

        public EnvironmentSnapshot build() {
            return new EnvironmentSnapshot(
                    System.currentTimeMillis(), true,
                    x, y, z,
                    // exemptGameMode, allowFlight, flying, gliding, insideVehicle
                    false, false, false, gliding, false,
                    sprinting,
                    // sneaking, swimming, dead, fallDistance
                    false, false, false, 0f,
                    // inWater, inLava, climbing, inCobweb
                    inWater, false, false, false,
                    nearClimbable, solidBelow, insideSolidBlock, blockBelow,
                    // blockedPosX, blockedNegX, blockedPosZ, blockedNegZ
                    blockedPosX, blockedNegX, blockedPosZ, blockedNegZ,
                    // levitation, slowFalling, jumpBoost, blindness, dolphinsGrace, speedAmplifier
                    false, false, jumpBoost, false, false, -1,
                    // depthStrider, soulSpeed
                    0, 0,
                    // jumpStrength
                    movementSpeed, jumpStrength,
                    // containerOpen, openInventoryType
                    containerOpen, openInventoryType,
                    // stepHeight, nearLiquid, nearStuck, containerOpenSinceMillis, recentExemption
                    stepHeight, nearLiquid, nearStuck, containerOpenSince, recentExemption);
        }
    }
}
