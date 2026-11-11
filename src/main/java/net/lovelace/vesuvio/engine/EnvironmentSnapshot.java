package net.lovelace.vesuvio.engine;

import org.bukkit.Material;

/**
 * Immutable, main-thread-captured view of everything the movement checks need to know about a
 * player and the blocks around them.
 *
 * <p>Movement packets arrive on a Netty thread and are dispatched to virtual threads, so the
 * checks that consume them run off the server's main thread. Reading the Bukkit world from there
 * ({@code World#getBlockAt}, {@code Player#getOpenInventory}, {@code Player#getEquipment}, ...) is
 * not thread-safe: on Paper it can trip the async-catcher, force a chunk load from the wrong
 * thread, or silently return a torn view of state that is being mutated by the main thread at the
 * same moment. Snapshotting on the main thread once per tick and handing the checks this immutable
 * record keeps the detection logic exactly as expressive while making the reads safe and, as a
 * bonus, cheaper (one capture serves every check that tick instead of each check re-querying).
 *
 * <p>All fields are captured together, so a check never sees a half-updated player: the block
 * data, potion effects and equipment in one snapshot all describe the same tick.
 *
 * @param captureMillis wall-clock time the snapshot was taken, so consumers can reject stale data
 * @param valid         false for the "nothing captured yet" placeholder; checks must skip on false
 *
 * Author: Lovelace
 */
public record EnvironmentSnapshot(
        long captureMillis,
        boolean valid,

        // --- Position (server-side, main thread authoritative) ---
        double x,
        double y,
        double z,

        // --- Game mode / flight state ---
        boolean exemptGameMode,   // CREATIVE or SPECTATOR
        boolean allowFlight,
        boolean flying,
        boolean gliding,
        boolean insideVehicle,
        boolean sprinting,
        boolean sneaking,
        boolean swimming,
        boolean dead,
        float fallDistance,

        // --- Fluids / climbing / special blocks ---
        boolean inWater,
        boolean inLava,
        boolean climbing,
        boolean inCobweb,
        boolean nearClimbable,   // ladder/vine/scaffolding/slime/honey/cobweb within reach
        boolean solidBelow,      // solid or otherwise fall-breaking block under the feet
        boolean insideSolidBlock,// feet or head occupy a full occluding cube (Phase/Clip signal)
        Material blockBelow,     // block directly beneath the feet (ice, soul sand, slime, ...)

        // --- Immediate horizontal neighbours (feet/head height), for "is a wall right there"
        // reasoning - e.g. VelocityCheck telling a genuine wall-collision apart from an
        // anti-knockback client absorbing a knockback in the open ---
        boolean blockedPosX,
        boolean blockedNegX,
        boolean blockedPosZ,
        boolean blockedNegZ,

        // --- Potion effects ---
        boolean levitation,
        boolean slowFalling,
        boolean jumpBoost,
        boolean blindness,
        boolean dolphinsGrace,
        int speedAmplifier,      // -1 when the player has no Speed effect

        // --- Equipment enchantments (boots) ---
        int depthStrider,
        int soulSpeed,

        // --- Live attributes (base value + any equipped item's AttributeModifier, resolved by
        // the server exactly like it resolves attack damage or movement speed from gear) ---
        double movementSpeed, // Resolved MOVEMENT_SPEED, including equipment/effect modifiers.
        double jumpStrength, // Attribute.JUMP_STRENGTH; vanilla default ~0.42

        // --- Inventory ---
        boolean containerOpen,   // a real container GUI, not the always-open 2x2 crafting view; never in CREATIVE
        String openInventoryType,

        // --- Added 2026-11-11 ---
        double stepHeight,       // Attribute.STEP_HEIGHT (vanilla 0.6); some gear and effects raise it
        boolean nearLiquid,      // water, lava, waterlogged block, kelp, seagrass, bubbles in the 3x3 around the player
        boolean nearStuck,       // berry bush, powder snow, cobweb near the player, not only in the feet block
        long containerOpenSinceMillis, // when the current container was first seen open; 0 when none
        boolean recentExemption  // flight, elytra, vehicle ended within the last 20 ticks (the client has not caught up)
) {

    /** Placeholder used before the first capture; every check treats this as "skip". */
    public static final EnvironmentSnapshot EMPTY = new EnvironmentSnapshot(
            0L, false,
            0, 0, 0,
            false, false, false, false, false, false, false, false, false, 0f,
            false, false, false, false, false, false, false, Material.AIR,
            false, false, false, false,
            false, false, false, false, false, -1,
            0, 0,
            0.1, 0.42,
            false, "NONE",
            0.6, false, false, 0L, false);

    /**
     * A snapshot older than a few ticks means the capture task fell behind (server hitch, or the
     * player only just joined). Checks treat stale data as "unknown" and skip rather than judge a
     * player on where they used to be.
     */
    public boolean isFresh(long nowMillis, long maxAgeMillis) {
        return valid && (nowMillis - captureMillis) <= maxAgeMillis;
    }

    /**
     * Movement checks share one broad exemption set (creative flight, elytra, vehicles, death).
     * Centralised here so a newly added check cannot forget one of them.
     */
    public boolean isMovementExempt() {
        return exemptGameMode || allowFlight || flying || gliding || insideVehicle || dead || recentExemption;
    }

    /**
     * True if a solid wall stands between the player and travel in the given horizontal
     * direction (e.g. the direction of a knockback impulse). Deliberately axis-based rather than
     * a precise angle match: Minecraft resolves X and Z collision independently, so a wall on
     * either contributing axis can legitimately zero out displacement along a diagonal impulse.
     */
    public boolean isHorizontallyBlocked(double dirX, double dirZ) {
        boolean blocked = false;
        if (dirX > 0.02) blocked |= blockedPosX;
        else if (dirX < -0.02) blocked |= blockedNegX;
        if (dirZ > 0.02) blocked |= blockedPosZ;
        else if (dirZ < -0.02) blocked |= blockedNegZ;
        return blocked;
    }
}
