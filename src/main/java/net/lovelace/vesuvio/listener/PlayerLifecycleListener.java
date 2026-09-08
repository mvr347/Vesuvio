package net.lovelace.vesuvio.listener;

import net.lovelace.vesuvio.check.selflearning.SelfLearningManager;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.data.UserDataManager;
import net.lovelace.vesuvio.packet.BrandPacketListener;
import net.lovelace.vesuvio.staff.SpectateManager;
import net.lovelace.vesuvio.storage.DatabaseManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Handles Bukkit player join and quit lifecycle events.
 * Ensures clean memory allocation and state synchronization.
 *
 * Author: Lovelace
 */
public final class PlayerLifecycleListener implements Listener {

    private final UserDataManager userDataManager;
    private final DatabaseManager databaseManager;
    private final SpectateManager spectateManager;
    private final BrandPacketListener brandListener;
    private final net.lovelace.vesuvio.engine.LagCompensator lagCompensator;
    private final net.lovelace.vesuvio.engine.HitboxHistoryTracker hitboxTracker;
    private final WorldInteractionListener worldInteractionListener;
    private final SelfLearningManager selfLearningManager;

    public PlayerLifecycleListener(UserDataManager userDataManager,
                                   DatabaseManager databaseManager,
                                   SpectateManager spectateManager,
                                   BrandPacketListener brandListener,
                                   net.lovelace.vesuvio.engine.LagCompensator lagCompensator,
                                   net.lovelace.vesuvio.engine.HitboxHistoryTracker hitboxTracker,
                                   WorldInteractionListener worldInteractionListener,
                                   SelfLearningManager selfLearningManager) {
        this.userDataManager = userDataManager;
        this.databaseManager = databaseManager;
        this.spectateManager = spectateManager;
        this.brandListener = brandListener;
        this.lagCompensator = lagCompensator;
        this.hitboxTracker = hitboxTracker;
        this.worldInteractionListener = worldInteractionListener;
        this.selfLearningManager = selfLearningManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UserData data = userDataManager.getOrCreate(player);

        // Asynchronously load persistent player metrics from database
        Thread.ofVirtual().start(() -> {
            DatabaseManager.PlayerProfile profile = databaseManager.loadPlayer(player.getUniqueId());
            if (profile != null) {
                data.setTrustScore(profile.trustScore());
                data.setRiskIndex(profile.riskScore());
                data.setManualSuspect(profile.isSuspect());
                if (profile.clientBrand() != null && !profile.clientBrand().isBlank()) {
                    data.setClientBrand(profile.clientBrand());
                }
            }
        });

        // Try reading brand from Paper API
        try {
            String brand = player.getClientBrandName();
            if (brand != null && !brand.isBlank()) {
                brandListener.processBrand(player, brand);
            }
        } catch (Throwable ignored) {}
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UserData data = userDataManager.get(player.getUniqueId());

        if (data != null) {
            // Persist metrics before eviction
            databaseManager.savePlayerSync(
                    player.getUniqueId(),
                    player.getName(),
                    data.getTrustScore(),
                    data.getRiskIndex(),
                    data.getClientBrand(),
                    data.isManualSuspect()
            );
        }

        if (spectateManager.isSpectating(player.getUniqueId())) {
            spectateManager.stopSpectating(player);
        }

        // Evict from memory and trackers
        userDataManager.remove(player.getUniqueId());
        if (lagCompensator != null) {
            lagCompensator.remove(player.getUniqueId());
        }
        if (hitboxTracker != null) {
            hitboxTracker.remove(player.getUniqueId());
        }
        if (worldInteractionListener != null) {
            worldInteractionListener.forgetPlayer(player.getUniqueId());
        }
        if (selfLearningManager != null) {
            selfLearningManager.getAutoDatasetCollector().forgetPlayer(player.getUniqueId());
        }
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(org.bukkit.event.player.PlayerTeleportEvent event) {
        UserData data = userDataManager.get(event.getPlayer().getUniqueId());
        if (data != null) {
            data.setInitialRotation(false);
            data.resetGcdStreak();
        }
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onRespawn(org.bukkit.event.player.PlayerRespawnEvent event) {
        UserData data = userDataManager.get(event.getPlayer().getUniqueId());
        if (data != null) {
            data.setInitialRotation(false);
            data.resetGcdStreak();
        }
    }
}
