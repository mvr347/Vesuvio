package net.lovelace.vesuvio;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.check.statistical.StatisticalClickCheck;
import net.lovelace.vesuvio.data.UserData;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class StatisticalClickCheckTest {

    @Test
    public void testAutoclickerFlagged() {
        StatisticalClickCheck check = new StatisticalClickCheck();
        UserData user = new UserData(UUID.randomUUID(), "TestBot");

        long base = 1_000_000_000L;
        // 20 CPS constant 50ms intervals
        for (int i = 0; i < 65; i++) {
            user.getClickBuffer().addClick(base + (i * 50_000_000L), false);
        }

        CheckResult result = check.check(user);

        assertTrue(result.isFlag(), "Robotic 20 CPS autoclicker must be flagged");
        assertTrue(result.confidence() > 0.85);
        assertTrue(result.vl() > 0);
    }

    @Test
    public void testHumanLegitPassed() {
        StatisticalClickCheck check = new StatisticalClickCheck();
        UserData user = new UserData(UUID.randomUUID(), "LegitPlayer");

        long now = 1_000_000_000L;
        Random rng = new Random(12345);

        // Natural human clicking 8-9 CPS with realistic variance
        for (int i = 0; i < 65; i++) {
            long delta = (long) ((115 + rng.nextGaussian() * 28) * 1_000_000L);
            if (delta < 50_000_000L) delta = 50_000_000L;
            now += delta;
            user.getClickBuffer().addClick(now, false);
        }

        CheckResult result = check.check(user);

        assertFalse(result.isFlag(), "Legitimate human clicking should not be flagged");
    }
}
