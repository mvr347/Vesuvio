package net.lovelace.vesuvio;

import net.lovelace.vesuvio.check.combat.MoveDirectionCheck;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-math coverage for MoveDirectionCheck's movement-vs-target angle geometry, mirroring how
 * StaticAimTrackingMathTest covers KillauraAngleCheck's own math without needing a live Player.
 */
class MoveDirectionMathTest {

    private static final double EPS = 0.01;

    @Test
    void sameDirectionIsZeroDegrees() {
        // Running straight toward the target: no angle at all.
        assertEquals(0.0, MoveDirectionCheck.angleBetween(1, 0, 1, 0), EPS);
        assertEquals(0.0, MoveDirectionCheck.angleBetween(0.28, 0.0, 5.0, 0.0), EPS);
    }

    @Test
    void oppositeDirectionIs180Degrees() {
        // Running dead away from the target.
        assertEquals(180.0, MoveDirectionCheck.angleBetween(1, 0, -1, 0), EPS);
    }

    @Test
    void perpendicularIs90Degrees() {
        // Running sideways relative to the target - the "attacked from the side" case.
        assertEquals(90.0, MoveDirectionCheck.angleBetween(1, 0, 0, 1), EPS);
        assertEquals(90.0, MoveDirectionCheck.angleBetween(0, 1, 1, 0), EPS);
    }

    @Test
    void diagonalIs45Degrees() {
        assertEquals(45.0, MoveDirectionCheck.angleBetween(1, 0, 1, 1), EPS);
    }

    @Test
    void zeroLengthVectorIsSafelyZero() {
        // No movement or coincident position: nothing to compare, must not throw or return NaN.
        assertEquals(0.0, MoveDirectionCheck.angleBetween(0, 0, 1, 1), EPS);
        assertEquals(0.0, MoveDirectionCheck.angleBetween(1, 1, 0, 0), EPS);
    }

    @Test
    void behindWhileSprintingIsWellPastThreshold() {
        // The real signature this check exists for: running forward (+Z) while the target is
        // directly behind (-Z) - a human sprinting forward cannot also be attacking backward.
        double angle = MoveDirectionCheck.angleBetween(0, 1, 0, -1);
        assertEquals(180.0, angle, EPS);
    }
}
