package net.lovelace.vesuvio;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.check.movement.SpeedCheck;
import net.lovelace.vesuvio.check.movement.TimerCheck;
import net.lovelace.vesuvio.check.movement.VelocityCheck;
import net.lovelace.vesuvio.data.UserData;
import net.lovelace.vesuvio.engine.EnvironmentSnapshot;
import net.lovelace.vesuvio.engine.TransactionManager;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MovementCheckTest {

    private static final long MS = 1_000_000L;

    // ------------------------------------------------------------------
    // Timer: drift balance
    // ------------------------------------------------------------------

    @Test
    void timerAcceptsVanillaPacketRate() {
        TimerCheck timerCheck = new TimerCheck();
        UserData data = new UserData(UUID.randomUUID(), "TestPlayer");

        long now = 0L;
        for (int i = 0; i < 400; i++) {
            now += 50 * MS; // exactly one tick apart
            assertFalse(timerCheck.check(data, 1.0, now).isFlag(),
                    "vanilla 20pps must never flag (packet " + i + ")");
        }
    }

    @Test
    void timerToleratesOrdinaryJitter() {
        TimerCheck timerCheck = new TimerCheck();
        UserData data = new UserData(UUID.randomUUID(), "TestPlayer");

        // Alternating 42ms/58ms: the same average as vanilla, just an unsteady connection.
        long now = 0L;
        for (int i = 0; i < 400; i++) {
            now += (i % 2 == 0 ? 42 : 58) * MS;
            assertFalse(timerCheck.check(data, 1.0, now).isFlag(),
                    "jitter around a correct average must not flag (packet " + i + ")");
        }
    }

    @Test
    void timerDetectsSustainedAcceleration() {
        TimerCheck timerCheck = new TimerCheck();
        UserData data = new UserData(UUID.randomUUID(), "TestPlayer");

        // 25ms intervals = the client simulating at 2x real time.
        long now = 0L;
        boolean flagged = false;
        for (int i = 0; i < 60 && !flagged; i++) {
            now += 25 * MS;
            CheckResult result = timerCheck.check(data, 1.0, now);
            if (result.isFlag()) {
                flagged = true;
                assertEquals("Timer", result.checkName());
            }
        }
        assertTrue(flagged, "2x timer must be caught");
    }

    @Test
    void timerDetectsShortBurstThatPerSecondCountingWouldMiss() {
        TimerCheck timerCheck = new TimerCheck();
        UserData data = new UserData(UUID.randomUUID(), "TestPlayer");

        long now = 0L;
        // Half a second of normal play first.
        for (int i = 0; i < 10; i++) {
            now += 50 * MS;
            timerCheck.check(data, 1.0, now);
        }

        // ~300ms burst at 2x. Across the whole second this is only a handful of extra packets -
        // comfortably under any per-second packet cap - but the drift is real and immediate.
        boolean flagged = false;
        for (int i = 0; i < 24 && !flagged; i++) {
            now += 25 * MS;
            flagged = timerCheck.check(data, 1.0, now).isFlag();
        }
        assertTrue(flagged, "a short timer burst must be caught on its own merit");
    }

    @Test
    void timerDoesNotBankCreditDuringLagForLaterUse() {
        TimerCheck timerCheck = new TimerCheck();
        UserData data = new UserData(UUID.randomUUID(), "TestPlayer");

        long now = 0L;
        // A long stretch of a slow connection: 90ms per packet builds negative balance.
        for (int i = 0; i < 200; i++) {
            now += 90 * MS;
            timerCheck.check(data, 1.0, now);
        }
        // The floor clamp means only a few ticks of headroom were kept, not 200 packets' worth.
        assertTrue(data.getTimerBalanceMs() >= -260.0,
                "negative balance must be clamped, was " + data.getTimerBalanceMs());

        // So the same 2x burst is still caught promptly afterwards.
        boolean flagged = false;
        for (int i = 0; i < 60 && !flagged; i++) {
            now += 25 * MS;
            flagged = timerCheck.check(data, 1.0, now).isFlag();
        }
        assertTrue(flagged, "banked lag credit must not buy a free timer window");
    }

    @Test
    void timerIgnoresGenuineStalls() {
        TimerCheck timerCheck = new TimerCheck();
        UserData data = new UserData(UUID.randomUUID(), "TestPlayer");

        long now = 0L;
        for (int i = 0; i < 10; i++) {
            now += 50 * MS;
            timerCheck.check(data, 1.0, now);
        }
        // Three second freeze (chunk load / server hitch), then normal play resumes.
        now += 3000 * MS;
        assertFalse(timerCheck.check(data, 1.0, now).isFlag());
        assertEquals(0.0, data.getTimerBalanceMs(), 1e-9, "a stall must reset, not credit");
    }

    // ------------------------------------------------------------------
    // Speed: momentum prediction
    // ------------------------------------------------------------------

    @Test
    void speedAcceptsVanillaSprintAcceleration() {
        SpeedCheck speedCheck = new SpeedCheck();
        UserData data = new UserData(UUID.randomUUID(), "TestPlayer");
        data.setEnvironment(ground(Material.GRASS_BLOCK));
        data.setLastPosition(0, 64, 0, true);

        // Reproduce vanilla's own recurrence: speed = speed * friction + accel, converging on
        // roughly 0.286 b/t. If the model cannot accept the movement it is modelling, it is wrong.
        double speed = 0.0;
        for (int i = 0; i < 200; i++) {
            speed = speed * (0.6 * 0.91) + 0.13;
            CheckResult result = speedCheck.check(data, speed, 0, 50.0, 1.0);
            assertFalse(result.isFlag(), "vanilla sprint must not flag (tick " + i + ", speed " + speed + ")");
        }
    }

    @Test
    void speedAcceptsIceSprint() {
        SpeedCheck speedCheck = new SpeedCheck();
        UserData data = new UserData(UUID.randomUUID(), "TestPlayer");
        data.setEnvironment(ground(Material.PACKED_ICE));
        data.setLastPosition(0, 64, 0, true);

        double friction = 0.98 * 0.91;
        double accel = 0.1 * (0.16277136 / (friction * friction * friction)) * 1.3;
        double speed = 0.0;
        for (int i = 0; i < 200; i++) {
            speed = speed * friction + accel;
            assertFalse(speedCheck.check(data, speed, 0, 50.0, 1.0).isFlag(),
                    "ice sprint must not flag (tick " + i + ", speed " + speed + ")");
        }
    }

    @Test
    void speedToleratesSingleSpike() {
        SpeedCheck speedCheck = new SpeedCheck();
        UserData data = new UserData(UUID.randomUUID(), "TestPlayer");
        data.setEnvironment(ground(Material.GRASS_BLOCK));
        data.setLastPosition(0, 64, 0, true);

        // Settle into a sprint.
        double speed = 0.0;
        for (int i = 0; i < 40; i++) {
            speed = speed * (0.6 * 0.91) + 0.13;
            speedCheck.check(data, speed, 0, 50.0, 1.0);
        }
        // One unexplained fast tick - a bounce, a piston, a plugin push we do not model.
        assertFalse(speedCheck.check(data, 0.75, 0, 50.0, 1.0).isFlag(),
                "one mispredicted tick must not be enough to flag");
    }

    @Test
    void speedDetectsSustainedSpeedHack() {
        SpeedCheck speedCheck = new SpeedCheck();
        UserData data = new UserData(UUID.randomUUID(), "TestPlayer");
        data.setEnvironment(ground(Material.GRASS_BLOCK));
        data.setLastPosition(0, 64, 0, true);

        // Roughly twice sprint speed, held steady - exactly what the old flat 0.65 cap allowed.
        boolean flagged = false;
        for (int i = 0; i < 100 && !flagged; i++) {
            CheckResult result = speedCheck.check(data, 0.57, 0, 50.0, 1.0);
            if (result.isFlag()) {
                flagged = true;
                assertEquals("Speed", result.checkName());
            }
        }
        assertTrue(flagged, "sustained 2x sprint speed must be caught");
    }

    @Test
    void speedDetectsModestSustainedMultiplier() {
        SpeedCheck speedCheck = new SpeedCheck();
        UserData data = new UserData(UUID.randomUUID(), "TestPlayer");
        data.setEnvironment(ground(Material.GRASS_BLOCK));
        data.setLastPosition(0, 64, 0, true);

        // 1.6x sprint - small enough that a flat cap would never see it, but it still cannot be
        // produced by the friction model.
        boolean flagged = false;
        for (int i = 0; i < 400 && !flagged; i++) {
            flagged = speedCheck.check(data, 0.46, 0, 50.0, 1.0).isFlag();
        }
        assertTrue(flagged, "a modest but sustained speed multiplier must eventually be caught");
    }

    @Test
    void speedSkipsWhenSnapshotIsStale() {
        SpeedCheck speedCheck = new SpeedCheck();
        UserData data = new UserData(UUID.randomUUID(), "TestPlayer");
        // No snapshot published at all: the check must not judge terrain it cannot see.
        for (int i = 0; i < 50; i++) {
            assertFalse(speedCheck.check(data, 2.0, 0, 50.0, 1.0).isFlag());
        }
    }

    @Test
    void speedSkipsMultiTickGaps() {
        SpeedCheck speedCheck = new SpeedCheck();
        UserData data = new UserData(UUID.randomUUID(), "TestPlayer");
        data.setEnvironment(ground(Material.GRASS_BLOCK));
        data.setLastPosition(0, 64, 0, true);

        // A player who stood still and then resumed: the delta covers many ticks, so it is not a
        // per-tick speed at all and must not be scored as one.
        for (int i = 0; i < 20; i++) {
            assertFalse(speedCheck.check(data, 1.8, 0, 900.0, 1.0).isFlag());
        }
    }

    // ------------------------------------------------------------------
    // Velocity: anti-knockback
    // ------------------------------------------------------------------

    @Test
    void velocityAcceptsPlayerWhoTakesKnockback() {
        VelocityCheck check = new VelocityCheck();
        TransactionManager transactions = new TransactionManager();
        UUID uuid = UUID.randomUUID();
        UserData data = new UserData(uuid, "TestPlayer");
        data.setEnvironment(ground(Material.GRASS_BLOCK));

        for (int event = 0; event < 6; event++) {
            data.recordPendingVelocity(0.4, 0.36, 0.0, 0L);
            // Travels the full decayed impulse over the window.
            double speed = 0.4;
            for (int t = 0; t < 4; t++) {
                assertFalse(check.check(uuid, data, transactions, speed, 0).isFlag());
                speed *= 0.6 * 0.91;
            }
        }
    }

    @Test
    void velocityDetectsAbsorbedKnockback() {
        VelocityCheck check = new VelocityCheck();
        TransactionManager transactions = new TransactionManager();
        UUID uuid = UUID.randomUUID();
        UserData data = new UserData(uuid, "TestPlayer");
        data.setEnvironment(ground(Material.GRASS_BLOCK));

        boolean flagged = false;
        for (int event = 0; event < 8 && !flagged; event++) {
            data.recordPendingVelocity(0.4, 0.36, 0.0, 0L);
            for (int t = 0; t < 4 && !flagged; t++) {
                // Barely moves: the knockback was nulled client-side.
                CheckResult result = check.check(uuid, data, transactions, 0.01, 0);
                if (result.isFlag()) {
                    flagged = true;
                    assertEquals("Velocity", result.checkName());
                }
            }
        }
        assertTrue(flagged, "a player repeatedly absorbing knockback must be caught");
    }

    @Test
    void velocityNeedsRepetitionBeforeFlagging() {
        VelocityCheck check = new VelocityCheck();
        TransactionManager transactions = new TransactionManager();
        UUID uuid = UUID.randomUUID();
        UserData data = new UserData(uuid, "TestPlayer");
        data.setEnvironment(ground(Material.GRASS_BLOCK));

        // A single absorbed knockback is what walking into a wall looks like.
        data.recordPendingVelocity(0.4, 0.36, 0.0, 0L);
        for (int t = 0; t < 4; t++) {
            assertFalse(check.check(uuid, data, transactions, 0.0, 0).isFlag(),
                    "one wall collision must not flag");
        }
    }

    @Test
    void velocityWaitsForAcknowledgementBeforeJudging() {
        VelocityCheck check = new VelocityCheck();
        TransactionManager transactions = new TransactionManager();
        UUID uuid = UUID.randomUUID();
        transactions.register(uuid);
        UserData data = new UserData(uuid, "LaggyPlayer");
        data.setEnvironment(ground(Material.GRASS_BLOCK));

        // A knockback the client has not confirmed receiving yet. Until it does, its movement
        // says nothing about whether it took the knockback - it had not been told about it.
        long sequence = transactions.onServerEvent(uuid);
        data.recordPendingVelocity(0.4, 0.36, 0.0, sequence);
        assertFalse(transactions.isAcknowledged(uuid, sequence));

        for (int t = 0; t < 30; t++) {
            assertFalse(check.check(uuid, data, transactions, 0.0, 0).isFlag(),
                    "an unacknowledged knockback must never be scored");
        }
    }

    @Test
    void velocityGivesUpOnKnockbackNeverAcknowledged() {
        VelocityCheck check = new VelocityCheck();
        TransactionManager transactions = new TransactionManager();
        UUID uuid = UUID.randomUUID();
        transactions.register(uuid);
        UserData data = new UserData(uuid, "LaggyPlayer");
        data.setEnvironment(ground(Material.GRASS_BLOCK));

        data.recordPendingVelocity(0.4, 0.36, 0.0, transactions.onServerEvent(uuid));
        for (int t = 0; t < 200; t++) {
            check.check(uuid, data, transactions, 0.0, 0);
        }
        assertFalse(data.isVelocityPending(),
                "a knockback that is never acknowledged must be dropped, not tracked forever");
    }

    // ------------------------------------------------------------------

    @Test
    void testFlyStreakTracking() {
        UserData data = new UserData(UUID.randomUUID(), "TestPlayer");
        assertEquals(0, data.getFlyStreak());
        assertEquals(0, data.getAirTicks());

        data.incrementAirTicks();
        data.incrementAirTicks();
        assertEquals(2, data.getAirTicks());

        data.incrementFlyStreak();
        assertEquals(1, data.getFlyStreak());

        data.resetAirTicks();
        assertEquals(0, data.getAirTicks());
    }

    private static EnvironmentSnapshot ground(Material below) {
        return TestSnapshots.ground(below);
    }
}
