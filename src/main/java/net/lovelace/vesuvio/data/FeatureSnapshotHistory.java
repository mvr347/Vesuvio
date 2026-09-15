package net.lovelace.vesuvio.data;

import java.util.ArrayList;
import java.util.List;

/**
 * A short rolling history of a player's click/aim feature vectors.
 *
 * <p>Why: the dataset used to record exactly one feature vector per label - the one that happened
 * to be in the buffer at the instant the ban fired, or the instant the trap was hit. That single
 * window is the least representative moment available. It is captured right after the behaviour
 * that triggered the punishment, so it over-represents the extreme tail of the session, and one
 * row per banned player means the models learn from a handful of points no matter how long the
 * cheater actually played. Worse, a cheat that only engages in bursts is recorded only in its
 * burst state, so the models never see what it looks like the rest of the time.
 *
 * <p>Keeping a few earlier windows and emitting them alongside the triggering one gives several
 * genuinely different views of the same confirmed session. The training script already splits by
 * player ({@code GroupShuffleSplit}), so these extra rows cannot leak across the train/test
 * boundary and inflate the reported metrics - they stay grouped with their player.
 *
 * <p>Bounded and array-copying on insert; a handful of small float arrays per online player.
 * Synchronized because snapshots are taken on the main thread while the collectors may drain on a
 * punishment thread.
 *
 * Author: Lovelace
 */
public final class FeatureSnapshotHistory {

    /** One captured moment. Either vector may be {@code null} if that buffer was not full enough. */
    public record Snapshot(long timestampMillis, float[] click, float[] aim) {}

    private static final int CAPACITY = 8;

    private final Snapshot[] ring = new Snapshot[CAPACITY];
    private int writeIndex = 0;
    private int size = 0;
    private long lastCaptureMillis = 0L;

    /**
     * Whether a capture would be accepted right now, so a caller can skip the feature extraction
     * entirely when the throttle has not elapsed - extraction is the expensive half.
     */
    public synchronized boolean shouldCapture(long nowMillis, long minIntervalMillis) {
        return lastCaptureMillis == 0L || (nowMillis - lastCaptureMillis) >= minIntervalMillis;
    }

    /**
     * Records a snapshot if at least {@code minIntervalMillis} passed since the last one.
     *
     * @return true if it was recorded, so the caller can log/meter captures
     */
    public synchronized boolean capture(long nowMillis, long minIntervalMillis, float[] click, float[] aim) {
        if (click == null && aim == null) return false;
        if (lastCaptureMillis != 0L && (nowMillis - lastCaptureMillis) < minIntervalMillis) return false;

        lastCaptureMillis = nowMillis;
        ring[writeIndex] = new Snapshot(nowMillis, click, aim);
        writeIndex = (writeIndex + 1) % CAPACITY;
        if (size < CAPACITY) size++;
        return true;
    }

    /**
     * The most recent snapshots, newest first, no older than {@code maxAgeMillis} and no more than
     * {@code limit} of them.
     */
    public synchronized List<Snapshot> recent(long nowMillis, long maxAgeMillis, int limit) {
        List<Snapshot> out = new ArrayList<>(Math.min(limit, size));
        for (int i = 1; i <= size && out.size() < limit; i++) {
            Snapshot s = ring[Math.floorMod(writeIndex - i, CAPACITY)];
            if (s == null) continue;
            if (nowMillis - s.timestampMillis() > maxAgeMillis) break; // ring is ordered, older only
            out.add(s);
        }
        return out;
    }

    /** Drops everything - called once a session's history has been harvested into the dataset. */
    public synchronized void clear() {
        java.util.Arrays.fill(ring, null);
        writeIndex = 0;
        size = 0;
        lastCaptureMillis = 0L;
    }
}
