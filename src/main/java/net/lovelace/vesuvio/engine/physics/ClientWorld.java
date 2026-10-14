package net.lovelace.vesuvio.engine.physics;

import java.util.*;
import java.util.function.IntFunction;

/**
 * Per-recipient world replica. Writes are staged in outbound order, fenced after the writes,
 * and committed only on the matching, ordered response. No server-world fallback exists.
 */
public final class ClientWorld {
    public static final int MAX_CHUNKS = 32;
    private static final int MAX_CHANGES = 256, MAX_FENCES = 40;
    private static final long MAX_BYTES = 8L * 1024 * 1024, ACK_TIMEOUT = 5_000_000_000L;
    public record ChunkKey(int x, int z) {}
    public record BlockEdit(int x, int y, int z, int stateId) {
        public BlockEdit { if (stateId < 0) throw new IllegalArgumentException("Отрицательный идентификатор состояния"); }
    }
    public sealed interface Change permits Load, Blocks, Unload, PredictionAck {
        ChunkKey key();
        long bytes();
    }
    public record Load(ChunkKey key, ClientChunk chunk) implements Change {
        public long bytes() { return chunk.bytes(); }
    }
    public record Blocks(ChunkKey key, List<BlockEdit> edits) implements Change {
        public Blocks {
            edits = List.copyOf(edits);
            if (edits.size() > 4096) throw new IllegalArgumentException("Превышен лимит обновлений блоков");
            for (var edit : edits) if ((edit.x() >> 4) != key.x() || (edit.z() >> 4) != key.z())
                throw new IllegalArgumentException("Блок за пределами обновляемого чанка");
        }
        public long bytes() { return 16L * edits.size(); }
    }
    public record Unload(ChunkKey key) implements Change { public long bytes() { return 0; } }
    public record PredictionAck(int sequence) implements Change {
        public ChunkKey key() { return null; }
        public long bytes() { return 0; }
    }
    private record Prediction(Set<ChunkKey> chunks, long createdNanos) {}
    private record Batch(int id, long sentNanos, List<Change> changes) {}
    private record Entry(ClientChunk chunk, long revision) {}
    public record Stats(int chunks, int pendingChanges, int pendingFences, long payloadBytes,
                        long generation, long acknowledgedBatches, int clientPredictions, String state) {}

    private final LinkedHashMap<ChunkKey, Entry> committed = new LinkedHashMap<>();
    private final List<Change> staged = new ArrayList<>();
    private final Deque<Batch> pending = new ArrayDeque<>();
    private final Map<ChunkKey, Integer> dirty = new HashMap<>();
    private final Map<ChunkKey, Integer> reservedDirty = new HashMap<>();
    private long reservedBytes;
    private int reservedCount;
    private final NavigableMap<Integer, Prediction> predictions = new TreeMap<>();
    private int lastPredictionAck = -1;
    private final LinkedHashSet<Integer> retiredIds = new LinkedHashSet<>();
    private long generation, revision, committedBytes, pendingBytes, acknowledgements;
    private long detectionEpoch = Long.MIN_VALUE;
    private int changeCount, protocol = -1, minSection, sectionCount;
    private boolean hasFocus;
    private int focusX, focusZ;
    private int nextId = -2_000_000_000 + java.util.concurrent.ThreadLocalRandom.current().nextInt(100_000_000);
    private String status = "WAIT_CHUNKS";

    public synchronized long generation() { return generation; }
    public synchronized void synchronizeDetection(long epoch) {
        if (detectionEpoch == epoch) return;
        if (detectionEpoch != Long.MIN_VALUE) reset("WAIT_CHUNKS");
        detectionEpoch = epoch;
    }
    public synchronized int protocol() { return protocol; }
    public synchronized Stats stats() {
        return new Stats(committed.size(), changeCount + reservedCount, pending.size(), committedBytes + pendingBytes + reservedBytes,
                generation, acknowledgements, predictions.size(), status);
    }
    public synchronized void reset(String reason) {
        for (Batch batch : pending) retire(batch.id());
        committed.clear(); staged.clear(); pending.clear(); dirty.clear();
        predictions.clear(); lastPredictionAck = -1;
        committedBytes = pendingBytes = 0; changeCount = 0; generation++; status = reason;
        protocol = -1; sectionCount = 0;
        hasFocus = false;
    }
    public synchronized void focus(int chunkX, int chunkZ) {
        focusX = chunkX; focusZ = chunkZ; hasFocus = true;
    }
    /** Budget captured packet data waiting for write-completion callbacks, including backpressure. */
    public synchronized boolean reserve(ChunkKey key, long bytes) {
        if (bytes < 0 || pendingBytes + reservedBytes + bytes > MAX_BYTES || reservedCount + changeCount >= MAX_CHANGES) {
            reset("WORLD_QUEUE_LIMIT"); return false;
        }
        reservedCount++; reservedBytes += bytes;
        if (key != null) reservedDirty.merge(key, 1, Integer::sum);
        return true;
    }
    public synchronized void release(ChunkKey key, long bytes) {
        reservedCount--; reservedBytes -= bytes;
        if (key != null) reservedDirty.computeIfPresent(key, (k, count) -> count == 1 ? null : count - 1);
    }
    public synchronized void configure(int protocol, int minSection, int sections) {
        if (sections < 1 || sections > 64) { reset("WORLD_HEIGHT"); return; }
        if (this.protocol == protocol && this.minSection == minSection && sectionCount == sections) return;
        if (this.protocol >= 0) reset("WAIT_CHUNKS");
        this.protocol = protocol; this.minSection = minSection; sectionCount = sections;
    }
    /** Called after the corresponding packet write, not before cancellation or encoding. */
    public synchronized void stage(Change change) {
        if (protocol < 0) return;
        if (change instanceof Load load && (load.chunk().minSection() != minSection || load.chunk().sectionCount() != sectionCount)) {
            reset("WORLD_HEIGHT"); return;
        }
        if (!hasFocus && change instanceof Load) focus(change.key().x(), change.key().z());
        if (changeCount + reservedCount >= MAX_CHANGES || pendingBytes + reservedBytes + change.bytes() > MAX_BYTES) { reset("WORLD_QUEUE_LIMIT"); return; }
        staged.add(change); changeCount++; pendingBytes += change.bytes();
        if (change.key() != null) dirty.merge(change.key(), 1, Integer::sum);
        status = "WORLD_PENDING";
    }
    /** Must be called in the same outbound event loop immediately before writing this Ping. */
    public synchronized OptionalInt fence(long nowNanos) {
        expire(nowNanos);
        if (staged.isEmpty()) return OptionalInt.empty();
        if (pending.size() >= MAX_FENCES) { reset("WORLD_ACK_BACKLOG"); return OptionalInt.empty(); }
        int id = nextId++;
        if (nextId >= -1_000_000_000) nextId = -2_000_000_000;
        pending.addLast(new Batch(id, nowNanos, List.copyOf(staged)));
        staged.clear();
        return OptionalInt.of(id);
    }
    public synchronized void expire(long nowNanos) {
        if (!pending.isEmpty() && nowNanos - pending.getFirst().sentNanos() > ACK_TIMEOUT) reset("WORLD_ACK_TIMEOUT");
        if (predictions.values().stream().anyMatch(p -> nowNanos - p.createdNanos() > ACK_TIMEOUT)) reset("WORLD_PREDICTION_TIMEOUT");
    }
    /** Client-side mining/placement can change collision before any authoritative server reply. */
    public synchronized void predict(int sequence, Set<ChunkKey> affected, long nowNanos) {
        if (protocol < 0) return;
        if (sequence < 0 || sequence <= lastPredictionAck) { reset("WORLD_PREDICTION_ORDER"); return; }
        if (predictions.size() >= 256 || affected.size() > 9) { reset("WORLD_PREDICTION_LIMIT"); return; }
        Prediction previous = predictions.get(sequence);
        Set<ChunkKey> union = new HashSet<>(affected);
        if (previous != null) union.addAll(previous.chunks());
        if (union.size() > 9) { reset("WORLD_PREDICTION_LIMIT"); return; }
        predictions.put(sequence, new Prediction(Set.copyOf(union), previous == null ? nowNanos : previous.createdNanos()));
        status = "CLIENT_PREDICTION";
    }
    public synchronized boolean acknowledge(int id, long nowNanos) {
        if (retiredIds.contains(id)) return true; // Our delayed/duplicate reply; never reapply it.
        Batch head = pending.peekFirst();
        if (head == null || pending.stream().noneMatch(b -> b.id() == id)) return false;
        if (head.id() != id) { reset("WORLD_ACK_ORDER"); return true; }
        if (nowNanos - head.sentNanos() > ACK_TIMEOUT) { reset("WORLD_ACK_TIMEOUT"); return true; }
        pending.removeFirst(); retire(id);
        for (Change change : head.changes()) {
            apply(change);
            changeCount--; pendingBytes -= change.bytes();
            if (change.key() != null) dirty.computeIfPresent(change.key(), (key, count) -> count == 1 ? null : count - 1);
        }
        acknowledgements++;
        status = changeCount > 0 ? "WORLD_PENDING" : !predictions.isEmpty() ? "CLIENT_PREDICTION" : committed.isEmpty() ? "WAIT_CHUNKS" : "READY";
        return true;
    }
    private void apply(Change change) {
        if (change instanceof PredictionAck ack) {
            var settled = predictions.headMap(ack.sequence(), true);
            for (var prediction : settled.values()) for (ChunkKey key : prediction.chunks()) {
                Entry entry = committed.get(key);
                if (entry != null) committed.put(key, new Entry(entry.chunk(), ++revision));
            }
            settled.clear();
            lastPredictionAck = Math.max(lastPredictionAck, ack.sequence());
            return;
        }
        Entry previous = committed.remove(change.key());
        if (previous != null) committedBytes -= previous.chunk().bytes();
        ClientChunk next = change instanceof Load load ? load.chunk()
                : change instanceof Blocks blocks && previous != null ? previous.chunk().withBlocks(blocks.edits()) : null;
        if (next != null && next.bytes() <= MAX_BYTES) {
            committed.put(change.key(), new Entry(next, ++revision)); committedBytes += next.bytes();
        }
        while (committed.size() > MAX_CHUNKS || committedBytes > MAX_BYTES) {
            ChunkKey farthest = committed.keySet().stream().max(Comparator.comparingLong(key ->
                    Math.max(Math.abs((long) key.x() - focusX), Math.abs((long) key.z() - focusZ)))).orElseThrow();
            committedBytes -= committed.remove(farthest).chunk().bytes();
        }
    }
    private void retire(int id) {
        retiredIds.add(id);
        if (retiredIds.size() > 128) retiredIds.remove(retiredIds.iterator().next());
    }
    /** -1 is unknown, including updates in flight. Never infer air from an absent chunk. */
    public synchronized int block(int x, int y, int z) {
        ChunkKey key = new ChunkKey(x >> 4, z >> 4);
        Entry entry = committed.get(key);
        return entry == null || dirty.containsKey(key) || reservedDirty.containsKey(key) || predicted(key) ? -1 : entry.chunk().block(x, y, z);
    }
    private boolean predicted(ChunkKey key) { return predictions.values().stream().anyMatch(p -> p.chunks().contains(key)); }

    /** Capture IDs and revision on receipt; resolve geometry later without holding the world lock. */
    public PhysicsFrame snapshot(PhysicsFrame attributes, MotionVector position,
                                 IntFunction<BlockGeometry> geometry, long nowNanos) {
        return capture(attributes, position, nowNanos).build(geometry);
    }

    public synchronized Capture capture(PhysicsFrame attributes, MotionVector position, long nowNanos) {
        expire(nowNanos);
        if (attributes == null || attributes.parameters() == null || attributes.unavailable() != null)
            return Capture.unavailable(attributes == null ? PhysicsFrame.unknown("NO_ATTRIBUTES") : attributes);
        if (protocol < 0) return Capture.unavailable(PhysicsFrame.unknown(status));
        if (Math.abs(position.x()) > 30_000_000 || Math.abs(position.z()) > 30_000_000 || Math.abs(position.y()) > 30_000_000)
            return Capture.unavailable(PhysicsFrame.unknown("OUTSIDE_WORLD"));
        int bx = (int) Math.floor(position.x()), by = (int) Math.floor(position.y()), bz = (int) Math.floor(position.z());
        focus(bx >> 4, bz >> 4);
        if (by - 2 < minSection * 16 || by + 4 >= (minSection + sectionCount) * 16)
            return Capture.unavailable(PhysicsFrame.unknown("WORLD_HEIGHT"));
        long localRevision = 1;
        Map<ChunkKey, ClientChunk> region = new HashMap<>();
        for (int cx = (bx - 2) >> 4; cx <= (bx + 2) >> 4; cx++) for (int cz = (bz - 2) >> 4; cz <= (bz + 2) >> 4; cz++) {
            ChunkKey key = new ChunkKey(cx, cz);
            if (dirty.containsKey(key) || reservedDirty.containsKey(key)) return Capture.unavailable(PhysicsFrame.unknown("WORLD_PENDING"));
            if (predicted(key)) return Capture.unavailable(PhysicsFrame.unknown("CLIENT_PREDICTION"));
            Entry entry = committed.get(key);
            if (entry == null) return Capture.unavailable(PhysicsFrame.unknown("WAIT_CHUNKS"));
            localRevision = 31 * localRevision + entry.revision();
            region.put(key, entry.chunk());
        }
        int[] ids = new int[175];
        int index = 0;
        for (int x = bx - 2; x <= bx + 2; x++) for (int z = bz - 2; z <= bz + 2; z++) for (int y = by - 2; y <= by + 4; y++) {
            int id = region.get(new ChunkKey(x >> 4, z >> 4)).block(x, y, z);
            if (id < 0) return Capture.unavailable(PhysicsFrame.unknown("UNKNOWN_SECTION"));
            ids[index++] = id;
        }
        int support = region.get(new ChunkKey(bx >> 4, bz >> 4)).block(bx, (int) Math.floor(position.y() - .5000001), bz);
        if (support < 0) return Capture.unavailable(PhysicsFrame.unknown("UNKNOWN_SECTION"));
        return new Capture(attributes, attributes.world() + "/replica:" + generation + ":" + localRevision,
                protocol, bx, by, bz, ids, support);
    }

    /** All arrays are private and freshly allocated; no mutable world or server API is retained. */
    public static final class Capture {
        private final PhysicsFrame attributes;
        private final String revision;
        private final int protocol, bx, by, bz, support;
        private final int[] ids;
        private Capture(PhysicsFrame attributes, String revision, int protocol, int bx, int by, int bz, int[] ids, int support) {
            this.attributes = attributes; this.revision = revision; this.protocol = protocol;
            this.bx = bx; this.by = by; this.bz = bz; this.ids = ids; this.support = support;
        }
        private static Capture unavailable(PhysicsFrame frame) { return new Capture(frame, null, -1, 0, 0, 0, null, -1); }
        public int protocol() { return protocol; }
        public PhysicsFrame build(IntFunction<BlockGeometry> geometry) {
            if (ids == null) return attributes;
            List<CollisionBox> boxes = new ArrayList<>();
            int index = 0;
            for (int x = bx - 2; x <= bx + 2; x++) for (int z = bz - 2; z <= bz + 2; z++) for (int y = by - 2; y <= by + 4; y++) {
                BlockGeometry shape = geometry.apply(ids[index++]);
                if (!shape.supported()) return PhysicsFrame.unknown("UNSUPPORTED_SHAPE");
                for (CollisionBox box : shape.shapes()) {
                    boxes.add(box.move(new MotionVector(x, y, z)));
                    if (boxes.size() > 512) return PhysicsFrame.unknown("SHAPE_BUDGET");
                }
            }
            var p = attributes.parameters();
            var parameters = new MovementSimulator.Parameters(p.movementSpeed(), p.sprintAttributeApplied(), p.gravity(),
                    p.jumpStrength(), p.stepHeight(), geometry.apply(support).slipperiness(), p.width(), p.height());
            return new PhysicsFrame(revision, attributes.capturedNanos(), attributes.resetEpoch(), parameters,
                    new CollisionSpace(new CollisionBox(bx - 1, by - 1, bz - 1, bx + 2, by + 4, bz + 2), boxes), null);
        }
    }
}
