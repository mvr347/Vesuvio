package net.lovelace.vesuvio;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.check.movement.SpeedCheck;
import net.lovelace.vesuvio.data.UserData;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives SpeedCheck with the exact vanilla horizontal recurrence (LivingEntity#travel / moveRelative), tick by tick, and
 * checks that honest play never flags while a speed multiplier does.
 *
 * <p>Per tick, with {@code g} = onGround at the START of the tick and {@code slip} the block slipperiness there:
 * <pre>
 *   if (jump and g) vel += 0.2 (sprint)
 *   accel = g ? attr * 0.98 * 0.216 / slip^3 : 0.026 * 0.98
 *   d     = vel + accel                       // displacement the client reports
 *   vel   = d * (g ? slip * 0.91 : 0.91)      // decay for the NEXT tick, by the same start-of-tick state
 * </pre>
 */
class SpeedVanillaSimulationTest {

    private static final double INPUT = 0.98;

    private record Scenario(double attribute, int speedAmplifier, double slipperiness, Material below,
                            double ceilingHeadroom, boolean bunnyHop, double cheatFactor) {}

    /** Returns the first flagging result, or null. */
    private static CheckResult run(Scenario sc, int ticks) {
        SpeedCheck check = new SpeedCheck();
        UserData data = new UserData(UUID.randomUUID(), "t");
        data.setEnvironment(TestSnapshots.builder().blockBelow(sc.below).movementSpeed(sc.attribute)
                .sprinting(true).build());
        double vel = 0.0;
        double y = 0.0;
        double vy = 0.0;
        boolean ground = true;
        for (int t = 0; t < ticks; t++) {
            boolean jump = sc.bunnyHop && ground && t > 10;
            if (jump) {
                vel += 0.2;
                vy = 0.42;
            }
            double accel = ground
                    ? sc.attribute * INPUT * 0.216 / Math.pow(sc.slipperiness, 3)
                    : 0.026 * INPUT * (sc.speedAmplifier >= 0 ? 1.0 + 0.2 * (sc.speedAmplifier + 1) : 1.0);
            double d = (vel + accel) * sc.cheatFactor;
            double friction = ground ? sc.slipperiness * 0.91 : 0.91;
            vel = d * friction;

            // Vertical: a ceiling clips the rise.
            double dy = 0.0;
            boolean groundNext = ground;
            if (!ground || jump) {
                double ny = y + vy;
                if (ny > sc.ceilingHeadroom) ny = sc.ceilingHeadroom;
                dy = ny - y;
                y = ny;
                vy = (vy - 0.08) * 0.98;
                if (ny >= sc.ceilingHeadroom - 1e-9 && vy > 0) vy = 0;
                if (y <= 0) {
                    dy = dy - y;
                    y = 0;
                    groundNext = true;
                    vy = 0;
                } else {
                    groundNext = false;
                }
            }
            ground = groundNext;
            CheckResult r = check.check(data, d, 0.0, dy, ground, 50.0, 1.0);
            if (r.isFlag()) return r;
        }
        return null;
    }

    private static final double NO_CEILING = 100.0;
    private static final double LOW_CEILING = 0.2; // 2-block ceiling above a 1.8 tall player

    @Test
    void sprintOnGroundNeverFlags() {
        assertNull(run(new Scenario(0.13, -1, 0.6, Material.STONE, NO_CEILING, false, 1.0), 200));
    }

    @Test
    void sprintJumpsNeverFlag() {
        assertNull(run(new Scenario(0.13, -1, 0.6, Material.STONE, NO_CEILING, true, 1.0), 400));
    }

    @Test
    void sprintJumpsUnderATwoBlockCeilingNeverFlag() {
        assertNull(run(new Scenario(0.13, -1, 0.6, Material.STONE, LOW_CEILING, true, 1.0), 400));
    }

    @Test
    void sprintOnIceNeverFlags() {
        assertNull(run(new Scenario(0.13, -1, 0.98, Material.ICE, NO_CEILING, false, 1.0), 300));
    }

    @Test
    void sprintJumpsOnIceNeverFlag() {
        assertNull(run(new Scenario(0.13, -1, 0.98, Material.ICE, NO_CEILING, true, 1.0), 400));
    }

    @Test
    void sprintJumpsOnIceUnderACeilingNeverFlag() {
        assertNull(run(new Scenario(0.13, -1, 0.98, Material.ICE, LOW_CEILING, true, 1.0), 400));
    }

    @Test
    void speedTwoNeverFlags() {
        assertNull(run(new Scenario(0.1 * 1.4 * 1.3, 1, 0.6, Material.STONE, NO_CEILING, true, 1.0), 400));
        assertNull(run(new Scenario(0.1 * 1.4 * 1.3, 1, 0.6, Material.STONE, NO_CEILING, false, 1.0), 200));
    }

    @Test
    void aSpeedMultiplierOfOnePointThreeFlags() {
        assertNotNull(run(new Scenario(0.13, -1, 0.6, Material.STONE, NO_CEILING, false, 1.3), 200));
        assertNotNull(run(new Scenario(0.13, -1, 0.6, Material.STONE, NO_CEILING, true, 1.3), 400));
    }
}
