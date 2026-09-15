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

    @Test
    public void testOutlierResistantFeaturesSeparateAMacroFromAHuman() {
        // The point of features 16-19: a humanized client hides behind mean/std-dev by injecting a
        // few long pauses, which barely moves the quantiles or the run length underneath.
        ClickRingBuffer macro = new ClickRingBuffer();
        long now = 1_000_000_000L;
        for (int i = 0; i < 65; i++) {
            // A rigid 60ms cadence, with an occasional long pause thrown in to inflate std-dev
            // exactly the way a "humanized" macro does.
            long delta = (i % 12 == 11) ? 320_000_000L : 60_000_000L;
            now += delta;
            macro.addClick(now, false);
        }
        // extract() hands back a shared thread-local buffer, so the first result has to be cloned
        // before extracting the second - otherwise both references point at the same array.
        float[] m = ClickFeatureExtractor.extract(macro).clone();

        ClickRingBuffer human = new ClickRingBuffer();
        Random rng = new Random(7);
        now = 1_000_000_000L;
        for (int i = 0; i < 65; i++) {
            long delta = (long) ((110 + rng.nextGaussian() * 25) * 1_000_000L);
            if (delta < 40_000_000L) delta = 40_000_000L;
            now += delta;
            human.addClick(now, false);
        }
        float[] h = ClickFeatureExtractor.extract(human).clone();

        assertEquals(ClickFeatureExtractor.FEATURE_COUNT, m.length);

        // The injected pauses lift the macro's std-dev into the same range a real human shows
        // (the human test above asserts > 12ms), so std-dev alone no longer separates them...
        assertTrue(m[1] > 20.0f,
                "injected pauses should lift the macro's std-dev into human range, was " + m[1]);
        // ...but its interquartile range stays far tighter than a human's, and its longest
        // single-cadence run far longer. That is the separation those features exist to provide.
        assertTrue(m[16] < h[16],
                "macro IQR (" + m[16] + ") must stay tighter than human IQR (" + h[16] + ")");
        assertTrue(m[18] > h[18],
                "macro longest-run (" + m[18] + ") must exceed human longest-run (" + h[18] + ")");
    }
}
