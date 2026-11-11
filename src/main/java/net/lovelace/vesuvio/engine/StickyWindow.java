package net.lovelace.vesuvio.engine;

/**
 * Keeps a state "on" for a number of ticks after the server stopped reporting it.
 *
 * <p>The server applies the end of an effect (levitation, flight, a vehicle dismount) at once, but the
 * client learns about it a few ticks later and keeps moving under the old rules. A check that reads the
 * snapshot taken at the start of the tick would otherwise judge those ticks by the new rules.
 * Main thread only.
 */
public final class StickyWindow {

    public static final int DEFAULT_HOLD_TICKS = 20;

    private final int holdTicks;
    private long lastActiveTick = Long.MIN_VALUE / 2;

    // Rolling maximum: one slot per tick of the window.
    private final double[] values;
    private final long[] stamps;

    public StickyWindow() {
        this(DEFAULT_HOLD_TICKS);
    }

    public StickyWindow(int holdTicks) {
        this.holdTicks = holdTicks;
        this.values = new double[holdTicks];
        this.stamps = new long[holdTicks];
        java.util.Arrays.fill(stamps, Long.MIN_VALUE / 2);
    }

    /** True while the state is active or ended no more than {@code holdTicks} ticks ago. */
    public boolean update(long tick, boolean activeNow) {
        if (activeNow) lastActiveTick = tick;
        return tick - lastActiveTick <= holdTicks;
    }

    /** Maximum of the values seen during the last {@code holdTicks} ticks, including this one. */
    public double max(long tick, double value) {
        int slot = (int) Math.floorMod(tick, (long) holdTicks);
        values[slot] = value;
        stamps[slot] = tick;
        double max = value;
        for (int i = 0; i < holdTicks; i++) {
            if (tick - stamps[i] < holdTicks && values[i] > max) max = values[i];
        }
        return max;
    }
}
