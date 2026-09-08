package net.lovelace.vesuvio.api;

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
}
