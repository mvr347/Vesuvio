package net.lovelace.vesuvio.engine.physics;

import net.lovelace.vesuvio.engine.EnvironmentSnapshot;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;

/** Main-thread player attributes only. Collision geometry comes exclusively from ClientWorld packets. */
public final class PaperPhysicsCapture {
    private PaperPhysicsCapture() {}

    public static PhysicsFrame capture(Player player, EnvironmentSnapshot env, long resetEpoch) {
        if (env.isMovementExempt() || player.isInvulnerable() || player.getPose() != Pose.STANDING
                || env.inWater() || env.inLava() || env.climbing() || env.nearClimbable() || env.inCobweb()
                || env.levitation() || env.slowFalling() || env.jumpBoost() || env.soulSpeed() > 0)
            return PhysicsFrame.unknown("UNSUPPORTED_STATE");
        var body = player.getBoundingBox();
        var parameters = new MovementSimulator.Parameters(env.movementSpeed(), env.sprinting(),
                attribute(player, Attribute.GRAVITY, 0.08), env.jumpStrength(),
                attribute(player, Attribute.STEP_HEIGHT, 0.6), 0.6, roundedSize(body.getWidthX()), roundedSize(body.getHeight()));
        return new PhysicsFrame(player.getWorld().getUID().toString(), System.nanoTime(), resetEpoch,
                parameters, null, null);
    }

    private static double attribute(Player player, Attribute attribute, double fallback) {
        var value = player.getAttribute(attribute);
        return value == null ? fallback : value.getValue();
    }

    private static double roundedSize(double size) {
        // Subtracting large world coordinates adds numerical noise; it is not a pose change.
        return Math.rint(size * 1_000_000) / 1_000_000;
    }

}
