package net.lovelace.vesuvio.listener;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.config.ConfigManager;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.data.UserDataManager;
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

    private final Map<UUID, Long> blockDamageTimes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastBlockPlaceNanos = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> fastPlaceStreak = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastTowerPlaceNanos = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> towerStreak = new ConcurrentHashMap<>();
    private final Map<UUID, Long> eatStartMillis = new ConcurrentHashMap<>();
    private final Map<UUID, Long> bowDrawStartMillis = new ConcurrentHashMap<>();

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
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;

        UserData data = userDataManager.get(player.getUniqueId());
        if (data == null) return;

        Block placed = event.getBlockPlaced();
        Block against = event.getBlockAgainst();

        // 1.1 AirPlace: placing block against air or disconnected in space
        if (config.isAirPlaceEnabled() && (against.getType().isAir() || !hasAdjacentSolidBlock(placed))) {
            event.setCancelled(true);
            Map<String, Object> details = new HashMap<>();
            details.put("placed", placed.getType().name());
            details.put("against", against.getType().name());

            CheckResult result = CheckResult.flag(
                    "AirPlace",
                    0.99,
                    15.0,
                    "Placed block in empty air without adjacent surface",
                    details
            );
            pipeline.handleFlag(player, data, result);
            data.addVl(result.vl());
            data.adjustRisk(20.0);
            return;
        }

        // 1.2 Scaffold: placing block under feet (y-1) while running/sprinting with forward/upward pitch
        if (config.isScaffoldEnabled()) {
            Location pLoc = player.getLocation();
            if (placed.getY() == pLoc.getBlockY() - 1) {
            float pitch = pLoc.getPitch(); // -90 is straight up, 0 is horizon, 90 is straight down
            double dx = placed.getX() + 0.5 - pLoc.getX();
            double dz = placed.getZ() + 0.5 - pLoc.getZ();
            double distSq = dx * dx + dz * dz;

            // Placing directly below feet requires looking down (pitch >= 60°).
            // Scaffold places blocks under feet while pitch is looking horizontally forward (< 40°)
            if (distSq < 1.8 && pitch < 40.0f && (player.isSprinting() || data.getLastDeltaXZ() > 0.22)) {
                event.setCancelled(true);
                Map<String, Object> details = new HashMap<>();
                details.put("pitch", pitch);
                details.put("placedY", placed.getY());
                details.put("playerY", pLoc.getBlockY());
                details.put("sprinting", player.isSprinting());

                CheckResult result = CheckResult.flag(
                        "Scaffold",
                        0.95,
                        12.0,
                        String.format(Locale.US, "Unnatural downward placement angle (pitch: %.1f° < 40° limit)", pitch),
                        details
                );
                pipeline.handleFlag(player, data, result);
                data.addVl(result.vl());
                data.adjustRisk(18.0);
            }
        }
        }

        long now = System.nanoTime();

        // 1.3 FastPlace: vanilla imposes roughly a 4-tick (200ms) cooldown between block
        // placements. Flagging at half that (100ms) leaves slack for a fast legitimate clicker
        // and for the occasional double-packet edge case, while still catching a client that
        // places every tick or faster.
        if (config.isFastPlaceEnabled()) {
            Long lastPlace = lastBlockPlaceNanos.get(player.getUniqueId());
            if (lastPlace != null) {
                double elapsedMs = (now - lastPlace) / 1_000_000.0;
                AtomicInteger streak = fastPlaceStreak.computeIfAbsent(player.getUniqueId(), k -> new AtomicInteger());
                if (elapsedMs < 100.0) {
                    int count = streak.incrementAndGet();
                    if (count >= 3) {
                        streak.set(0);
                        CheckResult result = CheckResult.flag(
                                "FastPlace",
                                0.93,
                                10.0,
                                String.format(Locale.US, "Block placement faster than vanilla's cycle allows (%.0fms, min ~200ms)", elapsedMs),
                                Map.of("elapsedMs", elapsedMs, "streak", count)
                        );
                        pipeline.handleFlag(player, data, result);
                        data.addVl(result.vl());
                        data.adjustRisk(16.0);
                    }
                } else {
                    streak.set(0);
                }
            }
            lastBlockPlaceNanos.put(player.getUniqueId(), now);
        }

        // 1.4 Tower: pillaring up faster than a jump-and-place cycle can physically repeat. A
        // vanilla jump takes several ticks to rise far enough to place another block underfoot
        // (~250-300ms minimum in practice); a Tower/NoSlow-style cheat spams placements straight
        // up with no jump delay at all. Narrower and stricter than FastPlace above because it
        // additionally requires the specific "block appears directly beneath the player's new
        // feet position" geometry, not just any two fast placements.
        if (config.isTowerEnabled() && placed.getX() == player.getLocation().getBlockX()
                && placed.getZ() == player.getLocation().getBlockZ()
                && placed.getY() == player.getLocation().getBlockY() - 1) {
            Long lastTower = lastTowerPlaceNanos.get(player.getUniqueId());
            if (lastTower != null) {
                double elapsedMs = (now - lastTower) / 1_000_000.0;
                AtomicInteger streak = towerStreak.computeIfAbsent(player.getUniqueId(), k -> new AtomicInteger());
                if (elapsedMs < 150.0) {
                    int count = streak.incrementAndGet();
                    if (count >= 3) {
                        streak.set(0);
                        CheckResult result = CheckResult.flag(
                                "Tower",
                                0.92,
                                11.0,
                                String.format(Locale.US, "Pillaring faster than jump physics allow (%.0fms between placements, min ~250ms)", elapsedMs),
                                Map.of("elapsedMs", elapsedMs, "streak", count)
                        );
                        pipeline.handleFlag(player, data, result);
                        data.addVl(result.vl());
                        data.adjustRisk(17.0);
                    }
                } else {
                    streak.set(0);
                }
            }
            lastTowerPlaceNanos.put(player.getUniqueId(), now);
        }

        // 1.5 BlockReach / GhostHand on the block being placed against.
        checkBlockReachAndGhostHand(player, data, against, "place");
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
    private void checkBlockReachAndGhostHand(Player player, UserData data, Block block, String action) {
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;

        Location eye = player.getEyeLocation();
        Location blockCenter = block.getLocation().add(0.5, 0.5, 0.5);
        double distance = eye.distance(blockCenter);

        if (config.isBlockReachEnabled()) {
            var attr = player.getAttribute(Attribute.BLOCK_INTERACTION_RANGE);
            double maxReach = (attr != null) ? attr.getValue() : 4.5;
            // Generous buffer: distance is measured to the block's center, not its nearest face/
            // corner, which can legitimately be up to ~0.87 blocks closer at a diagonal.
            double allowedReach = maxReach + 0.9;

            if (distance > allowedReach) {
                CheckResult result = CheckResult.flag(
                        "BlockReach",
                        0.93,
                        9.0,
                        String.format(Locale.US, "Block %s beyond reach (%.2fm, max %.2fm)", action, distance, allowedReach),
                        Map.of("distance", distance, "maxAllowed", allowedReach, "action", action)
                );
                pipeline.handleFlag(player, data, result);
                data.addVl(result.vl());
                data.adjustRisk(15.0);
                return;
            }
        }

        if (config.isGhostHandEnabled() && distance > 0.8) {
            var direction = blockCenter.toVector().subtract(eye.toVector()).normalize();
            RayTraceResult hit = player.getWorld().rayTraceBlocks(eye, direction, distance - 0.2, FluidCollisionMode.NEVER, true);
            if (hit != null && hit.getHitBlock() != null && !hit.getHitBlock().equals(block)) {
                Block obstruction = hit.getHitBlock();
                if (obstruction.getType().isOccluding() && !obstruction.isPassable()) {
                    CheckResult result = CheckResult.flag(
                            "GhostHand",
                            0.96,
                            13.0,
                            String.format(Locale.US, "Block %s through solid obstruction %s", action, obstruction.getType().name()),
                            Map.of("obstruction", obstruction.getType().name(), "action", action, "distance", distance)
                    );
                    pipeline.handleFlag(player, data, result);
                    data.addVl(result.vl());
                    data.adjustRisk(18.0);
                }
            }
        }
    }

    /**
     * Evicts a departed player's tracking state. Call from PlayerQuitEvent to avoid an
     * unbounded per-visitor memory leak in the per-player maps above over server uptime.
     */
    public void forgetPlayer(UUID uuid) {
        blockDamageTimes.remove(uuid);
        miningProfiles.remove(uuid);
        lastBlockPlaceNanos.remove(uuid);
        fastPlaceStreak.remove(uuid);
        lastTowerPlaceNanos.remove(uuid);
        towerStreak.remove(uuid);
        eatStartMillis.remove(uuid);
        bowDrawStartMillis.remove(uuid);
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
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;

        Block block = event.getBlock();
        UserData data = userDataManager.get(player.getUniqueId());

        // BedrockBreaker exploit attempt
        if (config.isFastBreakEnabled() && block.getType().getHardness() < 0) {
            event.setCancelled(true);
            if (data != null) {
                CheckResult result = CheckResult.flag(
                        "BedrockBreaker",
                        0.99,
                        25.0,
                        "Attempted to damage unbreakable block " + block.getType().name(),
                        Map.of("block", block.getType().name())
                );
                pipeline.handleFlag(player, data, result);
                data.addVl(result.vl());
                data.adjustRisk(30.0);
            }
            return;
        }

        if (data != null) {
            checkBlockReachAndGhostHand(player, data, block, "break");
        }

        if (config.isFastBreakEnabled()) {
            blockDamageTimes.put(player.getUniqueId(), System.currentTimeMillis());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!config.isFastBreakEnabled()) return;
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;

        Block block = event.getBlock();
        float hardness = block.getType().getHardness();

        // Bedrock breaker
        if (hardness < 0) {
            event.setCancelled(true);
            UserData data = userDataManager.get(player.getUniqueId());
            if (data != null) {
                CheckResult result = CheckResult.flag(
                        "BedrockBreaker",
                        1.0,
                        35.0,
                        "Broke unbreakable block " + block.getType().name(),
                        Map.of("block", block.getType().name())
                );
                pipeline.handleFlag(player, data, result);
                data.addVl(result.vl());
                data.adjustRisk(50.0);
            }
            return;
        }

        // FastBreak: instant break on hard blocks (obsidian, ancient debris, ores)
        Long startTime = blockDamageTimes.remove(player.getUniqueId());
        if (hardness >= 3.0f) { // e.g. Obsidian (50.0), Ancient Debris (30.0), Iron/Gold Ore (3.0), Diamond Ore (3.0)
            long elapsed = (startTime != null) ? (System.currentTimeMillis() - startTime) : 0L;
            long minLegitMs = estimateMinLegitBreakMillis(player, block, hardness);
            if (elapsed < minLegitMs) {
                event.setCancelled(true);
                UserData data = userDataManager.get(player.getUniqueId());
                if (data != null) {
                    CheckResult result = CheckResult.flag(
                            "FastBreak",
                            0.94,
                            15.0,
                            String.format(Locale.US, "Impossible mining speed on %s (%d ms, min legit ~%d ms)", block.getType().name(), elapsed, minLegitMs),
                            Map.of("block", block.getType().name(), "elapsedMs", elapsed, "hardness", hardness, "minLegitMs", minLegitMs)
                    );
                    pipeline.handleFlag(player, data, result);
                    data.addVl(result.vl());
                    data.adjustRisk(20.0);
                }
                return;
            }
        }

        // 2.5 X-Ray Statistical & Burst Ore Detection
        UserData uData = userDataManager.get(player.getUniqueId());
        if (uData != null) {
            processXrayCheck(player, block, uData);
        }
    }

    /**
     * Estimates the fastest a legitimate vanilla client could break this block, using the actual
     * vanilla digging-speed formula (tool tier, Efficiency, Haste/Conduit Power, off-ground and
     * underwater penalties) rather than a flat cutoff.
     *
     * A flat "150ms for any hardness >= 3.0" threshold (the previous behaviour, only excluding
     * Haste) badly under-counted legitimate speed: a plain diamond pickaxe with Efficiency IV/V
     * already produces enough digging speed to instant-break hardness-3 ore (iron/gold/diamond/
     * emerald) in vanilla with no exploit involved - that combo is common end-game gear, not an
     * edge case. Very hard blocks (obsidian 50.0, ancient debris 30.0) still can't be
     * instant-broken even with the best legal loadout, so those stay tightly enforced.
     *
     * Returns milliseconds; a 50% safety margin below the theoretical vanilla minimum is applied
     * by the caller multiplying elapsed against this floor, so an imperfect model still only
     * flags breaks that are clearly, not marginally, faster than anything legitimate.
     */
    private long estimateMinLegitBreakMillis(Player player, Block block, float hardness) {
        ItemStack tool = player.getInventory().getItemInMainHand();
        double speed = pickaxeSpeedMultiplier(tool.getType(), block.getType());

        int efficiencyLevel = tool.getEnchantmentLevel(Enchantment.EFFICIENCY);
        if (efficiencyLevel > 0) {
            speed += (double) (efficiencyLevel * efficiencyLevel) + 1.0;
        }

        PotionEffect haste = player.getPotionEffect(PotionEffectType.HASTE);
        int hasteAmplifier = (haste != null) ? haste.getAmplifier() : -1;
        PotionEffect conduit = player.getPotionEffect(PotionEffectType.CONDUIT_POWER);
        if (conduit != null) {
            hasteAmplifier = Math.max(hasteAmplifier, conduit.getAmplifier());
        }
        if (hasteAmplifier >= 0) {
            speed *= 1.0 + (hasteAmplifier + 1) * 0.2;
        }

        if (!player.isOnGround()) {
            speed /= 5.0;
        }
        if (player.isInWater()) {
            ItemStack helmet = player.getInventory().getHelmet();
            boolean hasAquaAffinity = helmet != null && helmet.getEnchantmentLevel(Enchantment.AQUA_AFFINITY) > 0;
            if (!hasAquaAffinity) {
                speed /= 5.0;
            }
        }

        double damagePerTick = speed / hardness;
        // damagePerTick >= 1.0 means vanilla itself considers this an instant break (a single
        // client tick, ~50ms) - not a violation regardless of how fast the packets arrive.
        double vanillaTicks = Math.max(1.0, Math.ceil(1.0 / Math.max(damagePerTick, 0.0001)));
        long vanillaMinMs = Math.round(vanillaTicks * 50.0);

        // 50% safety margin: only flag a break that's less than half of the theoretical vanilla
        // minimum for this exact loadout, so an imperfect model of the formula (or minor timing
        // jitter) can't false-flag a legitimately fast, well-geared miner.
        return Math.max(50L, vanillaMinMs / 2);
    }

    private double pickaxeSpeedMultiplier(Material tool, Material block) {
        // Only the hardness>=3.0 bucket reaches this (ores, obsidian, ancient debris, etc.) -
        // in vanilla those are all pickaxe-mined, so a non-pickaxe (or bare hand) gets the
        // "wrong tool" multiplier of 1.0, same as vanilla.
        String name = tool.name();
        if (!name.endsWith("_PICKAXE")) return 1.0;
        if (name.startsWith("WOODEN") || name.startsWith("WOOD")) return 2.0;
        if (name.startsWith("STONE")) return 4.0;
        if (name.startsWith("IRON")) return 6.0;
        if (name.startsWith("DIAMOND")) return 8.0;
        if (name.startsWith("GOLDEN") || name.startsWith("GOLD")) return 12.0;
        if (name.startsWith("NETHERITE")) return 9.0;
        return 1.0;
    }

    private static final class MiningProfile {
        int stoneCount = 0;
        int diamondOreCount = 0;
        long windowStart = System.currentTimeMillis();
        int burstOreCount = 0;
    }

    private final Map<UUID, MiningProfile> miningProfiles = new ConcurrentHashMap<>();

    private void processXrayCheck(Player player, Block block, UserData data) {
        if (!config.isXrayEnabled()) return;
        Material mat = block.getType();
        boolean isRareOre = (mat == Material.DIAMOND_ORE || mat == Material.DEEPSLATE_DIAMOND_ORE ||
                             mat == Material.ANCIENT_DEBRIS || mat == Material.EMERALD_ORE || mat == Material.DEEPSLATE_EMERALD_ORE);
        boolean isCommonStone = (mat == Material.STONE || mat == Material.DEEPSLATE || mat == Material.TUFF ||
                                 mat == Material.NETHERRACK || mat == Material.GRANITE || mat == Material.DIORITE || mat == Material.ANDESITE);

        if (!isRareOre && !isCommonStone) return;

        MiningProfile profile = miningProfiles.computeIfAbsent(player.getUniqueId(), k -> new MiningProfile());
        long now = System.currentTimeMillis();

        if (now - profile.windowStart > 60_000L) {
            profile.burstOreCount = 0;
            profile.windowStart = now;
        }

        if (isCommonStone) {
            profile.stoneCount++;
            if (profile.stoneCount + profile.diamondOreCount > 600) {
                profile.stoneCount /= 2;
                profile.diamondOreCount /= 2;
            }
        } else if (isRareOre) {
            profile.diamondOreCount++;
            profile.burstOreCount++;

            // Burst check: 6+ rare ores mined in < 60s
            if (profile.burstOreCount >= 6) {
                CheckResult result = CheckResult.flag(
                        "XrayBurst",
                        0.92,
                        15.0,
                        String.format(Locale.US, "Rapid rare ore discoveries (%d %s in <60s)", profile.burstOreCount, mat.name()),
                        Map.of("ore", mat.name(), "burstCount", profile.burstOreCount)
                );
                pipeline.handleFlag(player, data, result);
                data.addVl(result.vl());
                data.adjustRisk(25.0);
            }

            // Statistical ratio check: when >= 4 rare ores mined, ratio > 25% is abnormal
            int total = profile.stoneCount + profile.diamondOreCount;
            if (profile.diamondOreCount >= 4 && total >= 10) {
                double ratio = (double) profile.diamondOreCount / total;
                if (ratio > 0.25) {
                    CheckResult result = CheckResult.flag(
                            "XrayStatistical",
                            0.95,
                            20.0,
                            String.format(Locale.US, "Abnormal ore-to-stone mining ratio: %.1f%% (straight-to-ore tunneling)", ratio * 100),
                            Map.of("oreCount", profile.diamondOreCount, "stoneCount", profile.stoneCount, "ratio", ratio)
                    );
                    pipeline.handleFlag(player, data, result);
                    data.addVl(result.vl());
                    data.adjustRisk(30.0);
                }
            }
        }
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

                if (!supported && deltaY > 0.08) {
                    Block below = to.clone().subtract(0, 1.5, 0).getBlock();
                    if (below.getType().isAir()) {
                        Map<String, Object> details = new HashMap<>();
                        details.put("vehicle", vehicle.getType().name());
                        details.put("deltaY", deltaY);

                        CheckResult result = CheckResult.flag(
                                "VehicleFly",
                                0.96,
                                16.0,
                                String.format(Locale.US, "Ascending mid-air while mounted on %s (ΔY: %.2f)", vehicle.getType().name(), deltaY),
                                details
                        );
                        pipeline.handleFlag(player, data, result);
                        data.addVl(result.vl());
                        data.adjustRisk(25.0);

                        // Eject player from hacked flying vehicle
                        vehicle.eject();
                        data.resetVehicleClipTicks();
                        continue;
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

        Block atBlock = to.getBlock();
        Block aboveBlock = to.clone().add(0, 1, 0).getBlock();
        boolean insideSolid = (atBlock.getType().isOccluding() && !atBlock.isPassable())
                || (aboveBlock.getType().isOccluding() && !aboveBlock.isPassable());

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
                String.format(Locale.US, "Travelling through solid geometry on %s (%.2fb/t sustained for %d ticks)",
                        vehicle.getType().name(), horizontal, VEHICLE_CLIP_REQUIRED_TICKS),
                details
        );
        pipeline.handleFlag(player, data, result);
        data.addVl(result.vl());
        data.adjustRisk(22.0);
    }

    // -------------------------------------------------------------
    // 4. FastEat & FastBow Detection
    // -------------------------------------------------------------

    /**
     * Vanilla eating/drinking takes 32 ticks (1600ms) start to finish for both food and potions
     * (both fire {@link PlayerItemConsumeEvent} on completion). A 50% safety margin - the same
     * proportion FastBreak already uses - only flags a completion clearly, not marginally, faster
     * than any legitimate client could produce.
     */
    private static final long MIN_EAT_MS = 800L;

    /**
     * Vanilla lets a bow be released at any charge for a weaker shot - there is no minimum draw
     * time to fire at all - so this cannot be a flat "too fast" cutoff. What it can safely catch is
     * a shot claiming near-maximum force (which vanilla only reaches after roughly a second of
     * charging) with a draw time far too short to have earned it.
     */
    private static final double MIN_FULL_CHARGE_MS = 700.0;
    private static final float NEAR_FULL_FORCE = 0.95f;

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return; // avoid double-counting the off-hand event
        Player player = event.getPlayer();
        var action = event.getAction();
        if (action != org.bukkit.event.block.Action.RIGHT_CLICK_AIR && action != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        if (item == null) return;

        if (config.isFastEatEnabled() && (item.getType().isEdible() || item.getType() == Material.POTION)) {
            eatStartMillis.putIfAbsent(player.getUniqueId(), System.currentTimeMillis());
        }

        if (config.isFastBowEnabled() && (item.getType() == Material.BOW || item.getType() == Material.CROSSBOW)) {
            bowDrawStartMillis.putIfAbsent(player.getUniqueId(), System.currentTimeMillis());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemConsume(PlayerItemConsumeEvent event) {
        if (!config.isFastEatEnabled()) return;
        Player player = event.getPlayer();
        UserData data = userDataManager.get(player.getUniqueId());
        if (data == null) return;

        Long startMillis = eatStartMillis.remove(player.getUniqueId());
        if (startMillis == null) return; // never observed the start - nothing to compare

        long elapsed = System.currentTimeMillis() - startMillis;
        if (elapsed < MIN_EAT_MS) {
            CheckResult result = CheckResult.flag(
                    "FastEat",
                    0.93,
                    9.0,
                    String.format(Locale.US, "Consumed %s faster than vanilla's animation allows (%dms, min ~%dms)",
                            event.getItem().getType().name(), elapsed, MIN_EAT_MS),
                    Map.of("item", event.getItem().getType().name(), "elapsedMs", elapsed)
            );
            pipeline.handleFlag(player, data, result);
            data.addVl(result.vl());
            data.adjustRisk(14.0);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShootBow(EntityShootBowEvent event) {
        if (!config.isFastBowEnabled()) return;
        if (!(event.getEntity() instanceof Player player)) return;

        UserData data = userDataManager.get(player.getUniqueId());
        if (data == null) return;

        Long startMillis = bowDrawStartMillis.remove(player.getUniqueId());
        if (startMillis == null) return; // never observed the draw start - nothing to compare

        long elapsed = System.currentTimeMillis() - startMillis;
        float force = event.getForce();

        if (force >= NEAR_FULL_FORCE && elapsed < MIN_FULL_CHARGE_MS) {
            CheckResult result = CheckResult.flag(
                    "FastBow",
                    0.90,
                    8.0,
                    String.format(Locale.US, "Near-full draw force (%.2f) from an impossibly short charge (%dms, min ~%.0fms)",
                            force, elapsed, MIN_FULL_CHARGE_MS),
                    Map.of("force", force, "elapsedMs", elapsed)
            );
            pipeline.handleFlag(player, data, result);
            data.addVl(result.vl());
            data.adjustRisk(13.0);
        }
    }
}
