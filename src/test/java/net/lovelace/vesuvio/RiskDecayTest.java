package net.lovelace.vesuvio;

import net.lovelace.vesuvio.data.UserData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RiskDecayTest {

    private static final long MIN = 60_000L;

    @AfterEach
    void resetCap() {
        UserData.setSensitivityCap(1.3);
    }

    @Test
    void riskDoesNotDecayBeforeCleanPeriod() {
        UserData d = new UserData(UUID.randomUUID(), "t");
        d.setRiskIndex(40.0);
        d.adjustRisk(5.0);
        long now = System.currentTimeMillis();
        d.applyCleanDecay(now + 30_000L, MIN, 1.0, 10.0, 1.5);
        assertEquals(45.0, d.getRiskIndex(), 1e-9);
    }

    @Test
    void riskDecaysAfterCleanPeriodAndStopsAtFloor() {
        UserData d = new UserData(UUID.randomUUID(), "t");
        d.setRiskIndex(12.0);
        long t0 = System.currentTimeMillis();
        d.applyCleanDecay(t0 + 2 * MIN, MIN, 1.0, 10.0, 1.5);
        // Only the minute past the clean threshold counts: 12 - 1 = 11.
        assertEquals(11.0, d.getRiskIndex(), 0.05);
        d.applyCleanDecay(t0 + 10 * MIN, MIN, 1.0, 10.0, 1.5);
        assertEquals(10.0, d.getRiskIndex(), 1e-9, "never below the floor");
    }

    @Test
    void newFlagRestartsCleanPeriod() {
        UserData d = new UserData(UUID.randomUUID(), "t");
        d.setRiskIndex(50.0);
        long t0 = System.currentTimeMillis();
        d.applyCleanDecay(t0 + 5 * MIN, MIN, 1.0, 10.0, 1.5);
        double after = d.getRiskIndex();
        assertTrue(after < 50.0);
        d.adjustRisk(10.0);
        d.applyCleanDecay(System.currentTimeMillis() + 10_000L, MIN, 1.0, 10.0, 1.5);
        assertEquals(after + 10.0, d.getRiskIndex(), 0.05);
    }

    @Test
    void trustGrowsByOnePerConfiguredCleanHours() {
        UserData d = new UserData(UUID.randomUUID(), "t");
        d.setTrustScore(50.0);
        long t0 = System.currentTimeMillis();
        d.applyCleanDecay(t0 + MIN, MIN, 1.0, 10.0, 1.5);
        double base = d.getTrustScore();
        d.applyCleanDecay(t0 + MIN + 90 * MIN, MIN, 1.0, 10.0, 1.5);
        assertEquals(base + 1.0, d.getTrustScore(), 0.02);
    }

    @Test
    void sensitivityIsCapped() {
        UserData d = new UserData(UUID.randomUUID(), "t");
        d.setTrustScore(0.0);
        d.setRiskIndex(100.0);
        assertEquals(1.3, d.getSensitivityMultiplier(), 1e-9);
        UserData.setSensitivityCap(2.5);
        assertEquals(2.4, d.getSensitivityMultiplier(), 1e-9, "raw formula maximum");
    }

    @Test
    void windowedCheckReflagsOncePerInterval() {
        UserData d = new UserData(UUID.randomUUID(), "t");
        assertTrue(d.tryReflag("AimStatistical", 1_000L, 4_000L));
        assertFalse(d.tryReflag("AimStatistical", 2_000L, 4_000L));
        assertTrue(d.tryReflag("ClickStatistical", 2_000L, 4_000L), "per check");
        assertTrue(d.tryReflag("AimStatistical", 5_000L, 4_000L));
    }

    @Test
    void alertsAreLimitedPerCheckAndPlayer() {
        UserData d = new UserData(UUID.randomUUID(), "t");
        assertTrue(d.tryAlert("Fly", 1_000L, 1_500L));
        assertFalse(d.tryAlert("Fly", 2_000L, 1_500L), "same check inside the interval");
        assertTrue(d.tryAlert("Speed", 2_000L, 1_500L), "another check is independent");
        assertTrue(d.tryAlert("Fly", 2_600L, 1_500L));
        assertTrue(d.tryAlert("Fly", 2_601L, 0L), "0 disables the limit");
    }
}
