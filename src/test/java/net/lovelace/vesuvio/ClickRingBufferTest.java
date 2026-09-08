package net.lovelace.vesuvio;

import net.lovelace.vesuvio.data.ClickRingBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ClickRingBufferTest {

    private ClickRingBuffer buffer;

    @BeforeEach
    public void setUp() {
        buffer = new ClickRingBuffer();
    }

    @Test
    public void testEmptyBuffer() {
        assertFalse(buffer.isFull());
        assertEquals(0, buffer.getCount());
    }

    @Test
    public void testAddClicksAndChronologicalCopy() {
        long base = 1_000_000_000L;
        // Add 10 clicks, 50ms apart (50_000_000 ns)
        for (int i = 0; i < 10; i++) {
            buffer.addClick(base + (i * 50_000_000L), i % 2 == 0);
        }

        assertEquals(9, buffer.getCount()); // First click establishes baseline, 9 intervals recorded

        long[] dest = new long[buffer.getCount()];
        buffer.copyIntervals(dest);

        for (int i = 0; i < dest.length; i++) {
            assertEquals(50_000_000L, dest[i]);
        }
    }

    @Test
    public void testWrapAroundFullBuffer() {
        long base = 1_000_000_000L;
        // Add 100 clicks (more than capacity 64)
        for (int i = 0; i < 100; i++) {
            buffer.addClick(base + (i * 60_000_000L), false);
        }

        assertTrue(buffer.isFull());
        assertEquals(ClickRingBuffer.SIZE, buffer.getCount());

        long[] dest = new long[ClickRingBuffer.SIZE];
        buffer.copyIntervals(dest);
        assertEquals(ClickRingBuffer.SIZE, dest.length);
        for (long interval : dest) {
            assertEquals(60_000_000L, interval);
        }
    }

    @Test
    public void testSignatureGenerated() {
        long base = 1_000_000_000L;
        for (int i = 0; i < 20; i++) {
            buffer.addClick(base + (i * 70_000_000L), i == 5);
        }

        long[] sig = buffer.getSignature();
        assertNotNull(sig);
        assertEquals(8, sig.length);
        // Signature should have accumulated values
        assertNotEquals(0L, sig[0]);
        assertNotEquals(0L, sig[1]);
    }
}
