package net.lovelace.vesuvio.evasion;

import net.lovelace.vesuvio.data.ClickSignature;
import net.lovelace.vesuvio.storage.BanFingerprintRecord;
import net.lovelace.vesuvio.storage.DatabaseManager;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Ban-evasion / alt-account detection.
 *
 * Every time a player is banned, {@link #recordBanFingerprint} snapshots their IP address,
 * client brand, and click "playstyle" signature (the same 64-byte biometric used for the
 * trusted-player fingerprinting mechanic - see ClickSignature/ClickRingBuffer). Two independent
 * signals can later flag a new join as a likely evasion of that ban:
 *
 *  1. IP match ({@link #findIpMatch}) - fast, exact, checked on every join. Catches the common
 *     case (no VPN/proxy).
 *  2. Playstyle signature match ({@link #findSignatureMatch}) - fuzzy Hamming-distance
 *     comparison, checked once a new account's click buffer reaches the same 48-sample baseline
 *     already used for trusted-player fingerprinting. Catches someone who changed IP but kept
 *     their physical clicking habits (Kauri/GrimAC-style biometric continuity).
 *
 * This is intentionally I/O-only: it reads/writes the database and returns plain results.
 * Deciding what to do with a match (raise Risk, alert staff, log a violation) is the caller's
 * job (CheckPipeline / PlayerLifecycleListener), so this class has no Bukkit or CheckResult
 * dependency and stays trivially testable.
 *
 * Author: Lovelace
 */
public final class BanEvasionManager {

    public record IpMatch(String bannedUsername, String reason, long timestamp) {}

    public record SignatureMatch(String bannedUsername, float similarity) {}

    private final DatabaseManager databaseManager;

    public BanEvasionManager(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    /**
     * Snapshots a banned player's fingerprint. Call from an already-async context (this does a
     * synchronous JDBC write).
     */
    public void recordBanFingerprint(UUID uuid, String username, String ipAddress, String clientBrand,
                                      long[] clickSignature, String reason) {
        databaseManager.recordBanFingerprint(new BanFingerprintRecord(
                uuid, username, ipAddress, clientBrand, clickSignature, reason, System.currentTimeMillis()
        ));
    }

    /**
     * Checks whether this IP address was fingerprinted by a previous ban. Call from an
     * already-async context (this does a synchronous JDBC read).
     */
    public Optional<IpMatch> findIpMatch(String ipAddress) {
        BanFingerprintRecord record = databaseManager.findBanFingerprintByIp(ipAddress);
        if (record == null) return Optional.empty();
        return Optional.of(new IpMatch(record.username(), record.reason(), record.timestamp()));
    }

    /**
     * Compares a click signature against recent ban fingerprints and returns the closest match,
     * if it clears the given similarity threshold. Call from an already-async context.
     */
    public Optional<SignatureMatch> findSignatureMatch(long[] signature, float similarityThreshold, int scanLimit) {
        if (signature == null) return Optional.empty();

        List<BanFingerprintRecord> candidates = databaseManager.getRecentBanFingerprints(scanLimit);
        String bestUsername = null;
        float bestSimilarity = 0f;

        for (BanFingerprintRecord candidate : candidates) {
            if (candidate.clickSignature() == null) continue;
            float similarity = ClickSignature.hammingSimilarity(signature, candidate.clickSignature());
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity;
                bestUsername = candidate.username();
            }
        }

        if (bestUsername != null && bestSimilarity >= similarityThreshold) {
            return Optional.of(new SignatureMatch(bestUsername, bestSimilarity));
        }
        return Optional.empty();
    }
}
