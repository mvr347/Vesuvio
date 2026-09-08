package net.lovelace.vesuvio.storage;

import java.util.UUID;

/**
 * Top-level immutable punishment log record for database batching.
 *
 * Author: Lovelace
 */
public record PunishmentRecord(
        UUID uuid,
        String username,
        String action,
        String reason,
        long timestamp
) {}
