package net.lovelace.vesuvio.data;

/**
 * Per-tick record of where a player <em>had</em> to look to be on their target versus where they
 * actually looked.
 *
 * <h2>Why this exists separately from {@link AimRingBuffer}</h2>
 * {@code AimRingBuffer} stores absolute rotation deltas: "the camera turned 3.2° this tick". That
 * is blind to the only thing that distinguishes aim assistance from a human - the relationship
 * between the camera and the target. Every genuinely informative aim signal is target-relative
 * (tracking error, how the error responds when the target manoeuvres, how far the camera lags
 * behind the target's motion), and none of it can be reconstructed from absolute deltas after the
 * fact. This buffer is the missing input for those checks, and for target-relative ML features.
 *
 * <p>Filled on the main thread once per tick by {@code engine.AimTrackingService} (the Bukkit
 * world/entity reads have to happen there), consumed off-thread by the checks - hence the
 * synchronization.
 *
 * Author: Lovelace
 */
public final class AimTrackingBuffer {

    /** 128 ticks ≈ 6.4s at 20 TPS - long enough to hold several target manoeuvres. */
    public static final int SIZE = 128;
    private static final int MASK = SIZE - 1;

    private final float[] requiredYaw = new float[SIZE];
    private final float[] requiredPitch = new float[SIZE];
    private final float[] actualYaw = new float[SIZE];
    private final float[] actualPitch = new float[SIZE];
    private final long[] timestamps = new long[SIZE];

    private int head = 0;
    private int count = 0;

    public synchronized void record(float requiredYaw, float requiredPitch,
                                     float actualYaw, float actualPitch, long nowNanos) {
        this.requiredYaw[head] = requiredYaw;
        this.requiredPitch[head] = requiredPitch;
        this.actualYaw[head] = actualYaw;
        this.actualPitch[head] = actualPitch;
        this.timestamps[head] = nowNanos;

        head = (head + 1) & MASK;
        if (count < SIZE) count++;
    }

    public synchronized int getCount() {
        return count;
    }

    /**
     * Copies the buffer in chronological order into caller-provided arrays (each at least
     * {@link #SIZE} long).
     *
     * @return the number of entries written
     */
    public synchronized int copy(float[] outRequiredYaw, float[] outActualYaw,
                                  float[] outRequiredPitch, float[] outActualPitch, long[] outTimestamps) {
        int idx = (head - count) & MASK;
        for (int i = 0; i < count; i++) {
            outRequiredYaw[i] = requiredYaw[idx];
            outActualYaw[i] = actualYaw[idx];
            outRequiredPitch[i] = requiredPitch[idx];
            outActualPitch[i] = actualPitch[idx];
            outTimestamps[i] = timestamps[idx];
            idx = (idx + 1) & MASK;
        }
        return count;
    }

    public synchronized void reset() {
        head = 0;
        count = 0;
    }

    /** Signed shortest angular difference a-b, wrapped to (-180, 180]. */
    public static float wrapDegrees(float a, float b) {
        float d = (a - b) % 360f;
        if (d > 180f) d -= 360f;
        if (d <= -180f) d += 360f;
        return d;
    }
}
