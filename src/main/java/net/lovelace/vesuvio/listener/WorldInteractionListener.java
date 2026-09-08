package net.lovelace.vesuvio.listener;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.config.ConfigManager;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.data.UserDataManager;
import net.lovelace.vesuvio.pipeline.CheckPipeline;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates world and environment interactions:
 * - AirPlace (placing blocks against empty air)
 * - Scaffold (placing beneath feet with impossible look angle while sprinting)
 * - BedrockBreaker & FastBreak (breaking unbreakables or impossible mining speed)
 * - VehicleFly (flying / hovering mounted on Pig or Boat)
 *
 * Author: Lovelace
 */
public final class WorldInteractionListener implements Listener {

    private final UserDataManager userDataManager;
    private final CheckPipeline pipeline;
    private final ConfigManager config;

    private final Map<UUID, Long> blockDamageTimes = new ConcurrentHashMap<>();

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
    }

    /**
     * Evicts a departed player's tracking state. Call from PlayerQuitEvent to avoid an
     * unbounded per-visitor memory leak in blockDamageTimes/miningProfiles over server uptime.
     */
    public void forgetPlayer(UUID uuid) {
        blockDamageTimes.remove(uuid);
        miningProfiles.remove(uuid);
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
        if (!config.isFastBreakEnabled()) return;
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;

        Block block = event.getBlock();
        // BedrockBreaker exploit attempt
        if (block.getType().getHardness() < 0) {
            event.setCancelled(true);
            UserData data = userDataManager.get(player.getUniqueId());
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

        blockDamageTimes.put(player.getUniqueId(), System.currentTimeMillis());
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
            if (elapsed < 150L && !player.hasPotionEffect(org.bukkit.potion.PotionEffectType.HASTE)) {
                event.setCancelled(true);
                UserData data = userDataManager.get(player.getUniqueId());
                if (data != null) {
                    CheckResult result = CheckResult.flag(
                            "FastBreak",
                            0.94,
                            15.0,
                            String.format(Locale.US, "Impossible mining speed on %s (%d ms)", block.getType().name(), elapsed),
                            Map.of("block", block.getType().name(), "elapsedMs", elapsed, "hardness", hardness)
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
    // 3. Vehicle Movement (Boat & Pig Fly Detection)
    // -------------------------------------------------------------
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleMove(VehicleMoveEvent event) {
        if (!config.isVehicleFlyEnabled()) return;
        Entity vehicle = event.getVehicle();
        if (!(vehicle instanceof Boat) && !(vehicle instanceof Pig)) return;

        for (Entity passenger : vehicle.getPassengers()) {
            if (passenger instanceof Player player) {
                if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) continue;

                UserData data = userDataManager.get(player.getUniqueId());
                if (data == null) continue;

                double deltaY = event.getTo().getY() - event.getFrom().getY();
                Location toLoc = event.getTo();

                // If ascending in mid-air or flying without water/ground
                boolean inWater = vehicle.isInWater();
                boolean onGround = vehicle.isOnGround();

                if (!inWater && !onGround && deltaY > 0.08) {
                    Block below = toLoc.clone().subtract(0, 1.5, 0).getBlock();
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
                        break;
                    }
                }
            }
        }
    }
}
