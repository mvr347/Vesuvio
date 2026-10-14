package net.lovelace.vesuvio.engine;

/**
 * Conservative horizontal prediction: friction carries the previous velocity; player input
 * can add an acceleration vector within a disk. The disk contains every vanilla WASD input,
 * including diagonal movement, so a residual means no input direction explains the motion.
 * Collision/teleport/special-block transitions must be excluded by the caller.
 */
public final class HorizontalMotionModel {
    private HorizontalMotionModel() {}

    public static double residual(double previousX, double previousZ, double x, double z,
                                  double friction, double acceleration, double impulse, double slack) {
        double requestedAcceleration = Math.hypot(x - previousX * friction, z - previousZ * friction);
        return Math.max(0, requestedAcceleration - acceleration - impulse - slack);
    }

    public static double verticalVelocity(double previousVelocity, double gravity, double drag) {
        return (previousVelocity - gravity) * drag;
    }
}
