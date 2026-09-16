package net.lovelace.vesuvio;

import net.lovelace.vesuvio.listener.WorldInteractionListener;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the vanilla dig model FastBreak judges against.
 *
 * The check shipped for a long time without vanilla's /30 divisor, which put every floor thirty
 * times too low - obsidian, 9.4 real seconds of digging with a diamond pickaxe, was only called
 * impossible under 175ms. Nothing a fast-break module does is that fast, so the check never fired
 * on anything. These numbers are the vanilla wiki's own break times.
 *
 * Author: Lovelace
 */
final class FastBreakModelTest {

    private static final double DIAMOND = 8.0;
    private static final double HAND = 1.0;

    @Test
    void modelMatchesVanillaBreakTimes() {
        // hardness, tool speed, expected vanilla milliseconds
        assertEquals(9400L, WorldInteractionListener.vanillaBreakMillis(DIAMOND, 50.0), "obsidian, diamond pickaxe");
        assertEquals(300L, WorldInteractionListener.vanillaBreakMillis(DIAMOND, 1.5), "stone, diamond pickaxe");
        assertEquals(600L, WorldInteractionListener.vanillaBreakMillis(DIAMOND, 3.0), "diamond ore, diamond pickaxe");
    }

    @Test
    void aBlockVanillaBreaksInOneTickIsNeverAViolation() {
        // Speed comfortably exceeding hardness*30 is vanilla's own instant break.
        assertEquals(50L, WorldInteractionListener.vanillaBreakMillis(1000.0, 0.5));
    }

    @Test
    void theMissingDivisorWouldHaveMadeTheCheckUnreachable() {
        // What the model used to produce (speed/hardness, no /30) against what vanilla really
        // takes. A module set to 1.5x - the popular setting - breaks obsidian in ~6.3 seconds,
        // which has to land above the old floor and below the real one for the check to matter.
        long vanilla = WorldInteractionListener.vanillaBreakMillis(DIAMOND, 50.0);
        long oldFloor = 175L; // (8/50 -> 7 ticks -> 350ms, halved by the old safety margin)
        long factor15Break = Math.round(vanilla / 1.5);

        assertTrue(factor15Break > oldFloor,
                "a 1.5x fast break was far slower than the old floor, so it could never be flagged");
        assertTrue(factor15Break < vanilla,
                "and it is genuinely faster than vanilla allows, so it must be catchable now");
    }

    @Test
    void harderBlocksTakeProportionallyLonger() {
        long stone = WorldInteractionListener.vanillaBreakMillis(DIAMOND, 1.5);
        long obsidian = WorldInteractionListener.vanillaBreakMillis(DIAMOND, 50.0);
        assertTrue(obsidian > stone * 30, "obsidian is >30x stone's hardness and must take longer to match");
    }

    @Test
    void bareHandIsSlowerThanATool() {
        assertTrue(WorldInteractionListener.vanillaBreakMillis(HAND, 1.5)
                > WorldInteractionListener.vanillaBreakMillis(DIAMOND, 1.5));
    }
}
