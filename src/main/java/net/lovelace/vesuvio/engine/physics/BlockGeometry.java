package net.lovelace.vesuvio.engine.physics;

import java.util.List;

/** Independent collision description of a single packet state. Shapes are local to the block. */
public record BlockGeometry(List<CollisionBox> shapes, double slipperiness, boolean supported) {
    public static final BlockGeometry UNKNOWN = new BlockGeometry(List.of(), .6, false);
    public static final BlockGeometry AIR = new BlockGeometry(List.of(), .6, true);
    public static final BlockGeometry CUBE = new BlockGeometry(List.of(new CollisionBox(0, 0, 0, 1, 1, 1)), .6, true);
    public BlockGeometry { shapes = List.copyOf(shapes); }
}
