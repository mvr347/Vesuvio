package net.lovelace.vesuvio.engine;

/**
 * Wall-clock time of the last server hitch (a gap between two ticks much longer than 50 ms). Written once per tick by the
 * main-thread scheduler task, readable from any thread. Events handled on the main thread right after a hitch arrive in a
 * burst; whatever they measured against the server clock says nothing about the client.
 */
public final class ServerHitch {

    /** Tick-to-tick gap above which the server is considered to have hitched (3 ticks). */
    private static final long HITCH_GAP_MILLIS = 150L;

    private static volatile long lastTickMillis = 0L;
    private static volatile long lastHitchMillis = 0L;

    private ServerHitch() {}

    /** Main thread, once per tick. */
    public static void tick(long nowMillis) {
        long previous = lastTickMillis;
        if (previous != 0L && nowMillis - previous > HITCH_GAP_MILLIS) lastHitchMillis = nowMillis;
        lastTickMillis = nowMillis;
    }

    public static long lastHitchMillis() {
        return lastHitchMillis;
    }

    /** Test hook. */
    public static void reset() {
        lastTickMillis = 0L;
        lastHitchMillis = 0L;
    }
}
