package net.lovelace.vesuvio;

import net.lovelace.vesuvio.data.ClickRingBuffer;
import net.lovelace.vesuvio.feature.ClickFeatureExtractor;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public class ClickFeatureExtractorTest {

    @Test
    public void testConstantAutoclickerFeatures() {
        ClickRingBuffer buffer = new ClickRingBuffer();
        long base = 1_000_000_000L;

        // Simulate a robotic 20 CPS autoclicker with constant 50ms intervals
        for (int i = 0; i < 65; i++) {
            buffer.addClick(base + (i * 50_000_000L), false);
        }

        float[] f = ClickFeatureExtractor.extract(buffer);

        assertEquals(50.0f, f[0], 0.5f, "Mean should be ~50ms");
        assertTrue(f[1] < 1.0f, "StdDev should be virtually 0 for constant autoclicker");
        assertEquals(1.0f, f[4], 0.05f, "Duplicate ratio should be ~100%");
        assertEquals(20.0f, f[14], 0.5f, "Average CPS should be 20");
    }

    @Test
    public void testHumanClickFeatures() {
        ClickRingBuffer buffer = new ClickRingBuffer();
        long now = 1_000_000_000L;
        Random rng = new Random(42);

        // Simulate natural human clicking around 7-10 CPS (mean ~115ms, variance ~25ms)
        for (int i = 0; i < 65; i++) {
            long delta = (long) ((110 + rng.nextGaussian() * 25) * 1_000_000L);
            if (delta < 40_000_000L) delta = 40_000_000L;
            now += delta;
            buffer.addClick(now, false);
        }

        float[] f = ClickFeatureExtractor.extract(buffer);

        assertTrue(f[0] > 80.0f && f[0] < 140.0f, "Human mean delay should be between 80 and 140ms");
        assertTrue(f[1] > 12.0f, "Human std dev should be > 12ms");
        assertTrue(f[4] < 0.25f, "Human duplicate ratio should be low (< 25%)");
    }
}
