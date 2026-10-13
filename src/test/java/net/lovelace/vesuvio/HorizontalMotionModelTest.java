package net.lovelace.vesuvio;

import net.lovelace.vesuvio.engine.HorizontalMotionModel;
import net.lovelace.vesuvio.check.movement.SpeedCheck;
import net.lovelace.vesuvio.data.UserData;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class HorizontalMotionModelTest {
    @Test void repeatedImpossibleDirectionChangesReachTheSpeedCheck() {
        var data = new UserData(UUID.randomUUID(), "InstantStrafe");
        var check = new SpeedCheck();
        data.setLastPosition(0, 64, 0, true);
        boolean flagged = false;
        for (int i = 0; i < 40; i++) {
            data.setEnvironment(TestSnapshots.builder().solidBelow(true).movementSpeed(0.13).build());
            data.resetAirTicks();
            flagged |= check.check(data, i % 2 == 0 ? 0.28 : -0.28, 0, 50, 1).isFlag();
        }
        assertTrue(flagged, "A valid scalar speed cannot excuse impossible acceleration reversals");
    }

    @Test void ordinaryTurningDoesNotAccumulatePredictionViolations() {
        var data = new UserData(UUID.randomUUID(), "Turning");
        var check = new SpeedCheck();
        data.setLastPosition(0, 64, 0, true);
        double x = 0, z = 0;
        for (int i = 0; i < 200; i++) {
            double yaw = i * 0.11;
            x = x * 0.546 + 0.13 * Math.sin(yaw);
            z = z * 0.546 + 0.13 * Math.cos(yaw);
            data.setEnvironment(TestSnapshots.builder().solidBelow(true).movementSpeed(0.13).build());
            data.resetAirTicks();
            assertFalse(check.check(data, x, z, 50, 1).isFlag());
        }
    }

    @Test void allLegitimateInputDirectionsFitTheEnvelope() {
        double vx = 0.25, vz = 0.12, friction = 0.546, acceleration = 0.13;
        for (int degrees = 0; degrees < 360; degrees++) {
            double angle = Math.toRadians(degrees);
            double x = vx * friction + Math.cos(angle) * acceleration;
            double z = vz * friction + Math.sin(angle) * acceleration;
            assertEquals(0, HorizontalMotionModel.residual(vx, vz, x, z, friction, acceleration, 0, 0.001));
        }
    }

    @Test void instantVelocityReversalCannotHideBehindAValidSpeedMagnitude() {
        assertTrue(HorizontalMotionModel.residual(0.28, 0, -0.28, 0, 0.546, 0.143, 0, 0.03) > 0.25);
    }

    @Test void customMovementAttributeDoesNotLookLikeASpeedHack() {
        var data = new UserData(UUID.randomUUID(), "FastBoots");
        var check = new SpeedCheck();
        data.setLastPosition(0, 64, 0, true);
        double velocity = 0;
        for (int i = 0; i < 150; i++) {
            data.setEnvironment(TestSnapshots.builder().solidBelow(true).movementSpeed(0.3).build());
            velocity = velocity * 0.546 + 0.3;
            assertFalse(check.check(data, velocity, 0, 50, 1).isFlag());
        }
    }

    @Test void verticalStepMatchesVanillaGravityAndDrag() {
        assertEquals(0.3332, HorizontalMotionModel.verticalVelocity(0.42, 0.08, 0.98), 1e-8);
        assertEquals(-0.0784, HorizontalMotionModel.verticalVelocity(0, 0.08, 0.98), 1e-8);
    }
}
