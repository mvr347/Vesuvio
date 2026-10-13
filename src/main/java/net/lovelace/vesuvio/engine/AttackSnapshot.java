package net.lovelace.vesuvio.engine;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/** Immutable state belonging to ONE attack, never shared with the next target/rotation. */
public record AttackSnapshot(boolean positionKnown, double x, double y, double z,
                             boolean rotationKnown, float yaw, float pitch, long receivedNanos) {
    /** Called only on the server thread, after checking for teleport/respawn transitions. */
    public Location eyeLocation(Player player) {
        Location eye = player.getEyeLocation();
        if (positionKnown) {
            eye.setX(x);
            eye.setY(y + player.getEyeHeight());
            eye.setZ(z);
        }
        if (rotationKnown) {
            eye.setYaw(yaw);
            eye.setPitch(pitch);
        }
        return eye;
    }
}
