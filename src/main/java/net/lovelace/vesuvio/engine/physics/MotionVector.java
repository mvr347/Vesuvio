package net.lovelace.vesuvio.engine.physics;

/** Immutable vector in blocks or blocks/client tick. */
public record MotionVector(double x, double y, double z) {
    public static final MotionVector ZERO = new MotionVector(0, 0, 0);
    public MotionVector {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
            throw new IllegalArgumentException("Неконечные координаты движения");
    }
    public MotionVector add(MotionVector other) { return new MotionVector(x + other.x, y + other.y, z + other.z); }
    public MotionVector subtract(MotionVector other) { return new MotionVector(x - other.x, y - other.y, z - other.z); }
    public double lengthSquared() { return x * x + y * y + z * z; }
    public double horizontalSquared() { return x * x + z * z; }
    public double distance(MotionVector other) { return Math.sqrt(subtract(other).lengthSquared()); }
}
