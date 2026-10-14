package net.lovelace.vesuvio.engine.physics;

import java.util.List;
import java.util.Optional;

/** Bounded snapshot: lack of geometry outside the bounds must never mean air. */
public record CollisionSpace(CollisionBox knownBounds, List<CollisionBox> shapes) {
    private static final double EPSILON = 1e-9;
    public CollisionSpace {
        java.util.Objects.requireNonNull(knownBounds);
        shapes = List.copyOf(shapes);
        if (shapes.size() > 512) throw new IllegalArgumentException("Превышен лимит коллизий");
    }

    public Optional<MotionVector> move(CollisionBox body, MotionVector desired, boolean grounded, double stepHeight) {
        if (!Double.isFinite(stepHeight) || stepHeight < 0) throw new IllegalArgumentException("Некорректная высота шага");
        if (!knownBounds.contains(body.sweep(desired, stepHeight))
                || shapes.stream().anyMatch(body::intersects)) return Optional.empty();
        MotionVector direct = clip(body, desired);
        boolean landed = desired.y() < 0 && direct.y() > desired.y() + EPSILON;
        boolean horizontalHit = Math.abs(direct.x() - desired.x()) > EPSILON || Math.abs(direct.z() - desired.z()) > EPSILON;
        if (stepHeight == 0 || !horizontalHit || !(grounded || landed)) return Optional.of(direct);

        double up = clipAxis(body, stepHeight, 1);
        CollisionBox raised = body.move(new MotionVector(0, up, 0));
        MotionVector across = clip(raised, new MotionVector(desired.x(), 0, desired.z()));
        double down = clipAxis(raised.move(across), desired.y() - up, 1);
        MotionVector stepped = new MotionVector(across.x(), up + down, across.z());
        return Optional.of(stepped.horizontalSquared() > direct.horizontalSquared() + EPSILON ? stepped : direct);
    }

    private MotionVector clip(CollisionBox body, MotionVector desired) {
        double y = clipAxis(body, desired.y(), 1);
        body = body.move(new MotionVector(0, y, 0));
        double x, z;
        if (Math.abs(desired.x()) >= Math.abs(desired.z())) {
            x = clipAxis(body, desired.x(), 0);
            z = clipAxis(body.move(new MotionVector(x, 0, 0)), desired.z(), 2);
        } else {
            z = clipAxis(body, desired.z(), 2);
            x = clipAxis(body.move(new MotionVector(0, 0, z)), desired.x(), 0);
        }
        return new MotionVector(x, y, z);
    }

    private double clipAxis(CollisionBox body, double amount, int axis) {
        for (CollisionBox shape : shapes) {
            boolean overlap = switch (axis) {
                case 0 -> body.minY() < shape.maxY() && body.maxY() > shape.minY()
                        && body.minZ() < shape.maxZ() && body.maxZ() > shape.minZ();
                case 1 -> body.minX() < shape.maxX() && body.maxX() > shape.minX()
                        && body.minZ() < shape.maxZ() && body.maxZ() > shape.minZ();
                default -> body.minX() < shape.maxX() && body.maxX() > shape.minX()
                        && body.minY() < shape.maxY() && body.maxY() > shape.minY();
            };
            if (!overlap) continue;
            double near = axis == 0 ? shape.minX() - body.maxX() : axis == 1 ? shape.minY() - body.maxY() : shape.minZ() - body.maxZ();
            double far = axis == 0 ? shape.maxX() - body.minX() : axis == 1 ? shape.maxY() - body.minY() : shape.maxZ() - body.minZ();
            if (amount > 0 && near >= -EPSILON) amount = Math.min(amount, Math.max(0, near));
            if (amount < 0 && far <= EPSILON) amount = Math.max(amount, Math.min(0, far));
        }
        return amount;
    }
}
