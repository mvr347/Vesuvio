package net.lovelace.vesuvio.storage;

import java.util.UUID;

/**
 * A fingerprint snapshot taken the moment a player is banned - IP address, client brand, and
 * click-signature "playstyle" biometric - used by BanEvasionManager to recognize the same
 * person returning on a fresh account.
 *
 * Author: Lovelace
 */
public record BanFingerprintRecord(
        UUID uuid,
        String username,
        String ipAddress,
        String clientBrand,
        long[] clickSignature,
        String reason,
        long timestamp
) {}
