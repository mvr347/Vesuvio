package net.lovelace.vesuvio;

import net.lovelace.vesuvio.check.statistical.KillauraAngleCheck;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-math coverage for KillauraAngleCheck's "StaticAimTracking" sub-check (hit-rotation
 * consistency). requiredYaw/requiredPitch must match CraftBukkit's own Location#setDirection
 * convention exactly, since a wrong sign or axis here would make the check compare the
 * attacker's real rotation against a bogus "correct" angle and misfire on legitimate aim.
 */
public class StaticAimTrackingMathTest {

    private static final float EPS = 0.01f;

    @Test
    public void testRequiredYawPitchCardinalDirections() {
        // Facing south (+Z): yaw 0, pitch 0.
        assertEquals(0f, KillauraAngleCheck.requiredYaw(0, 1), EPS);
        assertEquals(0f, KillauraAngleCheck.requiredPitch(0, 0, 1), EPS);

        // Facing west (+X in Bukkit's convention is west... yaw -90 per setDirection formula).
        assertEquals(-90f, KillauraAngleCheck.requiredYaw(1, 0), EPS);

        // Facing north (-Z): yaw 180 or -180.
        double yawNorth = KillauraAngleCheck.requiredYaw(0, -1);
        assertEquals(180.0, Math.abs(yawNorth), EPS);

        // Straight down: pitch 90.
        assertEquals(90f, KillauraAngleCheck.requiredPitch(0, -1, 0), EPS);
        // Straight up: pitch -90.
        assertEquals(-90f, KillauraAngleCheck.requiredPitch(0, 1, 0), EPS);
    }

    @Test
    public void testAngularDiffWraparound() {
        // 359 vs 1 degree are only 2 degrees apart across the wrap, not 358.
        assertEquals(2.0, KillauraAngleCheck.angularDiff(359f, 1f), EPS);
        assertEquals(0.0, KillauraAngleCheck.angularDiff(10f, 10f), EPS);
        assertEquals(180.0, KillauraAngleCheck.angularDiff(0f, 180f), EPS);
        assertEquals(90.0, KillauraAngleCheck.angularDiff(45f, -45f), EPS);
    }
}
