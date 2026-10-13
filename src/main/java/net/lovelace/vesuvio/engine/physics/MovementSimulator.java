package net.lovelace.vesuvio.engine.physics;

import java.util.Optional;

/** Independent, initial land-motion profile. See docs/physics-spec.md for provenance and limits. */
public final class MovementSimulator {
    public record Parameters(double movementSpeed, boolean sprintAttributeApplied, double gravity,
                             double jumpStrength, double stepHeight, double slipperiness,
                             double width, double height) {
        public Parameters {
            if (!Double.isFinite(movementSpeed) || movementSpeed < 0 || !Double.isFinite(gravity) || gravity < 0
                    || !Double.isFinite(jumpStrength) || jumpStrength < 0 || !Double.isFinite(stepHeight) || stepHeight < 0
                    || !Double.isFinite(slipperiness) || slipperiness <= 0 || slipperiness > 1
                    || !Double.isFinite(width) || width <= 0 || !Double.isFinite(height) || height <= 0)
                throw new IllegalArgumentException("Некорректные параметры физики");
        }
    }
    public record State(MotionVector position, MotionVector velocity, boolean grounded) {}
    public record Input(int forward, int strafe, float yaw, boolean sprint, boolean jump, boolean slow) {
        public Input {
            if (Math.abs(forward) > 1 || Math.abs(strafe) > 1 || !Float.isFinite(yaw))
                throw new IllegalArgumentException("Некорректный ввод");
        }
    }
    public record Step(State state, MotionVector displacement, Input input) {}

    public Optional<Step> step(State state, Parameters p, Input input, CollisionSpace world) {
        double rad = Math.toRadians(input.yaw());
        double sin = Math.sin(rad), cos = Math.cos(rad);
        double norm = Math.max(1, Math.hypot(input.strafe(), input.forward()));
        double scale = 0.98 * (input.slow() ? 0.3 : 1) / norm;
        double localX = input.strafe() * scale, localZ = input.forward() * scale;
        double speed = p.movementSpeed() / (p.sprintAttributeApplied() ? 1.3 : 1) * (input.sprint() ? 1.3 : 1);
        double acceleration = state.grounded() ? speed * 0.216 / Math.pow(p.slipperiness(), 3)
                : (input.sprint() ? 0.026 : 0.02);
        double vx = snap(state.velocity().x()) + (localX * cos - localZ * sin) * acceleration;
        double vz = snap(state.velocity().z()) + (localZ * cos + localX * sin) * acceleration;
        double vy = snap(state.velocity().y());
        if (state.grounded() && input.jump()) {
            vy = p.jumpStrength();
            if (input.sprint()) { vx -= sin * 0.2; vz += cos * 0.2; }
        }
        MotionVector desired = new MotionVector(vx, vy, vz);
        var displacement = world.move(CollisionBox.body(state.position(), p.width(), p.height()), desired,
                state.grounded(), p.stepHeight());
        if (displacement.isEmpty()) return Optional.empty();
        MotionVector moved = displacement.get();
        boolean hitY = different(vy, moved.y());
        boolean grounded = vy < 0 && hitY;
        double drag = state.grounded() ? p.slipperiness() * 0.91 : 0.91;
        MotionVector remaining = new MotionVector(different(vx, moved.x()) ? 0 : vx * drag,
                ((hitY ? 0 : vy) - p.gravity()) * 0.98,
                different(vz, moved.z()) ? 0 : vz * drag);
        return Optional.of(new Step(new State(state.position().add(moved), remaining, grounded), moved, input));
    }
    private static boolean different(double a, double b) { return Math.abs(a - b) > 1e-9; }
    private static double snap(double velocity) { return Math.abs(velocity) < 0.003 ? 0 : velocity; }
}
