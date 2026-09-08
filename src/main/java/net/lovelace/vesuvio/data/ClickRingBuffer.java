package net.lovelace.vesuvio.data;

import java.util.Arrays;

/**
 * High-performance, zero-allocation ring buffer for click intervals (nanoseconds).
 * Runs on Netty/PacketEvents threads with O(1) bitwise operations.
 * Also maintains a 64-byte rolling click signature (8 longs) for fingerprinting.
 *
 * Author: Lovelace
 */
public final class ClickRingBuffer {

    public static final int SIZE = 64;
    private static final int MASK = SIZE - 1;

    private final long[] intervals = new long[SIZE]; // nanoseconds
    private int head = 0;
    private int count = 0;
    private long lastClickNanos = 0;

    // Compact signature (8 long = 64 bytes) representing playstyle fingerprint
    private final long[] signature = new long[8];

    // Burst tracking
    private int burstClicks = 0;
    private long burstStartNanos = 0;
    private boolean lastWasHit = false;

    /**
     * Records a click event. Zero object allocation.
     *
     * @param nowNanos Timestamp in nanoseconds (System.nanoTime())
     * @param isHit    Whether this click registered an entity hit
     */
    public synchronized void addClick(long nowNanos, boolean isHit) {
        if (lastClickNanos != 0) {
            long delta = nowNanos - lastClickNanos;

            // Ignore pauses > 500 ms (resets current burst)
            if (delta > 500_000_000L) {
                burstClicks = 0;
                burstStartNanos = nowNanos;
                lastClickNanos = nowNanos;
                lastWasHit = isHit;
                return;
            }

            // Reject negative or impossibly small delays (< 5 ms / 200 CPS) as packet glitches
            if (delta < 5_000_000L) {
                return;
            }

            intervals[head] = delta;
            head = (head + 1) & MASK;
            if (count < SIZE) {
                count++;
            }

            burstClicks++;
            updateSignature(delta, isHit);
        } else {
            burstStartNanos = nowNanos;
        }

        lastClickNanos = nowNanos;
        lastWasHit = isHit;
    }

    /**
     * Updates the 64-byte rolling signature vector with mathematical properties of the click.
     */
    private void updateSignature(long delta, boolean isHit) {
        long x = delta ^ (delta >>> 16);
        // slot 0: rolling hash
        signature[0] = signature[0] * 31L + x;
        // slot 1: cumulative interval sum
        signature[1] += delta;
        // slot 2: XOR accumulator
        signature[2] ^= delta;
        // slot 3: micro-pause tracking (deltas between 80ms and 150ms)
        if (delta >= 80_000_000L && delta <= 150_000_000L) {
            signature[3] = (signature[3] << 1) ^ delta;
        }
        // slot 4: burst acceleration (change in delta between consecutive clicks)
        int prevIdx = (head - 2) & MASK;
        if (count >= 2) {
            long prevDelta = intervals[prevIdx];
            long accel = delta - prevDelta;
            signature[4] = signature[4] * 17L + accel;
        }
        // slot 5: post-hit timing pattern (behavior right after hitting an entity)
        if (lastWasHit) {
            signature[5] = signature[5] * 23L + delta;
        }
        // slot 6: hit counter
        if (isHit) {
            signature[6]++;
        }
        // slot 7: variance estimator
        long meanEstimate = count > 0 ? (signature[1] / count) : delta;
        long diff = delta - meanEstimate;
        signature[7] += (diff * diff) >>> 12;
    }

    public synchronized boolean isFull() {
        return count == SIZE;
    }

    public synchronized int getCount() {
        return count;
    }

    /**
     * Copies recorded intervals in chronological order into destination array.
     * Zero heap allocation if dest is pre-allocated.
     */
    public synchronized void copyIntervals(long[] dest) {
        int idx = (head - count) & MASK;
        for (int i = 0; i < count; i++) {
            dest[i] = intervals[idx];
            idx = (idx + 1) & MASK;
        }
    }

    public synchronized void reset() {
        head = 0;
        count = 0;
        lastClickNanos = 0;
        burstClicks = 0;
        burstStartNanos = 0;
        lastWasHit = false;
        Arrays.fill(signature, 0L);
    }

    /**
     * Returns a snapshot copy of the 8-long signature vector.
     */
    public synchronized long[] getSignature() {
        return signature.clone();
    }

    public synchronized int getBurstClicks() {
        return burstClicks;
    }

    public synchronized long getLastClickNanos() {
        return lastClickNanos;
    }
}
