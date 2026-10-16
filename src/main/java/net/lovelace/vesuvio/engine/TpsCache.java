package net.lovelace.vesuvio.engine;

/**
 * Last known server TPS, written only by the main-thread snapshot task and read from any thread.
 * Bukkit#getTPS must not be called from netty/virtual threads (CLAUDE.md: main thread only).
 */
public final class TpsCache {
    private static volatile double tps = 20.0;

    private TpsCache() {}

    public static double get() { return tps; }

    /** Main thread only. Ignores NaN/non-positive values so a bad reading can't read as "lagging". */
    public static void update(double[] recent) {
        if (recent != null && recent.length > 0 && recent[0] > 0 && !Double.isNaN(recent[0])) {
            tps = Math.min(20.0, recent[0]);
        }
    }

    /** Test hook. */
    static void set(double value) { tps = value; }
}
