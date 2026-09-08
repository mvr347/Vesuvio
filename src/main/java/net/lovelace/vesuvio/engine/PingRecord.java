package net.lovelace.vesuvio.engine;

/**
 * Top-level immutable network ping snapshot.
 *
 * Author: Lovelace
 */
public record PingRecord(int ping, long timestamp) {}
