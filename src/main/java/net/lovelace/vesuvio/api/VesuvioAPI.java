package net.lovelace.vesuvio.api;

import java.util.List;
import java.util.UUID;

/**
 * Public Java API for other plugins running on the Paper/Purpur server.
 *
 * Author: Lovelace
 */
public interface VesuvioAPI {

    /**
     * Retrieves the current Trust Score (0.0 to 100.0) of a player.
     */
    double getTrustScore(UUID uuid);

    /**
     * Retrieves the current Risk Index (0.0 to 100.0) of a player.
     */
    double getRiskScore(UUID uuid);

    /**
     * Retrieves the current Violation Level (VL) of a player.
     */
    double getViolationLevel(UUID uuid);

    /**
     * Checks if a player is currently classified as a high-threat suspect.
     */
    boolean isSuspect(UUID uuid);

    /**
     * Checks if a player has a high risk score exceeding the configured threshold.
     */
    boolean isHighRisk(UUID uuid);

    /**
     * Checks if a player is currently queued in the Lava Wave punishment wave.
     */
    boolean isQueuedForWave(UUID uuid);

    /**
     * Returns a human-readable status string for the player ("Чист", "Подозрительный", "Высокий риск").
     */
    String getPlayerStatus(UUID uuid);

    /**
     * Adjusts the trust score of a player.
     */
    void adjustTrust(UUID uuid, double delta);

    /**
     * Adjusts the risk score of a player.
     */
    void adjustRisk(UUID uuid, double delta);

    /**
     * Retrieves the detected client brand of the player.
     */
    String getClientBrand(UUID uuid);

    // -------------------------------------------------------------
    // Web panel integration (e.g. LoveWebAdmin "Vesuvio" tab).
    // These are read-only DTOs, decoupled from the internal engine representation - see
    // WebPanelModels. Consumers should treat an empty Optional-like null / empty-list result
    // as "no data yet", not as an error.
    // -------------------------------------------------------------

    /**
     * Basic-tier: currently online suspects (manual flag, high risk, or non-zero high VL),
     * sorted by Risk Index descending.
     */
    List<WebPanelModels.SuspectInfo> getSuspects();

    /**
     * Basic-tier: most recent punishments issued (kicks/bans/notify-staff), newest first.
     */
    List<WebPanelModels.PunishmentInfo> getRecentPunishments(int limit);

    /**
     * Advanced-tier: raw violation (flag) log across all checks, newest first.
     */
    List<WebPanelModels.ViolationInfo> getRecentViolations(int limit);

    /**
     * Advanced-tier: full biometric/detection snapshot for one player, or null if the player
     * is not currently tracked (offline, or never generated a packet event).
     */
    WebPanelModels.PlayerDetail getPlayerDetail(UUID uuid);

    /**
     * Advanced-tier: engine-wide status (layer toggles, model load state, dataset/classifier
     * size, web server state).
     */
    WebPanelModels.EngineStatus getEngineStatus();

    /**
     * Resets a player's Violation Level to 0. Management action - callers should gate this
     * behind their own "manage" permission tier.
     */
    void resetViolationLevel(UUID uuid);

    /**
     * Sets or clears the manual suspect flag for a player (persists to Vesuvio's database).
     * Management action - callers should gate this behind their own "manage" permission tier.
     */
    void setManualSuspect(UUID uuid, boolean suspect);
}
