package net.lovelace.vesuvio.engine.physics;

/** Atomically published world/attribute snapshot, or an explicit unsupported-state reason. */
public record PhysicsFrame(String world, long capturedNanos, long resetEpoch,
                           MovementSimulator.Parameters parameters, CollisionSpace collisions, String unavailable) {
    public static PhysicsFrame unknown(String reason) { return new PhysicsFrame("", 0, 0, null, null, reason); }
    public boolean usable() { return unavailable == null && parameters != null && collisions != null; }
}
