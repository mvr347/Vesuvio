package net.lovelace.vesuvio;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.check.movement.ElytraCheck;
import net.lovelace.vesuvio.data.UserData;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage for the elytra sanity check added after gliding was found to be a total detection
 * blind spot (every other movement check exempts it outright). Deliberately narrow scope: only
 * sustained climb or sustained excess speed with zero recent firework use.
 */
class ElytraCheckTest {

    private static final long TICK_MS = 50L;

    @Test
    void elytraSkipsEntirelyWhenNotGliding() {
        ElytraCheck check = new ElytraCheck();
        UserData data = new UserData(UUID.randomUUID(), "Walker");
        data.setEnvironment(ground());

        long now = 0L;
        for (int i = 0; i < 100; i++) {
            now += TICK_MS;
            // Deltas that would be an obvious violation while gliding must be ignored entirely
            // for a player who is not gliding - this check is not a general movement check.
            assertFalse(check.check(data, 0, 10.0, 0, now).isFlag(),
                    "a non-gliding player must never be judged by this check");
        }
    }

    @Test
    void elytraIgnoresNormalDescendingGlide() {
        ElytraCheck check = new ElytraCheck();
        UserData data = new UserData(UUID.randomUUID(), "Glider");
        data.setEnvironment(gliding());

        long now = 0L;
        // A shallow, steady descending glide well under the unboosted speed ceiling, for several
        // climb-window cycles.
        for (int i = 0; i < 400; i++) {
            now += TICK_MS;
            assertFalse(check.check(data, 0.3, -0.2, 0.3, now).isFlag(),
                    "ordinary descending glide must never flag (tick " + i + ")");
        }
    }

    @Test
    void elytraDetectsSustainedClimbWithoutBoost() {
        ElytraCheck check = new ElytraCheck();
        UserData data = new UserData(UUID.randomUUID(), "FlyHacker");
        data.setEnvironment(gliding());

        long now = 0L;
        boolean flagged = false;
        // +0.1 b/tick net climb, low horizontal speed - well past the window's climb tolerance
        // once accumulated over the 3-second window, with no firework ever used.
        for (int i = 0; i < 80 && !flagged; i++) {
            now += TICK_MS;
            CheckResult result = check.check(data, 0.1, 0.1, 0.1, now);
            if (result.isFlag()) {
                flagged = true;
                assertEquals("Elytra", result.checkName());
            }
        }

        assertTrue(flagged, "sustained unboosted climb must be caught");
    }

    @Test
    void elytraIgnoresClimbAfterRecentBoost() {
        ElytraCheck check = new ElytraCheck();
        UserData data = new UserData(UUID.randomUUID(), "LegitBooster");
        data.setEnvironment(gliding());
        data.recordElytraBoost();

        long now = 0L;
        // Identical climb pattern to the detection test above, but with a recent firework use -
        // legitimate rocket-assisted ascent must never be flagged.
        for (int i = 0; i < 80; i++) {
            now += TICK_MS;
            assertFalse(check.check(data, 0.1, 0.1, 0.1, now).isFlag(),
                    "a recently boosted climb is legitimate (tick " + i + ")");
        }
    }

    @Test
    void elytraDetectsExcessiveUnboostedSpeed() {
        ElytraCheck check = new ElytraCheck();
        UserData data = new UserData(UUID.randomUUID(), "SpeedHacker");
        data.setEnvironment(gliding());

        long now = 0L;
        boolean flagged = false;
        // ~15.6 b/tick 3D speed (~312 m/s), well beyond vanilla's ~3.9 b/tick unboosted terminal
        // dive, sustained with no firework ever used.
        for (int i = 0; i < 20 && !flagged; i++) {
            now += TICK_MS;
            CheckResult result = check.check(data, 9.0, -9.0, 9.0, now);
            if (result.isFlag()) {
                flagged = true;
                assertEquals("Elytra", result.checkName());
            }
        }

        assertTrue(flagged, "sustained unboosted excess speed must be caught");
    }

    @Test
    void elytraIgnoresExcessiveSpeedAfterRecentBoost() {
        ElytraCheck check = new ElytraCheck();
        UserData data = new UserData(UUID.randomUUID(), "LegitRacer");
        data.setEnvironment(gliding());
        data.recordElytraBoost();

        long now = 0L;
        // Same extreme speed pattern, but rocket-boosted - this check deliberately does not try
        // to bound chained-firework speed, since that is a legitimate (if extreme) vanilla
        // mechanic and modelling it precisely without a live client is too risky.
        for (int i = 0; i < 20; i++) {
            now += TICK_MS;
            assertFalse(check.check(data, 9.0, -9.0, 9.0, now).isFlag(),
                    "boosted speed must never be judged by this check (tick " + i + ")");
        }
    }

    @Test
    void elytraToleratesASingleSpeedSpike() {
        ElytraCheck check = new ElytraCheck();
        UserData data = new UserData(UUID.randomUUID(), "Bumped");
        data.setEnvironment(gliding());

        long now = 0L;
        // One tick far over the ceiling (a terrain bump, a single mispredicted tick) followed by
        // ordinary glide - a single spike must not be enough on its own to flag.
        now += TICK_MS;
        assertFalse(check.check(data, 9.0, -9.0, 9.0, now).isFlag());

        for (int i = 0; i < 60; i++) {
            now += TICK_MS;
            assertFalse(check.check(data, 0.3, -0.2, 0.3, now).isFlag(),
                    "a single spike must decay rather than accumulate toward a flag (tick " + i + ")");
        }
    }

    private static net.lovelace.vesuvio.engine.EnvironmentSnapshot ground() {
        return TestSnapshots.builder().build();
    }

    private static net.lovelace.vesuvio.engine.EnvironmentSnapshot gliding() {
        return TestSnapshots.builder().gliding(true).build();
    }
}
