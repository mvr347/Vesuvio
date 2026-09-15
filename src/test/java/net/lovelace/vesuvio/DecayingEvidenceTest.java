package net.lovelace.vesuvio;

import net.lovelace.vesuvio.data.DecayingEvidence;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The accumulator exists because every "N suspicious hits in a row" counter is defeated for free by
 * a randomized cheat: behaving humanly one hit in five resets the counter forever. These tests pin
 * that difference down rather than the arithmetic.
 */
public class DecayingEvidenceTest {

    private static final double HALF_LIFE_MS = 4000.0;
    private static final double RELIEF = 0.5;
    private static final double THRESHOLD = 6.0;

    private static long ms(long millis) {
        return millis * 1_000_000L;
    }

    @Test
    public void testAFourInFiveCheaterStillCrossesTheThreshold() {
        // The exact pattern a hard streak counter can never catch: four suspicious hits, then one
        // human-looking one, repeating. A streak counter sits permanently at 4 and never fires.
        DecayingEvidence evidence = new DecayingEvidence();
        long now = 0L;
        double score = 0.0;

        for (int i = 0; i < 40; i++) {
            now += ms(120); // a brisk but ordinary fight cadence
            score = (i % 5 == 4)
                    ? evidence.relieve(RELIEF, now, HALF_LIFE_MS)
                    : evidence.reward(1.0, now, HALF_LIFE_MS);
            if (score >= THRESHOLD) break;
        }

        assertTrue(score >= THRESHOLD,
                "a player suspicious on 4 hits out of 5 must still reach the threshold, was " + score);
    }

    @Test
    public void testAnHonestPlayerWithOccasionalOddHitsNeverCrossesIt() {
        // The inverse case, and the one that decides whether this is usable in production: one odd
        // hit in every five, the rest clean. The accumulator must lose ground faster than it gains.
        DecayingEvidence evidence = new DecayingEvidence();
        long now = 0L;
        double peak = 0.0;

        for (int i = 0; i < 400; i++) {
            now += ms(120);
            double score = (i % 5 == 0)
                    ? evidence.reward(1.0, now, HALF_LIFE_MS)
                    : evidence.relieve(RELIEF, now, HALF_LIFE_MS);
            peak = Math.max(peak, score);
        }

        assertTrue(peak < THRESHOLD,
                "an occasionally-odd legitimate player must never reach the threshold, peaked at " + peak);
    }

    @Test
    public void testEvidenceFadesBetweenFights() {
        // Evidence from a fight twenty minutes ago must not contribute to a verdict now - otherwise
        // a long session slowly accumulates its way to a flag with no single suspicious episode.
        DecayingEvidence evidence = new DecayingEvidence();
        long now = 0L;
        for (int i = 0; i < 5; i++) {
            now += ms(120);
            evidence.reward(1.0, now, HALF_LIFE_MS);
        }
        assertTrue(evidence.current(now, HALF_LIFE_MS) > 4.0, "five suspicious hits should accumulate");

        now += ms(20 * 60 * 1000L); // twenty quiet minutes
        assertEquals(0.0, evidence.current(now, HALF_LIFE_MS), 1e-9,
                "evidence must fade to nothing across a long quiet gap");
        assertEquals(0, evidence.events(), "a fully faded accumulator also forgets its event count");
    }

    @Test
    public void testResetClearsEverything() {
        DecayingEvidence evidence = new DecayingEvidence();
        evidence.reward(1.0, ms(10), HALF_LIFE_MS);
        evidence.reward(1.0, ms(20), HALF_LIFE_MS);
        evidence.reset();

        assertEquals(0.0, evidence.peek(), 1e-9);
        assertEquals(0, evidence.events());
    }

    @Test
    public void testRelieveNeverDrivesTheScoreNegative() {
        // A negative reservoir would mean a player who behaved well for a while gets a free pass on
        // the next burst of genuine evidence - the accumulator floors at zero for exactly that reason.
        DecayingEvidence evidence = new DecayingEvidence();
        long now = 0L;
        for (int i = 0; i < 50; i++) {
            now += ms(100);
            assertTrue(evidence.relieve(RELIEF, now, HALF_LIFE_MS) >= 0.0);
        }
        assertEquals(0.0, evidence.peek(), 1e-9);
    }
}
