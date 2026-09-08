package net.lovelace.vesuvio.storage;

import java.util.UUID;

/**
 * Top-level immutable violation record for database batching.
 *
 * Author: Lovelace
 */
public record ViolationRecord(
        UUID uuid,
        String username,
        String checkName,
        double vl,
        double confidence,
        String explanation,
        String detailsJson,
        long timestamp
) {}
