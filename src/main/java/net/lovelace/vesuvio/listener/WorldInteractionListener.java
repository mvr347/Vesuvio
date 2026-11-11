package net.lovelace.vesuvio.listener;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.config.ConfigManager;
import net.lovelace.vesuvio.data.DecayingEvidence;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.data.UserDataManager;
import net.lovelace.vesuvio.engine.EnvironmentSnapshotService;
import net.lovelace.vesuvio.pipeline.CheckPipeline;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Camel;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Llama;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.entity.Strider;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Validates world and environment interactions:
 * - AirPlace (placing blocks against empty air)
 * - Scaffold (placing beneath feet with impossible look angle while sprinting)
 * - Tower (pillaring up faster than jump/gravity physics allow)
 * - FastPlace (placing blocks faster than the client's own click cycle allows)
 * - BedrockBreaker & FastBreak (breaking unbreakables or impossible mining speed)
 * - BlockReach & GhostHand (interacting with blocks beyond reach or through walls)
 * - FastEat & FastBow (consuming/drawing faster than vanilla's own animations allow)
 * - VehicleFly (flying / hovering mounted on Pig or Boat)
 *
 * Author: Lovelace
 */
public final class WorldInteractionListener implements Listener {

    private final UserDataManager userDataManager;
    private final CheckPipeline pipeline;
    private final ConfigManager config;

    /**
     * Start of the current dig, per player. Keyed by the block as well as the time: a player who
     * starts on one block and finishes on another has not mined anything impossibly fast, and
     * pairing those two events produced exactly that reading.
     */
    private record DigStart(String blockKey, int startedAtTick, long minimumMillis, Material material, long epoch) {}

    private final Map<UUID, DigStart> blockDamageTimes = new ConcurrentHashMap<>();

    /**
     * Accumulated evidence of breaks faster than vanilla allows. A single break slightly under the
     * model is not judged on its own - the model can be wrong at the margin, and network jitter
     * can shorten the measured interval by delaying the start packet. A run of them cannot be
     * explained that way, which is what this accumulates. See the class's detection doctrine in
     * CLAUDE.md: no "N in a row" counters, a decaying accumulator instead.
     */
    private final Map<UUID, DecayingEvidence> fastBreakEvidence = new ConcurrentHashMap<>();
    // Placements in a sliding 2 s window (2026-11-11). The gap between two placements measured on the server clock is ~0 for
    // every pair processed in one burst after a hang; a count over a window that is dropped when the server hitched is not.
    private static final long PLACE_WINDOW_MS = 2000L;
    private static final int FAST_PLACE_MAX_PER_WINDOW = 30; // vanilla: one per 4 ticks = 10 per 2 s
    private static final int TOWER_MAX_PER_WINDOW = 12;      // vanilla pillar: one per ~8-10 ticks = 4-5 per 2 s
    private final Map<UUID, net.lovelace.vesuvio.data.RateWindow> placeWindows = new ConcurrentHashMap<>();
    private final Map<UUID, net.lovelace.vesuvio.data.RateWindow> towerWindows = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> eatStartTick = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> bowDrawStartTick = new ConcurrentHashMap<>();

    public WorldInteractionListener(UserDataManager userDataManager, CheckPipeline pipeline, ConfigManager config) {
        this.userDataManager = userDataManager;
        this.pipeline = pipeline;
        this.config = config;
    }

    // -------------------------------------------------------------
    // 1. AirPlace & Scaffold Detection
    // -------------------------------------------------------------
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!config.isAnticheatEnabled()) return;
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;

        UserData data = userDataManager.get(player.getUniqueId());
        if (data == null) return;

        Block placed = event.getBlockPlaced();
        Block against = event.getBlockAgainst();

        // The player's own client may still hold a ghost block from a place/break the server cancelled a moment ago: placing
        // "against air" or "through a wall" in that window is the desync talking, not a cheat.
        boolean inDesync = data.inOwnBlockDesyncWindow(pipeline.transactionRtt(player.getUniqueId()));

        // 1.1 AirPlace: placing block against air or disconnected in space
        if (!inDesync && config.isAirPlaceEnabled() && (against.getType().isAir() || !hasAdjacentSolidBlock(placed))) {
            // One such placement is suspicious, two within about 20 s are a flag. The placement itself is still refused.
            if (config.mayPunish()) {
                event.setCancelled(true);
                data.markOwnBlockDesync();
            }
            if (!accumulate(data.getAirPlaceEvidence(), 2)) return;
            Map<String, Object> details = new HashMap<>();
            details.put("placed", placed.getType().name());
            details.put("against", against.getType().name());

            CheckResult result = CheckResult.flag(
                    "AirPlace",
                    0.99,
                    6.0,
                    "Установка блока в воздухе без соседней опоры",
                    details
            );
            pipeline.report(player, data, result, result.vl(), 20.0);
            return;
        }

        // 1.2 Scaffold: placing block under feet (y-1) while running/sprinting with forward/upward pitch
        if (!inDesync && config.isScaffoldEnabled()) {
            Location pLoc = player.getLocation();
            if (placed.getY() == pLoc.getBlockY() - 1) {
            float pitch = pLoc.getPitch(); // -90 is straight up, 0 is horizon, 90 is straight down
            double dx = placed.getX() + 0.5 - pLoc.getX();
            double dz = placed.getZ() + 0.5 - pLoc.getZ();
            double distSq = dx * dx + dz * dz;

            // Placing directly below feet requires looking down (pitch >= 60°).
            // Scaffold places blocks under feet while pitch is looking horizontally forward (< 40°)
            if (distSq < 1.8 && pitch < 40.0f && (player.isSprinting() || data.getLastDeltaXZ() > 0.22)) {
                if (config.mayPunish()) {
                    event.setCancelled(true);
                    data.markOwnBlockDesync();
                }
                // Bridging with a high pitch for one block is ordinary; three in a short time are the cheat.
                if (!accumulate(data.getScaffoldEvidence(), 3)) return;
                Map<String, Object> details = new HashMap<>();
                details.put("pitch", pitch);
                details.put("placedY", placed.getY());
                details.put("playerY", pLoc.getBlockY());
                details.put("sprinting", player.isSprinting());

                CheckResult result = CheckResult.flag(
                        "Scaffold",
                        0.95,
                        5.0,
                        String.format(Locale.US, "Недопустимый угол установки вниз (наклон: %.1f° при минимуме 40°)", pitch),
                        details
                );
                pipeline.report(player, data, result, result.vl(), 18.0);
            }
        }
        }

        long nowMillis = System.currentTimeMillis();
        long hitch = net.lovelace.vesuvio.engine.ServerHitch.lastHitchMillis();

        // 1.3 FastPlace: vanilla imposes roughly a 4-tick (200 ms) cooldown between block placements, so a client cannot place more
        // than about 10 blocks in 2 seconds; 30 leaves room for a fast clicker and double packets.
        if (config.isFastPlaceEnabled()) {
            int count = placeWindows.computeIfAbsent(player.getUniqueId(),
                    k -> new net.lovelace.vesuvio.data.RateWindow(PLACE_WINDOW_MS)).record(nowMillis, hitch);
            if (count > FAST_PLACE_MAX_PER_WINDOW) {
                placeWindows.get(player.getUniqueId()).clear();
                CheckResult result = CheckResult.flag(
                        "FastPlace",
                        0.93,
                        6.0,
                        String.format(Locale.US, "Слишком быстрая установка блоков (%d блоков за 2 с, максимум около %d)", count, FAST_PLACE_MAX_PER_WINDOW),
                        Map.of("blocksInWindow", count, "windowMs", PLACE_WINDOW_MS)
                );
                pipeline.report(player, data, result, result.vl(), 16.0);
            }
        }

        // 1.4 Tower: pillaring up faster than a jump-and-place cycle can physically repeat. A vanilla jump takes several ticks
        // to rise far enough to place another block underfoot; a Tower cheat places straight up with no jump delay at all.
        // Narrower than FastPlace because it additionally requires the "block appears directly beneath the player" geometry.
        if (config.isTowerEnabled() && placed.getX() == player.getLocation().getBlockX()
                && placed.getZ() == player.getLocation().getBlockZ()
                && placed.getY() == player.getLocation().getBlockY() - 1) {
            int count = towerWindows.computeIfAbsent(player.getUniqueId(),
                    k -> new net.lovelace.vesuvio.data.RateWindow(PLACE_WINDOW_MS)).record(nowMillis, hitch);
            if (count > TOWER_MAX_PER_WINDOW) {
                towerWindows.get(player.getUniqueId()).clear();
                CheckResult result = CheckResult.flag(
                        "Tower",
                        0.92,
                        6.0,
                        String.format(Locale.US, "Постройка столба быстрее допустимой физики прыжка (%d блоков под собой за 2 с, максимум около %d)", count, TOWER_MAX_PER_WINDOW),
                        Map.of("blocksInWindow", count, "windowMs", PLACE_WINDOW_MS)
                );
                pipeline.report(player, data, result, result.vl(), 17.0);
            }
        }

        // 1.5 BlockReach / GhostHand on the block being placed against.
        checkBlockReachAndGhostHand(player, data, against, "place", against.getFace(placed));

        // 1.6 Ghost-block resync: force the new block state out to nearby clients immediately,
        // rather than relying on the normal chunk-update packet, which lag can delay long enough
        // for a client's local view to briefly disagree with the server's - the classic cause of a
        // player "hovering" over what their client still thinks is empty air and catching a false
        // Fly/StepUp flag through no fault of their own.
        if (config.isGhostBlockResyncEnabled() && !event.isCancelled()) {
            resyncNearbyPlayers(placed);
        }
    }

    /** Radius, in blocks, within which nearby players are proactively resent a changed block's state. */
    private static final double GHOST_BLOCK_RESYNC_RADIUS = 10.0;

    /**
     * Sends the block's current state directly to every player within
     * {@link #GHOST_BLOCK_RESYNC_RADIUS}, independent of and ahead of the normal chunk-update
     * packet. Purely defensive - it changes nothing about what any check flags, it only shrinks
     * the window in which a client's view of the world can legitimately disagree with the
     * server's after a block changes near them.
     */
    private void resyncNearbyPlayers(Block block) {
        resyncNearbyPlayers(block.getWorld(), block.getLocation(), block.getBlockData());
    }

    /**
     * Air variant for breaking: {@code BlockBreakEvent} fires before the world actually updates,
     * so {@code block.getBlockData()} would still report the pre-break material at this priority -
     * the resulting state is always air regardless of what item the break drops.
     */
    private void resyncNearbyPlayersAir(Location location) {
        resyncNearbyPlayers(location.getWorld(), location, org.bukkit.Bukkit.createBlockData(Material.AIR));
    }

    private void resyncNearbyPlayers(org.bukkit.World world, Location center, org.bukkit.block.data.BlockData data) {
        double radiusSq = GHOST_BLOCK_RESYNC_RADIUS * GHOST_BLOCK_RESYNC_RADIUS;
        for (Player nearby : world.getPlayers()) {
            if (nearby.getLocation().distanceSquared(center) <= radiusSq) {
                nearby.sendBlockChange(center, data);
            }
        }
    }

    /**
     * BlockReach: vanilla 1.20.5+ exposes the real interaction range as an attribute
     * ({@link Attribute#BLOCK_INTERACTION_RANGE}, default 4.5 survival / 6.0 creative) rather than
     * a hardcoded constant, so any legitimate reach-modifying effect or gear is already accounted
     * for by reading it live instead of assuming a fixed number.
     *
     * <p>GhostHand: a raytrace from the eye toward the block must not be blocked by a closer,
     * different solid block - interacting with something behind a wall is not something a real
     * client's targeting can produce.
     */
    private void checkBlockReachAndGhostHand(Player player, UserData data, Block block, String action, BlockFace face) {
        if (data.hasRecentTeleport()) return;
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (data.inOwnBlockDesyncWindow(pipeline.transactionRtt(player.getUniqueId()))) return;

        Location eye = player.getEyeLocation();
        Location blockCenter = block.getLocation().add(0.5, 0.5, 0.5);
        double distance = eye.distance(blockCenter);

        if (config.isBlockReachEnabled()) {
            var attr = player.getAttribute(Attribute.BLOCK_INTERACTION_RANGE);
            double maxReach = (attr != null) ? attr.getValue() : 4.5;
            // Generous buffer: distance is measured to the block's center, not its nearest face/
            // corner, which can legitimately be up to ~0.87 blocks closer at a diagonal.
            // +0.5 more (2026-11-11): the eye height follows the pose (sneak, swim, crawl), and the pose in the snapshot the server reads
            // can be a tick behind the one the client aimed from.
            double allowedReach = maxReach + 0.9 + 0.5;

            if (distance > allowedReach && accumulate(data.getBlockReachEvidence(), 2)) {
                CheckResult result = CheckResult.flag(
                        "BlockReach",
                        0.93,
                        5.0,
                        String.format(Locale.US, "Действие с блоком (%s) за пределами досягаемости (%.2f м, максимум %.2f м)", net.lovelace.vesuvio.i18n.Russian.label(action), distance, allowedReach),
                        Map.of("distance", distance, "maxAllowed", allowedReach, "action", action)
                );
                pipeline.report(player, data, result, result.vl(), 15.0);
                return;
            }
        }

        if (config.isGhostHandEnabled() && distance > 0.8) {
            var direction = blockCenter.toVector().subtract(eye.toVector()).normalize();
            RayTraceResult hit = player.getWorld().rayTraceBlocks(eye, direction, distance - 0.2, FluidCollisionMode.NEVER, true);
            if (hit != null && hit.getHitBlock() != null && !hit.getHitBlock().equals(block)) {
                Block obstruction = hit.getHitBlock();
                if (EnvironmentSnapshotService.isGenuinelySolid(obstruction)
                        && !isAdjacent(obstruction, block) && !hasVisibleBlockFace(player, eye, block, face)
                        && accumulate(data.getGhostHandEvidence(), 2)) {
                    CheckResult result = CheckResult.flag(
                            "GhostHand",
                            0.96,
                            6.0,
                            String.format(Locale.US, "Действие с блоком (%s) сквозь сплошную преграду %s", net.lovelace.vesuvio.i18n.Russian.label(action), obstruction.getType().name()),
                            Map.of("obstruction", obstruction.getType().name(), "action", action, "distance", distance)
                    );
                    pipeline.report(player, data, result, result.vl(), 18.0);
                }
            }
        }
    }

    /**
     * One suspicious event is evidence, not a verdict: {@code events} of them within about 20 s (half-life 10 s, threshold 90% of
     * the count so the decay between events cannot keep it just under) are. Resets itself once it fires.
     */
    public static boolean accumulate(DecayingEvidence evidence, int events) {
        double score = evidence.reward(1.0, System.nanoTime(), 10_000.0);
        if (score >= events * 0.9 && evidence.events() >= events) {
            evidence.reset();
            return true;
        }
        return false;
    }

    // The server refused a place/break/interact: the player's client keeps a ghost state for about a round trip.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlaceCancelled(BlockPlaceEvent event) {
        if (event.isCancelled()) markDesync(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreakCancelled(BlockBreakEvent event) {
        if (event.isCancelled()) markDesync(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteractCancelled(PlayerInteractEvent event) {
        if (event.useInteractedBlock() == org.bukkit.event.Event.Result.DENY && event.getClickedBlock() != null) {
            markDesync(event.getPlayer());
        }
    }

    private void markDesync(Player player) {
        UserData data = userDataManager.get(player.getUniqueId());
        if (data != null) data.markOwnBlockDesync();
    }

    /** A blocked ray to the centre does not prove the visible face/corner is occluded. */
    private boolean hasVisibleBlockFace(Player player, Location eye, Block target, BlockFace face) {
        if (face != null && face != BlockFace.SELF && face.getModX() * face.getModX() + face.getModY() * face.getModY()
                + face.getModZ() * face.getModZ() == 1) {
            // The client NAMED the face it clicked: only that face has to be reachable, sampled on a 7x7 grid just outside the block.
            for (int i = 0; i < 7; i++) {
                for (int j = 0; j < 7; j++) {
                    double u = 0.04 + 0.92 * i / 6.0;
                    double v = 0.04 + 0.92 * j / 6.0;
                    double x = face.getModX() != 0 ? (face.getModX() > 0 ? 1.001 : -0.001) : u;
                    double y = face.getModY() != 0 ? (face.getModY() > 0 ? 1.001 : -0.001) : (face.getModX() != 0 ? u : v);
                    double z = face.getModZ() != 0 ? (face.getModZ() > 0 ? 1.001 : -0.001) : v;
                    var direction = target.getLocation().add(x, y, z).toVector().subtract(eye.toVector());
                    double length = direction.length();
                    if (length < 0.01) return true;
                    var hit = player.getWorld().rayTraceBlocks(eye, direction.normalize(), length,
                            FluidCollisionMode.NEVER, true);
                    if (hit == null || target.equals(hit.getHitBlock())) return true;
                }
            }
            return false;
        }
        for (double x : new double[]{0.01, 0.5, 0.99}) {
            for (double y : new double[]{0.01, 0.5, 0.99}) {
                for (double z : new double[]{0.01, 0.5, 0.99}) {
                    var direction = target.getLocation().add(x, y, z).toVector().subtract(eye.toVector());
                    double length = direction.length();
                    if (length < 0.01) return true;
                    var hit = player.getWorld().rayTraceBlocks(eye, direction.normalize(), length,
                            FluidCollisionMode.NEVER, true);
                    if (hit == null || target.equals(hit.getHitBlock())) return true;
                }
            }
        }
        return false;
    }

    /**
     * A block immediately touching the target (sharing a face, edge, or corner) is not a "wall" in
     * the GhostHand sense - it's ordinary geometry a ray toward the target's exact center can graze
     * near a boundary: a tunnel's own walls/ceiling while mining, or the block last placed while
     * building along a straight wall/bridge, both legitimately sit right next to the block being
     * interacted with. A genuine through-wall obstruction is never this close to its target.
     */
    private boolean isAdjacent(Block a, Block b) {
        return Math.abs(a.getX() - b.getX()) <= 1
                && Math.abs(a.getY() - b.getY()) <= 1
                && Math.abs(a.getZ() - b.getZ()) <= 1;
    }

    /**
     * Evicts a departed player's tracking state. Call from PlayerQuitEvent to avoid an
     * unbounded per-visitor memory leak in the per-player maps above over server uptime.
     */
    public void forgetPlayer(UUID uuid) {
        blockDamageTimes.remove(uuid);
        fastBreakEvidence.remove(uuid);
        placeWindows.remove(uuid);
        towerWindows.remove(uuid);
        eatStartTick.remove(uuid);
        bowDrawStartTick.remove(uuid);
    }

    private boolean hasAdjacentSolidBlock(Block b) {
        BlockFace[] faces = {BlockFace.DOWN, BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
        for (BlockFace face : faces) {
            Material mat = b.getRelative(face).getType();
            if (!mat.isAir()) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------
    // 2. BedrockBreaker & FastBreak Detection
    // -------------------------------------------------------------
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent event) {
        if (!config.isAnticheatEnabled()) return;
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;

        Block block = event.getBlock();
        UserData data = userDataManager.get(player.getUniqueId());

        // NOT a BedrockBreaker check: BlockDamageEvent fires the instant a survival player
        // left-clicks ANY block, including one they cannot possibly break (bedrock, barrier -
        // hardness < 0). Vanilla's own digging-progress math never completes for such a block, so
        // ordinary curiosity - clicking bedrock once to see what happens - produced this event on
        // every single click from every legitimate player, and used to be flagged here as a 0.99
        // confidence exploit attempt. Reaching an actual BlockBreakEvent on a hardness < 0 material
        // is what is actually impossible without a bypass; that is judged in onBlockBreak below,
        // which is the only place this ever needs to be judged.
        if (data != null) {
            checkBlockReachAndGhostHand(player, data, block, "break", event.getBlockFace());
        }

        if (config.isFastBreakEnabled()) {
            long minimum = event.getInstaBreak() ? 0 : minimumBreakMillis(block.getBreakSpeed(player));
            DigStart previous = blockDamageTimes.get(player.getUniqueId());
            String key = blockKey(block);
            // Repeated START_DESTROY packets for one dig must not move its clock forward.
            if (previous == null || !previous.blockKey().equals(key) || previous.material() != block.getType()
                    || previous.epoch() != config.runtime().detectionEpoch()) {
                blockDamageTimes.put(player.getUniqueId(), new DigStart(key, org.bukkit.Bukkit.getCurrentTick(), minimum,
                        block.getType(), config.runtime().detectionEpoch()));
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!config.isAnticheatEnabled()) return;
        if (!config.isFastBreakEnabled()) return;
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;

        Block block = event.getBlock();
        float hardness = block.getType().getHardness();

        // Bedrock breaker
        if (hardness < 0) {
            if (config.mayPunish()) event.setCancelled(true);
            UserData data = userDataManager.get(player.getUniqueId());
            if (data != null) {
                CheckResult result = CheckResult.flag(
                        "BedrockBreaker",
                        1.0,
                        35.0,
                        "Сломан неразрушаемый блок " + block.getType().name(),
                        Map.of("block", block.getType().name())
                );
                pipeline.report(player, data, result, result.vl(), 50.0);
            }
            return;
        }

        // FastBreak. Judged on EVERY breakable block, not just hard ones: the previous
        // "hardness >= 3.0" gate meant stone, wood and dirt - what players actually mine, and what
        // a fast-break module is actually used on - were never looked at. The vanilla dig model
        // below is per-block anyway, so there is nothing a hardness bucket buys.
        DigStart dig = blockDamageTimes.remove(player.getUniqueId());
        UserData breakData = userDataManager.get(player.getUniqueId());
        if (breakData != null && !breakData.hasRecentTeleport() && hardness > 0.0f && dig != null
                && dig.epoch() == config.runtime().detectionEpoch()
                && dig.material() == block.getType() && dig.blockKey().equals(blockKey(block))) {
            // Counted in SERVER ticks (2026-11-11), not nanoTime between two main-thread events: after a server hang the queued start
            // and stop packets are handled back to back, so the nanoTime gap is ~0 while the tick counter - the server catches up by
            // running the missed ticks - still shows the real time the player dug for. This is also what vanilla itself measures.
            long elapsed = (long) (org.bukkit.Bukkit.getCurrentTick() - dig.startedAtTick()) * 50L;
            // Paper computes actual per-tick progress, including modern mining attributes,
            // custom tools, enchantments, effects and harvestability. Use the faster endpoint
            // if equipment/effects changed during this dig; uncertainty must favour the player.
            long vanillaMinMs = Math.min(dig.minimumMillis(), minimumBreakMillis(block.getBreakSpeed(player)));
            double ratio = breakTimeRatio(elapsed, vanillaMinMs);

            DecayingEvidence evidence = fastBreakEvidence.computeIfAbsent(
                    player.getUniqueId(), k -> new DecayingEvidence());
            long nowNanos = System.nanoTime();
            double halfLife = config.getFastBreakEvidenceHalfLifeMs();

            if (ratio < config.getFastBreakConclusiveRatio()) {
                if (config.mayPunish()) event.setCancelled(true);
                evidence.reset();
                flagFastBreak(player, breakData, block, elapsed, vanillaMinMs, ratio, 0.97, 18.0,
                        String.format(Locale.US,
                                "Блок %s сломан за %d мс при минимуме %d мс для этого инструмента и эффектов (%.0f%% от минимума)",
                                block.getType().name(), elapsed, vanillaMinMs, ratio * 100.0));
                return;
            }

            if (ratio < config.getFastBreakSuspiciousRatio()) {
                double score = evidence.reward(1.0, nowNanos, halfLife);
                if (score >= config.getFastBreakEvidenceThreshold()
                        && evidence.events() >= config.getFastBreakEvidenceMinEvents()) {
                    if (config.mayPunish()) event.setCancelled(true);
                    evidence.reset();
                    flagFastBreak(player, breakData, block, elapsed, vanillaMinMs, ratio, 0.93, 15.0,
                            String.format(Locale.US,
                                    "Стабильная добыча быстрее допустимой (%s за %d мс при минимуме %d мс, %.0f%% от минимума, оценка %.1f)",
                                    block.getType().name(), elapsed, vanillaMinMs, ratio * 100.0, score));
                    return;
                }
            } else {
                evidence.relieve(0.5, nowNanos, halfLife);
            }
        }

        // 2.6 Ghost-block resync: the break resolves to air regardless of what drops, so nearby
        // clients are resent an explicit air state immediately rather than waiting on the normal
        // chunk-update packet (see the placement-side resync above for why this matters).
        if (config.isGhostBlockResyncEnabled() && !event.isCancelled()) {
            resyncNearbyPlayersAir(block.getLocation());
        }
    }

    /** Paper's break progress includes tools, effects and BLOCK_BREAK_SPEED; do not multiply the attribute twice. */
    public static long minimumBreakMillis(double damagePerTick) {
        if (!Double.isFinite(damagePerTick) || damagePerTick <= 0 || damagePerTick >= 1) return 0;
        return (long) Math.ceil(1.0 / damagePerTick) * 50L;
    }

    /** One initial progress tick plus one tick of packet batching are not impossible mining. */
    public static double breakTimeRatio(long elapsedMillis, long minimumMillis) {
        if (elapsedMillis < 0 || minimumMillis <= 50) return 1.0;
        return (elapsedMillis + 100.0) / minimumMillis;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDigAbort(org.bukkit.event.block.BlockDamageAbortEvent event) {
        blockDamageTimes.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onToolChange(org.bukkit.event.player.PlayerItemHeldEvent event) {
        blockDamageTimes.remove(event.getPlayer().getUniqueId());
    }

    /**
     * The arithmetic half of the dig model, free of Bukkit so it can be pinned by a test.
     *
     * <p>Vanilla is {@code damage_per_tick = speed / hardness / 30} when the block is harvestable
     * with the held tool, and {@code / 100} when it is not. <b>That divisor was missing here</b>,
     * which made every computed floor thirty times too low and is why this check never caught
     * anything: it put obsidian - 9400ms of real digging with a diamond pickaxe - at a 175ms floor,
     * so no fast-break setting on earth could fall under it. The harvestable divisor (30) is used
     * unconditionally because it is the smaller of the two and therefore always the more forgiving
     * reading of what the player could have done.
     *
     * @return the vanilla minimum for this speed/hardness pair, in milliseconds
     */
    public static long vanillaBreakMillis(double speed, double hardness) {
        if (hardness <= 0.0) return 0L;
        double damagePerTick = speed / hardness / 30.0;

        // damagePerTick >= 1.0 means vanilla itself considers this an instant break (a single
        // client tick, ~50ms) - not a violation regardless of how fast the packets arrive.
        double vanillaTicks = Math.max(1.0, Math.ceil(1.0 / Math.max(damagePerTick, 0.0001)));
        return Math.max(50L, Math.round(vanillaTicks * 50.0));
    }

    private static String blockKey(Block b) {
        return b.getWorld().getUID() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ();
    }

    private void flagFastBreak(Player player, UserData data, Block block, long elapsed,
                               long vanillaMinMs, double ratio, double confidence, double vl,
                               String explanation) {
        Map<String, Object> details = new HashMap<>();
        details.put("block", block.getType().name());
        details.put("elapsedMs", elapsed);
        details.put("vanillaMinMs", vanillaMinMs);
        details.put("ratioOfVanilla", ratio);
        details.put("hardness", block.getType().getHardness());
        details.put("tool", player.getInventory().getItemInMainHand().getType().name());

        CheckResult result = CheckResult.flag("FastBreak", confidence, vl, explanation, details);
        pipeline.report(player, data, result, result.vl(), 20.0);
    }

    // -------------------------------------------------------------
    // 3. Vehicle Movement (Fly & Clip Detection)
    // -------------------------------------------------------------

    /** Consecutive ticks a vehicle may sit inside a solid block before Clip is considered. */
    private static final int VEHICLE_CLIP_REQUIRED_TICKS = 6;

    /** Horizontal travel per tick required to call it "moving through" rather than being ejected. */
    private static final double VEHICLE_CLIP_MIN_HORIZONTAL = 0.06;

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleMove(VehicleMoveEvent event) {
        if (!config.isAnticheatEnabled()) return;
        Entity vehicle = event.getVehicle();

        // Ascending-without-support only applies to vehicles that walk/float like a living entity.
        // Minecarts are rail-bound - climbing a ramp is normal, and isOnGround() does not mean the
        // same thing for them, so they are deliberately excluded here and handled by Clip only.
        boolean walksLikeLivingEntity = vehicle instanceof Boat || vehicle instanceof Pig
                || vehicle instanceof AbstractHorse || vehicle instanceof Camel
                || vehicle instanceof Llama || vehicle instanceof Strider;
        boolean isMinecart = vehicle instanceof Minecart;
        if (!walksLikeLivingEntity && !isMinecart) return;

        for (Entity passenger : vehicle.getPassengers()) {
            if (!(passenger instanceof Player player)) continue;
            if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) continue;

            UserData data = userDataManager.get(player.getUniqueId());
            if (data == null) continue;

            Location from = event.getFrom();
            Location to = event.getTo();
            double deltaY = to.getY() - from.getY();

            if (config.isVehicleFlyEnabled() && walksLikeLivingEntity) {
                boolean supported = vehicle.isInWater() || vehicle.isInLava() || vehicle.isOnGround();
                // The vertical change of the previous vehicle tick: a horse's jump arc LOSES height speed every tick (gravity),
                // so a rise that keeps its speed or grows is what needs explaining. Always stored, also on supported ticks.
                double previousDeltaY = data.swapVehicleDeltaY(deltaY, System.currentTimeMillis());
                // Levitation, slow falling and a leash change vertical movement of the mount on their own.
                boolean externalLift = vehicle instanceof org.bukkit.entity.LivingEntity living
                        && (living.hasPotionEffect(PotionEffectType.LEVITATION)
                        || living.hasPotionEffect(PotionEffectType.SLOW_FALLING)
                        || living.isLeashed());

                if (!supported && !externalLift && deltaY > 0.08) {
                    Block below = to.clone().subtract(0, 1.5, 0).getBlock();
                    boolean notDecreasing = !Double.isNaN(previousDeltaY) && deltaY >= previousDeltaY - 0.003;
                    if (below.getType().isAir() && notDecreasing) {
                        // Needs a run of such ticks (jump arcs and bounces never produce one), not a single reading.
                        double score = data.getVehicleFlyEvidence().reward(1.0, System.nanoTime(), 1500.0);
                        if (score >= 2.7 && data.getVehicleFlyEvidence().events() >= 3) {
                            data.getVehicleFlyEvidence().reset();
                            Map<String, Object> details = new HashMap<>();
                            details.put("vehicle", vehicle.getType().name());
                            details.put("deltaY", deltaY);
                            details.put("previousDeltaY", previousDeltaY);

                            CheckResult result = CheckResult.flag(
                                    "VehicleFly",
                                    0.96,
                                    8.0,
                                    String.format(Locale.US, "Подъём в воздухе верхом на %s без затухания (ΔY: %.2f)", vehicle.getType().name(), deltaY),
                                    details
                            );
                            pipeline.report(player, data, result, result.vl(), 25.0);

                            // Eject player from hacked flying vehicle
                            vehicle.eject();
                            data.resetVehicleClipTicks();
                            continue;
                        }
                    } else if (!notDecreasing) {
                        data.getVehicleFlyEvidence().relieve(1.0, System.nanoTime(), 1500.0);
                    }
                }
            }

            if (config.isVehicleClipEnabled()) {
                checkVehicleClip(player, data, vehicle, from, to);
            }
        }
    }

    /**
     * BoatClip / vehicle phasing: a vehicle that stays inside a solid occluding block while still
     * travelling horizontally is not a state vanilla can produce - it resolves collisions and
     * ejects the vehicle+rider every tick, same as it does for a walking player (see
     * {@code check.movement.PhaseCheck}). Requiring both persistence and continued horizontal
     * movement excludes the ordinary "a block was placed on us, we're about to be pushed out"
     * case, which drifts rather than travels.
     */
    private void checkVehicleClip(Player player, UserData data, Entity vehicle, Location from, Location to) {
        double horizontal = Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ());

        // The vehicle's real hitbox against the true collision shapes (2026-11-11): the old test looked at the blocks at the
        // vehicle's origin and one above it, so a boat or minecart in a tunnel 1 block high (its box fits, the block above is a
        // ceiling) was "inside" a wall.
        boolean insideSolid = EnvironmentSnapshotService.boxInsideSolid(vehicle.getWorld(), vehicle.getBoundingBox());

        if (!insideSolid || horizontal < VEHICLE_CLIP_MIN_HORIZONTAL) {
            data.decrementVehicleClipTicks();
            return;
        }

        data.incrementVehicleClipTicks();
        if (data.getVehicleClipTicks() < VEHICLE_CLIP_REQUIRED_TICKS) {
            return;
        }

        data.resetVehicleClipTicks();

        Map<String, Object> details = new HashMap<>();
        details.put("vehicle", vehicle.getType().name());
        details.put("horizontalSpeed", horizontal);
        details.put("ticksInside", VEHICLE_CLIP_REQUIRED_TICKS);

        CheckResult result = CheckResult.flag(
                "VehicleClip",
                0.94,
                14.0,
                String.format(Locale.US, "Движение сквозь сплошные блоки верхом на %s (%.2f блока/такт в течение %d тактов)",
                        vehicle.getType().name(), horizontal, VEHICLE_CLIP_REQUIRED_TICKS),
                details
        );
        pipeline.report(player, data, result, result.vl(), 22.0);
    }

    // -------------------------------------------------------------
    // 4. FastEat & FastBow Detection
    // -------------------------------------------------------------

    /**
     * Eating/drinking runs for the item's own use duration ({@code ItemStack#getMaxItemUseDuration}, 32 ticks for ordinary food
     * and potions, shorter or longer for custom items) and fires {@link PlayerItemConsumeEvent} on completion. Measured in
     * SERVER ticks (2026-11-11): the gap between two main-thread events in nanoTime is ~0 after a server hang. 50% of the use
     * duration is the margin, as for FastBreak.
     */
    private static final double MIN_EAT_FRACTION = 0.5;

    /**
     * Vanilla lets a bow be released at any charge for a weaker shot - there is no minimum draw
     * time to fire at all - so this cannot be a flat "too fast" cutoff. What it can safely catch is
     * a shot claiming near-maximum force (which vanilla only reaches after roughly a second of
     * charging) with a draw time far too short to have earned it.
     */
    private static final int MIN_FULL_CHARGE_TICKS = 14; // 700 ms
    private static final float NEAR_FULL_FORCE = 0.95f;

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (!config.isAnticheatEnabled()) return;
        if (event.getHand() != EquipmentSlot.HAND) return; // avoid double-counting the off-hand event
        Player player = event.getPlayer();
        var action = event.getAction();
        if (action != org.bukkit.event.block.Action.RIGHT_CLICK_AIR && action != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        if (item == null) return;

        if (config.isFastEatEnabled() && (item.getType().isEdible() || item.getType() == Material.POTION)) {
            eatStartTick.putIfAbsent(player.getUniqueId(), org.bukkit.Bukkit.getCurrentTick());
        }

        // A crossbow is loaded, not drawn-and-released: Quick Charge, multishot and loading in the off hand all change what
        // "fast" means, so it is not judged at all.
        if (config.isFastBowEnabled() && item.getType() == Material.BOW) {
            bowDrawStartTick.putIfAbsent(player.getUniqueId(), org.bukkit.Bukkit.getCurrentTick());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemConsume(PlayerItemConsumeEvent event) {
        if (!config.isAnticheatEnabled()) return;
        if (!config.isFastEatEnabled()) return;
        Player player = event.getPlayer();
        UserData data = userDataManager.get(player.getUniqueId());
        if (data == null) return;

        Integer startTick = eatStartTick.remove(player.getUniqueId());
        if (startTick == null) return; // never observed the start - nothing to compare

        int useTicks = event.getItem().getMaxItemUseDuration(player);
        if (useTicks <= 0) return;
        long elapsedTicks = org.bukkit.Bukkit.getCurrentTick() - startTick;
        long minTicks = (long) Math.floor(useTicks * MIN_EAT_FRACTION);
        if (elapsedTicks < minTicks) {
            CheckResult result = CheckResult.flag(
                    "FastEat",
                    0.93,
                    4.0,
                    String.format(Locale.US, "Употребление %s быстрее допустимой анимации (%d тактов, минимум около %d из %d)",
                            event.getItem().getType().name(), elapsedTicks, minTicks, useTicks),
                    Map.of("item", event.getItem().getType().name(), "elapsedTicks", elapsedTicks, "useTicks", useTicks)
            );
            pipeline.report(player, data, result, result.vl(), 14.0);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShootBow(EntityShootBowEvent event) {
        if (!config.isAnticheatEnabled()) return;
        if (!config.isFastBowEnabled()) return;
        if (!(event.getEntity() instanceof Player player)) return;

        UserData data = userDataManager.get(player.getUniqueId());
        if (data == null) return;

        // Only a bow: a crossbow shot (including one loaded long ago, or after a teleport) has no draw to time.
        if (event.getBow() == null || event.getBow().getType() != Material.BOW) return;
        Integer startTick = bowDrawStartTick.remove(player.getUniqueId());
        if (startTick == null) return; // never observed the draw start - nothing to compare

        long elapsedTicks = org.bukkit.Bukkit.getCurrentTick() - startTick;
        float force = event.getForce();

        if (force >= NEAR_FULL_FORCE && elapsedTicks < MIN_FULL_CHARGE_TICKS) {
            CheckResult result = CheckResult.flag(
                    "FastBow",
                    0.90,
                    4.0,
                    String.format(Locale.US, "Почти полное натяжение (%.2f) за слишком короткое время (%d тактов, минимум около %d)",
                            force, elapsedTicks, MIN_FULL_CHARGE_TICKS),
                    Map.of("force", force, "elapsedTicks", elapsedTicks)
            );
            pipeline.report(player, data, result, result.vl(), 13.0);
        }
    }
}
