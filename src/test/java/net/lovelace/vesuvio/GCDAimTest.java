package net.lovelace.vesuvio;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.check.statistical.GCDAimCheck;
import net.lovelace.vesuvio.data.UserData;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class GCDAimTest {

    @Test
    public void testGCDMath() {
        // Greatest common divisor of 0.12 and 0.08 is 0.04
        double gcd = GCDAimCheck.calculateGCD(0.12, 0.08);
        assertEquals(0.04, gcd, 0.001);

        // Disjoint or small gcd
        double gcd2 = GCDAimCheck.calculateGCD(0.15, 0.05);
        assertEquals(0.05, gcd2, 0.001);
    }

    @Test
    public void testSmoothAimbotFlaggedInCombat() {
        GCDAimCheck check = new GCDAimCheck();
        UserData user = new UserData(UUID.randomUUID(), "AimBotter");
        user.setTrustScore(40.0);
        user.setRiskIndex(60.0);
        user.recordCombatAction(); // Active combat engagement

        // Smooth floating-point trigonometric aimbot deltas (atan2 interpolation)
        float deltaYaw = 7.849201f;
        float deltaPitch = 2.0000001f; // GCD collapses below 0.0008

        CheckResult result = CheckResult.pass("GCDAim");
        for (int i = 0; i < 5; i++) {
            result = check.check(user, deltaYaw, deltaPitch);
        }

        assertTrue(result.isFlag(), "Aimbot with consecutive infinitesimal unquantized GCD must be flagged");
        assertTrue(result.confidence() >= 0.90);
    }

    @Test
    public void testNonCombatMovementIgnored() {
        GCDAimCheck check = new GCDAimCheck();
        UserData user = new UserData(UUID.randomUUID(), "JumpingPlayer");
        // Player is NOT in combat (just jumping around spawn)

        float deltaYaw = 7.849201f;
        float deltaPitch = 2.0000001f;

        CheckResult result = check.check(user, deltaYaw, deltaPitch);
        assertFalse(result.isFlag(), "Movement outside combat must never trigger GCDAim check");
    }

    @Test
    public void testLegitimateMouseQuantizationPassed() {
        GCDAimCheck check = new GCDAimCheck();
        UserData user = new UserData(UUID.randomUUID(), "LegitPlayer");
        user.recordCombatAction();

        // Standard mouse movement quantized by sensitivity divisor
        float deltaYaw = 4.50f;
        float deltaPitch = 2.25f;

        CheckResult result = check.check(user, deltaYaw, deltaPitch);
        assertFalse(result.isFlag(), "Properly quantized mouse rotation must pass GCD check");
    }

    @Test
    public void testMicroRotationIgnored() {
        GCDAimCheck check = new GCDAimCheck();
        UserData user = new UserData(UUID.randomUUID(), "LegitPlayer");
        user.recordCombatAction();

        // Micro movement below minimum rotation threshold (< 1.5 degrees)
        CheckResult result = check.check(user, 0.5f, 0.3f);
        assertFalse(result.isFlag(), "Micro rotations should be safely ignored");
    }

    @Test
    public void testVanillaPlayerSensitivityNumbersPassed() {
        GCDAimCheck check = new GCDAimCheck();
        UserData user = new UserData(UUID.randomUUID(), "Ada123");
        user.recordCombatAction();

        // Exact numbers from vanilla 50% sensitivity Minecraft rotation deltas (Ada123 log)
        float[][] legitDeltas = new float[][]{
                {18.900002f, 1.199997f},
                {13.050003f, 0.7499981f},
                {29.550003f, 4.799999f},
                {10.650002f, 1.199997f},
                {6.4500046f, 1.6499977f},
                {11.850014f, 0.5999985f},
                {6.7499847f, 3.1500034f},
                {10.949982f, 1.9499998f}
        };

        for (float[] pair : legitDeltas) {
            CheckResult res = check.check(user, pair[0], pair[1]);
            assertFalse(res.isFlag(), "Vanilla 50% sensitivity deltas must pass: " + pair[0] + ", " + pair[1]);
        }
    }
}
