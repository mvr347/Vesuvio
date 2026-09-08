package net.lovelace.vesuvio.data;

/**
 * High-performance ring buffer for player aim/rotation tracking.
 * Stores yaw and pitch deltas over the last 64 rotation updates.
 *
 * Author: Lovelace
 */
public final class AimRingBuffer {

    public static final int SIZE = 64;
    private static final int MASK = SIZE - 1;

    private final float[] deltaYaw = new float[SIZE];
    private final float[] deltaPitch = new float[SIZE];
    private final long[] timestamps = new long[SIZE];

    private int head = 0;
    private int count = 0;

    private float lastYaw = 0f;
    private float lastPitch = 0f;
    private long lastTimestamp = 0;
    private boolean initialized = false;

    public synchronized void addRotation(float yaw, float pitch, long nowNanos) {
        if (!initialized) {
            lastYaw = yaw;
            lastPitch = pitch;
            lastTimestamp = nowNanos;
            initialized = true;
            return;
        }

        float dy = Math.abs(yaw - lastYaw);
        // Handle 360-degree wrap around
        if (dy > 180f) {
            dy = 360f - dy;
        }
        float dp = Math.abs(pitch - lastPitch);

        deltaYaw[head] = dy;
        deltaPitch[head] = dp;
        timestamps[head] = nowNanos;

        head = (head + 1) & MASK;
        if (count < SIZE) {
            count++;
        }

        lastYaw = yaw;
        lastPitch = pitch;
        lastTimestamp = nowNanos;
    }

    public synchronized boolean isFull() {
        return count == SIZE;
    }

    public synchronized int getCount() {
        return count;
    }

    public synchronized void copyDeltas(float[] outYaw, float[] outPitch) {
        int idx = (head - count) & MASK;
        for (int i = 0; i < count; i++) {
            outYaw[i] = deltaYaw[idx];
            outPitch[i] = deltaPitch[idx];
            idx = (idx + 1) & MASK;
        }
    }

    public synchronized void reset() {
        head = 0;
        count = 0;
        initialized = false;
    }
}
