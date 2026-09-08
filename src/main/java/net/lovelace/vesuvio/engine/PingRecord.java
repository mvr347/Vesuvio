package net.lovelace.vesuvio.engine;

/**
 * Top-level immutable network ping snapshot. emaPing is an exponential moving average of recent
 * ping used by LagCompensator to tell a genuine one-off latency spike apart from sustained
 * jitter (or a deliberately toggled ping) - see LagCompensator#isNetworkSpike.
 *
 * Author: Lovelace
 */
public record PingRecord(int ping, long timestamp, double emaPing) {}
