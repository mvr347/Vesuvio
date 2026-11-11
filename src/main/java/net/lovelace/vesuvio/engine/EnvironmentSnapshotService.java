package net.lovelace.vesuvio.engine;

import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.data.UserDataManager;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
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
public final class EnvironmentSnapshotService implements org.bukkit.event.Listener {

    /** Force a block re-scan at least this often, even for a player who has not moved. */
    private static final int BLOCK_REFRESH_TICKS = 10;

    /** Fallback used only if the JUMP_STRENGTH attribute is somehow unavailable. */
    private static final double VANILLA_JUMP_STRENGTH = 0.42;

    private final UserDataManager userDataManager;

    /** Per-player memo of the last block scan, so an unmoved player skips the world lookups. */
    private final Map<UUID, BlockCache> blockCaches = new HashMap<>();

    /** Per-player "sticky" release state (see {@link StickyWindow}). Main thread only. */
    private final Map<UUID, Sticky> sticky = new HashMap<>();

    private long tick = 0L;
    private net.lovelace.vesuvio.integration.essential.LoveEssentialHook mechanics;

    public void mechanics(net.lovelace.vesuvio.integration.essential.LoveEssentialHook hook) {
        this.mechanics = hook;
    }

    public EnvironmentSnapshotService(UserDataManager userDataManager) {
        this.userDataManager = userDataManager;
    }

    /** Runs on the main thread once per tick. */
    public void captureAll(Iterable<? extends Player> players) {
        captureAll(players, true);
    }

    public void captureAll(Iterable<? extends Player> players, boolean detectionEnabled) {
        tick++;
        for (Player player : players) {
            if (player == null || !player.isOnline() || player.hasMetadata("NPC")) continue;
            try {
                UserData data = userDataManager.get(player.getUniqueId());
                if (data == null) continue;
                if (mechanics != null) mechanics.capture(player, data);
                EnvironmentSnapshot env = capture(player);
                data.setEnvironment(env);
                if (detectionEnabled && data.isPhysicsObserved()) {
                    var location = player.getLocation();
                    // Cache retention hint only; server blocks never populate the client replica.
                    data.clientWorld().focus(location.getBlockX() >> 4, location.getBlockZ() >> 4);
                    long reset = data.getMovementResetNanos();
                    var frame =
                            data.mechanics().movement(System.nanoTime())
                                    ? net.lovelace.vesuvio.engine.physics.PhysicsFrame.unknown(
                                            "LOVEESSENTIAL_CONTROLLED")
                                    : net.lovelace.vesuvio.engine.physics.PaperPhysicsCapture.capture(player, env, reset);
                    data.setPhysicsFrame(frame); // Epoch is checked at consumption, including reset races.
                }
            } catch (Throwable t) {
                // A single misbehaving player (odd world state, a plugin-backed inventory that
                // throws) must never take down the capture loop for everyone else.
                blockCaches.remove(player.getUniqueId());
                sticky.remove(player.getUniqueId());
                UserData data = userDataManager.get(player.getUniqueId());
                if (data != null) data.setPhysicsFrame(net.lovelace.vesuvio.engine.physics.PhysicsFrame.unknown("CAPTURE_FAILED"));
            }
        }
    }

    public void remove(UUID uuid) {
        blockCaches.remove(uuid);
        sticky.remove(uuid);
    }

    /** Drops state of players who are no longer online (see StateJanitor). */
    public void retainOnline(java.util.Set<UUID> online) {
        blockCaches.keySet().retainAll(online);
        sticky.keySet().retainAll(online);
    }

    /** Closing a container is applied by the server at once; the snapshot of this tick still shows it open. */
    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onInventoryClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        UserData data = userDataManager.get(event.getPlayer().getUniqueId());
        if (data != null) data.markContainerClosed();
    }

    // A cancelled break/place is not ignored on purpose: the cancelling plugin (claims, regeneration) often restores the block a
    // moment later, so the cached scan next to the player is just as stale as after a real change.
    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onBlockBreak(org.bukkit.event.block.BlockBreakEvent event) {
        invalidateNear(event.getBlock());
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onBlockPlace(org.bukkit.event.block.BlockPlaceEvent event) {
        invalidateNear(event.getBlock());
    }

    /** Cobblestone/stone/obsidian generators and other blocks that form on their own. */
    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockForm(org.bukkit.event.block.BlockFormEvent event) {
        invalidateNear(event.getBlock());
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(org.bukkit.event.block.BlockExplodeEvent event) {
        for (org.bukkit.block.Block b : event.blockList()) invalidateNear(b);
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(org.bukkit.event.entity.EntityExplodeEvent event) {
        for (org.bukkit.block.Block b : event.blockList()) invalidateNear(b);
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(org.bukkit.event.block.BlockPistonExtendEvent event) {
        invalidateNear(event.getBlock());
        for (org.bukkit.block.Block b : event.getBlocks()) invalidateNear(b.getRelative(event.getDirection()));
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(org.bukkit.event.block.BlockPistonRetractEvent event) {
        invalidateNear(event.getBlock());
        for (org.bukkit.block.Block b : event.getBlocks()) invalidateNear(b);
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(org.bukkit.event.entity.EntityChangeBlockEvent event) {
        invalidateNear(event.getBlock());
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBurn(org.bukkit.event.block.BlockBurnEvent event) {
        invalidateNear(event.getBlock());
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFade(org.bukkit.event.block.BlockFadeEvent event) {
        invalidateNear(event.getBlock());
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeavesDecay(org.bukkit.event.block.LeavesDecayEvent event) {
        invalidateNear(event.getBlock());
    }

    private void invalidateNear(org.bukkit.block.Block block) {
        // The world mutation completes after the event. Evict now, then capture fresh shapes
        // next tick instead of reusing up to half a second of pre-mining terrain.
        blockCaches.entrySet().removeIf(entry -> {
            BlockCache cache = entry.getValue();
            boolean near = cache.worldUid.equals(block.getWorld().getUID())
                    && Math.abs(cache.blockX - block.getX()) <= 2
                    && Math.abs(cache.blockY - block.getY()) <= 3
                    && Math.abs(cache.blockZ - block.getZ()) <= 2;
            if (near) {
                UserData data = userDataManager.get(entry.getKey());
                if (data != null) {
                    data.markNearbyBlockChange(System.currentTimeMillis());
                    data.setEnvironment(EnvironmentSnapshot.EMPTY);
                    // Client-world geometry changes only through outgoing block packets and Pong.
                }
            }
            return near;
        });
    }

    public void clear() {
        blockCaches.clear();
        sticky.clear();
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

        // Live jump-strength attribute. This is the SAME value the vanilla server itself uses to
        // decide how hard a jump pushes the player up, and it already has any item's
        // AttributeModifier folded in - a custom sword/boots/armor piece granting extra jump
        // height via the vanilla `minecraft:attribute_modifiers` item component (or one applied by
        // another plugin) shows up here automatically, the same way a weapon's damage modifier
        // shows up in Attribute.ATTACK_DAMAGE. Reading the live value (not the base value) is what
        // lets the movement checks stop assuming everyone jumps at exactly the vanilla 0.42 and
        // instead judge each player against what their own gear actually entitles them to.
        double jumpStrength = VANILLA_JUMP_STRENGTH;
        try {
            AttributeInstance jumpAttr = player.getAttribute(Attribute.JUMP_STRENGTH);
            if (jumpAttr != null) {
                jumpStrength = jumpAttr.getValue();
            }
        } catch (Throwable ignored) {
            // Attribute unregistered or the API rejects the call for this entity type - fall back
            // to the vanilla constant rather than let a jump check see a bogus 0.
        }

        AttributeInstance movementAttribute = player.getAttribute(Attribute.MOVEMENT_SPEED);
        double movementSpeed = movementAttribute == null ? 0.1 : movementAttribute.getValue();

        InventoryType openType = InventoryType.CRAFTING;
        try {
            openType = player.getOpenInventory().getType();
        } catch (Throwable ignored) {
            // Some custom-inventory plugins throw from getOpenInventory during teardown.
        }
        // CRAFTING is the player's own always-present 2x2 view, i.e. "no container open".
        // CREATIVE: the client keeps its own inventory screen and the server reports it as an open view, which is not a
        // container that blocks movement or attacks.
        boolean containerOpen = openType != InventoryType.CRAFTING && mode != GameMode.CREATIVE;

        Sticky st = sticky.computeIfAbsent(player.getUniqueId(), id -> new Sticky());
        long nowMillis = System.currentTimeMillis();
        boolean levitation = st.levitation.update(tick, player.hasPotionEffect(PotionEffectType.LEVITATION));
        boolean slowFalling = st.slowFalling.update(tick, player.hasPotionEffect(PotionEffectType.SLOW_FALLING));
        boolean jumpBoost = st.jumpBoost.update(tick, player.hasPotionEffect(PotionEffectType.JUMP_BOOST));
        // The effect is already gone on the server but the client has not learned that yet: judge it by the best it had.
        movementSpeed = st.speed.max(tick, movementSpeed);
        jumpStrength = st.jump.max(tick, jumpStrength);
        boolean flightNow = player.getAllowFlight() || player.isFlying();
        boolean recentExemption = st.flight.update(tick, flightNow)
                | st.glide.update(tick, player.isGliding())
                | st.vehicle.update(tick, player.isInsideVehicle());
        if (!containerOpen) st.containerSince = 0L;
        else if (st.containerSince == 0L) st.containerSince = nowMillis;

        double stepHeight = 0.6;
        try {
            AttributeInstance stepAttr = player.getAttribute(Attribute.STEP_HEIGHT);
            if (stepAttr != null) stepHeight = stepAttr.getValue();
        } catch (Throwable ignored) {
            // Attribute unavailable: keep the vanilla value.
        }
        boolean insideSolid = loc.getWorld() != null
                && loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)
                && boxInsideSolid(loc.getWorld(), player.getBoundingBox());

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
                insideSolid,
                blocks.blockBelow,
                blocks.blockedPosX,
                blocks.blockedNegX,
                blocks.blockedPosZ,
                blocks.blockedNegZ,
                levitation,
                slowFalling,
                jumpBoost,
                player.hasPotionEffect(PotionEffectType.BLINDNESS),
                player.hasPotionEffect(PotionEffectType.DOLPHINS_GRACE),
                speedAmplifier,
                depthStrider,
                soulSpeed,
                movementSpeed,
                jumpStrength,
                containerOpen,
                openType.name(),
                stepHeight,
                blocks.nearLiquid,
                blocks.nearStuck,
                st.containerSince,
                recentExemption);
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
        // blocks are walkable, and a player standing in one is not clipping. See isGenuinelySolid
        // for the further special-cased materials that pass this test but still are not real walls.
        // (Since 2026-11-11 insideSolidBlock is computed per tick from the player's real hitbox in capture(), see
        // boxInsideSolid: the feet/head block test here called soul sand and mud, which are occluding cubes only 14/16 high,
        // "inside a block" during ordinary walking, and a crawling or swimming player in a 1-high tunnel too.)

        // 3x3 column around the feet: is there anything that legitimately breaks a fall, and is
        // there anything the player could legitimately be climbing or bouncing on?
        boolean solidBelow = false;
        boolean nearClimbable = false;
        boolean nearLiquid = false;
        boolean nearStuck = false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    org.bukkit.block.Block nb = world.getBlockAt(bx + dx, by + dy, bz + dz);
                    Material m = nb.getType();
                    if (m == Material.AIR) continue;
                    if (dy <= 0 && breaksFall(m)) solidBelow = true;
                    if (isClimbableOrBouncy(m)) nearClimbable = true;
                    if (isLiquidLike(m) || (m != Material.AIR && nb.getBlockData() instanceof org.bukkit.block.data.Waterlogged wl && wl.isWaterlogged())) {
                        nearLiquid = true;
                    }
                    if (isStuckBlock(m)) nearStuck = true;
                }
            }
        }
        cache.solidBelow = solidBelow;
        cache.nearClimbable = nearClimbable;
        cache.nearLiquid = nearLiquid;
        cache.nearStuck = nearStuck;

        // Immediate horizontal neighbours at feet and head height: enough to tell "player is
        // pressed against a wall on this side" from "player is in the open", which is all the
        // consumers of this need - it doesn't have to be a precise collision AABB.
        for (int dy = 0; dy <= 1; dy++) {
            if (isWallBlocking(world, bx + 1, by + dy, bz)) cache.blockedPosX = true;
            if (isWallBlocking(world, bx - 1, by + dy, bz)) cache.blockedNegX = true;
            if (isWallBlocking(world, bx, by + dy, bz + 1)) cache.blockedPosZ = true;
            if (isWallBlocking(world, bx, by + dy, bz - 1)) cache.blockedNegZ = true;
        }
        return cache;
    }

    /** Same "genuinely solid, not just occluding-looking" test used for {@code insideSolidBlock}. */
    private static boolean isWallBlocking(World world, int x, int y, int z) {
        return isGenuinelySolid(world.getBlockAt(x, y, z));
    }

    /**
     * The one "is this block a real, solid obstruction" test shared by Phase/Clip, the wall-
     * pressure detection above, {@code VehicleClip} and {@code GhostHand} (see their call sites).
     *
     * <p>{@code isOccluding()} is a purely visual/lighting property, and {@code isPassable()} alone
     * still misses a real gap: a handful of materials have a partial or hollow true collision shape
     * that Bukkit's simplified per-material flags do not model, and are reported as "occluding and
     * not passable" even though vanilla itself lets a player stand inside, walk through, or see/
     * reach past them.
     *
     * <p>Concretely: {@link Material#MANGROVE_ROOTS} (and its muddy variant) flagged an ordinary
     * player chopping a mangrove tree for BOTH Phase (standing "inside" root clutter vanilla itself
     * lets them occupy while working at the base of the tree) and GhostHand (a root block 2+ blocks
     * away from the log actually being broken, which is completely normal given how densely
     * mangrove roots cluster around a trunk, misread as a wall between the player and their axe's
     * actual target).
     */
    public static boolean isGenuinelySolid(org.bukkit.block.Block block) {
        Material type = block.getType();
        if (type == Material.MANGROVE_ROOTS || type == Material.MUDDY_MANGROVE_ROOTS) {
            return false;
        }
        return type.isOccluding() && !block.isPassable();
    }

    /**
     * Whether the given box (a player's or a vehicle's real hitbox, pose included) overlaps the true collision shape of a
     * genuinely solid block. Shared by Phase and VehicleClip. The box is shrunk slightly so that touching a wall or
     * standing on a floor is not "inside"; soul sand, mud and other partial cubes are judged by their real shape.
     */
    public static boolean boxInsideSolid(World world, org.bukkit.util.BoundingBox box) {
        final double eps = 1.0E-3;
        org.bukkit.util.BoundingBox probe = new org.bukkit.util.BoundingBox(
                box.getMinX() + eps, box.getMinY() + eps, box.getMinZ() + eps,
                box.getMaxX() - eps, box.getMaxY() - eps, box.getMaxZ() - eps);
        for (int x = (int) Math.floor(probe.getMinX()); x <= (int) Math.floor(probe.getMaxX()); x++) {
            for (int y = (int) Math.floor(probe.getMinY()); y <= (int) Math.floor(probe.getMaxY()); y++) {
                for (int z = (int) Math.floor(probe.getMinZ()); z <= (int) Math.floor(probe.getMaxZ()); z++) {
                    org.bukkit.block.Block block = world.getBlockAt(x, y, z);
                    if (!isGenuinelySolid(block)) continue;
                    // Collision shapes are block-local.
                    org.bukkit.util.BoundingBox local = probe.clone().shift(-x, -y, -z);
                    if (block.getCollisionShape().overlaps(local)) return true;
                }
            }
        }
        return false;
    }

    private static boolean isLiquidLike(Material m) {
        return m == Material.WATER || m == Material.LAVA || m == Material.KELP || m == Material.KELP_PLANT
                || m == Material.SEAGRASS || m == Material.TALL_SEAGRASS || m == Material.BUBBLE_COLUMN;
    }

    private static boolean isStuckBlock(Material m) {
        return m == Material.SWEET_BERRY_BUSH || m == Material.POWDER_SNOW || m == Material.COBWEB;
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

    /** Mutable per-player release windows. Only ever touched on the main thread. */
    private static final class Sticky {
        final StickyWindow levitation = new StickyWindow();
        final StickyWindow slowFalling = new StickyWindow();
        final StickyWindow jumpBoost = new StickyWindow();
        final StickyWindow flight = new StickyWindow();
        final StickyWindow glide = new StickyWindow();
        final StickyWindow vehicle = new StickyWindow();
        final StickyWindow speed = new StickyWindow();
        final StickyWindow jump = new StickyWindow();
        long containerSince;
    }

    /** Mutable holder for the memoised block scan. Only ever touched on the main thread. */
    private static final class BlockCache {
        long tick;
        UUID worldUid = EMPTY_WORLD;
        int blockX, blockY, blockZ;
        boolean solidBelow = true;
        boolean nearClimbable;
        boolean inCobweb;
        boolean nearLiquid;
        boolean nearStuck;
        Material blockBelow = Material.AIR;
        boolean blockedPosX;
        boolean blockedNegX;
        boolean blockedPosZ;
        boolean blockedNegZ;
    }
}
