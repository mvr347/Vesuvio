package net.lovelace.vesuvio;

import net.lovelace.vesuvio.engine.physics.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class PhysicsSimulationTest {
    private final MovementSimulator simulator = new MovementSimulator();
    private static final MovementSimulator.Parameters NORMAL = new MovementSimulator.Parameters(.1, false, .08, .42, .6, .6, .6, 1.8);
    private static final CollisionBox BOUNDS = new CollisionBox(-20, -10, -20, 20, 20, 20);
    private static final CollisionBox FLOOR = new CollisionBox(-20, -1, -20, 20, 0, 20);

    private static CollisionSpace world(CollisionBox... obstacles) {
        var shapes = new ArrayList<>(List.of(obstacles));
        shapes.add(FLOOR);
        return new CollisionSpace(BOUNDS, shapes);
    }
    private static MovementSimulator.State standing() {
        return new MovementSimulator.State(MotionVector.ZERO, new MotionVector(0, -.0784, 0), true);
    }
    private static MovementSimulator.Input input(int forward, int strafe, float yaw, boolean sprint, boolean jump) {
        return new MovementSimulator.Input(forward, strafe, yaw, sprint, jump, false);
    }

    @Test void walkingHasIndependentAnalyticalFirstAndSecondTick() {
        var step = simulator.step(standing(), NORMAL, input(1, 0, 0, false, false), world()).orElseThrow();
        assertEquals(.098, step.displacement().z(), 1e-12);
        assertEquals(.053508, step.state().velocity().z(), 1e-12);
        assertTrue(step.state().grounded());
        var next = simulator.step(step.state(), NORMAL, input(1, 0, 0, false, false), world()).orElseThrow();
        assertEquals(.151508, next.displacement().z(), 1e-12);
        assertEquals(.249508, next.state().position().z(), 1e-12);
    }

    @Test void resolvedSprintAttributeIsNotMultipliedTwice() {
        var resolved = new MovementSimulator.Parameters(.13, true, .08, .42, .6, .6, .6, 1.8);
        var sprint = input(1, 0, 0, true, false);
        assertEquals(.1274, simulator.step(standing(), resolved, sprint, world()).orElseThrow().displacement().z(), 1e-12);
        assertEquals(.1274, simulator.step(standing(), NORMAL, sprint, world()).orElseThrow().displacement().z(), 1e-12);
        assertEquals(.098, simulator.step(standing(), resolved, input(1, 0, 0, false, false), world()).orElseThrow().displacement().z(), 1e-12);
    }

    @Test void diagonalInputCannotIncreaseAccelerationAndYawRotatesIt() {
        var diagonal = simulator.step(standing(), NORMAL, input(1, 1, 0, false, false), world()).orElseThrow();
        assertEquals(.098, Math.sqrt(diagonal.displacement().horizontalSquared()), 1e-12);
        assertEquals(.098 / Math.sqrt(2), diagonal.displacement().x(), 1e-12);
        var rotated = simulator.step(standing(), NORMAL, input(1, 0, 90, false, false), world()).orElseThrow();
        assertEquals(-.098, rotated.displacement().x(), 1e-12);
        assertEquals(0, rotated.displacement().z(), 1e-12);
    }

    @Test void jumpAndFallFollowIndependentNumericalTrajectory() {
        var step = simulator.step(standing(), NORMAL, input(0, 0, 0, false, true), world()).orElseThrow();
        assertEquals(.42, step.displacement().y(), 1e-12);
        assertEquals(.3332, step.state().velocity().y(), 1e-12);
        assertFalse(step.state().grounded());
        double[] displacements = {.3332, .248136, .16477328, .0830778144, .003016258112, -.07544406705024};
        for (double y : displacements) {
            step = simulator.step(step.state(), NORMAL, input(0, 0, 0, false, false), world()).orElseThrow();
            assertEquals(y, step.displacement().y(), 1e-11);
        }
        for (int i = 0; i < 20 && !step.state().grounded(); i++)
            step = simulator.step(step.state(), NORMAL, input(0, 0, 0, false, false), world()).orElseThrow();
        assertTrue(step.state().grounded());
        assertEquals(0, step.state().position().y(), 1e-10);
    }

    @Test void sprintJumpImpulseIsDirectedAndNotRepeatedInAir() {
        var first = simulator.step(standing(), NORMAL, input(1, 0, 90, true, true), world()).orElseThrow();
        assertEquals(-.3274, first.displacement().x(), 1e-12);
        var next = simulator.step(first.state(), NORMAL, input(1, 0, 90, true, true), world()).orElseThrow();
        assertEquals(-(.3274 * .546 + .026 * .98), next.displacement().x(), 1e-12);
        assertEquals(.3332, next.displacement().y(), 1e-12);
    }

    @Test void customGravityAndJumpStrengthAreUsed() {
        var custom = new MovementSimulator.Parameters(.1, false, .04, .8, .6, .6, .6, 1.8);
        var step = simulator.step(standing(), custom, input(0, 0, 0, false, true), world()).orElseThrow();
        assertEquals(.8, step.displacement().y(), 1e-12);
        assertEquals(.7448, step.state().velocity().y(), 1e-12);
    }

    @Test void wallClipsOneAxisAndPreservesSlidingMomentum() {
        var wall = new CollisionBox(.5, 0, -5, 1.5, 4, 5);
        var state = new MovementSimulator.State(MotionVector.ZERO, new MotionVector(.8, -.0784, .25), true);
        var step = simulator.step(state, NORMAL, input(0, 0, 0, false, false), world(wall)).orElseThrow();
        assertEquals(.2, step.displacement().x(), 1e-12);
        assertEquals(.25, step.displacement().z(), 1e-12);
        assertEquals(0, step.state().velocity().x());
        assertEquals(.1365, step.state().velocity().z(), 1e-12);
    }

    @Test void ceilingClipsJumpAndRemovesUpwardMomentum() {
        var ceiling = new CollisionBox(-2, 1.95, -2, 2, 3, 2);
        var step = simulator.step(standing(), NORMAL, input(0, 0, 0, false, true), world(ceiling)).orElseThrow();
        assertEquals(.15, step.displacement().y(), 1e-12);
        assertEquals(-.0784, step.state().velocity().y(), 1e-12);
        assertFalse(step.state().grounded());
    }

    @Test void halfSlabCanBeSteppedUp() {
        var slab = new CollisionBox(.5, 0, -2, 2, .5, 2);
        var moved = world(slab).move(CollisionBox.body(MotionVector.ZERO, .6, 1.8), new MotionVector(.8, -.0784, 0), true, .6).orElseThrow();
        assertEquals(.8, moved.x(), 1e-12);
        assertEquals(.5, moved.y(), 1e-12);
    }

    @Test void highStepAndLowCeilingCannotBeBypassed() {
        var body = CollisionBox.body(MotionVector.ZERO, .6, 1.8);
        var desired = new MotionVector(.8, -.0784, 0);
        assertEquals(.2, world(new CollisionBox(.5, 0, -2, 2, 1, 2)).move(body, desired, true, .6).orElseThrow().x(), 1e-12);
        var lowCeiling = new CollisionBox(-2, 2.1, -2, 2, 3, 2);
        var slab = new CollisionBox(.5, 0, -2, 2, .5, 2);
        var blocked = world(slab, lowCeiling).move(body, desired, true, .6).orElseThrow();
        assertEquals(.2, blocked.x(), 1e-12);
        assertEquals(0, blocked.y(), 1e-12);
    }

    @Test void airborneMovementCannotStepOntoSlab() {
        var body = CollisionBox.body(new MotionVector(0, .2, 0), .6, 1.8);
        var moved = world(new CollisionBox(.5, 0, -2, 2, .5, 2)).move(body, new MotionVector(.8, .1, 0), false, .6).orElseThrow();
        assertEquals(.2, moved.x(), 1e-12);
        assertEquals(.1, moved.y(), 1e-12);
    }

    @Test void missingGeometryAndInitialPenetrationAreUnknown() {
        var body = CollisionBox.body(MotionVector.ZERO, .6, 1.8);
        assertTrue(world().move(body, new MotionVector(50, 0, 0), true, .6).isEmpty());
        assertTrue(world(new CollisionBox(-.1, 0, -.1, .1, 1, .1)).move(body, MotionVector.ZERO, true, .6).isEmpty());
    }

    @Test void randomWallApproachesDoNotPenetrateOrAddDistance() {
        Random random = new Random(91821);
        var wall = new CollisionBox(.5, -1, -10, 1.5, 8, 10);
        var body = CollisionBox.body(MotionVector.ZERO, .6, 1.8);
        for (int i = 0; i < 500; i++) {
            var desired = new MotionVector(random.nextDouble() * 3, -.0784, random.nextDouble() * 2 - 1);
            var moved = world(wall).move(body, desired, true, .6).orElseThrow();
            assertFalse(body.move(moved).intersects(wall));
            assertTrue(moved.horizontalSquared() <= desired.horizontalSquared() + 1e-12);
        }
    }

    @Test void snapshotDefensivelyCopiesShapesAndRejectsUnboundedInput() {
        List<CollisionBox> mutable = new ArrayList<>();
        var snapshot = new CollisionSpace(BOUNDS, mutable);
        mutable.add(FLOOR);
        assertTrue(snapshot.shapes().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.shapes().add(FLOOR));
        assertThrows(IllegalArgumentException.class, () -> new MotionVector(Double.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new MovementSimulator.Input(2, 0, 0, false, false, false));
        assertThrows(IllegalArgumentException.class, () -> new MovementSimulator.Parameters(.1, false, .08, .42, .6, 0, .6, 1.8));
    }
}
