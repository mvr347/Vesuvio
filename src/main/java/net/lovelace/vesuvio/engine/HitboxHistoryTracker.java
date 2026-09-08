package net.lovelace.vesuvio.engine;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Historical Hitbox & Latency Compensated Bounding Box Tracker (inspired by GrimAC).
 * Keeps a rolling 30-tick history of entity positions to rewind target hitboxes
 * accurately based on attacker latency, enabling false-positive-free Reach detection.
 *
 * Author: Lovelace
 */
public final class HitboxHistoryTracker {

    private static final int MAX_SNAPSHOTS = 40; // 2 seconds of tick history

    public record BoxSnapshot(
            double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ,
            long timestampNanos
    ) {
        public double distanceTo(double x, double y, double z) {
            double cx = Math.max(minX, Math.min(x, maxX));
            double cy = Math.max(minY, Math.min(y, maxY));
            double cz = Math.max(minZ, Math.min(z, maxZ));
            double dx = x - cx;
            double dy = y - cy;
            double dz = z - cz;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    private final Map<UUID, Deque<BoxSnapshot>> history = new ConcurrentHashMap<>();

    /**
     * Called every server tick to capture the current bounding boxes of all online players.
     */
    public void recordTick() {
        long now = System.nanoTime();
        for (Player p : Bukkit.getOnlinePlayers()) {
            BoundingBox bb = p.getBoundingBox();
            BoxSnapshot snap = new BoxSnapshot(
                    bb.getMinX(), bb.getMinY(), bb.getMinZ(),
                    bb.getMaxX(), bb.getMaxY(), bb.getMaxZ(),
                    now
            );

            Deque<BoxSnapshot> queue = history.computeIfAbsent(p.getUniqueId(), id -> new ArrayDeque<>(MAX_SNAPSHOTS));
            synchronized (queue) {
                if (queue.size() >= MAX_SNAPSHOTS) {
                    queue.pollFirst();
                }
                queue.addLast(snap);
            }
        }
    }

    /**
     * Returns the target's bounding box rewound to the moment seen on attacker's screen.
     */
    public BoxSnapshot getRewoundBox(Player target, int attackerPingMs) {
        Deque<BoxSnapshot> queue = history.get(target.getUniqueId());
        if (queue == null) {
            BoundingBox bb = target.getBoundingBox();
            return new BoxSnapshot(bb.getMinX(), bb.getMinY(), bb.getMinZ(), bb.getMaxX(), bb.getMaxY(), bb.getMaxZ(), System.nanoTime());
        }

        long targetTimeNanos = System.nanoTime() - (attackerPingMs * 1_000_000L);
        BoxSnapshot best = null;
        long minDiff = Long.MAX_VALUE;

        synchronized (queue) {
            for (BoxSnapshot snap : queue) {
                long diff = Math.abs(snap.timestampNanos() - targetTimeNanos);
                if (diff < minDiff) {
                    minDiff = diff;
                    best = snap;
                }
            }
        }

        if (best == null) {
            BoundingBox bb = target.getBoundingBox();
            return new BoxSnapshot(bb.getMinX(), bb.getMinY(), bb.getMinZ(), bb.getMaxX(), bb.getMaxY(), bb.getMaxZ(), System.nanoTime());
        }

        return best;
    }

    public void remove(UUID uuid) {
        history.remove(uuid);
    }
}
