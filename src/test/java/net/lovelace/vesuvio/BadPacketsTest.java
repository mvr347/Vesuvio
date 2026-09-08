package net.lovelace.vesuvio;

import net.lovelace.vesuvio.check.CheckResult;
import net.lovelace.vesuvio.check.protocol.BadPacketsCheck;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class BadPacketsTest {

    @Test
    public void testNoSwingCheck() {
        BadPacketsCheck check = new BadPacketsCheck();
        long now = System.nanoTime();

        // Swing was 20ms ago -> legitimate
        CheckResult legit = check.checkNoSwing(now - 20_000_000L);
        assertFalse(legit.isFlag(), "Recent arm swing should pass NoSwing check");

        // Swing was 300ms ago -> flag NoSwing
        CheckResult flag = check.checkNoSwing(now - 300_000_000L);
        assertTrue(flag.isFlag(), "Stale or missing arm swing must be flagged as NoSwing");
        assertTrue(flag.confidence() >= 0.95);

        // Never swung (0) -> flag NoSwing
        CheckResult zero = check.checkNoSwing(0);
        assertTrue(zero.isFlag(), "Zero swing timestamp must be flagged as NoSwing");
    }

    @Test
    public void testPitchBounds() {
        BadPacketsCheck check = new BadPacketsCheck();

        // Normal pitches
        assertFalse(check.checkPitch(0.0f).isFlag());
        assertFalse(check.checkPitch(45.0f).isFlag());
        assertFalse(check.checkPitch(-89.9f).isFlag());
        assertFalse(check.checkPitch(90.0f).isFlag());

        // Impossible pitches exceeding limits
        CheckResult flagHigh = check.checkPitch(91.2f);
        assertTrue(flagHigh.isFlag(), "Pitch > 90° is mathematically impossible in vanilla");
        assertEquals(1.0, flagHigh.confidence(), 0.001);

        CheckResult flagLow = check.checkPitch(-95.0f);
        assertTrue(flagLow.isFlag(), "Pitch < -90° is mathematically impossible in vanilla");
        assertEquals(1.0, flagLow.confidence(), 0.001);
    }
}
