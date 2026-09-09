package net.lovelace.vesuvio.engine;

import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.data.UserDataManager;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Captures an {@link EnvironmentSnapshot} for every online player once per tick, on the main
 * thread, and publishes it to that player's {@link UserData} for the async movement checks to read.
 *
 * <p>This is the single place in the plugin allowed to touch the Bukkit world on behalf of the
 * movement checks. Everything downstream reads the immutable snapshot instead, which is what makes
 * running those checks on virtual threads actually safe.
 *
 * <h2>Cost control</h2>
 * The player-state reads (game mode, potions, fluids) are plain field accesses and are refreshed
 * every tick. The block lookups are the only part that costs anything real, so they are recomputed
 * only when the player's <em>block</em> coordinates changed since the last capture, or when the
 * cached block data has gone {@link #BLOCK_REFRESH_TICKS} ticks without a refresh (so a block mined
 * out from under a standing player is still noticed promptly). A player standing still therefore
 * costs zero block lookups per tick, and a sprinting player costs one scan every few ticks rather
 * than one per tick.
 *
 * Author: Lovelace
 */
public final class EnvironmentSnapshotService {

    /** Force a block re-scan at least this often, even for a player who has not moved. */
    private static final int BLOCK_REFRESH_TICKS = 10;

    private final UserDataManager userDataManager;

    /** Per-player memo of the last block scan, so an unmoved player skips the world lookups. */
    private final Map<UUID, BlockCache> blockCaches = new HashMap<>();

    private long tick = 0L;

    public EnvironmentSnapshotService(UserDataManager userDataManager) {
        this.userDataManager = userDataManager;
    }

    /** Runs on the main thread once per tick. */
    public void captureAll(Iterable<? extends Player> players) {
        tick++;
        for (Player player : players) {
            if (player == null || !player.isOnline() || player.hasMetadata("NPC")) continue;
            try {
                UserData data = userDataManager.get(player.getUniqueId());
                if (data == null) continue;
                data.setEnvironment(capture(player));
            } catch (Throwable t) {
                // A single misbehaving player (odd world state, a plugin-backed inventory that
                // throws) must never take down the capture loop for everyone else.
                blockCaches.remove(player.getUniqueId());
            }
        }
    }

    public void remove(UUID uuid) {
        blockCaches.remove(uuid);
    }

    public void clear() {
        blockCaches.clear();
    }

    private EnvironmentSnapshot capture(Player player) {
        Location loc = player.getLocation();
        GameMode mode = player.getGameMode();
        boolean exemptGameMode = mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR;

        BlockCache blocks = blockCache(player, loc);

        PotionEffect speed = player.getPotionEffect(PotionEffectType.SPEED);
        int speedAmplifier = speed != null ? speed.getAmplifier() : -1;

        int depthStrider = 0;
        int soulSpeed = 0;
        PlayerInventory inv = player.getInventory();
        ItemStack boots = inv.getItem(EquipmentSlot.FEET);
        if (boots != null && !boots.getType().isAir()) {
            depthStrider = boots.getEnchantmentLevel(Enchantment.DEPTH_STRIDER);
            soulSpeed = boots.getEnchantmentLevel(Enchantment.SOUL_SPEED);
        }

        InventoryType openType = InventoryType.CRAFTING;
        try {
            openType = player.getOpenInventory().getType();
        } catch (Throwable ignored) {
            // Some custom-inventory plugins throw from getOpenInventory during teardown.
        }
        // CRAFTING is the player's own always-present 2x2 view, i.e. "no container open".
        boolean containerOpen = openType != InventoryType.CRAFTING;

        return new EnvironmentSnapshot(
                System.currentTimeMillis(),
                true,
                loc.getX(), loc.getY(), loc.getZ(),
                exemptGameMode,
                player.getAllowFlight(),
                player.isFlying(),
                player.isGliding(),
                player.isInsideVehicle(),
                player.isSprinting(),
                player.isSneaking(),
                player.isSwimming(),
                player.isDead(),
                player.getFallDistance(),
                player.isInWater(),
                player.isInLava(),
                player.isClimbing(),
                blocks.inCobweb,
                blocks.nearClimbable,
                blocks.solidBelow,
                blocks.insideSolidBlock,
                blocks.blockBelow,
                player.hasPotionEffect(PotionEffectType.LEVITATION),
                player.hasPotionEffect(PotionEffectType.SLOW_FALLING),
                player.hasPotionEffect(PotionEffectType.JUMP_BOOST),
                player.hasPotionEffect(PotionEffectType.BLINDNESS),
                player.hasPotionEffect(PotionEffectType.DOLPHINS_GRACE),
                speedAmplifier,
                depthStrider,
                soulSpeed,
                containerOpen,
                openType.name());
    }

    /**
     * Returns the cached block scan for this player, rescanning only when they crossed into a new
     * block or the cache aged out.
     */
    private BlockCache blockCache(Player player, Location loc) {
        UUID uuid = player.getUniqueId();
        int bx = loc.getBlockX();
        int by = loc.getBlockY();
        int bz = loc.getBlockZ();
        UUID worldUid = loc.getWorld() != null ? loc.getWorld().getUID() : EMPTY_WORLD;

        BlockCache cached = blockCaches.get(uuid);
        if (cached != null
                && cached.blockX == bx && cached.blockY == by && cached.blockZ == bz
                && cached.worldUid.equals(worldUid)
                && (tick - cached.tick) < BLOCK_REFRESH_TICKS) {
            return cached;
        }

        BlockCache fresh = scanBlocks(loc, bx, by, bz);
        blockCaches.put(uuid, fresh);
        return fresh;
    }

    private BlockCache scanBlocks(Location loc, int bx, int by, int bz) {
        World world = loc.getWorld();
        BlockCache cache = new BlockCache();
        cache.tick = tick;
        cache.blockX = bx;
        cache.blockY = by;
        cache.blockZ = bz;

        if (world == null) {
            cache.worldUid = EMPTY_WORLD;
            // Without a world we cannot prove the player is in mid-air, so assume the safe
            // (non-flagging) answer for every block-derived signal.
            cache.solidBelow = true;
            cache.nearClimbable = true;
            cache.blockBelow = Material.AIR;
            return cache;
        }
        cache.worldUid = world.getUID();

        // Unloaded chunk: same reasoning as a null world - never flag on data we do not have.
        if (!world.isChunkLoaded(bx >> 4, bz >> 4)) {
            cache.solidBelow = true;
            cache.nearClimbable = true;
            cache.blockBelow = Material.AIR;
            return cache;
        }

        cache.blockBelow = world.getBlockAt(bx, by - 1, bz).getType();

        org.bukkit.block.Block feetBlock = world.getBlockAt(bx, by, bz);
        org.bukkit.block.Block headBlock = world.getBlockAt(bx, by + 1, bz);
        Material feet = feetBlock.getType();
        Material head = headBlock.getType();
        cache.inCobweb = feet == Material.COBWEB || head == Material.COBWEB;

        // Occupying a full occluding cube is the Phase/Clip signal. Deliberately narrow:
        // isOccluding() excludes slabs, stairs, doors, trapdoors, fences, carpets and every other
        // partial or passable shape a player can legitimately stand inside, so only genuinely
        // impossible positions qualify. isPassable() is checked too because a few occluding-looking
        // blocks are walkable, and a player standing in one is not clipping.
        cache.insideSolidBlock = (feet.isOccluding() && !feetBlock.isPassable())
                || (head.isOccluding() && !headBlock.isPassable());

        // 3x3 column around the feet: is there anything that legitimately breaks a fall, and is
        // there anything the player could legitimately be climbing or bouncing on?
        boolean solidBelow = false;
        boolean nearClimbable = false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    Material m = world.getBlockAt(bx + dx, by + dy, bz + dz).getType();
                    if (m == Material.AIR) continue;
                    if (dy <= 0 && breaksFall(m)) solidBelow = true;
                    if (isClimbableOrBouncy(m)) nearClimbable = true;
                }
            }
        }
        cache.solidBelow = solidBelow;
        cache.nearClimbable = nearClimbable;
        return cache;
    }

    /** Blocks that make a claimed on-ground state or a survived fall plausible. */
    private static boolean breaksFall(Material m) {
        return m.isSolid()
                || m == Material.LADDER || m == Material.VINE || m == Material.SCAFFOLDING
                || m == Material.WATER || m == Material.LAVA || m == Material.COBWEB
                || m == Material.POWDER_SNOW || m == Material.TWISTING_VINES
                || m == Material.WEEPING_VINES || m == Material.CAVE_VINES;
    }

    /** Blocks that legitimately break the gravity / step-height invariants. */
    private static boolean isClimbableOrBouncy(Material m) {
        return m == Material.LADDER || m == Material.VINE || m == Material.SCAFFOLDING
                || m == Material.TWISTING_VINES || m == Material.WEEPING_VINES
                || m == Material.CAVE_VINES || m == Material.COBWEB
                || m == Material.SLIME_BLOCK || m == Material.HONEY_BLOCK
                || m == Material.POWDER_SNOW;
    }

    private static final UUID EMPTY_WORLD = new UUID(0L, 0L);

    /** Mutable holder for the memoised block scan. Only ever touched on the main thread. */
    private static final class BlockCache {
        long tick;
        UUID worldUid = EMPTY_WORLD;
        int blockX, blockY, blockZ;
        boolean solidBelow = true;
        boolean nearClimbable;
        boolean inCobweb;
        boolean insideSolidBlock;
        Material blockBelow = Material.AIR;
    }
}
