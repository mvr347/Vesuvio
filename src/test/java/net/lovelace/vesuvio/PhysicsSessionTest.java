package net.lovelace.vesuvio;

import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.engine.physics.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PhysicsSessionTest {
    private static final long START = 1_000_000_000L;
    private static final long TICK = 50_000_000L;
    private static final MovementSimulator.Parameters NORMAL = new MovementSimulator.Parameters(.1, false, .08, .42, .6, .6, .6, 1.8);
    private static final CollisionSpace WORLD = new CollisionSpace(new CollisionBox(-100, -10, -100, 100, 20, 100),
            List.of(new CollisionBox(-100, -1, -100, 100, 0, 100)));
    private static PhysicsFrame frame(long time) { return new PhysicsFrame("overworld", time, 0, NORMAL, WORLD, null); }
    private static PhysicsSession.Packet packet(long time, double z) {
        return new PhysicsSession.Packet(new MotionVector(0, 0, z), true, true, 0, time);
    }
    private static void warmup(PhysicsSession session) {
        session.observe(packet(START, 0), frame(START), 0);
        session.observe(packet(START + TICK, .098), frame(START + TICK), 0);
    }

    @Test void independentWalkingRecurrenceRemainsMatchedForManyTicks() {
        var session = new PhysicsSession();
        double position = 0, velocity = 0;
        for (int i = 0; i < 120; i++) {
            long time = START + i * TICK;
            session.observe(packet(time, position), frame(time), 0);
            if (i >= 2) assertEquals("MATCH", session.report().status(), "tick " + i);
            velocity = velocity * .546 + .098;
            position += velocity;
        }
        assertEquals(118, session.report().compared());
        assertEquals(0, session.report().mismatched());
        assertTrue(session.report().maximumResidual() < 1e-9);
    }

    @Test void impossibleAccelerationIsReportedAndRewarmsWithoutViolations() {
        var data = new UserData(UUID.randomUUID(), "PhysicsSubject");
        data.addVl(3);
        data.setPhysicsObserved(true);
        var session = data.physicsSession();
        warmup(session);
        session.observe(packet(START + 2 * TICK, 1.2), frame(START + 2 * TICK), 0);
        assertEquals("MISMATCH", session.report().status());
        assertTrue(session.report().residual() > .5);
        assertEquals(1, session.report().mismatched());
        assertEquals(3, data.getVl());
        session.observe(packet(START + 3 * TICK, 1.3), frame(START + 3 * TICK), 0);
        assertEquals("WARMUP", session.report().status());
    }

    @Test void independentTurningTrajectoryKeepsPacketYawAssociatedWithMovement() {
        var session = new PhysicsSession();
        double x = 0, z = 0, vx = 0, vz = 0;
        float lastYaw = 0;
        for (int i = 0; i < 90; i++) {
            long time = START + i * TICK;
            session.observe(new PhysicsSession.Packet(new MotionVector(x, 0, z), true, true, lastYaw, time), frame(time), 0);
            if (i >= 2) assertEquals("MATCH", session.report().status(), "turn tick " + i);
            lastYaw = i * 7;
            // Velocity is carried after the previous step; the profile snaps sub-0.003 components.
            vx = Math.abs(vx * .546) < .003 ? 0 : vx * .546;
            vz = Math.abs(vz * .546) < .003 ? 0 : vz * .546;
            vx -= Math.sin(Math.toRadians(lastYaw)) * .098;
            vz += Math.cos(Math.toRadians(lastYaw)) * .098;
            x += vx; z += vz;
        }
        assertEquals(0, session.report().mismatched());
    }

    @Test void independentJumpTrajectoryDoesNotTreatVerticalMovementAsFlight() {
        var session = new PhysicsSession();
        session.observe(packet(START, 0), frame(START), 0);
        session.observe(packet(START + TICK, 0), frame(START + TICK), 0);
        double y = 0, vy = .42;
        for (int i = 2; i < 12; i++) {
            long time = START + i * TICK;
            y += vy;
            session.observe(new PhysicsSession.Packet(new MotionVector(0, y, 0), false, true, 0, time), frame(time), 0);
            assertEquals("MATCH", session.report().status(), "jump tick " + i);
            vy = (vy - .08) * .98;
            if (Math.abs(vy) < .003) vy = 0;
        }
    }

    @Test void walkingThroughSolidWallProducesGeometricMismatch() {
        var session = new PhysicsSession();
        var wallWorld = new CollisionSpace(WORLD.knownBounds(), List.of(WORLD.shapes().getFirst(),
                new CollisionBox(-5, 0, .5, 5, 4, 1.5)));
        session.observe(packet(START, 0), new PhysicsFrame("overworld", START, 0, NORMAL, wallWorld, null), 0);
        session.observe(packet(START + TICK, .098), new PhysicsFrame("overworld", START + TICK, 0, NORMAL, wallWorld, null), 0);
        session.observe(packet(START + 2 * TICK, .4), new PhysicsFrame("overworld", START + 2 * TICK, 0, NORMAL, wallWorld, null), 0);
        assertEquals("MISMATCH", session.report().status());
        assertEquals(.2, session.report().predicted().z(), 1e-10);
        assertEquals(.2, session.report().residual(), 1e-10);
    }

    @Test void hoveringInAirDivergesFromGravity() {
        var session = new PhysicsSession();
        for (int i = 0; i < 3; i++) {
            long time = START + i * TICK;
            session.observe(new PhysicsSession.Packet(new MotionVector(0, 5, 0), false, true, 0, time), frame(time), 0);
        }
        assertEquals("MISMATCH", session.report().status());
        assertEquals(.0784, session.report().residual(), 1e-12);
    }

    @Test void movementWithoutPositionAndTimingGapsNeverBecomeZeroMotionEvidence() {
        var session = new PhysicsSession();
        warmup(session);
        session.observe(new PhysicsSession.Packet(null, true, true, 90, START + 2 * TICK), frame(START + 2 * TICK), 0);
        assertEquals("NO_POSITION", session.report().status());
        session.observe(packet(START + 3 * TICK, 10), frame(START + 3 * TICK), 0);
        assertEquals("WARMUP", session.report().status());
        session.observe(packet(START + 25 * TICK, 30), frame(START + 25 * TICK), 0);
        assertEquals("TIMING_UNCERTAIN", session.report().status());
        assertEquals(0, session.report().compared());
    }

    @Test void staleFutureAndPreTeleportSnapshotsAreRejected() {
        for (int scenario = 0; scenario < 3; scenario++) {
            var session = new PhysicsSession();
            warmup(session);
            long time = START + 2 * TICK;
            var snapshot = scenario == 0 ? frame(time - 200_000_000L) : scenario == 1 ? frame(time + 1) : frame(time);
            session.observe(packet(time, 50), snapshot, scenario == 2 ? 1 : 0);
            assertEquals("STALE_WORLD", session.report().status());
            assertEquals(0, session.report().compared());
        }
    }

    @Test void changingWorldOrParametersRewarms() {
        var session = new PhysicsSession();
        warmup(session);
        long time = START + 2 * TICK;
        session.observe(packet(time, 80), new PhysicsFrame("nether", time, 0, NORMAL, WORLD, null), 0);
        assertEquals("WARMUP", session.report().status());
        var custom = new MovementSimulator.Parameters(.4, false, .08, .42, .6, .6, .6, 1.8);
        session.observe(packet(time + TICK, 90), new PhysicsFrame("nether", time + TICK, 0, custom, WORLD, null), 0);
        assertEquals("WARMUP", session.report().status());
        assertEquals(0, session.report().compared());
    }

    @Test void unknownTerrainHasExplicitReasonAndResetsHistory() {
        var session = new PhysicsSession();
        warmup(session);
        session.observe(packet(START + 2 * TICK, 60), PhysicsFrame.unknown("UNLOADED_CHUNK"), 0);
        assertEquals("UNLOADED_CHUNK", session.report().status());
        session.observe(packet(START + 3 * TICK, 70), frame(START + 3 * TICK), 0);
        assertEquals("WARMUP", session.report().status());
    }

    @Test void toggleAndTeleportInvalidateSessionAndFrames() {
        var data = new UserData(UUID.randomUUID(), "RTP");
        data.setPhysicsObserved(true);
        long epoch = data.getPhysicsObservationEpoch();
        warmup(data.physicsSession());
        data.setPhysicsFrame(frame(START));
        data.recordTeleport();
        assertEquals("MOVEMENT_RESET", data.physicsSession().report().status());
        assertFalse(data.getPhysicsFrame().usable());
        data.setPhysicsObserved(false);
        data.setPhysicsObserved(true);
        assertTrue(data.getPhysicsObservationEpoch() > epoch);
        assertFalse(data.getPhysicsFrame().usable());
        assertEquals("WARMUP", data.physicsSession().report().status());
    }
}
