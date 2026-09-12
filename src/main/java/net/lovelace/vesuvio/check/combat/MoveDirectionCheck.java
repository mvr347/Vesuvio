package net.lovelace.vesuvio.check.combat;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.data.UserData;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * KillAura MoveDirection: compares where a sprinting attacker is actually travelling against
 * where their attack claims to be aimed - a different signal from {@code KillauraAngleCheck},
 * which only looks at the crosshair/look-direction angle.
 *
 * <h2>The signature</h2>
 * Vanilla sprint redirects a player's horizontal movement toward wherever their input keys point
 * relative to their current camera each tick - turning to face and attack a target naturally pulls
 * movement toward it too, or breaks the straight-line sprint. A silent-aim / LockView style
 * killaura instead computes the attack packet's required angle without genuinely changing input:
 * the player keeps running in their original direction, sprint uninterrupted, while attacking a
 * target well off to the side or behind them. A human sprinting forward physically cannot also be
 * landing clean hits on something behind them without that showing up as a change in where they
 * are actually going.
 *
 * Author: Lovelace
 */
public final class MoveDirectionCheck {

    /**
     * Minimum recent horizontal speed, in blocks/tick, before a movement direction is trusted at
     * all. A sprinting player blocked by geometry (wall, corner) has near-zero actual movement and
     * no meaningful direction to compare - well under vanilla sprint speed (~0.28b/t).
     */
    private static final double MIN_MOVE_SPEED = 0.15;

    /**
     * Angle beyond which the attack direction can no longer be explained as "mostly forward".
     * Generous: quick redirects during real PVP can momentarily produce a wide angle, so only
     * attacks clearly to the side or behind (comfortably past a right angle) count.
     */
    private static final double MAX_ANGLE_DEGREES = 100.0;

    private static final int REQUIRED_STREAK = 3;

    public CheckResult check(Player attacker, Entity target, UserData data) {
        if (attacker == null || target == null || data == null) return CheckResult.pass("MoveDirection");
        if (!attacker.isSprinting()) {
            data.resetMoveDirectionStreak();
            return CheckResult.pass("MoveDirection");
        }

        double moveX = data.getLastMoveDeltaX();
        double moveZ = data.getLastMoveDeltaZ();
        double moveSpeed = Math.hypot(moveX, moveZ);

        if (moveSpeed < MIN_MOVE_SPEED) {
            data.resetMoveDirectionStreak();
            return CheckResult.pass("MoveDirection");
        }

        Location eye = attacker.getEyeLocation();
        Location targetLoc = target.getLocation();
        double toTargetX = targetLoc.getX() - eye.getX();
        double toTargetZ = targetLoc.getZ() - eye.getZ();
        double toTargetLen = Math.hypot(toTargetX, toTargetZ);

        if (toTargetLen < 0.3) {
            // Right next to the target horizontally - direction is ambiguous, not evidence either way.
            return CheckResult.pass("MoveDirection");
        }

        double angleDegrees = angleBetween(moveX, moveZ, toTargetX, toTargetZ);

        if (angleDegrees <= MAX_ANGLE_DEGREES) {
            data.resetMoveDirectionStreak();
            return CheckResult.pass("MoveDirection");
        }

        data.incrementMoveDirectionStreak();
        if (data.getMoveDirectionStreak() < REQUIRED_STREAK) {
            return CheckResult.pass("MoveDirection");
        }

        data.resetMoveDirectionStreak();

        Map<String, Object> details = new HashMap<>();
        details.put("angle", angleDegrees);
        details.put("moveSpeed", moveSpeed);
        details.put("target", target.getName() != null ? target.getName() : target.getType().name());

        return CheckResult.flag("MoveDirection", 0.88, 3.5,
                String.format(Locale.US,
                        "Attacked %.1f° off sprint direction while sprint was maintained (%.2fb/t)",
                        angleDegrees, moveSpeed),
                details);
    }

    /**
     * Angle, in degrees, between the horizontal movement vector (moveX, moveZ) and the horizontal
     * direction to the target (toTargetX, toTargetZ). Exposed as pure math so the geometry can be
     * unit tested without a live Bukkit {@link Player}/{@link Entity}.
     */
    public static double angleBetween(double moveX, double moveZ, double toTargetX, double toTargetZ) {
        double moveLen = Math.hypot(moveX, moveZ);
        double toTargetLen = Math.hypot(toTargetX, toTargetZ);
        if (moveLen == 0 || toTargetLen == 0) return 0.0;

        double dot = (moveX * toTargetX + moveZ * toTargetZ) / (moveLen * toTargetLen);
        dot = Math.max(-1.0, Math.min(1.0, dot));
        return Math.toDegrees(Math.acos(dot));
    }
}
