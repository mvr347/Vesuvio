package net.lovelace.vesuvio.data;

/**
 * Sliding window of event times, for "N placements in the last 2 s" rules. Replaces a gap-between-two-events measurement:
 * after the server hangs, the queued packets are processed in one burst and the gap between two events is about zero, which
 * looked like a placement every nanosecond. A count over a window is not fooled by a single odd interval, and the window is
 * thrown away when the server hitched inside it, because then the timing of everything in it was set by the server.
 */
public final class RateWindow {

    private static final int CAPACITY = 128;

    private final long windowMillis;
    private final long[] stamps = new long[CAPACITY];
    private int head;
    private int size;

    public RateWindow(long windowMillis) {
        this.windowMillis = windowMillis;
    }

    /**
     * Records an event and returns how many events (including this one) fall inside the window.
     *
     * @param lastHitchMillis wall-clock time of the last server hitch; a hitch inside the window clears it first
     */
    public synchronized int record(long nowMillis, long lastHitchMillis) {
        long from = nowMillis - windowMillis;
        if (lastHitchMillis > from) {
            size = 0;
            head = 0;
        }
        // Drop what has left the window (oldest first).
        while (size > 0 && stamps[(head - size + CAPACITY) % CAPACITY] <= from) size--;
        stamps[head] = nowMillis;
        head = (head + 1) % CAPACITY;
        if (size < CAPACITY) size++;
        return size;
    }

    public synchronized void clear() {
        size = 0;
        head = 0;
    }
}
