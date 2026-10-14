package net.lovelace.vesuvio;

import net.lovelace.vesuvio.engine.physics.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ClientWorldTest {
    private static final long NOW = 1_000_000_000L;
    private static final ClientWorld.ChunkKey ORIGIN = new ClientWorld.ChunkKey(0, 0);
    private static final MovementSimulator.Parameters PARAMETERS = new MovementSimulator.Parameters(.1, false, .08, .42, .6, .6, .6, 1.8);
    private static ClientWorld world() { var w = new ClientWorld(); w.configure(999, 0, 1); return w; }
    private static ClientChunk air() { return new ClientChunk(0, new int[][]{{0}}); }
    private static int fence(ClientWorld w) { return w.fence(NOW).orElseThrow(); }
    private static void commit(ClientWorld w) { assertTrue(w.acknowledge(fence(w), NOW + 1)); }
    private static void load(ClientWorld w) { w.stage(new ClientWorld.Load(ORIGIN, air())); commit(w); }
    private static ClientWorld.Blocks change(int x, int y, int z, int id) {
        return new ClientWorld.Blocks(new ClientWorld.ChunkKey(x >> 4, z >> 4), List.of(new ClientWorld.BlockEdit(x, y, z, id)));
    }
    private static PhysicsFrame snapshot(ClientWorld w, double x, double z) {
        var attrs = new PhysicsFrame("overworld", NOW, 0, PARAMETERS, null, null);
        return w.snapshot(attrs, new MotionVector(x, 2, z), id -> id == 0 ? BlockGeometry.AIR : BlockGeometry.CUBE, NOW);
    }

    @Test void deferredGeometryUsesWorldAtReceiptEvenAfterUpdateAndReset() throws Exception {
        var w = world();
        load(w);
        var attrs = new PhysicsFrame("overworld", NOW, 0, PARAMETERS, null, null);
        var captured = w.capture(attrs, new MotionVector(8, 2, 8), NOW);
        w.stage(change(8, 2, 8, 1));
        commit(w);
        assertEquals(1, snapshot(w, 8, 8).collisions().shapes().size());
        w.reset("DIMENSION_CHANGE");
        try (var worker = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var frame = worker.submit(() -> captured.build(id -> {
                assertTrue(Thread.currentThread().isVirtual());
                return id == 0 ? BlockGeometry.AIR : BlockGeometry.CUBE;
            })).get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(frame.usable());
            assertTrue(frame.collisions().shapes().isEmpty());
            assertEquals(NOW, frame.capturedNanos());
        }
    }

    @Test void pendingCaptureDoesNotBecomeKnownWhenLaterAcknowledged() {
        var w = world();
        w.stage(new ClientWorld.Load(ORIGIN, air()));
        var captured = w.capture(new PhysicsFrame("overworld", NOW, 0, PARAMETERS, null, null),
                new MotionVector(8, 2, 8), NOW);
        commit(w);
        var frame = captured.build(id -> { fail("Unknown snapshot must not resolve shapes"); return BlockGeometry.AIR; });
        assertFalse(frame.usable());
        assertEquals("WORLD_PENDING", frame.unavailable());
    }

    @Test void nothingIsKnownUntilChunkWriteAndItsFenceAreAcknowledged() {
        var w = world();
        assertEquals(-1, w.block(8, 2, 8));
        w.stage(new ClientWorld.Load(ORIGIN, air()));
        assertEquals(-1, w.block(8, 2, 8));
        int id = fence(w);
        assertEquals(-1, w.block(8, 2, 8));
        assertTrue(w.acknowledge(id, NOW + 1));
        assertEquals(0, w.block(8, 2, 8));
        assertEquals(1, w.stats().chunks());
    }

    @Test void blockChangeDoesNotUseTheLiveServerOrTreatPendingGeometryAsCertain() {
        var w = world(); load(w);
        w.stage(change(8, 2, 8, 1));
        assertEquals("WORLD_PENDING", snapshot(w, 8, 8).unavailable());
        assertEquals(-1, w.block(8, 2, 8));
        commit(w);
        assertEquals(1, w.block(8, 2, 8));
        assertEquals(1, snapshot(w, 8, 8).collisions().shapes().size());
    }

    @Test void fencesSeparateBatchesAndForeignPongCannotCommitAnything() {
        var w = world(); load(w);
        w.stage(change(8, 2, 8, 1)); int first = fence(w);
        w.stage(change(8, 2, 8, 2)); int second = fence(w);
        assertFalse(w.acknowledge(1234, NOW + 1));
        assertEquals(2, w.stats().pendingFences());
        assertTrue(w.acknowledge(first, NOW + 2));
        assertEquals(-1, w.block(8, 2, 8), "Later pending write still makes the local region ambiguous");
        assertTrue(w.acknowledge(second, NOW + 3));
        assertEquals(2, w.block(8, 2, 8));
        assertTrue(w.acknowledge(first, NOW + 4));
        assertEquals(2, w.block(8, 2, 8), "Duplicate old response never replays the old batch");
    }

    @Test void outOfOrderPongInvalidatesWorldInsteadOfAcknowledgingAnUnseenPrefix() {
        var w = world(); load(w);
        w.stage(change(8, 2, 8, 1)); int first = fence(w);
        w.stage(change(8, 2, 8, 2)); int second = fence(w);
        assertTrue(w.acknowledge(second, NOW + 1));
        assertEquals("WORLD_ACK_ORDER", w.stats().state());
        assertEquals(0, w.stats().chunks());
        assertTrue(w.acknowledge(first, NOW + 2));
        assertEquals(-1, w.block(8, 2, 8));
    }

    @Test void timeoutAndDimensionResetRejectDelayedResponses() {
        var w = world();
        w.stage(new ClientWorld.Load(ORIGIN, air())); int id = fence(w);
        w.expire(NOW + 6_000_000_000L);
        assertEquals("WORLD_ACK_TIMEOUT", w.stats().state());
        assertTrue(w.acknowledge(id, NOW + 6_000_000_001L));
        assertEquals(0, w.stats().chunks());
        w.configure(999, 0, 1); w.stage(new ClientWorld.Load(ORIGIN, air())); id = fence(w);
        w.reset("DIMENSION_CHANGE");
        w.configure(999, -4, 24);
        assertTrue(w.acknowledge(id, NOW + 1));
        assertEquals(0, w.stats().chunks());
    }

    @Test void unloadDoesNotReappearAfterAnIsolatedBlockUpdate() {
        var w = world(); load(w);
        w.stage(new ClientWorld.Unload(ORIGIN)); commit(w);
        assertEquals(-1, w.block(8, 2, 8));
        w.stage(change(8, 2, 8, 1)); commit(w);
        assertEquals(-1, w.block(8, 2, 8));
        assertEquals(0, w.stats().chunks());
    }

    @Test void loadChangeUnloadAndReloadInOneBatchApplyInWireOrder() {
        var w = world();
        w.stage(new ClientWorld.Load(ORIGIN, air()));
        w.stage(change(8, 2, 8, 1));
        w.stage(new ClientWorld.Unload(ORIGIN));
        w.stage(new ClientWorld.Load(ORIGIN, new ClientChunk(0, new int[][]{{2}})));
        w.stage(change(8, 2, 8, 3)); commit(w);
        assertEquals(3, w.block(8, 2, 8));
        assertEquals(2, w.block(9, 2, 8));
    }

    @Test void twoRecipientsCanSeeDifferentBlocksWithoutAffectingEachOther() {
        var first = world(); var second = world(); load(first); load(second);
        first.stage(change(8, 2, 8, 1)); commit(first);
        assertEquals(1, first.block(8, 2, 8));
        assertEquals(0, second.block(8, 2, 8));
    }

    @Test void negativeCoordinatesAndSectionsAreNotRoundedTowardZero() {
        var w = new ClientWorld(); w.configure(999, -4, 2);
        w.stage(new ClientWorld.Load(new ClientWorld.ChunkKey(-1, -1), new ClientChunk(-4, new int[][]{{0}, {0}})));
        w.stage(change(-1, -64, -1, 5)); w.stage(change(-16, -49, -16, 6)); commit(w);
        assertEquals(5, w.block(-1, -64, -1));
        assertEquals(6, w.block(-16, -49, -16));
        assertEquals(0, w.block(-2, -64, -2));
        assertEquals(-1, w.block(0, -64, -1));
    }

    @Test void missingSectionsAndChunksAreUnknownRatherThanAir() {
        var w = world();
        w.stage(new ClientWorld.Load(ORIGIN, new ClientChunk(0, new int[][]{null}))); commit(w);
        assertEquals("UNKNOWN_SECTION", snapshot(w, 8, 8).unavailable());
        assertEquals("WAIT_CHUNKS", snapshot(w, 0, 0).unavailable());
        assertEquals(-1, w.block(8, 20, 8));
    }

    @Test void snapshotsRemainImmutableAfterMoreWorldPackets() {
        var w = world(); load(w);
        var old = snapshot(w, 8, 8);
        w.stage(change(8, 2, 8, 1)); commit(w);
        var current = snapshot(w, 8, 8);
        assertTrue(old.collisions().shapes().isEmpty());
        assertEquals(1, current.collisions().shapes().size());
        assertNotEquals(old.world(), current.world(), "Geometry revision causes predictor warmup");
    }

    @Test void distantPendingUpdatesDoNotDisableLocalGeometry() {
        var w = world(); load(w);
        var old = snapshot(w, 8, 8);
        w.stage(new ClientWorld.Load(new ClientWorld.ChunkKey(10, 10), air()));
        assertTrue(snapshot(w, 8, 8).usable());
        commit(w);
        assertEquals(old.world(), snapshot(w, 8, 8).world());
    }

    @Test void chunkAndQueueBudgetsKeepNearbyDataAndFailClosedOnOverflow() {
        var w = world(); w.focus(0, 0);
        for (int i = 0; i < 50; i++) {
            w.stage(new ClientWorld.Load(new ClientWorld.ChunkKey(i, 0), air())); commit(w);
        }
        assertEquals(ClientWorld.MAX_CHUNKS, w.stats().chunks());
        assertEquals(0, w.block(8, 2, 8), "Center-first server chunk sending must not evict the center");
        assertEquals(-1, w.block(49 * 16, 2, 8));
        for (int i = 0; i < 257; i++) w.stage(change(8, 2, 8, 1));
        assertEquals("WORLD_QUEUE_LIMIT", w.stats().state());
        assertEquals(0, w.stats().pendingChanges());
        assertEquals(0, w.stats().chunks());
    }

    @Test void immutableChunkDoesNotRetainMutablePacketArrays() {
        int[][] packet = {{0}};
        ClientChunk chunk = new ClientChunk(0, packet);
        packet[0][0] = 99;
        var changed = chunk.withBlocks(List.of(new ClientWorld.BlockEdit(1, 2, 3, 8)));
        assertEquals(0, chunk.block(1, 2, 3));
        assertEquals(8, changed.block(1, 2, 3));
        assertEquals(0, changed.block(2, 2, 3));
        assertThrows(IllegalArgumentException.class, () -> new ClientWorld.Blocks(ORIGIN, List.of(new ClientWorld.BlockEdit(16, 0, 0, 1))));
    }

    @Test void clientMiningWaitsForServerActionAckAndThenForPong() {
        var w = world(); load(w);
        w.predict(10, java.util.Set.of(ORIGIN), NOW);
        assertEquals("CLIENT_PREDICTION", snapshot(w, 8, 8).unavailable());
        w.stage(change(8, 2, 8, 1));
        w.stage(new ClientWorld.PredictionAck(10));
        int ping = fence(w);
        assertEquals(-1, w.block(8, 2, 8));
        assertTrue(w.acknowledge(ping, NOW + 1));
        assertEquals(1, w.block(8, 2, 8));
        assertEquals(0, w.stats().clientPredictions());
    }

    @Test void actionAckOnlySettlesItsPrefixAndCancelledPredictionCanRevertWithoutBlockUpdate() {
        var w = world(); load(w);
        var old = snapshot(w, 8, 8);
        w.predict(10, java.util.Set.of(ORIGIN), NOW);
        w.predict(11, java.util.Set.of(ORIGIN), NOW);
        w.stage(new ClientWorld.PredictionAck(10)); commit(w);
        assertEquals(1, w.stats().clientPredictions());
        assertEquals(-1, w.block(8, 2, 8));
        w.stage(new ClientWorld.PredictionAck(11)); commit(w);
        assertEquals(0, w.block(8, 2, 8));
        assertNotEquals(old.world(), snapshot(w, 8, 8).world());
    }

    @Test void missingActionAcknowledgementsCannotLeaveStaleWorldUsable() {
        var w = world(); load(w);
        w.predict(20, java.util.Set.of(ORIGIN), NOW);
        w.expire(NOW + 6_000_000_000L);
        assertEquals("WORLD_PREDICTION_TIMEOUT", w.stats().state());
        assertEquals(-1, w.block(8, 2, 8));
    }

    @Test void backpressuredWritesAreUnknownAndRetainedPayloadIsBudgetedAcrossReset() {
        var w = world(); load(w);
        assertTrue(w.reserve(ORIGIN, 8L * 1024 * 1024));
        assertEquals("WORLD_PENDING", snapshot(w, 8, 8).unavailable());
        assertFalse(w.reserve(ORIGIN, 1));
        assertEquals("WORLD_QUEUE_LIMIT", w.stats().state());
        assertEquals(8L * 1024 * 1024, w.stats().payloadBytes());
        w.release(ORIGIN, 8L * 1024 * 1024);
        assertEquals(0, w.stats().payloadBytes());
        assertEquals(0, w.stats().pendingChanges());
    }

    @Test void detectionPauseInvalidatesReplicaEvenWhenACommandHandlerWasNotUsed() {
        var w = world(); w.synchronizeDetection(0); load(w);
        w.synchronizeDetection(0);
        assertEquals(0, w.block(8, 2, 8));
        w.synchronizeDetection(2);
        assertEquals(-1, w.block(8, 2, 8));
        assertEquals("WAIT_CHUNKS", w.stats().state());
    }

    @Test void actualReplicaFeedsMovementAndRewarmsAroundUnconfirmedMining() {
        var w = world();
        int[] section = new int[4096];
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) section[(1 << 8) | (z << 4) | x] = 1;
        w.stage(new ClientWorld.Load(ORIGIN, new ClientChunk(0, new int[][]{section}))); commit(w);
        var session = new PhysicsSession();
        double z = 8, velocity = 0;
        for (int tick = 0; tick < 12; tick++) {
            long time = NOW + tick * 50_000_000L;
            var position = new MotionVector(8, 2, z);
            var attributes = new PhysicsFrame("overworld", time, 0, PARAMETERS, null, null);
            var view = w.snapshot(attributes, position, id -> id == 0 ? BlockGeometry.AIR : BlockGeometry.CUBE, time);
            session.observe(new PhysicsSession.Packet(position, true, true, 0, time), view, 0);
            if (tick >= 2) assertEquals("MATCH", session.report().status());
            velocity = velocity * .546 + .098; z += velocity;
        }
        w.predict(3, java.util.Set.of(ORIGIN), NOW + 600_000_000L);
        session.observe(new PhysicsSession.Packet(new MotionVector(8, 2, z), true, true, 0, NOW + 600_000_000L), snapshot(w, 8, z), 0);
        assertEquals("CLIENT_PREDICTION", session.report().status());
        w.stage(new ClientWorld.PredictionAck(3)); commit(w);
        var position = new MotionVector(8, 2, z);
        long time = NOW + 650_000_000L;
        var view = w.snapshot(new PhysicsFrame("overworld", time, 0, PARAMETERS, null, null), position,
                id -> id == 0 ? BlockGeometry.AIR : BlockGeometry.CUBE, time);
        session.observe(new PhysicsSession.Packet(position, true, true, 0, time), view, 0);
        assertEquals("WARMUP", session.report().status());
        assertEquals(0, session.report().mismatched());
    }

    @Test void partialShapeAndIceAreTakenFromClientStateNotServerParameters() {
        var w = world(); load(w);
        w.stage(change(8, 1, 8, 1)); commit(w);
        var attrs = new PhysicsFrame("overworld", NOW, 0, PARAMETERS, null, null);
        var frame = w.snapshot(attrs, new MotionVector(8, 2, 8),
                id -> BlockShapeRegistry.resolve(id == 0 ? "minecraft:air" : "minecraft:blue_ice"), NOW);
        assertEquals(.989, frame.parameters().slipperiness());
        assertEquals(new CollisionBox(8, 1, 8, 9, 2, 9), frame.collisions().shapes().getFirst());
    }
}
