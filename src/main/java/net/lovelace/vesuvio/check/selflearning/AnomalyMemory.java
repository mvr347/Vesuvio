package net.lovelace.vesuvio.check.selflearning;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.data.ClickSignature;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Advanced Mechanic 2.3: Anomaly Memory.
 * Remembers near-threshold behavioral patterns (confidence between 0.45 and 0.82).
 * When repetitive borderline patterns recur, amplifies player Risk Index.
 *
 * Author: Lovelace
 */
public final class AnomalyMemory {

    private static final int QUEUE_SIZE = 12;
    private static final float SIMILARITY_THRESHOLD = 0.78f;

    // Cache mapping UUID -> Circular FIFO of near-flag click signatures
    private final LoadingCache<UUID, Deque<long[]>> memory = Caffeine.newBuilder()
            .maximumSize(2000)
            .expireAfterAccess(30, TimeUnit.MINUTES)
            .build(u -> new ArrayDeque<>(QUEUE_SIZE));

    /**
     * Observes a check result. If confidence is in the borderline zone, records the signature.
     */
    public void observe(UUID uuid, long[] signature, CheckResult result) {
        if (signature == null || result == null) return;

        if (result.confidence() >= 0.45 && result.confidence() <= 0.82) {
            Deque<long[]> queue = memory.get(uuid);
            synchronized (queue) {
                if (queue.size() >= QUEUE_SIZE) {
                    queue.pollFirst();
                }
                queue.addLast(signature.clone());
            }
        }
    }

    /**
     * Calculates the proportion of historical borderline anomalies that closely match the current pattern.
     *
     * @return Score in range [0.0, 1.0]
     */
    public float getRepeatScore(UUID uuid, long[] current) {
        if (current == null) return 0f;

        Deque<long[]> history = memory.getIfPresent(uuid);
        if (history == null) return 0f;

        synchronized (history) {
            if (history.isEmpty()) return 0f;

            int matches = 0;
            for (long[] old : history) {
                if (ClickSignature.hammingSimilarity(old, current) >= SIMILARITY_THRESHOLD) {
                    matches++;
                }
            }
            return (float) matches / history.size();
        }
    }

    public void clear(UUID uuid) {
        memory.invalidate(uuid);
    }
}
