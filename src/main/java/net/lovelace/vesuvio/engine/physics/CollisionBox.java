package net.lovelace.vesuvio.engine.physics;

/** World-coordinate AABB, independent of the server API. Touching faces do not overlap. */
public record CollisionBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    public CollisionBox {
        if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ)
                || !Double.isFinite(maxX) || !Double.isFinite(maxY) || !Double.isFinite(maxZ)
                || minX > maxX || minY > maxY || minZ > maxZ)
            throw new IllegalArgumentException("Некорректные границы коллизии");
    }
    public CollisionBox move(MotionVector d) {
        return new CollisionBox(minX + d.x(), minY + d.y(), minZ + d.z(), maxX + d.x(), maxY + d.y(), maxZ + d.z());
    }
    public CollisionBox sweep(MotionVector d, double step) {
        return new CollisionBox(minX + Math.min(0, d.x()), minY + Math.min(0, d.y()), minZ + Math.min(0, d.z()),
                maxX + Math.max(0, d.x()), maxY + Math.max(step, d.y()), maxZ + Math.max(0, d.z()));
    }
    public boolean contains(CollisionBox other) {
        return minX <= other.minX && minY <= other.minY && minZ <= other.minZ
                && maxX >= other.maxX && maxY >= other.maxY && maxZ >= other.maxZ;
    }
    public boolean intersects(CollisionBox other) {
        return minX < other.maxX && maxX > other.minX && minY < other.maxY && maxY > other.minY
                && minZ < other.maxZ && maxZ > other.minZ;
    }
    public static CollisionBox body(MotionVector feet, double width, double height) {
        return new CollisionBox(feet.x() - width / 2, feet.y(), feet.z() - width / 2,
                feet.x() + width / 2, feet.y() + height, feet.z() + width / 2);
    }
}
